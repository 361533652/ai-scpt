package com.gmscpt.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmscpt.server.domain.ChatMessage;
import com.gmscpt.server.domain.ChatRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任意 OpenAI 兼容端点通道（云端 API、公司内网代理、或 Ollama 的 /v1 兼容层）。
 *
 * <p><b>鉴权头的红线</b>：本地端点（Ollama 的 /v1、LM Studio、vLLM、llama.cpp server）根本不校验
 * Authorization，但有些实现看到空 Bearer 会直接 401 或行为异常。旧版对本地 Ollama 硬发
 * {@code Authorization: Bearer }（空格后面什么都没有），是隐藏故障源之一。
 *
 * <p>这里不能靠「不调用 {@code apiKey(...)}」来避免——{@code OpenAiApi.Builder.build()} 会
 * {@code Assert.notNull(apiKey, "apiKey must be set")} 直接抛。而 {@code apiKey("")} 会构造
 * {@code SimpleApiKey("")}，最终照样 {@code setBearerAuth("")}。唯一正确的做法是喂一个
 * {@link NoopApiKey}：{@code OpenAiApi.addDefaultHeadersIfMissing} 里有
 * {@code if (apiKey instanceof NoopApiKey) skip} 的分支（已用 javap 核实字节码），
 * 这种情况下根本不写 Authorization 头。
 *
 * <p>为什么用 builder 手工构建而不是拿 Spring 注入的 Bean：baseUrl / apiKey / 模型都来自运行期设置
 * （{@code PUT /api/settings} 可改），启动期注入的 Bean 改不了这些。
 */
public class OpenAiCompatAiGateway implements AiGateway, ReconfigurableGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatAiGateway.class);

    static final String DEFAULT_ID = "openai-compat";
    static final String DEFAULT_BASE_URL = "https://api.openai.com";
    static final String DEFAULT_MODEL = "gpt-4o-mini";

    /**
     * OpenAI 侧的思考链字段名。
     *
     * <p>已用 javap 核实：{@code OpenAiChatModel} 在构造 Generation 时把
     * {@code ChatCompletionMessage.reasoningContent()} 放进 AssistantMessage 的 properties，
     * 键名就是字面量 {@code "reasoningContent"}（同步与流式两条路径都有）。
     * 不同于 Ollama 的 {@code "thinking"}——这正是不能共用一个常量名的地方。
     */
    private static final String REASONING_KEY = "reasoningContent";

    private static final Duration PROBE_CACHE_TTL = Duration.ofSeconds(10);
    private static final Duration MODELS_CACHE_TTL = Duration.ofSeconds(5);

    private final String id;
    private final AiTimeouts timeouts;
    private final ObjectMapper json = new ObjectMapper();

    private volatile Endpoint endpoint;
    private volatile ProbeCache probeCache = ProbeCache.empty();

    public OpenAiCompatAiGateway(String id, String baseUrl, String apiKey, String defaultModel) {
        this(id, baseUrl, apiKey, defaultModel, AiTimeouts.defaults());
    }

    public OpenAiCompatAiGateway(String id, String baseUrl, String apiKey, String defaultModel,
                                 AiTimeouts timeouts) {
        this.id = (id == null || id.isBlank()) ? DEFAULT_ID : id.strip();
        this.timeouts = timeouts == null ? AiTimeouts.defaults() : timeouts;
        this.endpoint = buildEndpoint(normalizeBaseUrl(baseUrl), blankToNull(apiKey),
                normalizeModel(defaultModel));
    }

    @Override
    public String id() {
        return id;
    }

    /**
     * 重配端点与密钥。
     *
     * <p>密钥不参与「是否重建」的比较——设置页每次保存都回传同一个（前端拿不到明文，
     * 只回 hasCloudApiKey，所以这里传进来的通常是与上次相同的值或 null）。
     * 真正决定重建的是 baseUrl / model / 密钥的有无变化。
     */
    public synchronized void reconfigure(String baseUrl, String apiKey, String defaultModel) {
        String url = normalizeBaseUrl(baseUrl);
        String key = blankToNull(apiKey);
        String model = normalizeModel(defaultModel);
        Endpoint cur = this.endpoint;
        if (cur.baseUrl().equals(url)
                && cur.defaultModel().equals(model)
                && java.util.Objects.equals(cur.hasKey(), key != null)) {
            return;
        }
        this.endpoint = buildEndpoint(url, key, model);
        this.probeCache = ProbeCache.empty();
        log.info("OpenAI 兼容通道 [{}] 已重配：baseUrl={} model={} 鉴权={}",
                id, url, model, key == null ? "不发 Authorization 头" : "已配置 key");
    }

    /** 仅换 key（设置页只填了新 key 时用），不必重建客户端。 */
    public synchronized void reconfigureApiKey(String apiKey) {
        String key = blankToNull(apiKey);
        Endpoint cur = this.endpoint;
        if (java.util.Objects.equals(cur.hasKey(), key != null)) {
            return;
        }
        this.endpoint = buildEndpoint(cur.baseUrl(), key, cur.defaultModel());
        this.probeCache = ProbeCache.empty();
    }

    public String baseUrl() {
        return endpoint.baseUrl();
    }

    public String defaultModel() {
        return endpoint.defaultModel();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code cloudApiKey} 为 null 表示「沿用已存密钥」——设置页的 GET 只回
     * {@code hasCloudApiKey} 不回明文，所以用户只改模型时 key 传过来就是 null。
     * 这种情况下必须<b>保留</b>原有密钥，而不是把它清空，否则用户改个模型名就会突然 401。
     */
    @Override
    public void reconfigure(com.gmscpt.server.domain.Settings settings, String cloudApiKey) {
        if (settings == null) {
            return;
        }
        String key = cloudApiKey;
        if (key == null) {
            key = this.endpoint.apiKey();
        }
        reconfigure(settings.cloudBaseUrl(), key, settings.cloudModel());
    }

    @Override
    public boolean available() {
        long now = System.nanoTime();
        ProbeCache cache = this.probeCache;
        if (cache.valid(now, PROBE_CACHE_TTL)) {
            return cache.available();
        }
        boolean ok;
        try {
            // 探活用 /v1/models：几乎每家兼容实现都实现了它，且不消耗 token。
            listModelsRaw();
            ok = true;
        } catch (Throwable t) {
            // 探活不上抛——providers 列表接口靠这一位决定徽标颜色，不该因为对端没开而整体 500。
            log.debug("OpenAI 兼容通道 [{}] 探活失败：{}", id, t.toString());
            ok = false;
        }
        this.probeCache = new ProbeCache(now, ok);
        return ok;
    }

    @Override
    public List<String> models() {
        long now = System.nanoTime();
        ProbeCache cache = this.probeCache;
        if (cache.modelsValid(now, MODELS_CACHE_TTL)) {
            return cache.models();
        }
        List<String> names = List.of();
        boolean ok = false;
        try {
            names = listModelsRaw();
            ok = true;
        } catch (Throwable t) {
            log.debug("OpenAI 兼容通道 [{}] 列模型失败：{}", id, t.toString());
        }
        this.probeCache = new ProbeCache(now, ok, names);
        return names;
    }

    /**
     * 流式对话。
     *
     * <p>OpenAI 侧的思考链（DeepSeek-R1 / 部分代理的 reasoning_content）由 Spring AI 放进
     * AssistantMessage 的 {@code reasoningContent} 属性，这里取出来走 {@code channel="thinking"}；
     * 正文同 Ollama 一样走 {@code channel="content"}。前端因此只有一套渲染逻辑。
     *
     * <p>usage：部分端点只在最后一帧给，部分要显式 {@code stream_options.include_usage}。
     * 这里两者都做——显式打开 include_usage，同时把沿途见到的最后一个非空 usage 记下来，
     * 结束时统一补一帧 {@link AiChunk#usage}。这样即使端点忽略 include_usage 也能拿到数。
     */
    @Override
    public Flux<AiChunk> stream(ChatRequest request) {
        return Flux.defer(() -> {
            Endpoint ep = this.endpoint;
            String model = pickModel(request, ep);
            if (model == null) {
                return Flux.error(new AiGatewayException(id, null, "通道没有可用模型：请先在设置里选择模型"));
            }

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(request.temperature() == null ? 0.3 : request.temperature())
                    // 显式要 usage，否则多数端点的流式响应不带 token 数。
                    .streamUsage(true)
                    .build();

            Prompt prompt;
            try {
                prompt = new Prompt(buildMessages(request), options);
            } catch (RuntimeException e) {
                return Flux.error(new AiGatewayException(id, e, "构造请求失败：" + e.getMessage()));
            }

            AtomicReference<int[]> usage = new AtomicReference<>();
            AtomicBoolean doneSeen = new AtomicBoolean(false);

            Flux<AiChunk> deltas = ep.model().stream(prompt)
                    .concatMap(resp -> {
                        List<AiChunk> out = new ArrayList<>(3);
                        recordUsage(resp, usage);
                        Generation g = resp.getResult();
                        if (g != null && g.getOutput() != null) {
                            AssistantMessage out2 = g.getOutput();
                            Object reasoning = out2.getMetadata() == null
                                    ? null : out2.getMetadata().get(REASONING_KEY);
                            if (reasoning != null && !String.valueOf(reasoning).isEmpty()) {
                                out.add(AiChunk.thinking(String.valueOf(reasoning)));
                            }
                            String text = out2.getText();
                            if (text != null && !text.isEmpty()) {
                                out.add(AiChunk.content(text));
                            }
                        }
                        return Flux.fromIterable(out);
                    });

            return deltas.concatWith(Flux.defer(() -> {
                List<AiChunk> tail = new ArrayList<>(2);
                int[] u = usage.get();
                if (u != null) {
                    tail.add(AiChunk.usage(u[0] < 0 ? null : u[0], u[1] < 0 ? null : u[1]));
                }
                tail.add(AiChunk.done(doneSeen.get() ? "stop" : "stop"));
                return Flux.fromIterable(tail);
            })).transform(this::applyTimeouts).onErrorMap(this::wrapError);
        });
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private List<Message> buildMessages(ChatRequest request) {
        List<Message> messages = new ArrayList<>();
        String system = AiMessages.mergeSystemPrompt(request.systemPrompt(), request.history());
        if (system != null && !system.isBlank()) {
            messages.add(new SystemMessage(system));
        }
        for (ChatMessage m : AiMessages.dialogue(request.history())) {
            messages.add(m.role() == ChatRole.ASSISTANT
                    ? new AssistantMessage(m.content())
                    : new UserMessage(m.content()));
        }
        if (request.userMessage() != null && !request.userMessage().isBlank()) {
            messages.add(new UserMessage(request.userMessage()));
        }
        return messages;
    }

    /** 记录沿途见到的最后一个非空 usage；用 -1 表示「该项还没见到」。 */
    private static void recordUsage(ChatResponse resp, AtomicReference<int[]> holder) {
        if (resp == null || resp.getMetadata() == null) {
            return;
        }
        Usage u = resp.getMetadata().getUsage();
        if (u == null) {
            return;
        }
        Integer in = u.getPromptTokens();
        Integer out = u.getCompletionTokens();
        if (in == null && out == null) {
            return;
        }
        int[] prev = holder.get();
        int[] next = prev == null ? new int[]{-1, -1} : prev.clone();
        if (in != null && in > 0) {
            next[0] = in;
        }
        if (out != null && out > 0) {
            next[1] = out;
        }
        holder.set(next);
    }

    /**
     * 首字节 / 块间静默两段超时。理由与 Ollama 通道一致：
     * 冷启动等模型排队的时间不该用「块间静默」那个更严的阈值去卡。
     */
    private Flux<AiChunk> applyTimeouts(Flux<AiChunk> upstream) {
        return Flux.defer(() -> {
            Duration first = timeouts.firstByte();
            Duration idle = timeouts.idle();
            return upstream
                    .timeout(first, Flux.error(new TimeoutException(
                            "上游在 " + first.toSeconds() + " 秒内没有返回第一个字节")))
                    .switchOnFirst((signal, flux) -> signal.hasValue()
                            ? flux.timeout(idle, Flux.error(new TimeoutException(
                                    "上游流中断：连续 " + idle.toSeconds() + " 秒没有新数据")))
                            : flux);
        });
    }

    private Throwable wrapError(Throwable t) {
        if (t instanceof AiGatewayException) {
            return t;
        }
        if (t instanceof TimeoutException) {
            return new AiGatewayException(id, t, t.getMessage());
        }
        String msg = t.getMessage() == null ? t.toString() : t.getMessage();
        // 401/403 单独点出来：最常见的原因是「云端填了 key 但错了」或「本地端点被误发了空 Bearer」。
        if (msg.contains("401") || msg.contains("Unauthorized")) {
            return new AiGatewayException(id, t,
                    "上游拒绝了鉴权（401）：请检查 API key 是否正确；本地端点请把 key 留空");
        }
        if (msg.contains("Connection refused") || msg.contains("ConnectException")) {
            return new AiGatewayException(id, t, "连不上上游 " + endpoint.baseUrl() + "：" + msg);
        }
        return new AiGatewayException(id, t, "上游错误：" + msg);
    }

    /** 打 /v1/models 取模型名列表。返回 name 或 id。 */
    private List<String> listModelsRaw() {
        Endpoint ep = this.endpoint;
        String body = ep.rest().get()
                .uri(ep.modelsUrl())
                .headers(h -> {
                    // 关键：没有 key 就完全不设这个头，而不是设成空的 "Bearer "。
                    if (ep.hasKey()) {
                        h.setBearerAuth(ep.apiKey());
                    }
                })
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (java.io.IOException e) {
            // 对端返回了非 JSON（常见于代理的 HTML 错误页）。转成可读异常，别把 Jackson 的类型漏给上层。
            throw new AiGatewayException(id, e,
                    "/v1/models 返回的不是合法 JSON（可能是反向代理的错误页）：" + e.getMessage());
        }
        JsonNode data = root.path("data");
        List<String> out = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                String n = item.path("id").asText(null);
                if (n == null || n.isBlank()) {
                    n = item.path("name").asText(null);
                }
                if (n != null && !n.isBlank()) {
                    out.add(n);
                }
            }
        }
        return List.copyOf(out);
    }

    private Endpoint buildEndpoint(String baseUrl, String apiKey, String defaultModel) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                // 流式：建连有上限，读侧交给 Flux 的 timeout。
                .webClientBuilder(AiHttp.webClient(timeouts.connect()))
                // 非流式（/v1/models 探活等）：建连与读都有上限，绝不留 0。
                .restClientBuilder(AiHttp.restClient(timeouts.connect(), timeouts.idle()));

        if (apiKey == null) {
            // NoopApiKey 是 Spring AI 用来表达「没有密钥」的哨兵类型，
            // OpenAiApi.addDefaultHeadersIfMissing 见到它就跳过 Authorization（已 javap 核实）。
            // 不能用 apiKey("")：那会构造 SimpleApiKey("") 并发出空的 "Bearer "。
            apiBuilder.apiKey(new NoopApiKey());
        } else {
            apiBuilder.apiKey(apiKey);
        }

        OpenAiApi api = apiBuilder.build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(defaultModel)
                        .streamUsage(true)
                        .build())
                .build();

        // /v1/models 用单独一个 RestClient，避免与模型内部持有的客户端互相干扰。
        RestClient rest = AiHttp.restClient(timeouts.connect(), timeouts.idle())
                .baseUrl(baseUrl)
                .build();

        return new Endpoint(baseUrl, defaultModel, apiKey, api, model, rest,
                baseUrl + "/v1/models");
    }

    /**
     * 归一化 baseUrl。
     *
     * <p>Spring AI 的默认 completionsPath 是 {@code /v1/chat/completions}，也就是 baseUrl 里
     * <b>不该</b>带 {@code /v1}。但用户在设置里手填 {@code https://api.openai.com/v1} 是最常见的错误，
     * 硬拼会得到 {@code /v1/v1/chat/completions}。这里统一剥掉末尾的 {@code /v1}。
     */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String url = baseUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
        }
        return url;
    }

    private static String normalizeModel(String model) {
        return model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String pickModel(ChatRequest request, Endpoint ep) {
        String m = request == null ? null : request.model();
        if (m != null && !m.isBlank()) {
            return m.strip();
        }
        String d = ep.defaultModel();
        return d == null || d.isBlank() ? null : d;
    }

    /** 一次构建好的接入端；reconfigure 时整体替换。 */
    private record Endpoint(String baseUrl, String defaultModel, String apiKey,
                            OpenAiApi api, OpenAiChatModel model, RestClient rest, String modelsUrl) {
        boolean hasKey() {
            return apiKey != null;
        }
    }

    /** 同 Ollama 通道：可用位与模型列表各自计时，互不冲掉。 */
    private record ProbeCache(long availableAtNanos, boolean available,
                              long modelsAtNanos, List<String> models) {

        static ProbeCache empty() {
            return new ProbeCache(0L, false, 0L, List.of());
        }

        ProbeCache(long nowNanos, boolean available) {
            this(nowNanos, available, 0L, List.of());
        }

        ProbeCache(long nowNanos, boolean available, List<String> models) {
            this(nowNanos, available, nowNanos, models);
        }

        boolean valid(long nowNanos, Duration ttl) {
            return availableAtNanos != 0L && nowNanos - availableAtNanos < ttl.toNanos();
        }

        boolean modelsValid(long nowNanos, Duration ttl) {
            return modelsAtNanos != 0L && nowNanos - modelsAtNanos < ttl.toNanos();
        }
    }
}
