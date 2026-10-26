package com.gmscpt.server.kernel.input;

import com.gmscpt.server.domain.HotkeyBinding;
import com.gmscpt.server.domain.HotkeyEvent;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.kernel.runtime.RuntimeManager;
import com.gmscpt.server.kernel.runtime.ScriptHost;
import com.gmscpt.server.kernel.runtime.StopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 输入事件的中央路由器——全局钩子线程里**只能**做这些事：
 * 翻译 → 更新状态 → 认领回声 → 匹配热键 → 入队 → 返回。
 *
 * <p>绝不在这里碰 Lua、磁盘、Swing。JNativeHook 的回调一旦超时，Windows 会把钩子摘掉，
 * 表现就是「脚本跑着跑着就不响应了」。
 */
public class InputRouter {

    private static final Logger log = LoggerFactory.getLogger(InputRouter.class);

    private final InputState state;
    private final EchoSuppressor echo;
    private final HotkeyRegistry hotkeys;
    private final LogBus logBus;

    private volatile RuntimeManager runtimeManager;
    private volatile HotkeyHandler hotkeyHandler;
    /** 急停热键的规范形式；默认 {@code ctrl+shift+f12}。 */
    private volatile Accelerator emergencyAccelerator = Accelerator.parse("ctrl+shift+f12");

    /** 丢弃计数：仅作诊断，避免日志刷屏。 */
    private long lastDropWarnAt;

    public InputRouter(InputState state, EchoSuppressor echo, HotkeyRegistry hotkeys, LogBus logBus) {
        this.state = state;
        this.echo = echo;
        this.hotkeys = hotkeys;
        this.logBus = logBus;
    }

    public void setRuntimeManager(RuntimeManager runtimeManager) {
        this.runtimeManager = runtimeManager;
    }

    public void setHotkeyHandler(HotkeyHandler handler) {
        this.hotkeyHandler = handler;
    }

    public void setEmergencyAccelerator(String accelerator) {
        this.emergencyAccelerator = Accelerator.parse(accelerator);
    }

    public String emergencyAccelerator() {
        return emergencyAccelerator.canonical();
    }

    // ---------------- 钩子入口 ----------------

    public void onKey(boolean pressed, int nativeCode, int keyLocation) {
        String name = KeyNames.fromNative(nativeCode, keyLocation);
        String token = EchoSuppressor.keyToken(name, pressed);
        if (echo.claim(token)) {
            // 自己注入的：不进物理态、不派发、不触发热键。
            return;
        }
        if (pressed) {
            state.physicalKeyDown(name);
        } else {
            state.physicalKeyUp(name);
        }
        HotkeyEvent event = pressed ? HotkeyEvent.KEY_PRESSED : HotkeyEvent.KEY_RELEASED;
        Set<String> mods = state.pressedModifiers();

        if (pressed && emergencyAccelerator.matchesKey(name, mods)) {
            logBus.publish(com.gmscpt.server.domain.LogLevel.WARN, "hotkey", null, null,
                    "急停触发（" + emergencyAccelerator.canonical() + "）");
            RuntimeManager rm = runtimeManager;
            if (rm != null) rm.stopAll(StopReason.EMERGENCY);
            return;
        }

        List<HotkeyBinding> hits = hotkeys.matchKey(name, event, mods);
        HotkeyHandler handler = hotkeyHandler;
        if (handler != null) {
            for (HotkeyBinding b : hits) {
                try {
                    handler.onHotkey(b);
                } catch (Throwable t) {
                    log.warn("热键回调异常 {}: {}", b.accelerator(), t.toString());
                }
            }
        }
        dispatch(InputEvent.key(System.currentTimeMillis(), eventType(pressed, InputEventType.KEY_PRESSED,
                InputEventType.KEY_RELEASED), name));
    }

    public void onMouseButton(boolean pressed, int hookButton) {
        int canonical = MouseButtons.fromHook(hookButton);
        if (canonical <= 0) return;
        String token = EchoSuppressor.buttonToken(canonical, pressed);
        if (echo.claim(token)) {
            return;
        }
        if (pressed) {
            state.physicalButtonDown(canonical);
        } else {
            state.physicalButtonUp(canonical);
        }
        HotkeyEvent event = pressed ? HotkeyEvent.KEY_PRESSED : HotkeyEvent.KEY_RELEASED;
        Set<String> mods = state.pressedModifiers();

        if (pressed && emergencyAccelerator.matchesButton(canonical, mods)) {
            RuntimeManager rm = runtimeManager;
            if (rm != null) rm.stopAll(StopReason.EMERGENCY);
            return;
        }

        List<HotkeyBinding> hits = hotkeys.matchButton(canonical, event, mods);
        HotkeyHandler handler = hotkeyHandler;
        if (handler != null) {
            for (HotkeyBinding b : hits) {
                try {
                    handler.onHotkey(b);
                } catch (Throwable t) {
                    log.warn("热键回调异常 {}: {}", b.accelerator(), t.toString());
                }
            }
        }
        dispatch(InputEvent.mouse(System.currentTimeMillis(),
                pressed ? InputEventType.MOUSE_BUTTON_PRESSED : InputEventType.MOUSE_BUTTON_RELEASED, canonical));
    }

    private static InputEventType eventType(boolean pressed, InputEventType pressedType, InputEventType releasedType) {
        return pressed ? pressedType : releasedType;
    }

    /** 派发给所有运行中的脚本。队列满时由 ScriptHost 自己丢最旧并计数。 */
    private void dispatch(InputEvent event) {
        RuntimeManager rm = runtimeManager;
        if (rm == null) return;
        for (ScriptHost host : rm.running()) {
            host.offer(event);
        }
    }

    /** 供诊断：键名归一化（小写）后的物理态查询。 */
    public boolean isKeyDown(String keyName) {
        return state.isKeyDown(keyName == null ? null : keyName.toLowerCase(Locale.ROOT));
    }
}
