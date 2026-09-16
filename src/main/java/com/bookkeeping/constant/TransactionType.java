package com.bookkeeping.constant;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;

import java.util.Objects;

@AllArgsConstructor
public enum TransactionType implements IEnum<String> {
    /**
     * 收入
     */
    INCOME("income"),
    /**
     * 支出
     */
    EXPENSE("expense"),
    /**
     * 转账
     */
    TRANSFER("transfer"),
    /**
     * 冲正
     */
    REVERSAL("reversal"),
    ;

    @JsonValue
    private final String value;

    @Override
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TransactionType fromValue(String value) {
        for (TransactionType type : TransactionType.values()) {
            if (Objects.equals(type.getValue(), value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("invalid transaction type:" + value);
    }
}
