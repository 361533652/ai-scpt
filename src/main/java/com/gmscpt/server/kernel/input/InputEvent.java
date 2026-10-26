package com.gmscpt.server.kernel.input;

/**
 * 一个规范化后的输入事件。
 *
 * @param timeMillis 钩子线程收到事件的时间（毫秒）
 * @param type       事件类型
 * @param keyName    键盘事件的规范键名；鼠标事件为 {@code null}
 * @param button     鼠标事件的**规范**按钮号（1=左 2=中 3=右 4/5=侧键）；键盘事件为 0
 * @param scriptOrigin 注入来源：非 null 表示该事件是自己注入的（回声），非 null 时不会被派发回脚本
 */
public record InputEvent(
        long timeMillis,
        InputEventType type,
        String keyName,
        int button,
        String syntheticRunId
) {

    public static InputEvent key(long timeMillis, InputEventType type, String keyName) {
        return new InputEvent(timeMillis, type, keyName, 0, null);
    }

    public static InputEvent mouse(long timeMillis, InputEventType type, int canonicalButton) {
        return new InputEvent(timeMillis, type, null, canonicalButton, null);
    }

    /** Lua 侧 {@code arg}：键盘为 null（脚本用 KeyName 判断），鼠标为按钮号。 */
    public Integer luaArg() {
        return type == InputEventType.KEY_PRESSED || type == InputEventType.KEY_RELEASED ? null : button;
    }

    public boolean isMouse() {
        return type == InputEventType.MOUSE_BUTTON_PRESSED || type == InputEventType.MOUSE_BUTTON_RELEASED;
    }

    public boolean isPress() {
        return type == InputEventType.KEY_PRESSED || type == InputEventType.MOUSE_BUTTON_PRESSED;
    }
}
