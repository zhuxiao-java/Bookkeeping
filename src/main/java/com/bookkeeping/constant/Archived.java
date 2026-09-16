package com.bookkeeping.constant;


import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;

import java.util.Objects;

@AllArgsConstructor
public enum Archived implements IEnum<Integer> {
    /**
     * 正常
     */
    NORMAL(0),
    /**
     * 已归档
     */
    ARCHIVED(1)
    ;

    @JsonValue
    private final int type;

    @Override
    public Integer getValue() {
        return type;
    }

    @JsonCreator
    public static Archived fromValue(Integer value) {
        for (Archived archived : Archived.values()) {
            if (Objects.equals(archived.getValue(), value)) {
                return archived;
            }
        }
        throw new IllegalArgumentException("Archived value " + value + " does not exist");
    }
}
