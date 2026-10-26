package com.gmscpt.server.kernel.runtime;

import com.gmscpt.server.domain.RunExit;

/** 运行态变化回调：界面靠它更新状态胶囊与运行列表。回调在脚本线程结束时触发，实现要快。 */
public interface RuntimeListener {

    void onStarted(ScriptHost host);

    void onFinished(ScriptHost host, RunExit exit, String errorMessage);
}
