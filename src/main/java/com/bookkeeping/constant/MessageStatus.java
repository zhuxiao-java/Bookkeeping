package com.bookkeeping.constant;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum MessageStatus implements IEnum<Integer> {
    /**
     * 未读
     */
    UN_READ(0),
    /**
     * 已读
     */
    READ(1);

    @JsonValue
    private final int status;

    @Override
    public Integer getValue() {
        return status;
    }

    @JsonCreator
    public static MessageStatus fromStatus(Integer status) {
        for (MessageStatus messageStatus : MessageStatus.values()) {
            if (messageStatus.status == status) {
                return messageStatus;
            }
        }
        throw new IllegalArgumentException("invalid status value");
    }

}
