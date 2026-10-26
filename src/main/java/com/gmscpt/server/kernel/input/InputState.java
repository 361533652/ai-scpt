package com.gmscpt.server.kernel.input;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 键鼠按下状态。
 *
 * <p>分两套记：{@code physical} 只由全局钩子写入（真实按键），
 * {@code synthetic} 只由注入通道写入（我们自己按下的）。
 * 对脚本可见的 {@code IsKeyPressed} 取两者之并——这样脚本按下 W 后再查 W 是 true，
 * 但紧急停止时 {@link #releaseAllSynthetic()} 能精确地只松开自己按下的键，
 * 不会误松用户正按着的键（旧版在这里是「按下的键永远留在集合里」，见 ADR 的 H1）。
 */
public final class InputState {

    private final Set<String> physicalKeys = ConcurrentHashMap.newKeySet();
    private final Set<Integer> physicalButtons = ConcurrentHashMap.newKeySet();
    private final Set<String> syntheticKeys = ConcurrentHashMap.newKeySet();
    private final Set<Integer> syntheticButtons = ConcurrentHashMap.newKeySet();

    // ---------- 物理态（钩子线程写入） ----------

    public void physicalKeyDown(String keyName) {
        if (keyName != null) physicalKeys.add(normalize(keyName));
    }

    public void physicalKeyUp(String keyName) {
        if (keyName != null) physicalKeys.remove(normalize(keyName));
    }

    public void physicalButtonDown(int canonicalButton) {
        physicalButtons.add(canonicalButton);
    }

    public void physicalButtonUp(int canonicalButton) {
        physicalButtons.remove(canonicalButton);
    }

    // ---------- 合成态（注入通道写入） ----------

    public void syntheticKeyDown(String keyName) {
        if (keyName != null) syntheticKeys.add(normalize(keyName));
    }

    public void syntheticKeyUp(String keyName) {
        if (keyName != null) syntheticKeys.remove(normalize(keyName));
    }

    public void syntheticButtonDown(int canonicalButton) {
        syntheticButtons.add(canonicalButton);
    }

    public void syntheticButtonUp(int canonicalButton) {
        syntheticButtons.remove(canonicalButton);
    }

    // ---------- 查询 ----------

    public boolean isKeyDown(String keyName) {
        if (keyName == null) return false;
        String n = normalize(keyName);
        return physicalKeys.contains(n) || syntheticKeys.contains(n);
    }

    public boolean isButtonDown(int canonicalButton) {
        return physicalButtons.contains(canonicalButton) || syntheticButtons.contains(canonicalButton);
    }

    /** 当前按下的修饰键集合（用于热键组合匹配），取自物理态。 */
    public Set<String> pressedModifiers() {
        Set<String> mods = new LinkedHashSet<>();
        for (String k : physicalKeys) {
            if (KeyNames.isModifier(k)) mods.add(k);
        }
        return mods;
    }

    /** 松开所有自己注入的键与按钮；返回实际松开的东西，供日志展示。 */
    public java.util.List<String> releaseAllSynthetic() {
        java.util.List<String> released = new java.util.ArrayList<>();
        for (String k : syntheticKeys) {
            released.add(k);
        }
        syntheticKeys.clear();
        for (Integer b : syntheticButtons) {
            released.add(MouseButtons.name(b));
        }
        syntheticButtons.clear();
        return released;
    }

    /** 暴露给「我自己注入了什么」的只读快照。 */
    public Set<String> syntheticKeySnapshot() {
        return Set.copyOf(syntheticKeys);
    }

    public Set<Integer> syntheticButtonSnapshot() {
        return Set.copyOf(syntheticButtons);
    }

    private static String normalize(String keyName) {
        return keyName.toLowerCase(Locale.ROOT);
    }
}
