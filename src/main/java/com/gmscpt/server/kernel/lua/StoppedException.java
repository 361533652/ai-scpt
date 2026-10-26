package com.gmscpt.server.kernel.lua;

/** 脚本被请求停止时抛出的信号。与 Lua 侧错误区分开，避免被脚本自己的 pcall 吃掉后继续跑。 */
public class StoppedException extends RuntimeException {

    public StoppedException(String message) {
        super(message);
    }
}
