package com.gmscpt.server.kernel.runtime;

/** 请求停止的原因，最终会落到 {@link com.gmscpt.server.domain.RunExit}。 */
public enum StopReason {
    /** 界面上的「停止」按钮 */
    USER,
    /** 热键绑定触发的停止 */
    HOTKEY,
    /** 全局急停键 */
    EMERGENCY,
    /** 超过 max-run-ms */
    TIMEOUT,
    /** 进程退出 */
    SHUTDOWN
}
