package com.bookkeeping.service;

import com.bookkeeping.dao.dto.ExperienceLogDTO;
import org.sf.service.IBaseCrudService;

import java.math.BigDecimal;

/**
 * @author
 * @date 2026年09月04日 11:02
 */
public interface ExperienceLogService extends IBaseCrudService<ExperienceLogDTO> {

    boolean exists(int year, int month);

    /**
     * 记录月预算和实际支出金额，以及经验变化
     * @param year 年
     * @param month 月
     * @param budgetAmount 月预算总额
     * @param actualAmount 实际支出金额
     * @param expChange 经验变化
     */
    void recordExperienceLog(int year, int month, BigDecimal budgetAmount, BigDecimal actualAmount, int expChange);
}
