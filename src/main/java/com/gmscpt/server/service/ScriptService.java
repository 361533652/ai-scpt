package com.gmscpt.server.service;

import com.gmscpt.server.domain.HotkeyBinding;
import com.gmscpt.server.domain.LogLevel;
import com.gmscpt.server.domain.Script;
import com.gmscpt.server.domain.ScriptStatus;
import com.gmscpt.server.kernel.input.Accelerator;
import com.gmscpt.server.kernel.input.HotkeyConflictException;
import com.gmscpt.server.kernel.input.HotkeyRegistry;
import com.gmscpt.server.kernel.lua.LuaCompiler;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.store.RevConflictException;
import com.gmscpt.server.store.ScriptRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 脚本库用例：多脚本 CRUD、导入导出、编译校验、热键绑定。
 *
 * <p>旧版这里根本不存在——只有一个编辑器缓冲加一个 {@code AppConfig.lastScript} 字符串，
 * 「运行」时把当前文本塞进去就算完事，于是既没有脚本列表，也没法给不同脚本绑不同热键。
 */
public class ScriptService {

    private final ScriptRepository repository;
    private final LogBus logBus;
    private final HotkeyRegistry hotkeyRegistry;

    public ScriptService(ScriptRepository repository, LogBus logBus, HotkeyRegistry hotkeyRegistry) {
        this.repository = repository;
        this.logBus = logBus;
        this.hotkeyRegistry = hotkeyRegistry;
    }

    public List<Script> list() {
        return repository.findAll();
    }

    public Script get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> ServiceException.notFound("脚本不存在: " + id));
    }

    public Script create(String name, String description, String source) {
        if (name == null || name.isBlank()) {
            throw ServiceException.badRequest("脚本名不能为空");
        }
        Script script = new Script(UUID.randomUUID().toString(), name.trim(), description,
                source == null ? defaultTemplate(name) : source, 1L, ScriptStatus.ENABLED,
                Instant.now(), Instant.now());
        Script saved = repository.insert(script);
        logBus.publish(LogLevel.INFO, "script", saved.id(), null, "新建脚本：" + saved.name());
        return saved;
    }

    public Script update(String id, String name, String description, String source, long expectedRev) {
        Script current = get(id);
        try {
            Script updated = repository.update(new Script(current.id(),
                    name == null || name.isBlank() ? current.name() : name.trim(),
                    description == null ? current.description() : description,
                    source == null ? current.source() : source,
                    current.rev(), current.status(), current.createdAt(), Instant.now()), expectedRev);
            logBus.publish(LogLevel.INFO, "script", id, null, "保存脚本（rev " + updated.rev() + "）");
            return updated;
        } catch (RevConflictException e) {
            throw ServiceException.conflict("REV_CONFLICT",
                    "脚本已被其他操作修改（当前 rev=" + e.getActual() + "，你提交的是 " + e.getExpected() + "），请先刷新再改");
        }
    }

    public void delete(String id) {
        get(id);
        repository.delete(id);
        reloadHotkeys();
        logBus.publish(LogLevel.INFO, "script", id, null, "删除脚本");
    }

    public void setStatus(String id, ScriptStatus status) {
        get(id);
        repository.updateStatus(id, status);
    }

    public LuaCompiler.Result validate(String source) {
        return LuaCompiler.compile("validate", source);
    }

    public Script importLua(String filename, String content) {
        String name = filename == null || filename.isBlank() ? "导入的脚本" : filename.replaceFirst("(?i)\\.lua$", "");
        return create(name, "从 " + (filename == null ? "文本" : filename) + " 导入", content);
    }

    public String exportLua(String id) {
        return get(id).source();
    }

    // ---------------- 热键 ----------------

    public List<HotkeyBinding> hotkeys(String scriptId) {
        get(scriptId);
        return repository.findHotkeysByScript(scriptId);
    }

    /** 全量替换某脚本的热键；冲突时返回 409 且不写入。 */
    public List<HotkeyBinding> saveHotkeys(String scriptId, List<HotkeyBinding> items) {
        get(scriptId);
        List<HotkeyBinding> normalized = new ArrayList<>();
        for (HotkeyBinding b : items) {
            // 落库前把热键表达式规范化（修饰键顺序统一为 ctrl+alt+shift+meta）。
            // 不规范化的话同一个组合会以 "shift+ctrl+f9" / "ctrl+shift+f9" 两种形态共存，
            // 冲突检测靠 canonical() 才能救回来，但界面上会显示成用户随手敲的顺序，
            // 重启后再读回来又变一种写法——对不上账。这里一次性定死。
            String canonical = Accelerator.parse(b.accelerator()).canonical();
            normalized.add(new HotkeyBinding(
                    b.id() == null || b.id().isBlank() ? UUID.randomUUID().toString() : b.id(),
                    scriptId, canonical, b.event(), b.action(), b.enabled()));
        }
        try {
            HotkeyRegistry.checkConflicts(normalized);
            repository.replaceHotkeys(scriptId, normalized);
            reloadHotkeys();
            return normalized;
        } catch (HotkeyConflictException e) {
            throw ServiceException.conflict("ACCEL_CONFLICT", e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ServiceException.badRequest(e.getMessage());
        }
    }

    /** 从库里全量重载热键绑定表（启动时与每次改动后调用）。 */
    public void reloadHotkeys() {
        try {
            hotkeyRegistry.reload(repository.findHotkeys());
        } catch (HotkeyConflictException e) {
            logBus.publish(LogLevel.ERROR, "hotkey", null, null, "热键绑定冲突，已忽略本次重载：" + e.getMessage());
        }
    }

    public static String defaultTemplate(String name) {
        return """
                -- %s
                -- 脚本入口：OnEvent(event, arg)
                -- { 在任何地方按下的键都会进来，用 event 判断类型、用 arg / IsKeyPressed 判断按键

                local TOGGLE_KEY = "f8"

                function OnEvent(event, arg)
                    if event == KEY_PRESSED and IsKeyPressed(TOGGLE_KEY) then
                        OutputLogMessage("按下了 %%s", TOGGLE_KEY)
                    end
                end
                """.formatted(name);
    }
}
