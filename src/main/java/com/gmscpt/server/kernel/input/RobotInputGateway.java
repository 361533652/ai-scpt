package com.gmscpt.server.kernel.input;

import com.gmscpt.server.config.GmScriptProperties;
import com.gmscpt.server.kernel.log.LogBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;

/**
 * {@link InputGateway} 的默认实现：键盘与绝对/滚轮走 AWT {@link Robot}，
 * 相对移动优先走 {@link NativeMouse}（SendInput 绝对移动，不受系统加速曲线影响）。
 *
 * <p>每一次注入都做三件事：① 登记回声令牌；② 记账到 {@link InputState} 的合成态；③ 计入限速。
 * 这样「自己造的输入」与「用户真实输入」在链路上彻底分开——旧版把两者混在同一个集合里，
 * 越用越乱。
 */
public final class RobotInputGateway implements InputGateway {

    private static final Logger log = LoggerFactory.getLogger(RobotInputGateway.class);

    private final InputState state;
    private final EchoSuppressor echo;
    private final LogBus logBus;
    private final GmScriptProperties props;

    private final Robot robot;
    private final boolean robotAvailable;

    /** 限速窗口：每秒最多 maxInputActionsPerSecond 次注入。 */
    private long windowStart = System.nanoTime();
    private int windowCount = 0;

    public RobotInputGateway(InputState state, EchoSuppressor echo, LogBus logBus, GmScriptProperties props) {
        this.state = state;
        this.echo = echo;
        this.logBus = logBus;
        this.props = props;
        Robot r = null;
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                r = new Robot();
            }
        } catch (Throwable t) {
            log.warn("AWT Robot 不可用，键盘注入将失败: {}", t.toString());
        }
        this.robot = r;
        this.robotAvailable = r != null;
    }

    // ---------------- 键盘 ----------------

    @Override
    public void keyDown(String keyName) {
        Integer code = KeyNames.awtCode(keyName);
        if (code == null) {
            throw new IllegalArgumentException("未知键名: " + keyName);
        }
        throttle();
        echo.expect(EchoSuppressor.keyToken(keyName, true));
        state.syntheticKeyDown(keyName);
        if (robotAvailable) {
            robot.keyPress(code);
        } else {
            throw new IllegalStateException("键盘注入不可用（无 AWT Robot）");
        }
    }

    @Override
    public void keyUp(String keyName) {
        Integer code = KeyNames.awtCode(keyName);
        if (code == null) {
            throw new IllegalArgumentException("未知键名: " + keyName);
        }
        throttle();
        echo.expect(EchoSuppressor.keyToken(keyName, false));
        state.syntheticKeyUp(keyName);
        if (robotAvailable) {
            robot.keyRelease(code);
        } else {
            throw new IllegalStateException("键盘注入不可用（无 AWT Robot）");
        }
    }

    @Override
    public void pressKey(String keyName) {
        keyDown(keyName);
        keyUp(keyName);
    }

    @Override
    public void pressAndReleaseKey(String keyName, long holdMs, Sleeper sleeper) {
        keyDown(keyName);
        try {
            if (holdMs > 0) {
                sleeper.sleep(holdMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 无论怎么退出都必须松开，否则就是卡键
            keyUp(keyName);
        }
    }

    // ---------------- 鼠标 ----------------

    @Override
    public void mouseDown(int canonicalButton) {
        throttle();
        boolean ok;
        if (NativeMouse.isAvailable()) {
            ok = NativeMouse.buttonDown(canonicalButton);
        } else {
            int mask = MouseButtons.toAwtMaskButton(canonicalButton);
            if (mask == 0) throw new IllegalArgumentException("未知鼠标按钮: " + canonicalButton);
            robot.mousePress(mask);
            ok = true;
        }
        if (!ok) {
            throw new IllegalStateException("鼠标注入被系统拒绝（目标程序可能以管理员权限运行）");
        }
        echo.expect(EchoSuppressor.buttonToken(canonicalButton, true));
        state.syntheticButtonDown(canonicalButton);
    }

    @Override
    public void mouseUp(int canonicalButton) {
        throttle();
        boolean ok;
        if (NativeMouse.isAvailable()) {
            ok = NativeMouse.buttonUp(canonicalButton);
        } else {
            int mask = MouseButtons.toAwtMaskButton(canonicalButton);
            if (mask == 0) throw new IllegalArgumentException("未知鼠标按钮: " + canonicalButton);
            robot.mouseRelease(mask);
            ok = true;
        }
        if (!ok) {
            throw new IllegalStateException("鼠标注入被系统拒绝（目标程序可能以管理员权限运行）");
        }
        echo.expect(EchoSuppressor.buttonToken(canonicalButton, false));
        state.syntheticButtonUp(canonicalButton);
    }

    @Override
    public void clickMouseButton(int canonicalButton) {
        mouseDown(canonicalButton);
        mouseUp(canonicalButton);
    }

    @Override
    public void moveRelative(double dx, double dy) {
        throttle();
        if (NativeMouse.isAvailable()) {
            if (!NativeMouse.moveRelative(dx, dy)) {
                throw new IllegalStateException("鼠标移动被系统拒绝（目标程序可能以管理员权限运行）");
            }
            return;
        }
        if (!robotAvailable) {
            throw new IllegalStateException("鼠标注入不可用（无 AWT Robot）");
        }
        java.awt.Point p = java.awt.MouseInfo.getPointerInfo().getLocation();
        robot.mouseMove((int) Math.round(p.x + dx), (int) Math.round(p.y + dy));
    }

    @Override
    public void moveAbsolute(int x, int y) {
        throttle();
        if (NativeMouse.isAvailable()) {
            if (!NativeMouse.moveAbsolute(x, y)) {
                throw new IllegalStateException("鼠标移动被系统拒绝（目标程序可能以管理员权限运行）");
            }
            return;
        }
        if (!robotAvailable) {
            throw new IllegalStateException("鼠标注入不可用（无 AWT Robot）");
        }
        robot.mouseMove(x, y);
    }

    @Override
    public int mouseX() {
        int[] p = NativeMouse.cursorPos();
        if (p != null) return p[0];
        return (int) java.awt.MouseInfo.getPointerInfo().getLocation().getX();
    }

    @Override
    public int mouseY() {
        int[] p = NativeMouse.cursorPos();
        if (p != null) return p[1];
        return (int) java.awt.MouseInfo.getPointerInfo().getLocation().getY();
    }

    @Override
    public void scroll(int amount) {
        throttle();
        if (NativeMouse.isAvailable()) {
            NativeMouse.wheel(amount);
            return;
        }
        if (!robotAvailable) {
            throw new IllegalStateException("滚轮注入不可用（无 AWT Robot）");
        }
        // Robot.mouseWheel 的符号与「向上为正」相反
        robot.mouseWheel(-amount);
    }

    @Override
    public boolean isKeyPressed(String keyName) {
        return state.isKeyDown(keyName);
    }

    @Override
    public boolean isMouseButtonPressed(int canonicalButton) {
        return state.isButtonDown(canonicalButton);
    }

    @Override
    public void releaseAll() {
        java.util.Set<String> keys = state.syntheticKeySnapshot();
        java.util.Set<Integer> buttons = state.syntheticButtonSnapshot();
        for (Integer b : buttons) {
            try {
                if (NativeMouse.isAvailable()) {
                    NativeMouse.buttonUp(b);
                } else if (robotAvailable) {
                    robot.mouseRelease(MouseButtons.toAwtMaskButton(b));
                }
            } catch (Throwable t) {
                log.warn("松开鼠标按钮 {} 失败: {}", b, t.toString());
            }
        }
        for (String k : keys) {
            try {
                Integer code = KeyNames.awtCode(k);
                if (robotAvailable && code != null) {
                    robot.keyRelease(code);
                }
            } catch (Throwable t) {
                log.warn("松开按键 {} 失败: {}", k, t.toString());
            }
        }
        int n = keys.size() + buttons.size();
        state.releaseAllSynthetic();
        if (n > 0) {
            logBus.publish(com.gmscpt.server.domain.LogLevel.WARN, "input", null, null,
                    "已释放残留输入 " + n + " 项：" + keys + " " + buttons);
        }
    }

    @Override
    public java.util.Set<String> heldKeys() {
        return state.syntheticKeySnapshot();
    }

    @Override
    public boolean nativeMouseAvailable() {
        return NativeMouse.isAvailable();
    }

    /** 每段注入前 reset 亚像素累加器（旧版跨运行残留）。 */
    public static void resetAccumulator() {
        NativeMouse.resetAccumulator();
    }

    private void throttle() {
        int max = props.getRuntime().getMaxInputActionsPerSecond();
        if (max <= 0) return;
        long now = System.nanoTime();
        if (now - windowStart >= 1_000_000_000L) {
            windowStart = now;
            windowCount = 0;
        }
        windowCount++;
        if (windowCount > max) {
            long over = windowCount - max;
            long sleepNanos = Math.min(20_000_000L, over * (1_000_000_000L / Math.max(1, max)));
            try {
                Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
