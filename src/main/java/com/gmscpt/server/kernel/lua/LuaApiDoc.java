package com.gmscpt.server.kernel.lua;

import java.util.List;

/**
 * Lua API 的机器可读文档。
 *
 * <p>旧版的系统提示词是**手写副本**，早就和实际注册的函数漂移了
 * （提示词里没有 {@code ResetMouseAccumulator}，AI 生成的脚本却可能用到）。
 * 新设计里注册表本身就是文档源：同一个对象同时喂给
 * 编辑器补全、{@code GET /api/lua/api}、以及 AI 的系统提示词，
 * 三者不可能不一致。
 */
public interface LuaApiDoc {

    /** 一个 Lua 函数。 */
    record LuaFunction(String name, String signature, String doc) {
    }

    /** 全部可用的 Lua 函数。 */
    List<LuaFunction> functions();

    /** 可用键名列表（键盘）。 */
    List<String> keyNames();

    /** 鼠标按钮名列表。 */
    List<String> mouseButtonNames();

    /** 事件常量说明。 */
    List<String> eventConstants();

    /** 组装成给大模型看的 API 片段（含「只能用这些函数」的硬约束）。 */
    String systemPromptFragment();
}
