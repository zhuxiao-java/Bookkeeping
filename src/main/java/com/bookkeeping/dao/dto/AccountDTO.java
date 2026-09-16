package com.bookkeeping.dao.dto;

import com.bookkeeping.constant.AccountType;
import com.bookkeeping.constant.Archived;
import com.bookkeeping.constant.Currency;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;

import java.math.BigDecimal;

/**
 * 账户dto
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AccountDTO extends BaseDTO<Integer> {

    private String name;

    private AccountType type;

    private BigDecimal initialBalance;

    private BigDecimal currentBalance;

    private Currency currency;

    private Archived archived;
}
