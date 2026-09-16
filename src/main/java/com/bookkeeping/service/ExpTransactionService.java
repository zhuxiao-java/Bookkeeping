package com.bookkeeping.service;

import com.bookkeeping.constant.ExpTransactionType;
import com.bookkeeping.dao.dto.ExpTransactionDTO;
import org.sf.service.IBaseCrudService;

/**
 * 经验变更流水service
 *
 * @author zhuxiao
 */
public interface ExpTransactionService extends IBaseCrudService<ExpTransactionDTO> {
    void recordExpTransaction(int refId, String description, ExpTransactionType type, int beforeExperience, int experience);

    /**
     * 统计当日（系统默认时区）指定来源已发放的经验变动之和，用于记账经验的单日封顶判定。
     *
     * @param type 经验来源
     * @return 当日该来源经验变动累计值（无记录时为 0）
     */
    int sumTodayExpBySource(ExpTransactionType type);
}
