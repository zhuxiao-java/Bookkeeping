package com.bookkeeping.service.impl;

import com.bookkeeping.bill.AccountMaps;
import com.bookkeeping.constant.AccountType;
import com.bookkeeping.exception.BusinessException;
import com.bookkeeping.constant.CategoryType;
import com.bookkeeping.constant.TransactionType;
import com.bookkeeping.controller.BackupController;
import com.bookkeeping.dao.dto.AccountDTO;
import com.bookkeeping.dao.dto.CategoryDTO;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.service.AccountService;
import com.bookkeeping.service.BackupService.CsvImportResult;
import com.bookkeeping.service.BackupService.CsvPreviewResult;
import com.bookkeeping.service.BackupService.CsvPreviewRow;
import com.bookkeeping.service.CategoryService;
import com.bookkeeping.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private final List<AccountDTO> billAccounts = new ArrayList<>();
    private final List<CategoryDTO> billCategories = new ArrayList<>();
    private final List<TransactionDTO> savedTx = new ArrayList<>();
    private int nextAccountId = 100;
    private int nextCategoryId = 200;

    private void givenBillDict() {
        billAccounts.add(account(1, "现金"));
        CategoryDTO food = category(10, "餐饮");
        food.setType(CategoryType.EXPENSE);
        billCategories.add(food);
        when(accountService.selectAll()).thenAnswer(invocation -> List.copyOf(billAccounts));
        when(categoryService.selectAll()).thenAnswer(invocation -> List.copyOf(billCategories));
        when(transactionService.selectAll()).thenAnswer(invocation -> List.copyOf(savedTx));
        when(accountService.insert(any())).thenAnswer(invocation -> {
            AccountDTO incoming = invocation.getArgument(0);
            AccountDTO saved = account(nextAccountId++, incoming.getName());
            saved.setType(incoming.getType());
            billAccounts.add(saved);
            return true;
        });
        when(categoryService.insert(any())).thenAnswer(invocation -> {
            CategoryDTO incoming = invocation.getArgument(0);
            CategoryDTO saved = category(nextCategoryId++, incoming.getName());
            saved.setType(incoming.getType());
            billCategories.add(saved);
            return true;
        });
        when(transactionService.insert(any())).thenAnswer(invocation -> {
            savedTx.add(invocation.getArgument(0));
            return true;
        });
    }

    private static final String WECHAT_BILL = ""
            + "微信支付账单明细\n"
            + "微信昵称：[测试]\n"
            + "----------------------微信支付账单明细列表--------------------\n"
            + "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n"
            + "2024-03-01 12:30:00,商户消费,美团,外卖,支出,¥25.50,零钱,支付成功,4200000001,m1,午饭\n"
            + "2024-03-02 08:00:00,商户消费,已关闭商户,关闭单,支出,¥9.00,零钱,交易关闭,4200000002,m2,\n"
            + "2024-03-03 09:00:00,商户消费,退款商户,已退,支出,¥8.00,零钱,已全额退款,4200000003,m3,\n"
            + "2024-03-04 10:00:00,餐饮美食,星巴克,咖啡,支出,¥30.00,招商银行信用卡(1234),支付成功,4200000004,m4,\n"
            + "2024-03-05 11:00:00,信用卡还款,银行,还款,不计收支,¥100.00,零钱,支付成功,4200000005,m5,\n"
            + "2024-03-06 12:00:00,零钱提现,招商银行,提现,不计收支,¥200.00,招商银行储蓄卡(5678),支付成功,4200000006,m6,\n";

    @Test
    void preview_wechatBillDoesNotCreateAccountOrCategory() {
        givenBillDict();

        CsvPreviewResult preview = service.previewTransactions(WECHAT_BILL.getBytes(StandardCharsets.UTF_8), "微信支付账单.csv");

        assertEquals(6, preview.total());
        assertEquals(3, preview.validCount());
        assertEquals(0, preview.invalidCount());
        assertEquals(0, preview.duplicateCount());
        assertEquals(3, preview.ignoredCount());
        CsvPreviewRow meal = rowWithAmount(preview, "25.50");
        assertEquals("valid", meal.status());
        assertEquals("微信零钱", meal.accountName());
        assertEquals("待整理", meal.categoryName());
        assertTrue(meal.reason().contains("将新建账户「微信零钱」"));
        assertTrue(meal.reason().contains("将新建分类「待整理」"));
        assertTrue(meal.note().contains("美团"));
        assertTrue(meal.line() > 1);
        assertEquals("ignored", rowWithAmount(preview, "9.00").status());
        assertTrue(rowWithAmount(preview, "9.00").reason().contains("交易关闭"));
        assertTrue(rowWithAmount(preview, "8.00").reason().contains("已全额退款"));
        assertTrue(rowWithAmount(preview, "100.00").reason().contains("信用卡还款"));
        CsvPreviewRow coffee = rowWithAmount(preview, "30.00");
        assertEquals("valid", coffee.status());
        assertEquals("餐饮", coffee.categoryName());
        assertEquals("招商银行信用卡(1234)", coffee.accountName());
        assertFalse(coffee.reason().contains("待整理"));
        CsvPreviewRow withdraw = rowWithAmount(preview, "200.00");
        assertEquals("转账", withdraw.type());
        assertEquals("微信零钱", withdraw.accountName());
        assertEquals("招商银行储蓄卡(5678)", withdraw.toAccountName());
        assertEquals(List.of("微信零钱", "招商银行信用卡(1234)", "招商银行储蓄卡(5678)"), preview.accountNames());
        verify(accountService, never()).insert(any());
        verify(categoryService, never()).insert(any());
    }

    @Test
    void import_wechatBillCreatesAccountAndDedupsByOrderId() {
        givenBillDict();

        CsvImportResult first = service.importTransactions(WECHAT_BILL.getBytes(StandardCharsets.UTF_8), "微信支付账单.csv", true);

        assertEquals(6, first.total());
        assertEquals(3, first.imported());
        assertEquals(3, first.skipped());
        assertEquals(0, first.duplicates());
        ArgumentCaptor<AccountDTO> accounts = ArgumentCaptor.forClass(AccountDTO.class);
        verify(accountService, times(3)).insert(accounts.capture());
        assertEquals(AccountType.WECHAT_PAY, accountType(accounts, "微信零钱"));
        assertEquals(AccountType.BANK, accountType(accounts, "招商银行信用卡(1234)"));
        assertEquals(AccountType.BANK, accountType(accounts, "招商银行储蓄卡(5678)"));
        ArgumentCaptor<CategoryDTO> categories = ArgumentCaptor.forClass(CategoryDTO.class);
        verify(categoryService).insert(categories.capture());
        assertEquals("待整理", categories.getValue().getName());
        assertEquals(CategoryType.EXPENSE, categories.getValue().getType());

        TransactionDTO meal = tx(savedTx, "4200000001");
        assertEquals(TransactionType.EXPENSE, meal.getType());
        assertEquals(0, new BigDecimal("25.50").compareTo(meal.getAmount()));
        assertEquals(idOf("微信零钱"), meal.getAccountId());
        assertEquals(categoryIdOf("待整理"), meal.getCategoryId());
        assertEquals("wechat", meal.getSource());
        assertEquals("4200000001", meal.getSourceId());
        assertTrue(meal.getNote().contains("美团"));
        assertTrue(meal.getNote().contains("外卖"));
        assertFalse(meal.getNote().contains("4200000001"));
        TransactionDTO coffee = tx(savedTx, "4200000004");
        assertEquals(10, coffee.getCategoryId());
        assertEquals(idOf("招商银行信用卡(1234)"), coffee.getAccountId());
        TransactionDTO withdraw = tx(savedTx, "4200000006");
        assertEquals(TransactionType.TRANSFER, withdraw.getType());
        assertNull(withdraw.getCategoryId());
        assertEquals(idOf("微信零钱"), withdraw.getAccountId());
        assertEquals(idOf("招商银行储蓄卡(5678)"), withdraw.getToAccountId());

        CsvImportResult second = service.importTransactions(WECHAT_BILL.getBytes(StandardCharsets.UTF_8), "微信支付账单.csv", false);

        assertEquals(0, second.imported());
        assertEquals(3, second.duplicates());
        assertEquals(3, second.skipped());
        verify(transactionService, times(3)).insert(any());
    }

    @Test
    void import_alipayGbkUsesExistingCategory() {
        givenBillDict();
        String text = ""
                + "支付宝交易记录明细查询\n"
                + "账号:[2088]\n"
                + "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注\n"
                + "2024-04-01 09:00:00,餐饮美食,商家,2088,午饭,支出,18.00,余额,交易成功,202404010001,b1,\n";
        byte[] gbk = text.getBytes(Charset.forName("GBK"));

        CsvImportResult result = service.importTransactions(gbk, "alipay_record.csv", true);

        assertEquals(1, result.imported());
        TransactionDTO tx = savedTx.get(0);
        assertEquals(10, tx.getCategoryId());
        assertEquals("alipay", tx.getSource());
        assertEquals("202404010001", tx.getSourceId());
        assertEquals(idOf("支付宝"), tx.getAccountId());
        assertEquals(AccountType.ALI_PAY, billAccounts.stream()
                .filter(account -> "支付宝".equals(account.getName()))
                .findFirst().orElseThrow().getType());
        verify(categoryService, never()).insert(any());
    }

    @Test
    void import_wechatXlsx() throws Exception {
        givenBillDict();

        CsvImportResult result = service.importTransactions(wechatXlsx(), "微信支付账单流水文件.xlsx", true);

        assertEquals(1, result.imported());
        TransactionDTO tx = savedTx.get(0);
        assertEquals("wechat", tx.getSource());
        assertEquals("4200000099", tx.getSourceId());
        assertEquals(idOf("微信零钱"), tx.getAccountId());
        assertEquals(LocalDateTime.of(2024, 5, 1, 8, 0), tx.getTransactionDate());
    }

    @Test
    void preview_duplicateOrderIdInsideFile() {
        givenBillDict();
        String csv = ""
                + "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n"
                + "2024-03-01 12:30:00,商户消费,美团,外卖,支出,25.50,零钱,支付成功,4200000001,m1,\n"
                + "2024-03-01 12:31:00,商户消费,美团,外卖,支出,25.50,零钱,支付成功,4200000001,m1,\n";

        CsvPreviewResult preview = service.previewTransactions(csv.getBytes(StandardCharsets.UTF_8), "微信支付账单.csv");

        assertEquals(1, preview.validCount());
        assertEquals(1, preview.duplicateCount());
        assertEquals("文件内交易单号重复", preview.rows().get(1).reason());
    }

    @Test
    void import_appCsvBytesDoesNotCreateMissingAccount() {
        givenDict();
        String csv = HEADER + "\n2026-08-15 10:00:00,expense,100,,未知账户,,餐饮,,\n";

        CsvImportResult result = service.importTransactions(csv.getBytes(StandardCharsets.UTF_8), "transactions.csv", true);

        assertEquals(0, result.imported());
        assertEquals(1, result.skipped());
        verify(accountService, never()).insert(any());
        verify(categoryService, never()).insert(any());
    }

    @Test
    void preview_appCsvBytesKeepsFingerprintBehavior() {
        givenDict();
        String csv = HEADER + "\n2026-08-15 10:00:00,expense,100,,现金,,餐饮,午餐,\n";

        CsvPreviewResult preview = service.previewTransactions(csv.getBytes(StandardCharsets.UTF_8), "transactions.csv");

        assertEquals(1, preview.validCount());
        assertEquals(0, preview.ignoredCount());
        assertEquals("valid", preview.rows().get(0).status());
        assertEquals("现金", preview.rows().get(0).accountName());
        assertEquals(List.of("现金"), preview.accountNames());
    }

    @Test
    void preview_wechatBillUsesChosenAccount() {
        givenBillDict();

        CsvPreviewResult preview = service.previewTransactions(
                WECHAT_BILL.getBytes(StandardCharsets.UTF_8), "微信支付账单.csv", Map.of("微信零钱", 1));

        CsvPreviewRow meal = rowWithAmount(preview, "25.50");
        assertEquals("现金", meal.accountName());
        assertEquals("valid", meal.status());
        assertFalse(meal.reason().contains("将新建账户「微信零钱」"));
        assertEquals("招商银行信用卡(1234)", rowWithAmount(preview, "30.00").accountName());
        assertEquals(List.of("微信零钱", "招商银行信用卡(1234)", "招商银行储蓄卡(5678)"), preview.accountNames());
        verify(accountService, never()).insert(any());
    }

    @Test
    void import_wechatBillUsesChosenAccount() {
        givenBillDict();
        Map<String, Integer> choices = new LinkedHashMap<>();
        choices.put("微信零钱", 1);
        choices.put("招商银行信用卡(1234)", 0);
        choices.put("招商银行储蓄卡(5678)", 0);

        CsvImportResult result = service.importTransactions(
                WECHAT_BILL.getBytes(StandardCharsets.UTF_8), "微信支付账单.csv", true, choices);

        assertEquals(3, result.imported());
        assertEquals(1, tx(savedTx, "4200000001").getAccountId());
        assertEquals(idOf("招商银行信用卡(1234)"), tx(savedTx, "4200000004").getAccountId());
        TransactionDTO withdraw = tx(savedTx, "4200000006");
        assertEquals(1, withdraw.getAccountId());
        assertEquals(idOf("招商银行储蓄卡(5678)"), withdraw.getToAccountId());
        verify(accountService, times(2)).insert(any());
        assertFalse(billAccounts.stream().anyMatch(account -> "微信零钱".equals(account.getName())));
    }

    @Test
    void import_appCsvUsesChosenAccountInsteadOfName() {
        givenDict();
        when(transactionService.selectAll()).thenReturn(List.of());
        when(transactionService.insert(any())).thenReturn(true);
        String csv = HEADER + "\n2026-08-15 10:00:00,expense,100,,未知账户,,餐饮,午餐,\n";

        CsvImportResult result = service.importTransactions(
                csv.getBytes(StandardCharsets.UTF_8), "transactions.csv", true, Map.of("未知账户", 1));

        assertEquals(1, result.imported());
        ArgumentCaptor<TransactionDTO> captor = ArgumentCaptor.forClass(TransactionDTO.class);
        verify(transactionService).insert(captor.capture());
        assertEquals(1, captor.getValue().getAccountId());
        verify(accountService, never()).insert(any());
    }

    @Test
    void accountMap_parsesChosenIds() {
        assertEquals(1, AccountMaps.parse("{\"现金\":1}").get("现金"));
        assertEquals(-1, AccountMaps.parse("{\"现金\":-1}").get("现金"));
        assertTrue(AccountMaps.parse("  ").isEmpty());
        assertThrows(BusinessException.class, () -> AccountMaps.parse("[]"));
    }

    private Integer idOf(String name) {
        return billAccounts.stream().filter(account -> name.equals(account.getName())).findFirst().orElseThrow().getId();
    }

    private Integer categoryIdOf(String name) {
        return billCategories.stream().filter(category -> name.equals(category.getName())).findFirst().orElseThrow().getId();
    }

    private static AccountType accountType(ArgumentCaptor<AccountDTO> accounts, String name) {
        return accounts.getAllValues().stream().filter(account -> name.equals(account.getName())).findFirst().orElseThrow().getType();
    }

    private static TransactionDTO tx(List<TransactionDTO> rows, String sourceId) {
        return rows.stream().filter(row -> sourceId.equals(row.getSourceId())).findFirst().orElseThrow();
    }

    private static CsvPreviewRow rowWithAmount(CsvPreviewResult preview, String amount) {
        return preview.rows().stream().filter(row -> amount.equals(row.amount())).findFirst().orElseThrow();
    }

    private static byte[] wechatXlsx() throws Exception {
        String sheet = """
                <worksheet><sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>微信支付账单明细</t></is></c></row>
                <row r="3">
                <c r="A3" t="inlineStr"><is><t>交易时间</t></is></c>
                <c r="B3" t="inlineStr"><is><t>交易类型</t></is></c>
                <c r="C3" t="inlineStr"><is><t>交易对方</t></is></c>
                <c r="D3" t="inlineStr"><is><t>商品</t></is></c>
                <c r="E3" t="inlineStr"><is><t>收/支</t></is></c>
                <c r="F3" t="inlineStr"><is><t>金额(元)</t></is></c>
                <c r="G3" t="inlineStr"><is><t>支付方式</t></is></c>
                <c r="H3" t="inlineStr"><is><t>当前状态</t></is></c>
                <c r="I3" t="inlineStr"><is><t>交易单号</t></is></c>
                <c r="J3" t="inlineStr"><is><t>商户单号</t></is></c>
                <c r="K3" t="inlineStr"><is><t>备注</t></is></c>
                </row>
                <row r="4">
                <c r="A4" t="inlineStr"><is><t>2024-05-01 08:00:00</t></is></c>
                <c r="B4" t="inlineStr"><is><t>商户消费</t></is></c>
                <c r="C4" t="inlineStr"><is><t>咖啡店</t></is></c>
                <c r="D4" t="inlineStr"><is><t>美式</t></is></c>
                <c r="E4" t="inlineStr"><is><t>支出</t></is></c>
                <c r="F4" t="inlineStr"><is><t>18.00</t></is></c>
                <c r="G4" t="inlineStr"><is><t>零钱</t></is></c>
                <c r="H4" t="inlineStr"><is><t>支付成功</t></is></c>
                <c r="I4" t="inlineStr"><is><t>4200000099</t></is></c>
                </row>
                </sheetData></worksheet>
                """;
        return zipXlsx(sheet, null);
    }

    private static byte[] zipXlsx(String sheetXml, String sharedXml) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheetXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (sharedXml != null) {
                zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
                zip.write(sharedXml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
