package com.gmscpt.server.config;

import com.gmscpt.server.ai.AiGateway;
import com.gmscpt.server.domain.Settings;
import com.gmscpt.server.kernel.input.Accelerator;
import com.gmscpt.server.kernel.input.EchoSuppressor;
import com.gmscpt.server.kernel.input.GlobalHook;
import com.gmscpt.server.kernel.input.HotkeyRegistry;
import com.gmscpt.server.kernel.input.InputRouter;
import com.gmscpt.server.kernel.input.InputState;
import com.gmscpt.server.kernel.input.RobotInputGateway;
import com.gmscpt.server.kernel.lua.DocumentationHostContext;
import com.gmscpt.server.kernel.lua.LuaApiDoc;
import com.gmscpt.server.kernel.lua.LuaApiRegistry;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.kernel.log.RingLogBus;
import com.gmscpt.server.kernel.runtime.RuntimeManager;
import com.gmscpt.server.kernel.runtime.RuntimeManagerImpl;
import com.gmscpt.server.service.ChatService;
import com.gmscpt.server.service.KnowledgeService;
import com.gmscpt.server.service.RunService;
import com.gmscpt.server.service.ScriptService;
import com.gmscpt.server.service.SettingsApplier;
import com.gmscpt.server.service.SettingsService;
import com.gmscpt.server.store.AsyncLogRepository;
import com.gmscpt.server.store.Database;
import com.gmscpt.server.store.KnowledgeRepository;
import com.gmscpt.server.store.LogRepository;
import com.gmscpt.server.store.RunRepository;
import com.gmscpt.server.store.ScriptFileStore;
import com.gmscpt.server.store.ScriptRepository;
import com.gmscpt.server.store.SettingsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 组合根：整个内核在这里被显式装配起来。
 *
 * <p>为什么手写而不是靠 {@code @ComponentScan}：内核层（{@code kernel}）刻意不依赖 Spring，
 * 这样才能脱离 Web 容器跑单元测试，也方便将来换 Web 框架。装配知识集中在这一个文件里，
 * 比散落在十几个 {@code @Component} 上更好审。
 */
@Configuration
public class KernelConfig {

    private static final Logger log = LoggerFactory.getLogger(KernelConfig.class);

    private Database database;
    private AsyncLogRepository logRepository;

    // ---------------- 存储 ----------------

    @Bean
    public Database database(GmScriptProperties props) {
        Path dataDir = resolveDataDir(props);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录: " + dataDir, e);
        }
        this.database = new Database(dataDir);
        // 种子导入由 Database 的构造过程负责（幂等：仅 kb_entry 为空时导入），这里不再重复调用
        return database;
    }

    private Path resolveDataDir(GmScriptProperties props) {
        String configured = props.getDataDir();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("user.home"), ".gmscpt");
    }

    @Bean
    public ScriptFileStore scriptFileStore(GmScriptProperties props) {
        return new ScriptFileStore(resolveDataDir(props));
    }

    @Bean
    public LogRepository logRepository(Database db) {
        this.logRepository = new AsyncLogRepository(db);
        return logRepository;
    }

    @Bean
    public com.gmscpt.server.store.ScriptRepository scriptRepository(Database db, ScriptFileStore files) {
        return new com.gmscpt.server.store.SqlScriptRepository(db, files);
    }

    @Bean
    public RunRepository runRepository(Database db) {
        return new com.gmscpt.server.store.SqlRunRepository(db);
    }

    @Bean
    public com.gmscpt.server.store.ChatRepository chatRepository(Database db) {
        return new com.gmscpt.server.store.SqlChatRepository(db);
    }

    @Bean
    public KnowledgeRepository knowledgeRepository(Database db) {
        return new com.gmscpt.server.store.SqlKnowledgeRepository(db);
    }

    @Bean
    public SettingsRepository settingsRepository(Database db) {
        return new com.gmscpt.server.store.SqlSettingsRepository(db);
    }

    // ---------------- 日志 ----------------

    @Bean
    public LogBus logBus(LogRepository repository, GmScriptProperties props) {
        return new RingLogBus(repository, props);
    }

    // ---------------- 输入内核 ----------------

    @Bean
    public InputState inputState() {
        return new InputState();
    }

    @Bean
    public EchoSuppressor echoSuppressor() {
        return new EchoSuppressor();
    }

    @Bean
    public HotkeyRegistry hotkeyRegistry() {
        return new HotkeyRegistry();
    }

    @Bean
    public RobotInputGateway inputGateway(InputState state, EchoSuppressor echo,
                                          LogBus logBus, GmScriptProperties props) {
        return new RobotInputGateway(state, echo, logBus, props);
    }

    @Bean
    public InputRouter inputRouter(InputState state, EchoSuppressor echo,
                                   HotkeyRegistry hotkeys, LogBus logBus) {
        return new InputRouter(state, echo, hotkeys, logBus);
    }

    @Bean
    public GlobalHook globalHook(InputRouter router, LogBus logBus) {
        return new GlobalHook(router, logBus);
    }

    // ---------------- 运行期 ----------------

    @Bean
    public RuntimeManagerImpl runtimeManager(GmScriptProperties props, LogBus logBus,
                                             RobotInputGateway gateway, InputState state,
                                             RunRepository runRepository) {
        return new RuntimeManagerImpl(props, logBus, gateway, state, runRepository);
    }

    @Bean
    public LuaApiDoc luaApiDoc() {
        // 只为取文档：真正执行时的注册在 ScriptRuntime 里按脚本重新建
        return LuaApiRegistry.create(DocumentationHostContext.INSTANCE);
    }

    // ---------------- 用例 ----------------

    @Bean
    public SettingsService settingsService(SettingsRepository repository) {
        return new SettingsService(repository);
    }

    @Bean
    public ScriptService scriptService(ScriptRepository repository, LogBus logBus, HotkeyRegistry registry) {
        return new ScriptService(repository, logBus, registry);
    }

    @Bean
    public RunService runService(RuntimeManagerImpl manager, ScriptRepository repository, LogBus logBus,
                                 InputRouter router) {
        RunService service = new RunService(manager, repository, logBus);
        // 热键执行端挂在这里：钩子线程只投递，真正的启停在本服务的单线程里做
        router.setHotkeyHandler(service);
        router.setRuntimeManager(manager);
        return service;
    }

    @Bean
    public KnowledgeService knowledgeService(KnowledgeRepository repository, LogBus logBus) {
        return new KnowledgeService(repository, logBus);
    }

    @Bean
    public ChatService chatService(com.gmscpt.server.store.ChatRepository chatRepository,
                                   KnowledgeService knowledgeService,
                                   SettingsService settingsService,
                                   LuaApiDoc apiDoc,
                                   LogBus logBus,
                                   List<AiGateway> gateways) {
        return new ChatService(chatRepository, knowledgeService, settingsService, apiDoc, logBus, gateways);
    }

    /**
     * 设置变更的落地：急停热键立即换、AI 通道地址立即重建客户端。
     *
     * <p>旧版改配置要重启程序（SettingsDialog 只写文件不重载）。
     * 两个通道各自的 {@code reconfigure} 签名不同（Ollama 不需要 apiKey），
     * 所以在这里按类型直接调用，而不是硬套一个统一接口。
     */
    @Bean
    public SettingsApplier settingsApplier(InputRouter router,
                                           com.gmscpt.server.ai.OllamaAiGateway ollama,
                                           com.gmscpt.server.ai.OpenAiCompatAiGateway cloud) {
        return (settings, cloudApiKey) -> {
            router.setEmergencyAccelerator(settings.emergencyHotkey());
            ollama.reconfigure(settings.ollamaBaseUrl(), settings.ollamaModel());
            String key = cloudApiKey;
            if (key == null) {
                key = settings.hasCloudApiKey() ? null : "";
            }
            cloud.reconfigure(settings.cloudBaseUrl(), key, settings.cloudModel());
        };
    }

    // ---------------- AI 通道 ----------------

    /**
     * 本地 Ollama（默认通道）。
     *
     * <p>初始值与 application.yml 里的一致；之后由 {@link #settingsApplier} 按界面设置热重配。
     */
    @Bean
    public com.gmscpt.server.ai.OllamaAiGateway ollamaAiGateway(SettingsService settingsService) {
        Settings s = settingsService.get();
        return new com.gmscpt.server.ai.OllamaAiGateway(s.ollamaBaseUrl(), s.ollamaModel());
    }

    /**
     * 任意 OpenAI 兼容端点（云端 API，或别的兼容服务）。
     *
     * <p>未配置密钥时依然要建出来（界面要能看到这个通道、能填地址），
     * 只是 {@code available()} 会是 false。
     */
    @Bean
    public com.gmscpt.server.ai.OpenAiCompatAiGateway openAiCompatAiGateway(SettingsService settingsService) {
        Settings s = settingsService.get();
        return new com.gmscpt.server.ai.OpenAiCompatAiGateway(
                "openai-compat", s.cloudBaseUrl(), settingsService.cloudApiKey(), s.cloudModel());
    }

    @PreDestroy
    public void closeResources() {
        if (logRepository != null) {
            try {
                logRepository.close();
            } catch (Exception e) {
                log.warn("关闭日志仓储失败: {}", e.toString());
            }
        }
        if (database != null) {
            try {
                database.close();
            } catch (Exception e) {
                log.warn("关闭数据库失败: {}", e.toString());
            }
        }
    }
}
