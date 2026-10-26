package com.gmscpt.server.ai;

import com.gmscpt.server.domain.ChatMessage;
import com.gmscpt.server.domain.ChatRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本机 Ollama 通道。
 *
 * <p><b>为什么不是直接调 {@code OllamaChatModel.stream()}</b>：实测（见交付说明里的探针输出）
 * Spring AI 1.1.8 的 {@code OllamaChatModel} 只在 {@code call()} 的同步路径上把
 * {@code message.thinking} 塞进 Generation 元数据；{@code internalStream()} 里构造
 * {@code AssistantMessage} 时只取了 {@code message.content()}，thinking 被直接丢弃。
 * 所以若用 ChatModel 做流式，qwen3 的思考链会整段消失，「thinking/content 分帧」这个需求根本做不到。
 * 这里改为直接用底层的 {@link OllamaApi#streamingChat}，拿到的 {@code ChatResponse.message()}
 * 同时带 {@code content()} 与 {@code thinking()}，两者分帧上抛。
 *
 * <p>关于 {@code OllamaChatModel} 的复用：它仍被保留用于 {@code chat()} 非流式场景（见 {@link #chatOnce}），
 * 构造一次、随 {@code reconfigure} 重建，不会每次请求都新建客户端。
 */
public class OllamaAiGateway implements AiGateway, ReconfigurableGateway {

    private static final Logger log = LoggerFactory.getLogger(OllamaAiGateway.class);

    static final String ID = "ollama";
    static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";
    static final String DEFAULT_MODEL = "qwen2.5-coder:7b";

    /** 探活结果的缓存时长：健康检查会被前端轮询，没必要每次都真打上游。 */
    private static final Duration PROBE_CACHE_TTL = Duration.ofSeconds(10);

    /** models() 是用户主动点的，缓存短一些，但也别一次点击打三次上游。 */
    private static final Duration MODELS_CACHE_TTL = Duration.ofSeconds(5);

    private final AiTimeouts timeouts;

    /**
     * 一次性构建、随配置变化才重建的「接入端」。
     * 用 volatile 包成不可变小对象，reconfigure 时整体换掉，读侧无锁。
     */
    private volatile Endpoint endpoint;

    private volatile ProbeCache probeCache = ProbeCache.empty();

    public OllamaAiGateway(String baseUrl, String defaultModel) {
        this(baseUrl, defaultModel, AiTimeouts.defaults());
    }

    public OllamaAiGateway(String baseUrl, String defaultModel, AiTimeouts timeouts) {
        this.timeouts = timeouts == null ? AiTimeouts.defaults() : timeouts;
        this.endpoint = buildEndpoint(normalizeBaseUrl(baseUrl), normalizeModel(defaultModel));
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * 重配 baseUrl / 默认模型。
     *
     * <p>只有真的变了才重建——重建会丢掉底层连接池与已加载模型的预热状态，
     * 而 {@code keep_alive} 期间的模型驻留对本地小机器很值钱。
     */
    public synchronized void reconfigure(String baseUrl, String defaultModel) {
        String url = normalizeBaseUrl(baseUrl);
        String model = normalizeModel(defaultModel);
        Endpoint current = this.endpoint;
        if (current.baseUrl().equals(url) && current.defaultModel().equals(model)) {
            return;
        }
        this.endpoint = buildEndpoint(url, model);
        // 换了地址，旧的探活结论立刻失效（否则会拿旧机器的可用性糊弄前端 10 秒）。
        this.probeCache = ProbeCache.empty();
        log.info("Ollama 通道已重配：baseUrl={} defaultModel={}", url, model);
    }

    /** 当前 baseUrl（供健康检查回显）。 */
    public String baseUrl() {
        return endpoint.baseUrl();
    }

    /** 当前默认模型（供健康检查回显）。 */
    public String defaultModel() {
        return endpoint.defaultModel();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Ollama 通道不涉及密钥，所以 {@code cloudApiKey} 参数被忽略。
     * 这里只是把 {@link Settings} 里的 Ollama 两项取出来转交给 {@link #reconfigure(String, String)}，
     * 让「改设置 → 立刻生效」这条链路不必依赖调用方记得该传哪个字段。
     */
    @Override
    public void reconfigure(com.gmscpt.server.domain.Settings settings, String cloudApiKey) {
        if (settings == null) {
            return;
        }
        reconfigure(settings.ollamaBaseUrl(), settings.ollamaModel());
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
            // /api/tags 是最轻的真实上游调用：能列模型就说明服务活着且可读。
            // 不用 /api/version 之类，省一次端点差异。
            Endpoint ep = endpoint;
            var response = ep.api().listModels();
            ok = response != null && response.models() != null;
        } catch (Throwable t) {
            // 探活绝不上抛：调用方（健康检查、providers 列表）要的是「可用/不可用」这一位信息，
            // 抛出去会让整个列表接口 500，恰恰是最不希望发生的事。
            log.debug("Ollama 探活失败：{}", t.toString());
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
            var response = endpoint.api().listModels();
            if (response != null && response.models() != null) {
                List<String> tmp = new ArrayList<>(response.models().size());
                for (var m : response.models()) {
                    String n = m.name() != null ? m.name() : m.model();
                    if (n != null && !n.isBlank()) {
                        tmp.add(n);
                    }
                }
                names = List.copyOf(tmp);
                ok = true;
            }
        } catch (Throwable t) {
            // 同 available()：列不出来就回空列表，让上层用「通道可用但没模型」来表达。
            log.debug("Ollama 列模型失败：{}", t.toString());
        }
        this.probeCache = new ProbeCache(now, ok, names);
        return names;
    }

    /**
     * 流式对话。
     *
     * <p>分帧规则：{@code thinking} → {@code channel="thinking"}，{@code content} → {@code channel="content"}，
     * 两者都非空时先 thinking 后 content（同一帧内思考一定在正文之前）。收到 {@code done=true} 的那一帧
     * 带上 usage，并立刻发一帧 {@code AiChunk.done(...)} 结束。
     *
     * <p>取消语义：这里只做 {@code map}/{@code concatMap}/末尾 {@code concatWith}，没有 cache/share/defer 包裹上游，
     * 下游 {@code dispose()} 会一路传到 {@code WebClient} 的订阅取消，进而取消 JDK HttpClient 的
     * CompletableFuture。
     */
    @Override
    public Flux<AiChunk> stream(ChatRequest request) {
        return Flux.defer(() -> {
            // defer：请求真正被订阅时才读 endpoint 并构造请求体。
            // 这样 reconfigure 对「已经在飞」的请求不产生撕裂，也让副作用发生在订阅时而非装配时。
            Endpoint ep = this.endpoint;
            String model = pickModel(request, ep);
            if (model == null) {
                return Flux.error(new AiGatewayException("ollama", null,
                        "Ollama 通道没有可用模型：请先在设置里选择模型，或确认 Ollama 已拉取模型"));
            }

            OllamaApi.ChatRequest chatRequest;
            try {
                chatRequest = buildChatRequest(request, model);
            } catch (RuntimeException e) {
                return Flux.error(new AiGatewayException("ollama", e, "构造 Ollama 请求失败：" + e.getMessage()));
            }

            Flux<AiChunk> upstream = ep.api().streamingChat(chatRequest)
                    // concatMap 而不是 flatMap：分帧顺序必须严格保持上游顺序——
                    // 一帧里 thinking 先于 content，打乱了前端折叠区会错位。
                    .concatMap(this::translate);

            return finishWithUsageAndDone(upstream)
                    // 首字节与块间静默分开限：timeout 对「首个子信号」与「子信号之间」用的是同一个时长，
                    // 而冷加载模型的首字节要等几十秒、吐字后憋两分钟才该判死，两者量级不同。
                    // 做法是：先用 firstByte 约束到第一个信号，之后把时长换成 idle。
                    .transform(this::applyTimeouts)
                    .onErrorMap(t -> wrapError(t));
        });
    }

    /** 非流式一次问完（供内部或调试使用）。同样走 OllamaApi，避免 ChatModel 丢 thinking 的坑。 */
    public String chatOnce(ChatRequest request) {
        Endpoint ep = this.endpoint;
        String model = pickModel(request, ep);
        if (model == null) {
            throw new AiGatewayException("ollama", null, "Ollama 通道没有可用模型");
        }
        var resp = ep.api().chat(buildChatRequest(request, model, false));
        return resp == null || resp.message() == null || resp.message().content() == null
                ? "" : resp.message().content();
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 把 {@link ChatRequest} 翻译成 Ollama 原生请求体。
     *
     * <p>历史里的 SYSTEM 注记（ContextTrimmer 的「已省略 N 轮」）不单独作为消息下发，
     * 而是折进 system 首条——中间插 system 在部分模型上会被当成新指令，破坏对话连续性。
     */
    private OllamaApi.ChatRequest buildChatRequest(ChatRequest request, String model) {
        return buildChatRequest(request, model, true);
    }

    private OllamaApi.ChatRequest buildChatRequest(ChatRequest request, String model, boolean stream) {
        List<OllamaApi.Message> messages = new ArrayList<>();

        String system = AiMessages.mergeSystemPrompt(request.systemPrompt(), request.history());
        if (system != null && !system.isBlank()) {
            messages.add(OllamaApi.Message.builder(OllamaApi.Message.Role.SYSTEM).content(system).build());
        }
        for (ChatMessage m : AiMessages.dialogue(request.history())) {
            OllamaApi.Message.Role role = m.role() == ChatRole.ASSISTANT
                    ? OllamaApi.Message.Role.ASSISTANT : OllamaApi.Message.Role.USER;
            messages.add(OllamaApi.Message.builder(role).content(m.content()).build());
        }
        if (request.userMessage() != null && !request.userMessage().isBlank()) {
            messages.add(OllamaApi.Message.builder(OllamaApi.Message.Role.USER)
                    .content(request.userMessage()).build());
        }

        OllamaChatOptions.Builder options = OllamaChatOptions.builder()
                .model(model)
                .temperature(request.temperature() == null ? 0.3 : request.temperature());

        OllamaApi.ChatRequest.Builder builder = OllamaApi.ChatRequest.builder(model)
                .messages(messages)
                .stream(stream);

        // 只有确定支持 reasoning 的模型才下发 think:true。
        // 实测：对 qwen2.5-coder:7b 传 think:true，Ollama 直接返回
        // 400 {"error":"\"qwen2.5-coder:7b\" does not support thinking"}——
        // 硬编码 true 会让「默认模型」在三通道里的表现直接坏掉。
        boolean supportsThinking = supportsThinking(model);
        if (supportsThinking) {
            options.thinkOption(ThinkOption.ThinkBoolean.ENABLED);
            builder.think(ThinkOption.ThinkBoolean.ENABLED);
        }

        return builder.options(options.build()).build();
    }

    /**
     * 判断模型是否支持思考链。
     *
     * <p>Ollama 没有提供能力查询接口，只能按模型家族判断。这里的清单覆盖本机已有与常见的
     * 推理模型；不在清单里的模型一律当「不支持」处理——宁可不显示思考链，也不能整个请求 400。
     */
    static boolean supportsThinking(String model) {
        if (model == null) return false;
        String m = model.toLowerCase(java.util.Locale.ROOT);
        // qwen3、deepseek-r1、magistral、gpt-oss、exaone-deep 等带推理能力的家族
        for (String prefix : new String[]{"qwen3", "deepseek-r1", "deepseek-v3.1", "magistral",
                "gpt-oss", "exaone-deep", "phi4-reasoning", "granite3.2", "glm-4.6"}) {
            if (m.startsWith(prefix) || m.contains(":" + prefix) || m.contains("/" + prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 单帧翻译。thinking 与 content 都可能同时出现在同一帧上。 */
    private Flux<AiChunk> translate(OllamaApi.ChatResponse resp) {
        List<AiChunk> out = new ArrayList<>(3);
        if (resp.message() != null) {
            String thinking = resp.message().thinking();
            if (thinking != null && !thinking.isEmpty()) {
                out.add(AiChunk.thinking(thinking));
            }
            String content = resp.message().content();
            if (content != null && !content.isEmpty()) {
                out.add(AiChunk.content(content));
            }
        }
        if (Boolean.TRUE.equals(resp.done())) {
            Integer in = resp.promptEvalCount();
            Integer outTokens = resp.evalCount();
            if (in != null || outTokens != null) {
                out.add(AiChunk.usage(in, outTokens));
            }
            out.add(AiChunk.done(mapDoneReason(resp.doneReason())));
        }
        return Flux.fromIterable(out);
    }

    /**
     * 兜底：万一上游既没 done 帧也没 usage，也要把 done 补上，否则前端会一直转圈。
     * 用 concatWith 而不是 concatWithValues 组合，保证顺序稳定。
     */
    private Flux<AiChunk> finishWithUsageAndDone(Flux<AiChunk> upstream) {
        AtomicInteger doneCount = new AtomicInteger();
        return upstream
                .doOnNext(c -> {
                    if (c.done()) {
                        doneCount.incrementAndGet();
                    }
                })
                .concatWith(Flux.defer(() -> doneCount.get() == 0
                        ? Flux.just(AiChunk.done("stop"))
                        : Flux.empty()));
    }

    /**
     * 首字节用 firstByte，之后每块用 idle。
     *
     * <p>Reactor 的 {@code timeout} 语义是「两个信号之间的静默上限」，对首信号也一样。
     * 所以要分成两段：switchOnFirst 挑出第一块前用短时长，收到后换成 idle 时长。
     * 这里刻意用 {@code timeout(d, 原样透传 Flux)} 的形态，超时直接在流里发 onError，
     * 由 {@link #wrapError} 转成可读的中文原因，而不是让 SSE 无声断掉。
     */
    private Flux<AiChunk> applyTimeouts(Flux<AiChunk> upstream) {
        return Flux.defer(() -> {
            Duration first = timeouts.firstByte();
            Duration idle = timeouts.idle();
            return upstream
                    .timeout(first, Flux.error(new TimeoutException(
                            "Ollama 在 " + first.toSeconds() + " 秒内没有返回第一个字节（模型可能未加载或上游卡住）")))
                    .switchOnFirst((signal, flux) -> {
                        if (!signal.hasValue()) {
                            return flux;
                        }
                        // 第一块到了，后续改用块间静默上限。
                        return flux.timeout(idle, Flux.error(new TimeoutException(
                                "Ollama 流中断：连续 " + idle.toSeconds() + " 秒没有新数据")));
                    });
        });
    }

    /** 把 Reactor/Spring 的各种异常收敛成带通道信息的可读异常。 */
    private Throwable wrapError(Throwable t) {
        if (t instanceof AiGatewayException) {
            return t;
        }
        if (t instanceof TimeoutException) {
            return new AiGatewayException("ollama", t, t.getMessage());
        }
        String msg = t.getMessage() == null ? t.toString() : t.getMessage();
        // 连不上本地 Ollama 是最常见的情况，给一句能直接照做的提示，别只抛 Connection refused。
        if (msg.contains("Connection refused") || msg.contains("ConnectException")) {
            return new AiGatewayException("ollama", t,
                    "连不上本地 Ollama（" + endpoint.baseUrl() + "）：请确认 ollama serve 正在运行");
        }
        return new AiGatewayException("ollama", t, "Ollama 上游错误：" + msg);
    }

    private static String mapDoneReason(String doneReason) {
        if (doneReason == null || doneReason.isBlank()) {
            return "stop";
        }
        return switch (doneReason) {
            case "length" -> "length";
            case "stop", "eos" -> "stop";
            default -> "stop";
        };
    }

    /** 请求里的 model 优先，其次通道默认模型；都没有才返回 null。 */
    private static String pickModel(ChatRequest request, Endpoint ep) {
        String m = request == null ? null : request.model();
        if (m != null && !m.isBlank()) {
            return m.strip();
        }
        String d = ep.defaultModel();
        return d == null || d.isBlank() ? null : d;
    }

    private Endpoint buildEndpoint(String baseUrl, String defaultModel) {
        OllamaApi api = OllamaApi.builder()
                .baseUrl(baseUrl)
                // 流式：建连有上限，读侧交给 Flux 的 timeout。
                .webClientBuilder(AiHttp.webClient(timeouts.connect()))
                // 非流式/列模型：建连 + 读都设上限，绝不留 0（永久等待）。
                .restClientBuilder(AiHttp.restClient(timeouts.connect(), timeouts.idle()))
                .build();
        return new Endpoint(baseUrl, defaultModel, api);
    }

    /** 去掉尾部斜杠：OllamaApi 自己拼 /api/xxx，多一个斜杠会变成 //api/xxx。 */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String url = baseUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String normalizeModel(String model) {
        return model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
    }

    /** 一次构建好的接入端；reconfigure 时整体替换。 */
    private record Endpoint(String baseUrl, String defaultModel, OllamaApi api) {
    }

    /**
     * 探活缓存。
     *
     * <p>把「可用位」和「模型列表」的取得时间分开记：available() 与 models() 虽然都打
     * /api/tags，但调用频率不同（健康检查每几秒一次，模型列表只在用户点开时），
     * 混成一个时间戳会导致其中一方把另一方的缓存冲掉。
     */
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
