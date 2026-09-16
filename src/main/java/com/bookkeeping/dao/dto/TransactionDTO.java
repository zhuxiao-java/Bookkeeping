package com.bookkeeping.dao.dto;

import com.bookkeeping.constant.TransactionType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易明细dto
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class TransactionDTO extends BaseDTO<Integer> {

    private TransactionType type;
    private BigDecimal fee;
    private BigDecimal amount;
    private Integer accountId;
    private Integer toAccountId;
    private Integer categoryId;
    private LocalDateTime transactionDate;
    private String note;
    private String tags;
}
