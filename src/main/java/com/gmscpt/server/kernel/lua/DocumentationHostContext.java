package com.gmscpt.server.kernel.lua;

import com.gmscpt.server.kernel.input.InputGateway;
import com.gmscpt.server.kernel.input.InputState;

/**
 * 只用于「取文档」的 HostContext：{@code GET /api/lua/api} 与 AI 提示词需要一份
 * 函数清单，但不需要真的能执行。任何执行路径被走到都说明用错了，直接抛异常暴露出来。
 */
public final class DocumentationHostContext implements HostContext {

    public static final DocumentationHostContext INSTANCE = new DocumentationHostContext();

    private DocumentationHostContext() {
    }

    private static UnsupportedOperationException never() {
        return new UnsupportedOperationException("文档上下文不可执行");
    }

    @Override
    public InputGateway gateway() {
        throw never();
    }

    @Override
    public InputState inputState() {
        throw never();
    }

    @Override
    public long runStartMillis() {
        return 0;
    }

    @Override
    public String runId() {
        return "doc";
    }

    @Override
    public String scriptId() {
        return "doc";
    }

    @Override
    public void log(String message) {
        throw never();
    }

    @Override
    public void incAction() {
        throw never();
    }

    @Override
    public void checkStop() {
        throw never();
    }

    @Override
    public void sleepWithPump(long millis) {
        throw never();
    }

    @Override
    public void pump() {
        throw never();
    }
}
