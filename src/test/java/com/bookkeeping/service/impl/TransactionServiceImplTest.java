package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bookkeeping.constant.TransactionType;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.dao.mapping.TransactionMapping;
import com.bookkeeping.service.BudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 覆盖 P2-3：批量删除逐条复用单删（含余额冲销）；批量改分类走单条 UPDATE 并对受影响支出月份重估超支。
 */
class TransactionServiceImplTest {

    private BudgetService budgetService;
    private TransactionServiceImpl service;

    @BeforeEach
    void setUp() {
        budgetService = mock(BudgetService.class);
        service = spy(new TransactionServiceImpl(mock(TransactionMapping.class)));
        ReflectionTestUtils.setField(service, "budgetService", budgetService);
    }

    private static TransactionDTO expense(LocalDateTime date) {
        TransactionDTO t = new TransactionDTO();
        t.setType(TransactionType.EXPENSE);
        t.setAmount(new BigDecimal("100"));
        t.setTransactionDate(date);
        return t;
    }

    @Test
    void batchDelete_delegatesToDeletePerId() {
        doReturn(true).when(service).delete(any());

        int count = service.batchDelete(List.of(1, 2, 3));

        assertEquals(3, count);
        verify(service, times(3)).delete(any());
    }

    @Test
    void batchDelete_emptyIds_returnsZero() {
        int count = service.batchDelete(List.of());

        assertEquals(0, count);
        verify(service, never()).delete(any());
    }

    @Test
    void batchUpdateCategory_updatesAndReevaluatesOverspend() {
        doReturn(expense(LocalDateTime.of(2026, 8, 15, 10, 0))).when(service).detail(any());
        doReturn(true).when(service).update(any(Wrapper.class));

        int count = service.batchUpdateCategory(List.of(1, 2), 99);

        assertEquals(2, count);
        verify(service, times(1)).update(any(Wrapper.class));
        // 两笔支出同属 2026-8，去重后只重估一次
        verify(budgetService, times(1)).evaluateOverspendAlerts(2026, 8);
    }

    @Test
    void batchUpdateCategory_emptyIds_returnsZero() {
        int count = service.batchUpdateCategory(List.of(), 99);

        assertEquals(0, count);
        verify(service, never()).update(any(Wrapper.class));
        verify(budgetService, never()).evaluateOverspendAlerts(anyInt(), anyInt());
    }
}
