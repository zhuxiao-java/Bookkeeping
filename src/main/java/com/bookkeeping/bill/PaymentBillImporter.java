package com.bookkeeping.bill;

import com.bookkeeping.bill.PaymentBillParser.BillDocument;
import com.bookkeeping.bill.PaymentBillParser.BillEntry;
import com.bookkeeping.constant.AccountType;
import com.bookkeeping.constant.Archived;
import com.bookkeeping.constant.CategoryType;
import com.bookkeeping.constant.Currency;
import com.bookkeeping.constant.TransactionType;
import com.bookkeeping.dao.dto.AccountDTO;
import com.bookkeeping.dao.dto.CategoryDTO;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.service.AccountService;
import com.bookkeeping.service.BackupService.CsvFailure;
import com.bookkeeping.service.BackupService.CsvImportResult;
import com.bookkeeping.service.BackupService.CsvPreviewResult;
import com.bookkeeping.service.BackupService.CsvPreviewRow;
import com.bookkeeping.service.CategoryService;
import com.bookkeeping.service.TransactionService;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把解析后的微信 / 支付宝账单变成预览行或流水。
 * 预览只计算「将新建账户 / 分类」，确认导入时才落库。交易单号已存在的行不再写入。
 */
public final class PaymentBillImporter {

    private static final int MAX_PREVIEW_ROWS = 500;
    private static final int MAX_FAILURES = 500;

    private final AccountService accountService;
    private final CategoryService categoryService;
    private final TransactionService transactionService;

    public PaymentBillImporter(AccountService accountService, CategoryService categoryService,
                               TransactionService transactionService) {
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.transactionService = transactionService;
    }

    public CsvPreviewResult preview(BillDocument document) {
        List<Prepared> rows = prepare(document, false);
        List<CsvPreviewRow> previewRows = new ArrayList<>();
        int valid = 0;
        int invalid = 0;
        int duplicate = 0;
        int ignored = 0;
        for (Prepared row : rows) {
            switch (row.status) {
                case "valid" -> valid++;
                case "invalid" -> invalid++;
                case "duplicate" -> duplicate++;
                default -> ignored++;
            }
            if (previewRows.size() < MAX_PREVIEW_ROWS) {
                previewRows.add(row.toPreview());
            }
        }
        return new CsvPreviewResult(rows.size(), valid, invalid, duplicate, ignored, previewRows);
    }

    public CsvImportResult importRows(BillDocument document) {
        List<Prepared> rows = prepare(document, true);
        List<CsvFailure> failures = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int duplicates = 0;
        for (Prepared row : rows) {
            if ("duplicate".equals(row.status)) {
                duplicates++;
                addFailure(failures, row.line, row.reason);
                continue;
            }
            if (!"valid".equals(row.status) || row.dto == null) {
                skipped++;
                addFailure(failures, row.line, row.reason);
                continue;
            }
            try {
                if (transactionService.insert(row.dto)) {
                    imported++;
                } else {
                    skipped++;
                    addFailure(failures, row.line, "插入失败");
                }
            } catch (RuntimeException e) {
                skipped++;
                addFailure(failures, row.line, "插入异常：" + e.getMessage());
            }
        }
        return new CsvImportResult(rows.size(), imported, skipped, duplicates, failures);
    }

    private List<Prepared> prepare(BillDocument document, boolean createMissing) {
        Map<String, Integer> accountIds = loadAccountIds();
        Map<String, CategoryDTO> categories = loadCategories();
        Set<String> existingKeys = loadSourceKeys();
        Set<String> seen = new HashSet<>();
        List<Prepared> rows = new ArrayList<>();
        for (BillEntry entry : document.entries()) {
            if (!"data".equals(entry.kind())) {
                rows.add(Prepared.from(entry, entry.kind(), entry.reason(), entry.categoryHint(), null));
                continue;
            }
            AccountRef from = resolveAccount(entry.accountName(), entry.accountType(), accountIds);
            AccountRef to = StringUtils.isBlank(entry.toAccountName())
                    ? null
                    : resolveAccount(entry.toAccountName(), entry.toAccountType(), accountIds);
            boolean transfer = entry.type() == TransactionType.TRANSFER;
            CategoryType want = entry.type() == TransactionType.INCOME ? CategoryType.INCOME : CategoryType.EXPENSE;
            CategoryAliases.Match category = transfer
                    ? null
                    : CategoryAliases.resolve(entry.categoryHint(), want, categories);
            String categoryName = category == null ? "" : category.name();
            String key = document.source() + "|" + entry.sourceId();
            if (existingKeys.contains(key) || seen.contains(key)) {
                String reason = existingKeys.contains(key) ? "交易单号已导入" : "文件内交易单号重复";
                rows.add(new Prepared(entry, "duplicate", reason, from.name, to == null ? "" : to.name, categoryName, null));
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (from.id == null) {
                reasons.add("将新建账户「" + from.name + "」");
            }
            if (to != null && to.id == null) {
                reasons.add("将新建账户「" + to.name + "」");
            }
            if (category != null && !categories.containsKey(category.name())) {
                reasons.add("将新建分类「" + category.name() + "」");
            } else if (category != null && category.fallback()) {
                reasons.add("未匹配到分类，记入「" + category.name() + "」");
            }
            if (!createMissing) {
                seen.add(key);
                rows.add(new Prepared(entry, "valid", String.join("；", reasons), from.name,
                        to == null ? "" : to.name, categoryName, null));
                continue;
            }
            Integer accountId = from.id != null ? from.id : ensureAccount(from.name, from.type, accountIds);
            Integer toAccountId = null;
            if (to != null) {
                toAccountId = to.id != null ? to.id : ensureAccount(to.name, to.type, accountIds);
            }
            if (accountId == null || (to != null && toAccountId == null)) {
                String missing = accountId == null ? from.name : to.name;
                rows.add(Prepared.from(entry, "invalid", "账户「" + missing + "」创建失败", categoryName, null));
                continue;
            }
            Integer categoryId = null;
            if (!transfer) {
                categoryId = categoryId(categories, category.name());
                if (categoryId == null) {
                    categoryId = ensureCategory(category.name(), want, categories);
                }
                if (categoryId == null) {
                    rows.add(Prepared.from(entry, "invalid", "分类「" + category.name() + "」创建失败", categoryName, null));
                    continue;
                }
            }
            TransactionDTO dto = new TransactionDTO();
            dto.setTransactionDate(entry.date());
            dto.setType(entry.type());
            dto.setAmount(entry.amount());
            dto.setFee(BigDecimal.ZERO);
            dto.setAccountId(accountId);
            dto.setToAccountId(toAccountId);
            dto.setCategoryId(categoryId);
            dto.setNote(StringUtils.trimToNull(entry.note()));
            dto.setSource(document.source());
            dto.setSourceId(entry.sourceId());
            seen.add(key);
            rows.add(new Prepared(entry, "valid", String.join("；", reasons), from.name,
                    to == null ? "" : to.name, categoryName, dto));
        }
        return rows;
    }

    private Integer categoryId(Map<String, CategoryDTO> categories, String name) {
        CategoryDTO category = categories.get(name);
        return category == null ? null : category.getId();
    }

    private Integer ensureAccount(String name, AccountType type, Map<String, Integer> accountIds) {
        Integer existing = accountIds.get(name);
        if (existing != null) {
            return existing;
        }
        AccountDTO dto = new AccountDTO();
        dto.setName(name);
        dto.setType(type);
        dto.setInitialBalance(BigDecimal.ZERO);
        dto.setCurrentBalance(BigDecimal.ZERO);
        dto.setCurrency(Currency.CNY);
        dto.setArchived(Archived.NORMAL);
        try {
            accountService.insert(dto);
        } catch (RuntimeException ignored) {
            // 名称已存在时下面重新读取
        }
        accountIds.clear();
        accountIds.putAll(loadAccountIds());
        return accountIds.get(name);
    }

    private Integer ensureCategory(String name, CategoryType type, Map<String, CategoryDTO> categories) {
        Integer existing = categoryId(categories, name);
        if (existing != null) {
            return existing;
        }
        CategoryDTO dto = new CategoryDTO();
        dto.setName(name);
        dto.setType(type);
        dto.setIcon("other");
        dto.setColor(type == CategoryType.INCOME ? "#67C23A" : "#909399");
        dto.setSortOrder(99);
        dto.setArchived(Archived.NORMAL);
        try {
            categoryService.insert(dto);
        } catch (RuntimeException ignored) {
            // 名称已存在时下面重新读取
        }
        categories.clear();
        categories.putAll(loadCategories());
        return categoryId(categories, name);
    }

    private AccountRef resolveAccount(String canonical, AccountType type, Map<String, Integer> accountIds) {
        if (accountIds.containsKey(canonical)) {
            return new AccountRef(canonical, type, accountIds.get(canonical));
        }
        if ("微信零钱".equals(canonical) && accountIds.containsKey("零钱")) {
            return new AccountRef("零钱", type, accountIds.get("零钱"));
        }
        if ("支付宝".equals(canonical)) {
            for (String alt : List.of("余额", "账户余额", "支付宝余额")) {
                if (accountIds.containsKey(alt)) {
                    return new AccountRef(alt, type, accountIds.get(alt));
                }
            }
        }
        return new AccountRef(canonical, type, null);
    }

    private Map<String, Integer> loadAccountIds() {
        Map<String, Integer> map = new HashMap<>();
        List<AccountDTO> accounts = accountService.selectAll();
        if (accounts == null) {
            return map;
        }
        for (AccountDTO account : accounts) {
            if (StringUtils.isNotBlank(account.getName()) && account.getId() != null) {
                map.put(account.getName(), account.getId());
            }
        }
        return map;
    }

    private Map<String, CategoryDTO> loadCategories() {
        Map<String, CategoryDTO> map = new HashMap<>();
        List<CategoryDTO> categories = categoryService.selectAll();
        if (categories == null) {
            return map;
        }
        for (CategoryDTO category : categories) {
            if (StringUtils.isNotBlank(category.getName())) {
                map.put(category.getName(), category);
            }
        }
        return map;
    }

    private Set<String> loadSourceKeys() {
        Set<String> keys = new HashSet<>();
        List<TransactionDTO> transactions = transactionService.selectAll();
        if (transactions == null) {
            return keys;
        }
        for (TransactionDTO tx : transactions) {
            if (StringUtils.isNotBlank(tx.getSource()) && StringUtils.isNotBlank(tx.getSourceId())) {
                keys.add(tx.getSource() + "|" + tx.getSourceId());
            }
        }
        return keys;
    }

    private static void addFailure(List<CsvFailure> failures, int line, String reason) {
        if (failures.size() < MAX_FAILURES) {
            failures.add(new CsvFailure(line, reason));
        }
    }

    private record AccountRef(String name, AccountType type, Integer id) {
    }

    private static final class Prepared {
        private final int line;
        private final String status;
        private final String reason;
        private final String dateText;
        private final String typeText;
        private final String amountText;
        private final String accountName;
        private final String toAccountName;
        private final String categoryName;
        private final String note;
        private final TransactionDTO dto;

        private Prepared(BillEntry entry, String status, String reason, String accountName, String toAccountName,
                         String categoryName, TransactionDTO dto) {
            this.line = entry.line();
            this.status = status;
            this.reason = reason == null ? "" : reason;
            this.dateText = entry.dateText();
            this.typeText = entry.typeText();
            this.amountText = entry.amountText();
            this.accountName = accountName;
            this.toAccountName = toAccountName;
            this.categoryName = categoryName;
            this.note = entry.note();
            this.dto = dto;
        }

        private static Prepared from(BillEntry entry, String status, String reason, String categoryName,
                                     TransactionDTO dto) {
            return new Prepared(entry, status, reason, entry.accountName(), entry.toAccountName(), categoryName, dto);
        }

        private CsvPreviewRow toPreview() {
            return new CsvPreviewRow(line, dateText, typeText, amountText, accountName, toAccountName, categoryName,
                    note, status, reason);
        }
    }
}
