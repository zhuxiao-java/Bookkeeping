package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bookkeeping.constant.MessageBizType;
import com.bookkeeping.constant.MessageType;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.dao.entity.BudgetEntity;
import com.bookkeeping.dao.mapping.BudgetMapping;
import com.bookkeeping.service.BudgetService.BudgetInfo;
import com.bookkeeping.service.CategoryService;
import com.bookkeeping.service.CategoryService.CategoryTree;
import com.bookkeeping.service.MessageService;
import com.bookkeeping.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 P0-3：selectBudgetInfoByYearMonth 对「未预算分类的支出」不应抛 NPE，
 * 且分类预算只归集其自身及子分类的支出，总预算归集全部支出。
 */
class BudgetServiceImplTest {

    private TransactionService transactionService;
    private CategoryService categoryService;
    private MessageService messageService;
    private BudgetServiceImpl service;

    @BeforeEach
    void setUp() {
        transactionService = mock(TransactionService.class);
        categoryService = mock(CategoryService.class);
        messageService = mock(MessageService.class);
        service = spy(new BudgetServiceImpl(mock(BudgetMapping.class)));
        ReflectionTestUtils.setField(service, "transactionService", transactionService);
        ReflectionTestUtils.setField(service, "categoryService", categoryService);
        ReflectionTestUtils.setField(service, "messageService", messageService);
    }

    private static BudgetEntity budget(Integer id, Integer categoryId, String amount) {
        BudgetEntity e = new BudgetEntity();
        e.setId(id);
        e.setCategoryId(categoryId);
        e.setAmount(new BigDecimal(amount));
        e.setYear(2026);
        e.setMonth(8);
        return e;
    }

    private static TransactionDTO expense(Integer categoryId, String amount) {
        TransactionDTO t = new TransactionDTO();
        t.setCategoryId(categoryId);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    @Test
    void skipsExpenseWithoutBudgetCategory_noNpe() {
        // 预算：一个总预算(categoryId=null) + 一个分类预算(categoryId=10)
        doReturn(List.of(budget(1, null, "1000"), budget(2, 10, "500")))
                .when(service).list(any(Wrapper.class));

        // 支出：10(预算分类本身) / 11(10 的子分类) / 99(无任何预算 —— 旧代码在此 NPE)
        when(transactionService.selectListByYearMonth(2026, 8)).thenReturn(List.of(
                expense(10, "100"),
                expense(11, "50"),
                expense(99, "30")
        ));

        // 分类树：根 10，递归子级含 11
        CategoryTree tree = new CategoryTree(10, "餐饮", "icon", "#ffffff",
                List.of(new CategoryTree(11, "早餐", "icon", "#eeeeee")));
        when(categoryService.getCategoryListByIds(any())).thenReturn(List.of(tree));

        List<BudgetInfo> result = service.selectBudgetInfoByYearMonth(2026, 8);

        assertEquals(2, result.size());
        BudgetInfo total = result.stream().filter(b -> b.categoryId() == null).findFirst().orElseThrow();
        BudgetInfo cat = result.stream().filter(b -> Integer.valueOf(10).equals(b.categoryId())).findFirst().orElseThrow();
        // 总预算：全部支出 100 + 50 + 30 = 180
        assertEquals(0, new BigDecimal("180").compareTo(total.amountUsed()));
        // 分类预算 10：只归集自身(100) + 子分类 11(50) = 150；未预算分类 99 被跳过
        assertEquals(0, new BigDecimal("150").compareTo(cat.amountUsed()));
    }

    @Test
    void onlyTotalBudget_emptyCategoryTree_noNpe() {
        // 只有总预算 -> categoryIdList 为空 -> 分类树为空 -> 旧代码每笔支出都命中 null
        doReturn(List.of(budget(1, null, "1000"))).when(service).list(any(Wrapper.class));
        when(transactionService.selectListByYearMonth(2026, 8)).thenReturn(List.of(
                expense(10, "100"),
                expense(99, "30")
        ));
        when(categoryService.getCategoryListByIds(any())).thenReturn(List.of());

        List<BudgetInfo> result = service.selectBudgetInfoByYearMonth(2026, 8);

        assertEquals(1, result.size());
        // 总预算归集全部支出 100 + 30 = 130
        assertEquals(0, new BigDecimal("130").compareTo(result.get(0).amountUsed()));
    }

    @Test
    void overspend_pushesBudgetAlert() {
        // 总预算 1000，支出 700+500=1200 超支 -> 推送一条 BUDGET 站内信
        doReturn(List.of(budget(1, null, "1000"))).when(service).list(any(Wrapper.class));
        when(transactionService.selectListByYearMonth(2026, 8)).thenReturn(List.of(
                expense(10, "700"), expense(11, "500")));
        when(categoryService.getCategoryListByIds(any())).thenReturn(List.of());
        when(messageService.existsBiz(eq(MessageType.BUDGET), eq(MessageBizType.BUDGET), eq(1), any(), any()))
                .thenReturn(false);

        service.evaluateOverspendAlerts(2026, 8);

        verify(messageService, times(1)).pushMessage(eq("预算超支提醒"), any(), eq(1),
                eq(MessageType.BUDGET), eq(MessageBizType.BUDGET));
    }

    @Test
    void underBudget_noAlert() {
        // 支出 300 < 预算 1000 -> 不推送
        doReturn(List.of(budget(1, null, "1000"))).when(service).list(any(Wrapper.class));
        when(transactionService.selectListByYearMonth(2026, 8)).thenReturn(List.of(expense(10, "300")));
        when(categoryService.getCategoryListByIds(any())).thenReturn(List.of());

        service.evaluateOverspendAlerts(2026, 8);

        verify(messageService, never()).pushMessage(any(), any(), any(), any(MessageType.class), any(MessageBizType.class));
    }

    @Test
    void overspend_alreadyAlertedThisMonth_skips() {
        // 同预算同月已提醒过（existsBiz=true）-> 去重不重推
        doReturn(List.of(budget(1, null, "1000"))).when(service).list(any(Wrapper.class));
        when(transactionService.selectListByYearMonth(2026, 8)).thenReturn(List.of(expense(10, "1200")));
        when(categoryService.getCategoryListByIds(any())).thenReturn(List.of());
        when(messageService.existsBiz(eq(MessageType.BUDGET), eq(MessageBizType.BUDGET), eq(1), any(), any()))
                .thenReturn(true);

        service.evaluateOverspendAlerts(2026, 8);

        verify(messageService, never()).pushMessage(any(), any(), any(), any(MessageType.class), any(MessageBizType.class));
    }
}
