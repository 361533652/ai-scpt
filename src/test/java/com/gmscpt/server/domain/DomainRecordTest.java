package com.gmscpt.server.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领域记录的构造约束回归测试。
 *
 * <p>背景：{@code KnowledgeEntry} 曾经在紧凑构造器里要求 id 非空，而
 * {@code POST /api/kb} 按契约就是不带 id 的——Jackson 反序列化时直接抛异常，
 * 接口稳定 500。这类「反序列化阶段就炸」的 bug 用接口测试最容易漏，
 * 因为它看起来像业务错误。
 */
class DomainRecordTest {

    @Test
    @DisplayName("知识条目允许 id 为空（新建时由服务层生成）")
    void knowledgeEntryAllowsNullId() {
        KnowledgeEntry entry = assertDoesNotThrow(() -> new KnowledgeEntry(
                null, "游戏", "标题", "描述", "function OnEvent(e,a) end",
                List.of("tag"), "user", true, null));
        assertNull(entry.id());
        assertEquals("标题", entry.title());
    }

    @Test
    @DisplayName("知识条目的空白 id 归一成 null，而不是抛出")
    void blankIdNormalizedToNull() {
        KnowledgeEntry entry = new KnowledgeEntry("   ", "游戏", "标题", "", "", List.of(), null, true, null);
        assertNull(entry.id());
        assertEquals(List.of(), entry.tags());
        assertEquals("", entry.script());
    }

    @Test
    @DisplayName("脚本名仍然必须非空（这个约束是有意的）")
    void scriptStillRequiresName() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Script("id-1", "  ", "", "", 1, ScriptStatus.ENABLED, null, null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Script("  ", "名字", "", "", 1, ScriptStatus.ENABLED, null, null));
    }

    @Test
    @DisplayName("脚本的缺失字段有安全默认值")
    void scriptDefaultsAreSafe() {
        Script s = new Script("id-1", "名字", null, null, 1, null, null, null);
        assertEquals("", s.source());
        assertEquals(ScriptStatus.ENABLED, s.status());
        assertTrue(s.createdAt() != null);
    }

    @Test
    @DisplayName("热键绑定缺省为「按下即切换」")
    void hotkeyDefaults() {
        HotkeyBinding b = new HotkeyBinding(null, "script-1", "f8", null, null, true);
        assertEquals(HotkeyEvent.KEY_PRESSED, b.event());
        assertEquals(HotkeyAction.TOGGLE, b.action());
    }
}
