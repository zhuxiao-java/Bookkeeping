package com.bookkeeping.service;

import com.bookkeeping.constant.Granularity;
import com.bookkeeping.constant.TransactionType;
import com.bookkeeping.dao.dto.TransactionDTO;
import org.sf.service.IBaseCrudService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 交易service
 * @author
 */
public interface TransactionService extends IBaseCrudService<TransactionDTO> {
    BigDecimal selectBeforeExpenseAmount(LocalDate before);

    List<TransactionDTO> selectListByYearMonth(int year, int month);


    SummaryDTO summary(LocalDateTime start, LocalDateTime end);

    List<TrendDTO> trend(LocalDateTime start, LocalDateTime end, Granularity granularity);

    List<TransactionCategoryDTO> category(LocalDateTime start, LocalDateTime end, TransactionType type, String groupBy);

    MonthlyDTO monthly(LocalDate start, LocalDate end);

    List<TransactionDTO> recent();

    /**
     * 对账重算：全部账户余额复位到期初余额后，按时间顺序重放全部流水。
     * 用于修复历史数据漂移（如曾经未联动余额的增删改）。
     *
     * @return 重放的流水条数
     */
    int reconcileBalances();

    /**
     * 批量删除流水：逐条复用单条 {@link #delete}（内含账户余额冲销），整体事务原子。
     *
     * @param ids 流水主键列表
     * @return 实际删除条数
     */
    int batchDelete(List<Integer> ids);

    /**
     * 批量修改分类：单条 UPDATE 批量置 f_category_id（金额/类型/账户不变，无余额影响）。
     * 分类归属影响预算命中，故对受影响支出流水所在月份重估预算超支。整体事务原子。
     *
     * @param ids        流水主键列表
     * @param categoryId 目标分类 id
     * @return 受影响条数
     */
    int batchUpdateCategory(List<Integer> ids, Integer categoryId);

    /**
     * 记账（新增流水）并返回本次发放的经验奖励，供前端记账成功后即时激励反馈。
     * 复用与 {@link #insert} 相同的主流程（余额联动 + 超支评估 + 经验发放），整体事务原子。
     *
     * @param dto 流水
     * @return 本次发放经验值（&gt;=0）；新增失败返回 -1
     */
    int saveWithReward(TransactionDTO dto);

    record SummaryDTO(BigDecimal income, BigDecimal expense, BigDecimal balance, int count) {
        public SummaryDTO(BigDecimal income, BigDecimal expense, int count) {
            this(income, expense, income.subtract(expense), count);
        }
    }

    record TransactionCategoryDTO(Integer categoryId, String name, String icon, String color, BigDecimal amount, int count) {

    }

    record TrendDTO(String key, BigDecimal income, BigDecimal expense) {

    }

    record MonthlyInfo(String key, int year, int month, BigDecimal income, BigDecimal expense, BigDecimal balance, BigDecimal cumulative) {

        public MonthlyInfo(String key, int year, int month, BigDecimal income, BigDecimal expense, BigDecimal cumulative) {
            this(key, year, month, income, expense, income.subtract(expense), cumulative);
        }
    }

    record MonthlyDTO(List<MonthlyInfo> rows, int surplusMonths, int deficitMonths, BigDecimal cumulative) {
    }
}
