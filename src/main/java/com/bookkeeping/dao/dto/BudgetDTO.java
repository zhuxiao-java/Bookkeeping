package com.bookkeeping.dao.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;

import java.math.BigDecimal;

/**
 * 预算DTO
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class BudgetDTO extends BaseDTO<Integer> {
    private Integer categoryId;
    private BigDecimal amount;
    private int month;
    private int year;
}
