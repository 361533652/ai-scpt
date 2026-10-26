package com.gmscpt.server.api;

import com.gmscpt.server.ai.AiGateway;
import com.gmscpt.server.config.AccessToken;
import com.gmscpt.server.config.GmScriptProperties;
import com.gmscpt.server.kernel.input.GlobalHook;
import com.gmscpt.server.service.RunService;
import com.gmscpt.server.service.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康与运行态。这是界面的第一屏依赖：钩子到底注册上没有、Ollama 通不通。
 *
 * <p>旧版钩子注册失败只写 {@code System.err}，而 jpackage 打出来的 exe 没有控制台
 * （{@code build-exe.ps1} 里没有 {@code --win-console}），用户看到的现象是
 * 「脚本显示在运行，但按什么键都没反应」，且无从排查。
 */
@RestController
public class HealthController {

    private final GlobalHook globalHook;
    private final RunService runService;
    private final SettingsService settingsService;
    private final GmScriptProperties props;
    private final AccessToken token;
    private final List<AiGateway> gateways;

    public HealthController(GlobalHook globalHook,
                            RunService runService,
                            SettingsService settingsService,
                            GmScriptProperties props,
                            AccessToken token,
                            List<AiGateway> gateways) {
        this.globalHook = globalHook;
        this.runService = runService;
        this.settingsService = settingsService;
        this.props = props;
        this.token = token;
        this.gateways = gateways;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> hook = new LinkedHashMap<>();
        hook.put("registered", globalHook.isRegistered());
        hook.put("error", globalHook.lastError());

        List<Map<String, Object>> providers = new ArrayList<>();
        for (AiGateway gw : gateways) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", gw.id());
            // 关键：健康检查**不能**去阻塞探活远端通道。
            // 云端通道（api.openai.com）在没网/没 key 时，一次探活就要等满超时，
            // 实测把 /api/health 拖到 20 秒——前端 8 秒就判超时，于是首屏永远显示「状态未知」。
            // 所以只有本机通道（ollama）做真实探活；远端通道只回报 configured，
            // 真正的连通性等用户在设置页点「测试」时再验。
            boolean isLocal = "ollama".equals(gw.id());
            List<String> models = List.of();
            boolean available = false;
            if (isLocal) {
                try {
                    models = gw.models();
                    available = !models.isEmpty() || gw.available();
                } catch (Throwable t) {
                    available = false;
                    models = List.of();
                }
            } else {
                available = settingsService.get().hasCloudApiKey();
            }
            p.put("available", available);
            p.put("probed", isLocal);
            p.put("models", models);
            providers.add(p);
        }

        String ollamaUrl = settingsService.get().ollamaBaseUrl();
        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("baseUrl", ollamaUrl);
        providers.stream().filter(p -> "ollama".equals(p.get("id"))).findFirst().ifPresent(p -> {
            ollama.put("reachable", p.get("available"));
            ollama.put("models", p.get("models"));
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", "2.0.0-SNAPSHOT");
        out.put("authRequired", !props.isAuthDisabled());
        out.put("tokenPreview", token.value().substring(0, 4) + "****");
        out.put("hook", hook);
        out.put("ollama", ollama);
        out.put("providers", providers);
        out.put("running", runService.active());
        out.put("sandbox", com.gmscpt.server.kernel.lua.LuaSandbox.describe());
        return out;
    }
}
