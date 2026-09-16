package com.bookkeeping.constant;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;

import java.util.Objects;

@AllArgsConstructor
public enum Currency implements IEnum<String> {

    CNY("CNY"),

    DOLLAR("DOLLAR");

    @JsonValue
    private final String value;

    @Override
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static Currency fromValue(String value) {
        for (Currency currency : Currency.values()) {
            if (Objects.equals(currency.getValue(), value)) {
                return currency;
            }
        }
        throw new IllegalArgumentException("invalid value " + value);
    }
}
