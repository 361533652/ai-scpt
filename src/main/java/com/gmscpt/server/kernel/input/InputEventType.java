package com.gmscpt.server.kernel.input;

/** 派发给 Lua {@code OnEvent(event, arg)} 的事件类型，命名沿用旧版与 Logitech G 系列的约定。 */
public enum InputEventType {
    MOUSE_BUTTON_PRESSED(1),
    MOUSE_BUTTON_RELEASED(2),
    KEY_PRESSED(3),
    KEY_RELEASED(4);

    private final int code;

    InputEventType(int code) {
        this.code = code;
    }

    /** 传给 Lua 的数字码（脚本里用 `event == 3` 这种写法判断）。 */
    public int code() {
        return code;
    }
}
