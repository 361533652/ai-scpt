package com.gmscpt.server.kernel.runtime;

import com.gmscpt.server.domain.RunExit;
import com.gmscpt.server.domain.Script;

import java.util.List;
import java.util.Optional;

/**
 * 运行期管理器：脚本的启动、停止、查询。
 *
 * <p>旧版 {@code MainFrame.runScript()} 直接覆盖 {@code currentEngine}，
 * 不 join 旧线程、也不从 {@code EventDispatcher} 注销，于是「跑第二个脚本」时
 * 第一个脚本仍在收事件。这里由本管理器统一收口。
 */
public interface RuntimeManager {

    /** 启动一个脚本；同一脚本已在运行时直接返回既有实例（不重复启动）。 */
    ScriptHost start(Script script, String trigger);

    Optional<ScriptHost> find(String scriptId);

    boolean isRunning(String scriptId);

    List<ScriptHost> running();

    /** 停止指定脚本：置停止位 → interrupt → 宽限期 → 超时判 KILLED。 */
    void stop(String scriptId, StopReason reason);

    /** 停止全部（急停/退出用）。 */
    void stopAll(StopReason reason);

    void addListener(RuntimeListener listener);

    void removeListener(RuntimeListener listener);
}
