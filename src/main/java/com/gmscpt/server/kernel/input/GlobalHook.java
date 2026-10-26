package com.gmscpt.server.kernel.input;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;
import com.gmscpt.server.kernel.log.LogBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局键鼠钩子的注册与卸载。
 *
 * <p>旧版只把注册失败写进 {@code System.err}，而 jpackage 打出来的 app-image 没有控制台
 * （{@code build-exe.ps1} 里没有 {@code --win-console}），于是「钩子没注册上」这件事
 * 用户永远看不到。这里把状态与错误信息暴露给 {@code /api/health}，界面上一眼可见。
 */
public class GlobalHook implements NativeKeyListener, NativeMouseListener {

    private static final Logger log = LoggerFactory.getLogger(GlobalHook.class);

    private final InputRouter router;
    private final LogBus logBus;

    private final AtomicBoolean registered = new AtomicBoolean(false);
    private volatile String lastError;

    public GlobalHook(InputRouter router, LogBus logBus) {
        this.router = router;
        this.logBus = logBus;
    }

    /**
     * 注册全局钩子并在事件派发线程上跑监听。
     *
     * @return 是否注册成功
     */
    public synchronized boolean register() {
        if (registered.get()) return true;
        // 必须在触碰 GlobalScreen 之前装好：fat jar 下默认定位器会抛
        // "URI is not hierarchical"，钩子从此不可能注册成功。
        NativeLibraryLoader.installIfNeeded();
        // JNativeHook 默认用 java.util.logging 打一堆 INFO，静音掉
        java.util.logging.Logger jnhLogger =
                java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
        jnhLogger.setLevel(java.util.logging.Level.WARNING);
        jnhLogger.setUseParentHandlers(false);
        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            GlobalScreen.addNativeMouseListener(this);
            registered.set(true);
            lastError = null;
            logBus.info("hook", "全局钩子已注册");
            return true;
        } catch (NativeHookException | UnsatisfiedLinkError e) {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            registered.set(false);
            logBus.error("hook", "全局钩子注册失败，热键与输入事件都不会生效：" + lastError);
            log.error("全局钩子注册失败", e);
            return false;
        }
    }

    public synchronized void unregister() {
        if (!registered.get()) return;
        try {
            GlobalScreen.removeNativeKeyListener(this);
            GlobalScreen.removeNativeMouseListener(this);
            GlobalScreen.unregisterNativeHook();
        } catch (Throwable t) {
            log.warn("卸载全局钩子失败: {}", t.toString());
        } finally {
            registered.set(false);
            logBus.info("hook", "全局钩子已卸载");
        }
    }

    public boolean isRegistered() {
        return registered.get();
    }

    public String lastError() {
        return lastError;
    }

    // ---------------- JNativeHook 回调（钩子线程！必须极快返回） ----------------

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        router.onKey(true, e.getKeyCode(), e.getKeyLocation());
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        router.onKey(false, e.getKeyCode(), e.getKeyLocation());
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        // 忽略：按下/抬起已经覆盖，typed 只会造成重复派发
    }

    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        router.onMouseButton(true, e.getButton());
    }

    @Override
    public void nativeMouseReleased(NativeMouseEvent e) {
        router.onMouseButton(false, e.getButton());
    }

    @Override
    public void nativeMouseClicked(NativeMouseEvent e) {
        // 忽略：clicked 是 pressed+released 的结果，重复派发会让脚本收到双份
    }
}
