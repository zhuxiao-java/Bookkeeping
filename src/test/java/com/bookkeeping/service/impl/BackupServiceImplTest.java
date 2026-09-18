package com.bookkeeping.service.impl;

import com.bookkeeping.constant.TransactionType;
import com.bookkeeping.controller.BackupController;
import com.bookkeeping.dao.dto.AccountDTO;
import com.bookkeeping.dao.dto.CategoryDTO;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.service.AccountService;
import com.bookkeeping.service.BackupService.CsvImportResult;
import com.bookkeeping.service.BackupService.CsvPreviewResult;
import com.bookkeeping.service.CategoryService;
import com.bookkeeping.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 覆盖 P3-1 的 CSV 导入导出：按账户名/分类名解析、仅新增、逐行容错跳过非法行；
 * 导出含 BOM、表头与名称还原。快照/恢复涉及真实文件与 DataSource，属集成层，不在此单测。
 */
class BackupServiceImplTest {

    private TransactionService transactionService;
    private AccountService accountService;
    private CategoryService categoryService;
    private BackupServiceImpl service;

    @BeforeEach
    void setUp() {
        transactionService = mock(TransactionService.class);
        accountService = mock(AccountService.class);
        categoryService = mock(CategoryService.class);
        service = new BackupServiceImpl();
        ReflectionTestUtils.setField(service, "transactionService", transactionService);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "categoryService", categoryService);
    }

    @Test
    void storagePath_resolvesRelativeDirectory() {
        ReflectionTestUtils.setField(service, "bookkeepingDir", "./data");

        String result = service.storagePath();

        assertTrue(Path.of(result).isAbsolute());
        assertEquals(Path.of("").toAbsolutePath().resolve("data").toString(), result);
    }

    @Test
    void storagePath_normalizesCustomDirectoryWithoutCreatingIt(@TempDir Path tempDir) {
        Path expected = tempDir.resolve("账本 数据");
        Path configured = tempDir.resolve("旧目录").resolve("..").resolve("账本 数据");
        ReflectionTestUtils.setField(service, "bookkeepingDir", configured.toString());

        assertEquals(expected.toString(), service.storagePath());
        assertFalse(Files.exists(expected));
    }

    @Test
    void storagePath_endpointReturnsConfiguredDirectory(@TempDir Path tempDir) throws Exception {
        ReflectionTestUtils.setField(service, "bookkeepingDir", tempDir.toString());

        MockMvcBuilders.standaloneSetup(new BackupController(service)).build()
                .perform(get("/backup/storagePath"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("S0806"))
                .andExpect(jsonPath("$.data").value(tempDir.toString()));
    }

    private static AccountDTO account(Integer id, String name) {
        AccountDTO a = new AccountDTO();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private static CategoryDTO category(Integer id, String name) {
        CategoryDTO c = new CategoryDTO();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private void givenDict() {
        when(accountService.selectAll()).thenReturn(List.of(account(1, "现金")));
        when(categoryService.selectAll()).thenReturn(List.of(category(10, "餐饮")));
    }

    private static final String HEADER = "日期,类型,金额,手续费,账户,转入账户,分类,备注,标签";

    @Test
    void import_resolvesByNameAndInserts() {
        givenDict();
        when(transactionService.insert(any())).thenReturn(true);
        String csv = HEADER + "\n2026-08-15 10:00:00,expense,100,,现金,,餐饮,午餐,\n";

        CsvImportResult r = service.importTransactionsCsv(csv, true);

        assertEquals(1, r.total());
        assertEquals(1, r.imported());
        assertEquals(0, r.skipped());
        ArgumentCaptor<TransactionDTO> captor = ArgumentCaptor.forClass(TransactionDTO.class);
        verify(transactionService).insert(captor.capture());
        TransactionDTO dto = captor.getValue();
        assertEquals(1, dto.getAccountId());
        assertEquals(10, dto.getCategoryId());
        assertEquals(TransactionType.EXPENSE, dto.getType());
        assertEquals(0, new BigDecimal("100").compareTo(dto.getAmount()));
        assertEquals(LocalDateTime.of(2026, 8, 15, 10, 0), dto.getTransactionDate());
        assertEquals("午餐", dto.getNote());
    }

    @Test
    void import_dateOnly_parsedAsStartOfDay() {
        givenDict();
        when(transactionService.insert(any())).thenReturn(true);
        String csv = HEADER + "\n2026-08-15,expense,50,,现金,,餐饮,,\n";

        CsvImportResult r = service.importTransactionsCsv(csv, true);

        assertEquals(1, r.imported());
        ArgumentCaptor<TransactionDTO> captor = ArgumentCaptor.forClass(TransactionDTO.class);
        verify(transactionService).insert(captor.capture());
        assertEquals(LocalDateTime.of(2026, 8, 15, 0, 0), captor.getValue().getTransactionDate());
    }

    @Test
    void import_skipsUnresolvableAccount() {
        givenDict();
        String csv = HEADER + "\n2026-08-15 10:00:00,expense,100,,未知账户,,餐饮,,\n";

        CsvImportResult r = service.importTransactionsCsv(csv, true);

        assertEquals(1, r.total());
        assertEquals(0, r.imported());
        assertEquals(1, r.skipped());
        verify(transactionService, never()).insert(any());
    }

    @Test
    void import_skipsInvalidAmount() {
        givenDict();
        String csv = HEADER + "\n2026-08-15 10:00:00,expense,abc,,现金,,餐饮,,\n";

        CsvImportResult r = service.importTransactionsCsv(csv, true);

        assertEquals(0, r.imported());
        assertEquals(1, r.skipped());
        verify(transactionService, never()).insert(any());
    }

    @Test
    void import_skipsDuplicateAgainstExisting() {
        givenDict();
        // 库中已存在一条同指纹流水（日期+类型+金额+账户+分类）
        TransactionDTO existing = new TransactionDTO();
        existing.setType(TransactionType.EXPENSE);
        existing.setAmount(new BigDecimal("100"));
        existing.setAccountId(1);
        existing.setCategoryId(10);
        existing.setTransactionDate(LocalDateTime.of(2026, 8, 15, 10, 0));
        when(transactionService.selectAll()).thenReturn(List.of(existing));
        String csv = HEADER + "\n2026-08-15 10:00:00,expense,100,,现金,,餐饮,,\n";

        CsvImportResult r = service.importTransactionsCsv(csv, true);

        assertEquals(1, r.total());
        assertEquals(0, r.imported());
        assertEquals(1, r.duplicates());
        verify(transactionService, never()).insert(any());
    }

    @Test
    void import_skipDuplicatesFalse_insertsDuplicate() {
        givenDict();
        TransactionDTO existing = new TransactionDTO();
        existing.setType(TransactionType.EXPENSE);
        existing.setAmount(new BigDecimal("100"));
        existing.setAccountId(1);
        existing.setCategoryId(10);
        existing.setTransactionDate(LocalDateTime.of(2026, 8, 15, 10, 0));
        when(transactionService.selectAll()).thenReturn(List.of(existing));
        when(transactionService.insert(any())).thenReturn(true);
        String csv = HEADER + "\n2026-08-15 10:00:00,expense,100,,现金,,餐饮,,\n";

        CsvImportResult r = service.importTransactionsCsv(csv, false);

        assertEquals(1, r.imported());
        assertEquals(0, r.duplicates());
    }

    @Test
    void preview_marksValidInvalidDuplicate() {
        givenDict();
        String csv = HEADER
                + "\n2026-08-15 10:00:00,expense,100,,现金,,餐饮,午餐,"   // valid
                + "\n2026-08-15 10:00:00,expense,abc,,现金,,餐饮,,"      // invalid（金额非法）
                + "\n2026-08-15 10:00:00,expense,100,,现金,,餐饮,午餐,";  // duplicate（文件内重复）

        CsvPreviewResult p = service.previewTransactionsCsv(csv);

        assertEquals(3, p.total());
        assertEquals(1, p.validCount());
        assertEquals(1, p.invalidCount());
        assertEquals(1, p.duplicateCount());
        assertEquals(3, p.rows().size());
        assertEquals("valid", p.rows().get(0).status());
        assertEquals("invalid", p.rows().get(1).status());
        assertEquals("duplicate", p.rows().get(2).status());
    }

    @Test
    void export_transactions_containsBomHeaderAndNames() {
        givenDict();
        TransactionDTO tx = new TransactionDTO();
        tx.setType(TransactionType.EXPENSE);
        tx.setAmount(new BigDecimal("100"));
        tx.setAccountId(1);
        tx.setCategoryId(10);
        tx.setTransactionDate(LocalDateTime.of(2026, 8, 15, 10, 0));
        tx.setNote("午餐");
        when(transactionService.selectAll()).thenReturn(List.of(tx));

        String csv = service.exportTransactionsCsv();

        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("日期,类型,金额"));
        assertTrue(csv.contains("现金"));
        assertTrue(csv.contains("餐饮"));
        assertTrue(csv.contains("expense"));
        assertTrue(csv.contains("2026-08-15 10:00:00"));
    }

    @Test
    void export_emptyData_onlyHeader() {
        when(transactionService.selectAll()).thenReturn(List.of());
        when(accountService.selectAll()).thenReturn(List.of());
        when(categoryService.selectAll()).thenReturn(List.of());

        String csv = service.exportTransactionsCsv();

        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("日期,类型,金额"));
    }
}
