package com.bookkeeping.service;

import com.bookkeeping.dao.dto.BudgetDTO;
import org.sf.service.IBaseCrudService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 预算service
 *
 * @author zx
 */
public interface BudgetService extends IBaseCrudService<BudgetDTO> {

    List<BudgetDTO> selectBudgetByYearMonth(int year, int month);

    BigDecimal searchCategoryBudgetSum(int year, int month);

    BudgetDTO selectOneByCategoryId(int year, int month, Integer categoryId);

    List<BudgetInfo> selectBudgetInfoByYearMonth(int year, int month);

    /**
     * 评估指定年月的预算超支情况，对达到/超出预算的项推送 BUDGET 站内信（同一预算同一月只推一次）。
     */
    void evaluateOverspendAlerts(int year, int month);

    /**
     * 预算信息
     * @param id 主键id
     * @param main 是否为主预算
     * @param categoryName 分类预算名称
     * @param amount 预算金额
     * @param amountUsed 预算已用金额
     */
    record BudgetInfo(Integer id, Integer categoryId ,boolean main, String categoryName, BigDecimal amount, BigDecimal amountUsed) {
        public BudgetInfo(Integer id, Integer categoryId ,String categoryName, BigDecimal amount, BigDecimal amountUsed){
            this(id, categoryId, Objects.isNull(categoryName), categoryName, amount, amountUsed);
        }
    }
}
