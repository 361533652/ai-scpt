package com.gmscpt.server.store;

import com.gmscpt.server.domain.HotkeyBinding;
import com.gmscpt.server.domain.Script;
import com.gmscpt.server.domain.ScriptStatus;

import java.util.List;
import java.util.Optional;

/** 脚本与其热键绑定的持久化端口。 */
public interface ScriptRepository {

    List<Script> findAll();

    Optional<Script> findById(String id);

    /** 新建；返回落库后的对象（id 由实现生成）。 */
    Script insert(Script script);

    /**
     * 更新；{@code expectedRev} 不匹配时抛 {@link RevConflictException}（乐观锁）。
     */
    Script update(Script script, long expectedRev);

    void delete(String id);

    void updateStatus(String id, ScriptStatus status);

    /** 追加一条历史版本（用于「AI 改写」与手改之间的回溯）。 */
    void appendRevision(String scriptId, long rev, String source, String note);

    List<String> listRevisions(String scriptId, int limit);

    List<HotkeyBinding> findHotkeys();

    List<HotkeyBinding> findHotkeysByScript(String scriptId);

    /** 全量替换某脚本的热键绑定。 */
    void replaceHotkeys(String scriptId, List<HotkeyBinding> bindings);
}
