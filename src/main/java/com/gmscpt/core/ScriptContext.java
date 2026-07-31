package com.gmscpt.core;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Runtime context for a single script execution.
 * Holds running state, log callback, and provides stop mechanism.
 * Also holds a thread-safe event queue so that input events from the
 * hook thread are processed on the script thread (LuaJ is NOT thread-safe).
 */
public class ScriptContext {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Consumer<String> logCallback;
    private Thread scriptThread;

    // Event queue: events enqueued by hook thread, processed by script thread
    private final ConcurrentLinkedQueue<Object[]> eventQueue = new ConcurrentLinkedQueue<>();
    // Handler registered by ScriptEngine to actually call OnEvent (runs on script thread)
    private BiConsumer<String, Object> eventHandler;

    public ScriptContext(Consumer<String> logCallback) {
        this.logCallback = logCallback;
    }

    public void log(String message) {
        if (logCallback != null) {
            logCallback.accept(message);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setRunning(boolean value) {
        running.set(value);
    }

    public void requestStop() {
        stopRequested.set(true);
        // Interrupt the script thread if it's sleeping
        Thread t = scriptThread;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    public void setScriptThread(Thread thread) {
        this.scriptThread = thread;
    }

    public Thread getScriptThread() {
        return scriptThread;
    }

    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    // --- Event queue mechanism (thread-safe) ---

    /**
     * Register the event handler (called by ScriptEngine).
     * This handler will be invoked on the script thread during Sleep().
     */
    public void setEventHandler(BiConsumer<String, Object> handler) {
        this.eventHandler = handler;
    }

    /**
     * Enqueue an event with integer arg (mouse button).
     * Called from the hook thread - thread-safe, does NOT call Lua.
     */
    public void enqueueEvent(String event, int arg) {
        eventQueue.offer(new Object[]{event, arg});
    }

    /**
     * Enqueue an event with string arg (key name).
     * Called from the hook thread - thread-safe, does NOT call Lua.
     */
    public void enqueueEvent(String event, String arg) {
        eventQueue.offer(new Object[]{event, arg});
    }

    /**
     * Process all pending events. MUST be called from the script thread only.
     * Called during Sleep() to dispatch queued events to OnEvent.
     */
    public void processPendingEvents() {
        if (eventHandler == null) return;
        Object[] ev;
        while ((ev = eventQueue.poll()) != null) {
            eventHandler.accept((String) ev[0], ev[1]);
        }
    }
}
