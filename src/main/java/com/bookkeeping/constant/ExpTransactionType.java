package com.bookkeeping.constant;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum ExpTransactionType implements IEnum<String> {
    BUDGET("budget"),
    CHECK_IN("check_in"),
    /**
     * 记账奖励（今日首笔较大、后续每笔小额、单日封顶）
     */
    RECORD("record"),
    OTHER("other");

    private final String source;

    @Override
    public String getValue() {
        return source;
    }
}
