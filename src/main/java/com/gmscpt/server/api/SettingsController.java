package com.gmscpt.server.api;

import com.gmscpt.server.domain.Settings;
import com.gmscpt.server.service.SettingsApplier;
import com.gmscpt.server.service.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 设置接口。语义见 {@code docs/api-contract.md} 第 8 节（apiKey 只进不出）。 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    /** 请求体：cloudApiKey 传 null 表示不改动已存密钥，传空串表示清除。 */
    public record SettingsRequest(
            String providerId,
            String ollamaBaseUrl,
            String ollamaModel,
            String cloudBaseUrl,
            String cloudModel,
            String cloudApiKey,
            String emergencyHotkey,
            Boolean preciseRelativeMove,
            Integer eventQueueCapacity,
            Long stopGraceMs
    ) {
    }

    private final SettingsService settingsService;
    private final SettingsApplier applier;

    public SettingsController(SettingsService settingsService, SettingsApplier applier) {
        this.settingsService = settingsService;
        this.applier = applier;
    }

    @GetMapping
    public Settings get() {
        return settingsService.get();
    }

    @PutMapping
    public Settings put(@RequestBody SettingsRequest req) {
        Settings current = settingsService.get();
        Settings next = new Settings(
                value(req.providerId(), current.providerId()),
                value(req.ollamaBaseUrl(), current.ollamaBaseUrl()),
                value(req.ollamaModel(), current.ollamaModel()),
                value(req.cloudBaseUrl(), current.cloudBaseUrl()),
                value(req.cloudModel(), current.cloudModel()),
                current.hasCloudApiKey(),
                value(req.emergencyHotkey(), current.emergencyHotkey()),
                req.preciseRelativeMove() == null ? current.preciseRelativeMove() : req.preciseRelativeMove(),
                req.eventQueueCapacity() == null ? current.eventQueueCapacity() : req.eventQueueCapacity(),
                req.stopGraceMs() == null ? current.stopGraceMs() : req.stopGraceMs());
        Settings saved = settingsService.save(next, req.cloudApiKey());
        try {
            applier.apply(saved, req.cloudApiKey());
        } catch (RuntimeException e) {
            // 配置已经存下来了，但热应用失败（例如地址填错）——要让用户看见，不能静默
            throw new com.gmscpt.server.service.ServiceException("SETTINGS_APPLY_FAILED", 400,
                    "设置已保存，但应用失败：" + e.getMessage(), e);
        }
        return saved;
    }

    @GetMapping("/lua-api-placeholder")
    public Map<String, Object> placeholder() {
        return Map.of();
    }

    private static String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }
}
