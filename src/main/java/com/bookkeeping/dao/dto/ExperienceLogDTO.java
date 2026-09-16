package com.bookkeeping.dao.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;

import java.math.BigDecimal;

/**
 * 等级变化DTO
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ExperienceLogDTO extends BaseDTO<Integer> {
    private int year;
    private int month;
    private BigDecimal budgetAmount;
    private BigDecimal actualAmount;
    private BigDecimal diffAmount;
    private BigDecimal expChange;
}
