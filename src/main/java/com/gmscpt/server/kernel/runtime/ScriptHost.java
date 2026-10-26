package com.gmscpt.server.kernel.runtime;

import com.gmscpt.server.kernel.input.InputEvent;

/**
 * 一个正在运行的脚本实例（一个脚本一份 Lua 状态 + 一条专属线程）。
 *
 * <p>线程约束（旧版最大的坑）：{@code Globals}/{@code LuaValue} **只能**在脚本线程里碰，
 * 任何跨线程的操作都必须通过 {@link #offer(InputEvent)} 或 {@link #requestStop} 投递。
 */
public interface ScriptHost {

    String scriptId();

    String scriptName();

    String runId();

    String trigger();

    /** 脚本线程是否还活着。 */
    boolean isAlive();

    /**
     * 投递一个输入事件。由 JNativeHook 派发线程调用，**必须非阻塞**：
     * 队列满时丢最旧并累加 {@link #droppedEvents()}，绝不等待。
     */
    void offer(InputEvent event);

    /** 请求停止。可从任意线程调用；幂等。 */
    void requestStop(StopReason reason);

    /** 等待脚本线程结束。 */
    boolean awaitStop(long millis) throws InterruptedException;

    /** 累计丢弃的事件数（队列溢出）。 */
    int droppedEvents();

    int eventCount();

    int actionCount();

    /**
     * 宽限期内没停下来时的收尾：判定 KILLED、释放已注入的按键、断开对 Lua 状态的一切引用。
     *
     * <p>Java 无法强杀线程，所以被判定 KILLED 的 Lua 状态只能**弃用**：
     * 我们不再触碰它（否则可能踩到已损坏的状态），代价是泄漏一份 {@code Globals} 换稳定性。
     */
    void forceAbandon(String reason);

    /** 本次运行的结局；未结束时为 {@code null}。 */
    com.gmscpt.server.domain.RunExit exit();

    /** 本次运行的开始时刻（epoch millis）。 */
    long startedAtMillis();
}
