package com.gmscpt.server.config;

import com.gmscpt.server.kernel.input.InputRouter;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.kernel.runtime.RuntimeManagerImpl;
import com.gmscpt.server.service.ChatService;
import com.gmscpt.server.service.ScriptService;
import com.gmscpt.server.service.SettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

/**
 * 启动与关停编排：注册全局钩子 → 装载热键 → 打印（并打开）带 token 的地址；退出时全部收尾。
 *
 * <p>顺序很重要：钩子必须真的注册成功再对外说「可以用热键」；
 * 旧版无论钩子是否注册成功，界面都显示运行正常。
 */
@Component
@Order(10)
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final GmScriptProperties props;
    private final AccessToken token;
    private final com.gmscpt.server.kernel.input.GlobalHook globalHook;
    private final ScriptService scriptService;
    private final SettingsService settingsService;
    private final InputRouter inputRouter;
    private final RuntimeManagerImpl runtimeManager;
    private final ChatService chatService;
    private final LogBus logBus;
    private final ApplicationContext context;
    private final com.gmscpt.server.service.SettingsApplier settingsApplier;

    public StartupRunner(GmScriptProperties props,
                         AccessToken token,
                         com.gmscpt.server.kernel.input.GlobalHook globalHook,
                         ScriptService scriptService,
                         SettingsService settingsService,
                         InputRouter inputRouter,
                         RuntimeManagerImpl runtimeManager,
                         ChatService chatService,
                         LogBus logBus,
                         ApplicationContext context,
                         com.gmscpt.server.service.SettingsApplier settingsApplier) {
        this.props = props;
        this.token = token;
        this.globalHook = globalHook;
        this.scriptService = scriptService;
        this.settingsService = settingsService;
        this.inputRouter = inputRouter;
        this.runtimeManager = runtimeManager;
        this.chatService = chatService;
        this.logBus = logBus;
        this.context = context;
        this.settingsApplier = settingsApplier;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 1. 全局钩子：失败也要继续启动（前端会显示「钩子未启用」并给出原因）
        boolean hooked = globalHook.register();
        if (!hooked) {
            log.warn("全局钩子未注册：热键不生效。原因见日志与 /api/health");
        }

        // 2. 热键绑定与设置
        scriptService.reloadHotkeys();
        var settings = settingsService.get();
        try {
            settingsApplier.apply(settings, null);
        } catch (RuntimeException e) {
            log.warn("启动时应用设置失败（先用默认值跑）：{}", e.getMessage());
        }

        // 3. 对外公布地址
        int port = 8790;
        if (context instanceof WebServerApplicationContext web) {
            port = web.getWebServer().getPort();
        }
        String url = "http://127.0.0.1:" + port + "/?token=" + token.value();
        logBus.info("startup", "GmScript 已启动，钩子" + (hooked ? "已注册" : "未注册") + "，地址：" + url);
        log.info("================================================================");
        log.info(" GmScript 已启动：{}", url);
        log.info(" 钩子状态：{}", hooked ? "已注册" : "未注册（热键不会生效）");
        log.info(" 沙箱：{}", com.gmscpt.server.kernel.lua.LuaSandbox.describe());
        log.info("================================================================");

        if (props.isOpenBrowser() && Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception e) {
                log.warn("自动打开浏览器失败，请手动访问上面的地址：{}", e.toString());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("GmScript 正在退出：停止脚本、取消 AI 请求、释放输入、卸载钩子");
        try {
            chatService.cancelAll();
        } catch (Throwable ignored) {
            // 关停路径不抛异常
        }
        try {
            runtimeManager.shutdown();
        } catch (Throwable t) {
            log.warn("停止脚本时出错: {}", t.toString());
        }
        try {
            globalHook.unregister();
        } catch (Throwable t) {
            log.warn("卸载钩子时出错: {}", t.toString());
        }
        log.info("已退出");
    }

    /** 供界面显示的急停热键（当前设置值）。 */
    public String emergencyHotkey() {
        return inputRouter.emergencyAccelerator();
    }
}
