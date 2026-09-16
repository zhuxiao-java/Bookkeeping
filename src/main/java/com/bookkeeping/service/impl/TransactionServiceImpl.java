package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bookkeeping.constant.Granularity;
import com.bookkeeping.constant.TransactionType;
import com.bookkeeping.dao.dto.AccountDTO;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.dao.entity.TransactionEntity;
import com.bookkeeping.dao.mapper.TransactionMapper;
import com.bookkeeping.dao.mapping.TransactionMapping;
import com.bookkeeping.service.AccountService;
import com.bookkeeping.service.BudgetService;
import com.bookkeeping.service.CategoryService;
import com.bookkeeping.service.TransactionService;
import com.bookkeeping.service.UserLevelService;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.sf.util.StreamUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 交易service实现类
 *
 * @author zhuxiao
 */
@Service
@Slf4j
public class TransactionServiceImpl extends IBaseCrudServiceImpl<TransactionDTO, TransactionEntity, TransactionMapper, TransactionMapping> implements TransactionService {
    @Resource
    private AccountService accountService;
    @Resource
    private CategoryService categoryService;
    /**
     * BudgetService 与本类构成循环依赖（BudgetServiceImpl 依赖 TransactionService），
     * Spring Boot 2.6+ 默认禁止循环引用，用 @Lazy 注入代理在首次使用时才解析，打破创建期循环。
     */
    @Lazy
    @Autowired
    private BudgetService budgetService;

    /**
     * UserLevelService 与本类构成循环依赖（UserLevelServiceImpl 依赖 TransactionService），
     * 同样用 @Lazy @Autowired 注入代理打破创建期环（@Lazy 对 @Resource 不生效）。
     */
    @Lazy
    @Autowired
    private UserLevelService userLevelService;

    public TransactionServiceImpl(TransactionMapping mapping) {
        super(mapping);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insert(TransactionDTO dto) {
        return doInsert(dto) >= 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveWithReward(TransactionDTO dto) {
        return doInsert(dto);
    }

    /**
     * 记账主流程：落库 + 余额联动 + 超支评估 + 经验发放。
     * insert / saveWithReward 共用，保证两条路径行为一致。
     *
     * @return 本次发放经验（&gt;=0）；落库失败返回 -1
     */
    private int doInsert(TransactionDTO dto) {
        if (!super.insert(dto)) {
            return -1;
        }
        accountService.applyEffect(dto, false);
        alertOverspend(dto);
        return awardRecordExperience();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(TransactionDTO dto) {
        // 改前完整快照
        TransactionDTO before = detail(dto.getId());
        if (!super.update(dto)) {
            return false;
        }
        // 读回落库后的完整快照，规避部分字段更新（updateById 只写非 null 字段）导致的算错
        TransactionDTO after = detail(dto.getId());
        accountService.applyEffect(before, true);   // 冲销旧影响
        accountService.applyEffect(after, false);   // 施加新影响
        alertOverspend(after);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Serializable id) {
        // 删前完整快照
        TransactionDTO before = detail(id);
        if (!super.delete(id)) {
            return false;
        }
        accountService.applyEffect(before, true);   // 冲销
        return true;
    }

    @Override
    public BigDecimal selectBeforeExpenseAmount(LocalDate before) {
        QueryWrapper<TransactionEntity> qw = new QueryWrapper<>();
        // 左闭右开覆盖当天：ge 含 00:00:00 零点流水，lt 排除次日零点（NEW-02）
        qw.ge("f_date", before.atStartOfDay())
                .lt("f_date", before.plusDays(1).atStartOfDay());
        List<TransactionEntity> entityList = list(qw);
        return StreamUtil.sum(entityList, TransactionEntity::getAmount);
    }

    @Override
    public List<TransactionDTO> selectListByYearMonth(int year, int month) {
        QueryWrapper<TransactionEntity> qw = new QueryWrapper<>();
        LocalDate date = LocalDate.of(year, month, 1);
        LocalDateTime start = date.atStartOfDay();
        // 左闭右开覆盖整月：ge 含月初 00:00:00 零点流水，lt 排除下月初零点（NEW-02）
        qw.ge("f_date", start).
                lt("f_date", date.plusMonths(1).atStartOfDay())
                .eq("f_type", TransactionType.EXPENSE.getValue());
        return mapping.toDtoList(super.list(qw));
    }

    @Override
    public SummaryDTO summary(LocalDateTime start, LocalDateTime end) {
        QueryWrapper<TransactionEntity> qw = qw();
        qw.between("f_date", start, end)
                .in("f_type", TransactionType.INCOME.getValue(),
                        TransactionType.EXPENSE.getValue());
        List<TransactionEntity> list = super.list(qw);
        AmountDTO dto = calculateAmount(list);
        return new SummaryDTO(dto.income(), dto.expense, list.size());
    }

    private AmountDTO calculateAmount(List<TransactionEntity> entityList) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (TransactionEntity entity : entityList) {
            if (Objects.equals(entity.getType(), TransactionType.INCOME)) {
                income = income.add(entity.getAmount());
            } else {
                expense = expense.add(entity.getAmount());
            }
        }
        return new AmountDTO(income, expense);
    }

    private record AmountDTO(BigDecimal income, BigDecimal expense) {

    }

    @Override
    public List<TrendDTO> trend(LocalDateTime start, LocalDateTime end, Granularity granularity) {
        QueryWrapper<TransactionEntity> qw = qw().
                between("f_date", start, end).
                orderByAsc("f_date").in("f_type", TransactionType.INCOME.getValue(),
                TransactionType.EXPENSE.getValue());
        List<TransactionEntity> list = list(qw);
        Map<String, List<TransactionEntity>> dateGroup = new LinkedHashMap<>();
        for (TransactionEntity transaction : list) {
            dateGroup.computeIfAbsent(granularity.granularityKey(transaction.getTransactionDate()), key -> new ArrayList<>()).add(transaction);
        }
        List<TrendDTO> dtoList = new ArrayList<>(dateGroup.size());
        dateGroup.forEach((key, val) -> {
            AmountDTO dto = calculateAmount(val);
            dtoList.add(new TrendDTO(key, dto.income(), dto.expense()));
        });
        return dtoList;
    }

    @Override
    public List<TransactionCategoryDTO> category(LocalDateTime start, LocalDateTime end, TransactionType type, String groupBy) {
        QueryWrapper<TransactionEntity> qw = qw().between("f_date", start, end)
                .eq("f_type", type.getValue());
        List<TransactionEntity> list = list(qw);
        List<Integer> categoryIdList = StreamUtil.map(list, TransactionEntity::getCategoryId);
        List<CategoryService.CategoryTree> treeList = categoryService.getCategoryTreeListByIds(categoryIdList);
        List<TransactionCategoryDTO> dtoList = new ArrayList<>();
        Map<CategoryService.CategoryTree, List<TransactionEntity>> group = StreamUtil.group(list, transaction -> {
            if (Objects.equals(groupBy, "self")) {
                return StreamUtil.any(treeList, tree -> tree.isChild(transaction.getCategoryId()))
                        .chooseCategory(transaction.getCategoryId());
            } else {
                return StreamUtil.any(treeList, tree -> tree.isChild(transaction.getCategoryId()));
            }
        });
        group.forEach((tree, transactionList) -> {
            BigDecimal amount = StreamUtil.sum(transactionList, TransactionEntity::getAmount);
            dtoList.add(
                    new TransactionCategoryDTO(tree.categoryId(), tree.categoryName(), tree.icon(), tree.color(), amount, transactionList.size())
            );
        });
        return dtoList;
    }

    @Override
    public MonthlyDTO monthly(LocalDate start, LocalDate end) {
        // 月度盈亏按整月归集：左闭右开覆盖 [start 所在月 1 号零点, end 所在月末次日零点)。
        // 原 between(..., end.plusMonths(1)) 为闭区间且以具体日推算，会越界多算下月并漏月初零点（NEW-02）
        List<TransactionEntity> list = list(
                qw().in("f_type", List.of(TransactionType.INCOME.getValue(), TransactionType.EXPENSE.getValue()))
                        .ge("f_date", start.withDayOfMonth(1).atStartOfDay())
                        .lt("f_date", end.withDayOfMonth(end.lengthOfMonth()).plusDays(1).atStartOfDay())
                        .orderByAsc("f_date")
        );
        Map<String, MonthlyAmount> monthlyMap = new LinkedHashMap<>();
        for (TransactionEntity entity : list) {
            MonthlyAmount monthlyAmount = monthlyMap.computeIfAbsent(entity.getTransactionDate().getYear() + "-" + entity.getTransactionDate().getMonth().getValue(), key -> new MonthlyAmount());
            monthlyAmount.add(entity.getAmount(), entity.getType());
        }
        List<MonthlyInfo> infos = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        int surplusMonths = 0;
        int deficitMonths = 0;
        for (Map.Entry<String, MonthlyAmount> entry : monthlyMap.entrySet()) {
            String[] array = entry.getKey().split("-");
            cumulative = cumulative.add(entry.getValue().getCumulative());
            MonthlyInfo info = new MonthlyInfo(
                    entry.getKey(),
                    Integer.parseInt(array[0]),
                    Integer.parseInt(array[1]),
                    entry.getValue().getIncome(),
                    entry.getValue().getExpense(),
                    cumulative
            );
            int compareTo = info.balance().compareTo(BigDecimal.ZERO);
            if (compareTo > 0) {
                surplusMonths++;
            } else if (compareTo < 0) {
                deficitMonths++;
            }
            infos.add(info);
        }
        return new MonthlyDTO(infos, surplusMonths, deficitMonths, cumulative);
    }

    @Override
    public List<TransactionDTO> recent() {
        return mapping.toDtoList(baseMapper.recent());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int reconcileBalances() {
        // 1) 全部账户复位到期初余额
        for (AccountDTO account : accountService.selectAll()) {
            accountService.setBalance(account.getId(), nz(account.getInitialBalance()));
        }
        // 2) 按时间顺序重放全部流水，复用 applyEffect（与增删改同一套符号逻辑，天然一致）
        List<TransactionDTO> all = mapping.toDtoList(list(qw().orderByAsc("f_date", "f_id")));
        for (TransactionDTO tx : all) {
            accountService.applyEffect(tx, false);
        }
        return all.size();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Integer> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return 0;
        }
        int count = 0;
        for (Integer id : ids) {
            // 复用单条 delete：内含改前快照 + 余额冲销，与单删同一套逻辑
            if (delete(id)) {
                count++;
            }
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateCategory(List<Integer> ids, Integer categoryId) {
        if (CollectionUtils.isEmpty(ids)) {
            return 0;
        }
        // 先收集受影响支出流水的月份：改分类会改变预算归属，需在改后重估这些月份的超支
        Set<String> expenseMonths = new HashSet<>();
        for (Integer id : ids) {
            TransactionDTO tx = detail(id);
            if (tx != null && tx.getType() == TransactionType.EXPENSE && tx.getTransactionDate() != null) {
                expenseMonths.add(tx.getTransactionDate().getYear() + "-" + tx.getTransactionDate().getMonthValue());
            }
        }
        // 金额/类型/账户均不变，无余额影响，一条 UPDATE 批量置分类即可
        UpdateWrapper<TransactionEntity> uw = new UpdateWrapper<>();
        uw.in("f_id", ids).set("f_category_id", categoryId);
        update(uw);
        for (String ym : expenseMonths) {
            String[] arr = ym.split("-");
            budgetService.evaluateOverspendAlerts(Integer.parseInt(arr[0]), Integer.parseInt(arr[1]));
        }
        return ids.size();
    }

    /**
     * 记账成功后发放经验奖励（今日首笔较大、后续小额、单日封顶），返回本次发放值。
     * 属激励副作用，任何异常都不得影响记账主流程，故吞掉并记日志。
     */
    private int awardRecordExperience() {
        try {
            return userLevelService.gainRecordExperience();
        } catch (Exception e) {
            log.warn("记账经验奖励发放失败，已忽略以保证记账成功", e);
            return 0;
        }
    }

    /**
     * 支出落账后实时评估所在月份预算超支（仅 EXPENSE 影响支出预算；去重由 BudgetService 按预算/月保证）。
     */
    private void alertOverspend(TransactionDTO tx) {
        if (tx == null || tx.getType() != TransactionType.EXPENSE || tx.getTransactionDate() == null) {
            return;
        }
        budgetService.evaluateOverspendAlerts(tx.getTransactionDate().getYear(), tx.getTransactionDate().getMonthValue());
    }

    @Data
    static
    class MonthlyAmount {
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal expense = BigDecimal.ZERO;
        private BigDecimal cumulative = BigDecimal.ZERO;

        public BigDecimal getCumulative() {
            return income.subtract(expense);
        }

        public void add(BigDecimal amount, TransactionType type) {
            if (Objects.equals(type, TransactionType.INCOME)) {
                this.income = income.add(amount);
            } else if (Objects.equals(type, TransactionType.EXPENSE)) {
                this.expense = expense.add(amount);
            }
        }
    }
}
