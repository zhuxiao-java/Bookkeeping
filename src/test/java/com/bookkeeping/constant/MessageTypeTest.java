package com.bookkeeping.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 覆盖 P2-1：MessageType.fromValue 大小写不敏感，且对未知/空值降级为 SYSTEM 而非抛异常，
 * 避免历史脏数据导致整条消息反序列化失败、进而 page 查询整体报错。
 */
class MessageTypeTest {

    @Test
    void fromValue_exactMatch() {
        assertEquals(MessageType.BUDGET, MessageType.fromValue("budget"));
        assertEquals(MessageType.CHECK_IN, MessageType.fromValue("check_in"));
    }

    @Test
    void fromValue_caseInsensitive() {
        assertEquals(MessageType.BUDGET, MessageType.fromValue("Budget"));
        assertEquals(MessageType.GREETING, MessageType.fromValue("GREETING"));
    }

    @Test
    void fromValue_unknown_fallbackToSystem() {
        assertEquals(MessageType.SYSTEM, MessageType.fromValue("not_a_type"));
    }

    @Test
    void fromValue_null_fallbackToSystem() {
        assertEquals(MessageType.SYSTEM, MessageType.fromValue(null));
    }
}
