package com.gmscpt.server.api;

import com.gmscpt.server.domain.HotkeyBinding;
import com.gmscpt.server.domain.Script;
import com.gmscpt.server.domain.ScriptStatus;
import com.gmscpt.server.kernel.lua.LuaCompiler;
import com.gmscpt.server.service.ScriptService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 脚本库接口。语义见 {@code docs/api-contract.md} 第 2、4 节。 */
@RestController
@RequestMapping("/api/scripts")
public class ScriptController {

    public record CreateRequest(String name, String description, String source) {
    }

    public record UpdateRequest(String name, String description, String source, Long rev) {
    }

    public record ValidateRequest(String source) {
    }

    public record ImportRequest(String filename, String content) {
    }

    public record StatusRequest(String status) {
    }

    public record HotkeyItem(String accelerator, String event, String action, Boolean enabled) {
    }

    public record HotkeysRequest(List<HotkeyItem> items) {
    }

    private final ScriptService scriptService;

    public ScriptController(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("items", scriptService.list());
    }

    @PostMapping
    public ResponseEntity<Script> create(@RequestBody CreateRequest req) {
        Script created = scriptService.create(req.name(), req.description(), req.source());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public Script get(@PathVariable String id) {
        return scriptService.get(id);
    }

    @PutMapping("/{id}")
    public Script update(@PathVariable String id, @RequestBody UpdateRequest req) {
        long rev = req.rev() == null ? scriptService.get(id).rev() : req.rev();
        return scriptService.update(id, req.name(), req.description(), req.source(), rev);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        scriptService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/status")
    public Script setStatus(@PathVariable String id, @RequestBody StatusRequest req) {
        scriptService.setStatus(id, ScriptStatus.valueOf(req.status()));
        return scriptService.get(id);
    }

    @PostMapping("/{id}/validate")
    public Map<String, Object> validate(@PathVariable String id, @RequestBody(required = false) ValidateRequest req) {
        String source = req == null || req.source() == null ? scriptService.get(id).source() : req.source();
        LuaCompiler.Result result = scriptService.validate(source);
        List<Map<String, Object>> diagnostics = result.diagnostics().stream()
                .map(d -> Map.<String, Object>of("line", d.line(), "column", d.column(), "message", d.message()))
                .toList();
        return Map.of("ok", result.ok(), "diagnostics", diagnostics,
                "hasOnEvent", LuaCompiler.hasOnEvent(source));
    }

    @PostMapping("/import")
    public ResponseEntity<Script> importLua(@RequestBody ImportRequest req) {
        Script created = scriptService.importLua(req.filename(), req.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping(value = "/{id}/export", produces = MediaType.TEXT_PLAIN_VALUE)
    public String export(@PathVariable String id) {
        return scriptService.exportLua(id);
    }

    @GetMapping("/{id}/hotkeys")
    public Map<String, Object> hotkeys(@PathVariable String id) {
        return Map.of("items", scriptService.hotkeys(id));
    }

    @PutMapping("/{id}/hotkeys")
    public Map<String, Object> saveHotkeys(@PathVariable String id, @RequestBody HotkeysRequest req) {
        List<HotkeyBinding> bindings = req.items() == null ? List.of() : req.items().stream()
                .map(i -> new HotkeyBinding(null, id, i.accelerator(),
                        com.gmscpt.server.domain.HotkeyEvent.valueOf(i.event() == null ? "KEY_PRESSED" : i.event()),
                        com.gmscpt.server.domain.HotkeyAction.valueOf(i.action() == null ? "TOGGLE" : i.action()),
                        i.enabled() == null || i.enabled()))
                .toList();
        return Map.of("items", scriptService.saveHotkeys(id, bindings));
    }
}
