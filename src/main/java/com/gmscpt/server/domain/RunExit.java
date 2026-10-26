package com.gmscpt.server.domain;

/** 一次运行的结局。 */
public enum RunExit {
    /** 正常结束（顶层代码跑完且未请求停止） */
    OK,
    /** Lua 运行时错误 */
    LUA_ERROR,
    /** 被用户 / 热键 / 急停请求停止并成功退出 */
    STOPPED,
    /** 超过 max-run-ms 被停止 */
    TIMEOUT,
    /** 宽限期内没停下来，被强制判死（Lua 状态弃用，不再触碰） */
    KILLED
}
