package com.gmscpt.server.kernel.input;

/**
 * 唯一的输入注入入口。Lua API 只能经由它注入，以便统一做回声登记、状态记账与限速。
 *
 * <p>旧版有两条注入路径（{@code InputSimulator} 的 Robot 与 {@code NativeMouseInput} 的 SendInput），
 * 各自维护一套「模拟中」集合，语义还不一致——这是自激与卡键的根源。
 */
public interface InputGateway {

    void keyDown(String keyName);

    void keyUp(String keyName);

    /** 按下并立刻松开。 */
    void pressKey(String keyName);

    /**
     * 按下、保持 holdMs、松开。
     *
     * <p>{@code sleeper} 由调用方提供：Lua 侧传的是「会继续泵事件的睡眠」，
     * 否则长按期间事件全堵在队列里（旧版 {@code PressAndReleaseKey} 正是直接用裸 Sleep
     * 绕过了事件泵，导致长按期间 OnEvent 完全不触发）。
     */
    void pressAndReleaseKey(String keyName, long holdMs, Sleeper sleeper);

    /** 睡眠策略；允许抛 InterruptedException 以便停止时立刻中断。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    void mouseDown(int canonicalButton);

    void mouseUp(int canonicalButton);

    void clickMouseButton(int canonicalButton);

    /** 相对移动，带亚像素累加，尽量做到不漂移。 */
    void moveRelative(double dx, double dy);

    void moveAbsolute(int x, int y);

    int mouseX();

    int mouseY();

    /** 滚轮：正数向上（与 Lua 侧约定一致）。 */
    void scroll(int amount);

    boolean isKeyPressed(String keyName);

    boolean isMouseButtonPressed(int canonicalButton);

    /**
     * 松开本进程注入过的所有键与按钮。
     * **停止路径必须调用**：旧版正是漏了这一步，导致脚本结束后按键卡死、
     * 且该键因残留在「模拟集合」里而永久收不到事件。
     */
    void releaseAll();

    /** 当前已注入但尚未松开的键（只读，供日志/诊断）。 */
    java.util.Set<String> heldKeys();

    boolean nativeMouseAvailable();
}
