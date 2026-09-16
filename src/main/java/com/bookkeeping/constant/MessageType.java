package com.bookkeeping.constant;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public enum MessageType implements IEnum<String> {
    SYSTEM("system"),
    BUDGET("budget"),
    LEVEL("level"),
    CHECK_IN("check_in"),
    GREETING("greeting"),
    WEATHER("weather"),
    ;
    @JsonValue
    private final String type;

    @JsonCreator
    public static MessageType fromValue(String value) {
        for (MessageType type : MessageType.values()) {
            if (type.type.equalsIgnoreCase(value)) {
                return type;
            }
        }
        // 历史脏数据 / 未知类型不再抛异常中断整条消息反序列化（会导致 page 查询整体失败），
        // 降级为 SYSTEM 并记录 warn，保证消息列表可读
        log.warn("Unknown MessageType value: {}, fallback to SYSTEM", value);
        return SYSTEM;
    }

    @Override
    public String getValue() {
        return type;
    }
}
