package com.gmscpt.server.kernel.input;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的注入通道：只记账，不真的碰鼠标键盘（跑测试时不允许影响用户桌面）。
 */
public class FakeInputGateway implements InputGateway {

    public final AtomicInteger actions = new AtomicInteger();
    public final Set<String> downKeys = new LinkedHashSet<>();
    public final Set<Integer> downButtons = new LinkedHashSet<>();
    public final Set<String> log = new LinkedHashSet<>();
    public volatile boolean failNextMouseMove;

    @Override
    public void keyDown(String keyName) {
        actions.incrementAndGet();
        downKeys.add(keyName);
        log.add("down:" + keyName);
    }

    @Override
    public void keyUp(String keyName) {
        actions.incrementAndGet();
        downKeys.remove(keyName);
        log.add("up:" + keyName);
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
            if (holdMs > 0) sleeper.sleep(holdMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            keyUp(keyName);
        }
    }

    @Override
    public void mouseDown(int canonicalButton) {
        actions.incrementAndGet();
        downButtons.add(canonicalButton);
        log.add("mdown:" + canonicalButton);
    }

    @Override
    public void mouseUp(int canonicalButton) {
        actions.incrementAndGet();
        downButtons.remove(canonicalButton);
        log.add("mup:" + canonicalButton);
    }

    @Override
    public void clickMouseButton(int canonicalButton) {
        mouseDown(canonicalButton);
        mouseUp(canonicalButton);
    }

    @Override
    public void moveRelative(double dx, double dy) {
        actions.incrementAndGet();
        if (failNextMouseMove) {
            failNextMouseMove = false;
            throw new IllegalStateException("鼠标移动被系统拒绝");
        }
        log.add("move:" + dx + "," + dy);
    }

    @Override
    public void moveAbsolute(int x, int y) {
        actions.incrementAndGet();
        log.add("moveTo:" + x + "," + y);
    }

    @Override
    public int mouseX() {
        return 100;
    }

    @Override
    public int mouseY() {
        return 200;
    }

    @Override
    public void scroll(int amount) {
        actions.incrementAndGet();
        log.add("scroll:" + amount);
    }

    @Override
    public boolean isKeyPressed(String keyName) {
        return downKeys.contains(keyName);
    }

    @Override
    public boolean isMouseButtonPressed(int canonicalButton) {
        return downButtons.contains(canonicalButton);
    }

    @Override
    public void releaseAll() {
        for (String k : new LinkedHashSet<>(downKeys)) {
            keyUp(k);
        }
        for (Integer b : new LinkedHashSet<>(downButtons)) {
            mouseUp(b);
        }
    }

    @Override
    public Set<String> heldKeys() {
        return Set.copyOf(downKeys);
    }

    @Override
    public boolean nativeMouseAvailable() {
        return false;
    }
}
