package com.gmscpt.server.kernel.input;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 热键表达式：{@code ctrl+shift+f8}、{@code f8}、{@code mouse4}。
 *
 * <p>规范形式：修饰键顺序固定 {@code ctrl+alt+shift+meta}，主键在最后，全部小写。
 */
public record Accelerator(Set<String> modifiers, String mainKey) {

    private static final List<String> MODIFIER_ORDER = List.of("ctrl", "alt", "shift", "meta");

    /** 解析用户输入；非法时抛 {@link IllegalArgumentException}。 */
    public static Accelerator parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("热键不能为空");
        }
        String[] parts = raw.toLowerCase(Locale.ROOT).trim().split("\\s*\\+\\s*");
        Set<String> mods = new LinkedHashSet<>();
        String main = null;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            String norm = KeyNames.normalizeModifier(part);
            if (KeyNames.isModifier(norm)) {
                mods.add(norm);
            } else if (main == null) {
                main = norm;
            } else {
                throw new IllegalArgumentException("一个热键只能有一个主键: " + raw);
            }
        }
        if (main == null) {
            throw new IllegalArgumentException("热键缺少主键: " + raw);
        }
        boolean isMouse = MouseButtons.parseName(main) > 0;
        if (!isMouse && !KeyNames.isKnown(main)) {
            throw new IllegalArgumentException("无法识别的键名: " + main);
        }
        return new Accelerator(mods, main);
    }

    /** 规范字符串形式。 */
    public String canonical() {
        List<String> ordered = new ArrayList<>();
        for (String m : MODIFIER_ORDER) {
            if (modifiers.contains(m)) ordered.add(m);
        }
        ordered.add(mainKey);
        return String.join("+", ordered);
    }

    public boolean isMouse() {
        return MouseButtons.parseName(mainKey) > 0;
    }

    /** 键盘事件是否命中本热键（要求该热键的所有修饰键都处于按下状态）。 */
    public boolean matchesKey(String keyName, Set<String> pressedModifiers) {
        if (isMouse() || keyName == null) return false;
        if (!mainKey.equals(keyName.toLowerCase(Locale.ROOT))) return false;
        return pressedModifiers.containsAll(modifiers);
    }

    /** 鼠标事件是否命中本热键。 */
    public boolean matchesButton(int canonicalButton, Set<String> pressedModifiers) {
        int expected = MouseButtons.parseName(mainKey);
        if (expected == 0) return false;
        if (expected != canonicalButton) return false;
        return pressedModifiers.containsAll(modifiers);
    }

    @Override
    public String toString() {
        return canonical();
    }
}
