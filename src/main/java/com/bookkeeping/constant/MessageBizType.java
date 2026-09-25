package com.bookkeeping.constant;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum MessageBizType implements IEnum<String> {
    TRANSACTION("transaction"),
    BUDGET("budget"),
    LEVEL("level"),
    MONTHLY_REPORT("monthly_report"),
    WEEKLY_REPORT("weekly_report"),
    YEARLY_REPORT("yearly_report")
    ;
    @JsonValue
    private final String type;

    @Override
    public String getValue() {
        return type;
    }
    @JsonCreator
    public static MessageBizType fromValue(String value) {
        for (MessageBizType type : MessageBizType.values()) {
            if (type.type.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid MessageBizType: " + value);
    }
}
