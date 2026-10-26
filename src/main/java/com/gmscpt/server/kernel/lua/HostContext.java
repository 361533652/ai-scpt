package com.gmscpt.server.kernel.lua;

import com.gmscpt.server.kernel.input.InputGateway;
import com.gmscpt.server.kernel.input.InputState;

/**
 * 脚本运行时暴露给 Lua API 实现的上下文。
 *
 * <p>所有会阻塞的 Lua API（{@code Sleep}、{@code PressAndReleaseKey} 等）都必须走
 * {@link #sleepWithPump(long)} 或先调 {@link #pump()}，否则事件会在队列里堆积到脚本结束
 * ——旧版只有 {@code Sleep} 才泵事件，且 {@code PressAndReleaseKey} 直接裸睡，导致长按期间
 * OnEvent 完全不触发（ADR 的 M1/M9）。
 */
public interface HostContext {

    InputGateway gateway();

    InputState inputState();

    long runStartMillis();

    String runId();

    String scriptId();

    /** 写一条属于本次运行的日志。 */
    void log(String message);

    /** 记一次输入动作（用于运行统计）。 */
    void incAction();

    /** 若已请求停止则抛 {@link StoppedException}，脚本线程据此立刻退出。 */
    void checkStop() throws StoppedException;

    /** 边睡边泵事件的睡眠；被中断时抛 {@link InterruptedException}。 */
    void sleepWithPump(long millis) throws InterruptedException;

    /** 立刻处理一批已入队的事件。 */
    void pump();
}
