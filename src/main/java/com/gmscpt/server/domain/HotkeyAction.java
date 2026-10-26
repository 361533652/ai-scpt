package com.gmscpt.server.domain;

/** 热键触发的动作。 */
public enum HotkeyAction {
    /** 启动脚本（已在运行时忽略） */
    RUN,
    /** 停止脚本（未运行时忽略） */
    STOP,
    /** 未运行则启动，运行中则停止 */
    TOGGLE
}
