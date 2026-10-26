package com.gmscpt.server.kernel.input;

import com.gmscpt.server.domain.HotkeyBinding;
import com.gmscpt.server.domain.HotkeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 热键绑定表。
 *
 * <p>旧版根本没有这个功能：唯一能按的是硬编码的 {@code Ctrl+Shift+F12} 急停，
 * 而 {@code EventDispatcher} 把每一个输入事件无条件广播给所有运行中的引擎——
 * 用户没法让「F8 只启动脚本 A」。
 *
 * <p>本表按 {@code accelerator + event} 唯一索引，重载时做冲突检测。
 */
public final class HotkeyRegistry {

    private record Key(String accelerator, HotkeyEvent event) {
    }

    private final Map<Key, HotkeyBinding> bindings = new HashMap<>();

    /** 全量重载；有冲突时抛 {@link HotkeyConflictException} 且**不改变**现有绑定。 */
    public synchronized void reload(List<HotkeyBinding> newBindings) {
        Map<Key, HotkeyBinding> next = new HashMap<>();
        for (HotkeyBinding b : newBindings) {
            if (!b.enabled()) continue;
            Accelerator acc = Accelerator.parse(b.accelerator());
            Key key = new Key(acc.canonical(), b.event());
            HotkeyBinding existing = next.get(key);
            if (existing != null) {
                throw new HotkeyConflictException(acc.canonical(), existing.scriptId(), b.scriptId());
            }
            next.put(key, new HotkeyBinding(b.id(), b.scriptId(), acc.canonical(), b.event(), b.action(), true));
        }
        bindings.clear();
        bindings.putAll(next);
    }

    public synchronized List<HotkeyBinding> all() {
        return List.copyOf(bindings.values());
    }

    /** 键盘事件命中的绑定（可能多个：不同修饰键组合各自独立）。 */
    public synchronized List<HotkeyBinding> matchKey(String keyName, HotkeyEvent event, Set<String> pressedModifiers) {
        List<HotkeyBinding> hits = new ArrayList<>();
        for (HotkeyBinding b : bindings.values()) {
            if (b.event() != event) continue;
            Accelerator acc = Accelerator.parse(b.accelerator());
            if (acc.matchesKey(keyName, pressedModifiers)) hits.add(b);
        }
        return hits;
    }

    /** 鼠标事件命中的绑定。 */
    public synchronized List<HotkeyBinding> matchButton(int canonicalButton, HotkeyEvent event, Set<String> pressedModifiers) {
        List<HotkeyBinding> hits = new ArrayList<>();
        for (HotkeyBinding b : bindings.values()) {
            if (b.event() != event) continue;
            Accelerator acc = Accelerator.parse(b.accelerator());
            if (acc.matchesButton(canonicalButton, pressedModifiers)) hits.add(b);
        }
        return hits;
    }

    /** 冲突检测（不改动现有绑定），供保存前预检。 */
    public static void checkConflicts(List<HotkeyBinding> candidates) {
        Map<String, String> seen = new HashMap<>();
        for (HotkeyBinding b : candidates) {
            if (!b.enabled()) continue;
            Accelerator acc = Accelerator.parse(b.accelerator());
            String key = acc.canonical().toLowerCase(Locale.ROOT) + "|" + b.event();
            String owner = seen.putIfAbsent(key, b.scriptId());
            if (owner != null && !owner.equals(b.scriptId())) {
                throw new HotkeyConflictException(acc.canonical(), owner, b.scriptId());
            }
        }
    }
}
