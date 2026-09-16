package com.bookkeeping.dao.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 记账dto
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class CheckInDTO extends BaseDTO<Integer> {
    private LocalDate checkDate;
    private int expReward;
    private int baseExp;
    private int bonusExp;
    private int streakDays;
}
