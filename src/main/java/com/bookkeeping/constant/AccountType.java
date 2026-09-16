package com.bookkeeping.constant;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;

import java.util.Objects;

@AllArgsConstructor
public enum AccountType implements IEnum<String> {
    /**
     * 现金
     */
    CASH("cash"),
    /**
     * 银行卡
     */
    BANK("bank"),
    /**
     * 支付宝
     */
    ALI_PAY("ali_pay"),
    /**
     * 微信支付
     */
    WECHAT_PAY("wechat_pay");


    @JsonValue
    private final String type;

    @Override
    public String getValue() {
        return type;
    }

    @JsonCreator
    public static AccountType fromValue(String value) {
        for (AccountType accountType : AccountType.values()) {
            if (Objects.equals(value, accountType.getValue())) {
                return accountType;
            }
        }
        throw new IllegalArgumentException("Invalid AccountType: " + value);
    }
}
