package com.gmscpt.server.api;

import com.gmscpt.server.kernel.lua.LuaApiDoc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Lua API 文档接口：编辑器补全、AI 提示词、界面帮助三处共用同一份事实。
 *
 * <p>旧版把 API 文档写成了提示词里的手抄副本，实际注册的函数早就和它不一致了
 * （例如缺 {@code ResetMouseAccumulator}），于是 AI 生成「看起来对、跑起来 attempt to call nil」的脚本。
 */
@RestController
public class LuaApiController {

    private final LuaApiDoc apiDoc;

    public LuaApiController(LuaApiDoc apiDoc) {
        this.apiDoc = apiDoc;
    }

    @GetMapping("/api/lua/api")
    public Map<String, Object> api() {
        List<Map<String, String>> functions = apiDoc.functions().stream()
                .map(f -> Map.of("name", f.name(), "signature", f.signature(), "doc", f.doc()))
                .toList();
        return Map.of(
                "functions", functions,
                "keys", apiDoc.keyNames(),
                "mouseButtons", apiDoc.mouseButtonNames(),
                "events", apiDoc.eventConstants(),
                "sandbox", com.gmscpt.server.kernel.lua.LuaSandbox.describe());
    }
}
