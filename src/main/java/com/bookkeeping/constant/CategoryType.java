package com.bookkeeping.constant;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;

import java.util.Objects;

@AllArgsConstructor
public enum CategoryType implements IEnum<String> {
    /**
     * 收入
     */
    INCOME("income"),
    /**
     * 支出
     */
    EXPENSE("expense");
    @JsonValue
    private final String category;

    @Override
    public String getValue() {
        return category;
    }

    @JsonCreator
    public static CategoryType fromValue(String category) {
        for (CategoryType categoryType : CategoryType.values()) {
            if (Objects.equals(categoryType.getValue(), category)) {
                return categoryType;
            }
        }
        throw new IllegalArgumentException("invalid category type: " + category);
    }
}
