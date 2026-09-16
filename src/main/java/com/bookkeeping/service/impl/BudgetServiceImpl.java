package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.constant.MessageBizType;
import com.bookkeeping.constant.MessageType;
import com.bookkeeping.dao.dto.BudgetDTO;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.dao.entity.BudgetEntity;
import com.bookkeeping.dao.mapper.BudgetMapper;
import com.bookkeeping.dao.mapping.BudgetMapping;
import com.bookkeeping.exception.BusinessException;
import com.bookkeeping.service.BudgetService;
import com.bookkeeping.service.CategoryService;
import com.bookkeeping.service.MessageService;
import com.bookkeeping.service.TransactionService;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.sf.util.StreamUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 预算service实现类
 *
 * @author zhuxiao
 */
@Service
public class BudgetServiceImpl extends IBaseCrudServiceImpl<BudgetDTO, BudgetEntity, BudgetMapper, BudgetMapping> implements BudgetService {
    @Resource
    private TransactionService transactionService;
    @Resource
    private CategoryService categoryService;
    @Resource
    private MessageService messageService;

    public BudgetServiceImpl(BudgetMapping mapping) {
        super(mapping);
    }

    @Override
    protected void savePreCheck(BudgetEntity entity) {
        BudgetDTO baseBudget = selectOneByCategoryId(entity.getYear(), entity.getMonth(), null);
        if (Objects.isNull(baseBudget) && Objects.nonNull(entity.getCategoryId())) {
            throw new BusinessException(BookkeepingResp.MONTHLY_BUDGET_NOT_EXISTS);
        }
        if (Objects.nonNull(baseBudget)) {
            if (Objects.isNull(entity.getCategoryId())) {
                throw new BusinessException(BookkeepingResp.BUDGET_EXISTS);
            } else if (selectOneByCategoryId(entity.getYear(), entity.getMonth(), entity.getCategoryId()) != null) {
                throw new BusinessException(BookkeepingResp.BUDGET_CATEGORY_EXISTS);
            } else {
                BigDecimal budgetSum = searchCategoryBudgetSum(entity.getYear(), entity.getMonth());
                BigDecimal newBudgetSum = budgetSum.add(entity.getAmount());
                if (newBudgetSum.compareTo(baseBudget.getAmount()) > 0) {
                    throw new BusinessException(BookkeepingResp.CLASSIFICATION_BUDGET_EXCEED.getCode(),
                            String.format(BookkeepingResp.CLASSIFICATION_BUDGET_EXCEED.getMsg(), "总预算:" + baseBudget.getAmount()
                                    + ", 已存在的分类预算:" + budgetSum + ",本次新增分类预算:" + entity.getAmount()));
                }
            }
        }
    }

    @Override
    public BudgetDTO selectOneByCategoryId(int year, int month, Integer categoryId) {
        QueryWrapper<BudgetEntity> qw = new QueryWrapper<>();
        qw.eq("f_year", year)
                .eq("f_month", month);
        if (Objects.nonNull(categoryId)) {
            qw.eq("f_category_id", categoryId);
        } else {
            qw.isNull("f_category_id");
        }
        return mapping.toDto(super.getOne(qw));
    }

    @Override
    public List<BudgetInfo> selectBudgetInfoByYearMonth(int year, int month) {
        QueryWrapper<BudgetEntity> qw = new QueryWrapper<>();
        qw.eq("f_year", year).eq("f_month", month);
        List<BudgetEntity> entityList = list(qw);
        if (CollectionUtils.isEmpty(entityList)) {
            return Collections.emptyList();
        }
        List<TransactionDTO> dtoList = transactionService.selectListByYearMonth(year, month);
        List<Integer> categoryIdList = StreamUtil.map(entityList, entity -> Objects.nonNull(entity.getCategoryId()), BudgetEntity::getCategoryId);
        List<CategoryService.CategoryTree> treeList = categoryService.getCategoryListByIds(categoryIdList);
        // 合并函数兜底，避免极端重复 categoryId 触发 Collectors.toMap 的 IllegalStateException
        Map<Integer, CategoryService.CategoryTree> treeMap = treeList.stream()
                .collect(Collectors.toMap(CategoryService.CategoryTree::categoryId, tree -> tree, (a, b) -> a));
        BigDecimal expenseAmount = StreamUtil.sum(dtoList, TransactionDTO::getAmount);
        // 按预算分类归集支出：一笔支出可能不属于任何预算分类（既非预算分类本身、也非其子分类），
        // 此时 StreamUtil.any 返回 null，应跳过而非取 categoryId（否则 NPE）
        Map<Integer, BigDecimal> categoryExpenseMap = new HashMap<>();
        for (TransactionDTO tx : dtoList) {
            CategoryService.CategoryTree tree = matchBudgetTree(treeList, tx.getCategoryId());
            if (Objects.nonNull(tree)) {
                categoryExpenseMap.merge(tree.categoryId(), tx.getAmount(), BigDecimal::add);
            }
        }
        return StreamUtil.map(entityList, dto -> buildBudgetInfo(dto, treeMap.get(dto.getCategoryId()), Objects.isNull(dto.getCategoryId())? expenseAmount : categoryExpenseMap.getOrDefault(dto.getCategoryId(), BigDecimal.ZERO)));
    }

    private BudgetInfo buildBudgetInfo(BudgetEntity entity, CategoryService.CategoryTree tree, BigDecimal amountUsed) {
        return new BudgetInfo(entity.getId(), entity.getCategoryId(), Objects.isNull(tree)? null: tree.categoryName(), entity.getAmount(), amountUsed);
    }

    /**
     * 找到分类归属的预算分类树：分类本身即预算分类，或为某预算分类的（任意层）子分类。
     * getCategoryListByIds 递归返回全部后代，故深层子分类也能命中；无归属返回 null。
     */
    private CategoryService.CategoryTree matchBudgetTree(List<CategoryService.CategoryTree> treeList, Integer categoryId) {
        return StreamUtil.any(treeList, tree -> Objects.equals(categoryId, tree.categoryId()) || tree.isChild(categoryId));
    }

    @Override
    public List<BudgetDTO> selectBudgetByYearMonth(int year, int month) {
        QueryWrapper<BudgetEntity> qw = new QueryWrapper<>();
        qw.eq("f_year", year)
                .eq("f_month", month);
        return mapping.toDtoList(super.list(qw));
    }

    @Override
    public void evaluateOverspendAlerts(int year, int month) {
        List<BudgetInfo> infoList = selectBudgetInfoByYearMonth(year, month);
        if (CollectionUtils.isEmpty(infoList)) {
            return;
        }
        LocalDate first = LocalDate.of(year, month, 1);
        LocalDateTime start = first.atStartOfDay();
        LocalDateTime end = first.plusMonths(1).atStartOfDay();
        for (BudgetInfo info : infoList) {
            if (Objects.isNull(info.amount()) || Objects.isNull(info.amountUsed())
                    || info.amountUsed().compareTo(info.amount()) < 0) {
                continue;   // 未达预算，跳过
            }
            if (messageService.existsBiz(MessageType.BUDGET, MessageBizType.BUDGET, info.id(), start, end)) {
                continue;   // 本预算本月已提醒过
            }
            String name = info.main() ? "总预算"
                    : (Objects.isNull(info.categoryName()) ? "分类预算" : info.categoryName() + "预算");
            String content = String.format("%d 年 %d 月%s已用 %s，达到/超出预算 %s，请注意控制开销。",
                    year, month, name, info.amountUsed(), info.amount());
            messageService.pushMessage("预算超支提醒", content, info.id(), MessageType.BUDGET, MessageBizType.BUDGET);
        }
    }

    @Override
    public BigDecimal searchCategoryBudgetSum(int year, int month) {
        BigDecimal result = baseMapper.searchCategoryBudgetSum(year, month);
        return Objects.isNull(result) ? BigDecimal.ZERO : result;
    }
}
