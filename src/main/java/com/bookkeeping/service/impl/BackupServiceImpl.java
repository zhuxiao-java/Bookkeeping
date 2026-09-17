package com.bookkeeping.service.impl;

import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.constant.TransactionType;
import com.bookkeeping.dao.dto.AccountDTO;
import com.bookkeeping.dao.dto.CategoryDTO;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.exception.BusinessException;
import com.bookkeeping.service.AccountService;
import com.bookkeeping.service.BackupService;
import com.bookkeeping.service.CategoryService;
import com.bookkeeping.service.TransactionService;
import com.bookkeeping.util.CsvUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 备份 / 恢复 / CSV 导入导出实现（GAP-09）。
 *
 * @author zhuxiao
 */
@Service
@Slf4j
public class BackupServiceImpl implements BackupService {

    /** SQLite 文件头前 16 字节固定为 "SQLite format 3\0"，用于校验上传的备份文件 */
    private static final byte[] SQLITE_HEADER = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 备份文件名时间戳（.bak 与自动备份共用） */
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 恢复前完整性校验要求存在的关键表，缺失即判定为非本应用备份 */
    private static final Set<String> REQUIRED_TABLES = Set.of("t_account", "t_transaction", "t_category");

    /** 流水 CSV 表头（与导入解析顺序严格一致） */
    private static final List<String> TX_HEADERS =
            List.of("日期", "类型", "金额", "手续费", "账户", "转入账户", "分类", "备注", "标签");
    /** 账户 CSV 表头（仅导出，不做导入） */
    private static final List<String> ACCOUNT_HEADERS =
            List.of("名称", "类型", "初始余额", "当前余额", "币种", "归档");

    /** CSV 预览最多返回的明细行数（NEW-11） */
    private static final int MAX_PREVIEW_ROWS = 500;
    /** 导入失败明细最多保留条数（NEW-11） */
    private static final int MAX_FAILURES = 500;

    @Resource
    private DataSource dataSource;
    @Resource
    private TransactionService transactionService;
    @Resource
    private AccountService accountService;
    @Resource
    private CategoryService categoryService;

    @Value("${bookkeeping.dir}")
    private String bookkeepingDir;

    /** 自动备份滚动保留份数（data/backups 目录下 auto-*.db 最多保留几份） */
    @Value("${bookkeeping.auto-backup.max-keep:5}")
    private int autoBackupMaxKeep;

    // ---------------------------------------------------------------- 备份 / 恢复

    @Override
    public byte[] createSnapshot() {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("bookkeeping-backup-", ".db");
            // VACUUM INTO 要求目标文件不存在，先删除占位空文件
            Files.deleteIfExists(tmp);
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("VACUUM INTO '" + escapeSqlPath(tmp.toAbsolutePath().toString()) + "'");
            }
            return Files.readAllBytes(tmp);
        } catch (SQLException | IOException e) {
            throw new BusinessException(BookkeepingResp.BACKUP_SNAPSHOT_FAIL);
        } finally {
            deleteQuietly(tmp);
        }
    }

    @Override
    public void restore(byte[] dbBytes) {
        if (!isSqlite(dbBytes)) {
            throw new BusinessException(BookkeepingResp.RESTORE_FILE_INVALID);
        }
        Path dbPath = Path.of(bookkeepingDir, "accounts.db").toAbsolutePath().normalize();
        Path staging = dbPath.resolveSibling(dbPath.getFileName() + ".restore");
        try {
            Files.write(staging, dbBytes);
            // 覆盖前用独立连接校验备份完整性与关键表结构：杜绝用损坏/异类库覆盖现库（NEW-03）
            validateBackupDb(staging);
            // 安全兜底：校验通过后把现库另存为带时间戳的 .bak（多次恢复不互相覆盖），出错可回退
            if (Files.exists(dbPath)) {
                Path bak = dbPath.resolveSibling(
                        dbPath.getFileName() + "." + BACKUP_STAMP.format(LocalDateTime.now()) + ".bak");
                Files.copy(dbPath, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            // rename 覆盖：POSIX 下活动连接仍指向旧 inode，故必须重启后端才能加载新库
            Files.move(staging, dbPath, StandardCopyOption.REPLACE_EXISTING);
            // 清理属于旧库的 sidecar 文件，避免新库配旧日志
            deleteQuietly(dbPath.resolveSibling(dbPath.getFileName() + "-journal"));
            deleteQuietly(dbPath.resolveSibling(dbPath.getFileName() + "-wal"));
            deleteQuietly(dbPath.resolveSibling(dbPath.getFileName() + "-shm"));
        } catch (IOException e) {
            throw new BusinessException(BookkeepingResp.RESTORE_FAIL);
        } finally {
            // 校验失败/异常时清理暂存文件（成功 move 后已不存在，deleteQuietly 幂等）
            deleteQuietly(staging);
        }
    }

    /**
     * 用独立 JDBC 连接校验暂存备份：PRAGMA integrity_check 必须为 ok，且关键表齐全。
     * 不复用 dataSource（其指向活动库），确保校验的是待恢复文件本身（NEW-03）。
     */
    private void validateBackupDb(Path staging) {
        String url = "jdbc:sqlite:" + staging.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                    throw new BusinessException(BookkeepingResp.RESTORE_FILE_INVALID);
                }
            }
            Set<String> tables = new HashSet<>();
            try (ResultSet rs = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            if (!tables.containsAll(REQUIRED_TABLES)) {
                throw new BusinessException(BookkeepingResp.RESTORE_FILE_INVALID);
            }
        } catch (SQLException e) {
            throw new BusinessException(BookkeepingResp.RESTORE_FILE_INVALID);
        }
    }

    /** 启动即做一次自动备份：首次安装/异常退出后保留一份可回退快照（NEW-03 / DT-02） */
    @PostConstruct
    public void backupOnStartup() {
        autoBackup();
    }

    /** 每日 03:00 自动备份，滚动保留最近 N 份（NEW-03 / DT-02） */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledAutoBackup() {
        autoBackup();
    }

    /** 生成一份自动备份到 data/backups/auto-{时间戳}.db，并按保留份数滚动清理；失败不影响主流程 */
    private void autoBackup() {
        try {
            byte[] snapshot = createSnapshot();
            Path dir = Path.of(bookkeepingDir, "backups");
            Files.createDirectories(dir);
            Path file = dir.resolve("auto-" + BACKUP_STAMP.format(LocalDateTime.now(ZoneId.systemDefault())) + ".db");
            Files.write(file, snapshot);
            pruneAutoBackups(dir);
        } catch (Exception e) {
            log.warn("自动备份失败，已忽略以保证主流程", e);
        }
    }

    /** 仅保留 auto-*.db 中文件名时间戳最新的 maxKeep 份，其余删除 */
    private void pruneAutoBackups(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> autos = stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("auto-") && name.endsWith(".db");
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (int i = autoBackupMaxKeep; i < autos.size(); i++) {
                deleteQuietly(autos.get(i));
            }
        } catch (IOException ignored) {
            // 清理失败不影响备份结果
        }
    }

    // ---------------------------------------------------------------- CSV 导出

    @Override
    public String exportTransactionsCsv() {
        Map<Integer, String> accountNames = accountIdToName();
        Map<Integer, String> categoryNames = categoryIdToName();
        List<List<String>> rows = new ArrayList<>();
        rows.add(TX_HEADERS);
        for (TransactionDTO tx : transactionService.selectAll()) {
            rows.add(List.of(
                    formatDateTime(tx.getTransactionDate()),
                    tx.getType() == null ? "" : tx.getType().getValue(),
                    plain(tx.getAmount()),
                    plain(tx.getFee()),
                    accountNames.getOrDefault(tx.getAccountId(), ""),
                    accountNames.getOrDefault(tx.getToAccountId(), ""),
                    categoryNames.getOrDefault(tx.getCategoryId(), ""),
                    StringUtils.defaultString(tx.getNote()),
                    StringUtils.defaultString(tx.getTags())
            ));
        }
        return CsvUtil.toCsv(rows);
    }

    @Override
    public String exportAccountsCsv() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(ACCOUNT_HEADERS);
        for (AccountDTO account : accountService.selectAll()) {
            rows.add(List.of(
                    StringUtils.defaultString(account.getName()),
                    account.getType() == null ? "" : account.getType().getValue(),
                    plain(account.getInitialBalance()),
                    plain(account.getCurrentBalance()),
                    account.getCurrency() == null ? "" : account.getCurrency().getValue(),
                    account.getArchived() == null ? "" : String.valueOf(account.getArchived().getValue())
            ));
        }
        return CsvUtil.toCsv(rows);
    }

    // ---------------------------------------------------------------- CSV 导入

    @Override
    public CsvPreviewResult previewTransactionsCsv(String csvContent) {
        List<List<String>> records = CsvUtil.parse(csvContent);
        if (records.isEmpty()) {
            return new CsvPreviewResult(0, 0, 0, 0, List.of());
        }
        int start = isHeaderRow(records.get(0)) ? 1 : 0;
        Map<String, Integer> accountIds = accountNameToId();
        Map<String, Integer> categoryIds = categoryNameToId();
        Set<String> existing = existingFingerprints();
        Set<String> seenInFile = new HashSet<>();
        List<CsvPreviewRow> rows = new ArrayList<>();
        int total = 0;
        int valid = 0;
        int invalid = 0;
        int duplicate = 0;
        for (int i = start; i < records.size(); i++) {
            List<String> row = records.get(i);
            if (isBlankRow(row)) {
                continue;
            }
            int lineNo = i + 1;
            total++;
            RowParse parsed = parseRow(row, accountIds, categoryIds);
            String status;
            String reason;
            if (parsed.dto == null) {
                status = "invalid";
                reason = parsed.reason;
                invalid++;
            } else if (existing.contains(parsed.fingerprint)) {
                status = "duplicate";
                reason = "与已有流水重复";
                duplicate++;
            } else if (seenInFile.contains(parsed.fingerprint)) {
                status = "duplicate";
                reason = "文件内重复";
                duplicate++;
            } else {
                seenInFile.add(parsed.fingerprint);
                status = "valid";
                reason = "";
                valid++;
            }
            if (rows.size() < MAX_PREVIEW_ROWS) {
                rows.add(new CsvPreviewRow(lineNo, cell(row, 0), cell(row, 1), cell(row, 2),
                        cell(row, 4), cell(row, 5), cell(row, 6), cell(row, 7), status, reason));
            }
        }
        return new CsvPreviewResult(total, valid, invalid, duplicate, rows);
    }

    @Override
    public CsvImportResult importTransactionsCsv(String csvContent, boolean skipDuplicates) {
        List<List<String>> records = CsvUtil.parse(csvContent);
        if (records.isEmpty()) {
            return new CsvImportResult(0, 0, 0, 0, List.of());
        }
        int start = isHeaderRow(records.get(0)) ? 1 : 0;
        Map<String, Integer> accountIds = accountNameToId();
        Map<String, Integer> categoryIds = categoryNameToId();
        Set<String> existing = skipDuplicates ? existingFingerprints() : new HashSet<>();
        Set<String> seenInFile = new HashSet<>();
        List<CsvFailure> failures = new ArrayList<>();
        int total = 0;
        int imported = 0;
        int skipped = 0;
        int duplicates = 0;
        for (int i = start; i < records.size(); i++) {
            List<String> row = records.get(i);
            if (isBlankRow(row)) {
                continue;
            }
            int lineNo = i + 1;
            total++;
            RowParse parsed = parseRow(row, accountIds, categoryIds);
            if (parsed.dto == null) {
                skipped++;
                addFailure(failures, lineNo, parsed.reason);
                continue;
            }
            if (skipDuplicates) {
                boolean dupExisting = existing.contains(parsed.fingerprint);
                boolean dupFile = !dupExisting && seenInFile.contains(parsed.fingerprint);
                if (dupExisting || dupFile) {
                    duplicates++;
                    addFailure(failures, lineNo, dupExisting ? "与已有流水重复，已跳过" : "文件内重复，已跳过");
                    continue;
                }
            }
            seenInFile.add(parsed.fingerprint);
            try {
                if (transactionService.insert(parsed.dto)) {
                    imported++;
                    // 已落库的行加入指纹集，避免同文件后续重复行再次插入
                    if (skipDuplicates) {
                        existing.add(parsed.fingerprint);
                    }
                } else {
                    skipped++;
                    addFailure(failures, lineNo, "插入失败");
                }
            } catch (Exception e) {
                // 单行失败不影响其余行（insert 自身 @Transactional，失败仅回滚该行）
                skipped++;
                addFailure(failures, lineNo, "插入异常：" + e.getMessage());
            }
        }
        return new CsvImportResult(total, imported, skipped, duplicates, failures);
    }

    /**
     * 解析一行流水（NEW-11）：必填项缺失或账户/分类名无法解析时返回带原因的 invalid 结果；
     * 成功时携带 dto 与用于去重的 fingerprint。
     */
    private RowParse parseRow(List<String> row, Map<String, Integer> accountIds,
                              Map<String, Integer> categoryIds) {
        String dateStr = cell(row, 0);
        String typeStr = cell(row, 1);
        String amountStr = cell(row, 2);
        String feeStr = cell(row, 3);
        String accountName = cell(row, 4);
        String toAccountName = cell(row, 5);
        String categoryName = cell(row, 6);

        LocalDateTime date = parseDateTime(dateStr);
        if (date == null) {
            return RowParse.invalid(StringUtils.isBlank(dateStr) ? "缺少日期" : "日期「" + dateStr + "」无法解析");
        }
        if (StringUtils.isBlank(typeStr)) {
            return RowParse.invalid("缺少类型");
        }
        TransactionType type = parseType(typeStr);
        if (type == null) {
            return RowParse.invalid("类型「" + typeStr + "」无法识别");
        }
        if (StringUtils.isBlank(amountStr)) {
            return RowParse.invalid("缺少金额");
        }
        BigDecimal amount = parseDecimal(amountStr);
        if (amount == null) {
            return RowParse.invalid("金额「" + amountStr + "」格式非法");
        }
        Integer accountId = accountIds.get(accountName);
        if (accountId == null) {
            return RowParse.invalid(StringUtils.isBlank(accountName) ? "缺少账户" : "账户「" + accountName + "」不存在");
        }
        Integer toAccountId = null;
        Integer categoryId = null;
        if (type == TransactionType.TRANSFER) {
            toAccountId = accountIds.get(toAccountName);
            if (toAccountId == null) {
                return RowParse.invalid(StringUtils.isBlank(toAccountName)
                        ? "转账缺少转入账户" : "转入账户「" + toAccountName + "」不存在");
            }
        } else {
            categoryId = categoryIds.get(categoryName);
            if (categoryId == null) {
                return RowParse.invalid(StringUtils.isBlank(categoryName)
                        ? "缺少分类" : "分类「" + categoryName + "」不存在");
            }
        }
        TransactionDTO dto = new TransactionDTO();
        dto.setTransactionDate(date);
        dto.setType(type);
        dto.setAmount(amount);
        dto.setFee(parseDecimal(feeStr));
        dto.setAccountId(accountId);
        dto.setToAccountId(toAccountId);
        dto.setCategoryId(categoryId);
        dto.setNote(emptyToNull(cell(row, 7)));
        dto.setTags(emptyToNull(cell(row, 8)));
        String fp = fingerprint(date, type, amount, accountId, toAccountId, categoryId);
        return new RowParse(dto, "", fp);
    }

    /** 去重指纹：日期时间 + 类型 + 金额（2 位）+ 账户 + 转入账户 + 分类 */
    private static String fingerprint(LocalDateTime date, TransactionType type, BigDecimal amount,
                                      Integer accountId, Integer toAccountId, Integer categoryId) {
        String amt = amount == null ? "" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return date.format(DATE_TIME) + "|" + type.getValue() + "|" + amt
                + "|" + accountId + "|" + toAccountId + "|" + categoryId;
    }

    /** 库中现有全部流水的去重指纹集合 */
    private Set<String> existingFingerprints() {
        Set<String> set = new HashSet<>();
        for (TransactionDTO tx : transactionService.selectAll()) {
            set.add(fingerprint(tx.getTransactionDate(), tx.getType(), tx.getAmount(),
                    tx.getAccountId(), tx.getToAccountId(), tx.getCategoryId()));
        }
        return set;
    }

    /** 追加失败明细，超过 MAX_FAILURES 后不再记录（仅计数仍准确） */
    private static void addFailure(List<CsvFailure> failures, int line, String reason) {
        if (failures.size() < MAX_FAILURES) {
            failures.add(new CsvFailure(line, reason));
        }
    }

    /** 单行解析结果：dto 为 null 表示 invalid（携带 reason）；否则携带 fingerprint 供去重 */
    private static final class RowParse {
        private final TransactionDTO dto;
        private final String reason;
        private final String fingerprint;

        private RowParse(TransactionDTO dto, String reason, String fingerprint) {
            this.dto = dto;
            this.reason = reason;
            this.fingerprint = fingerprint;
        }

        private static RowParse invalid(String reason) {
            return new RowParse(null, reason, null);
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private boolean isHeaderRow(List<String> row) {
        return !row.isEmpty() && row.get(0) != null && row.get(0).contains("日期");
    }

    private boolean isBlankRow(List<String> row) {
        for (String s : row) {
            if (StringUtils.isNotBlank(s)) {
                return false;
            }
        }
        return true;
    }

    private static String cell(List<String> row, int index) {
        return index < row.size() ? StringUtils.trimToEmpty(row.get(index)) : "";
    }

    private static String emptyToNull(String s) {
        return StringUtils.isBlank(s) ? null : s;
    }

    private Map<Integer, String> accountIdToName() {
        Map<Integer, String> map = new HashMap<>();
        for (AccountDTO account : accountService.selectAll()) {
            map.put(account.getId(), account.getName());
        }
        return map;
    }

    private Map<Integer, String> categoryIdToName() {
        Map<Integer, String> map = new HashMap<>();
        for (CategoryDTO category : categoryService.selectAll()) {
            map.put(category.getId(), category.getName());
        }
        return map;
    }

    private Map<String, Integer> accountNameToId() {
        Map<String, Integer> map = new HashMap<>();
        for (AccountDTO account : accountService.selectAll()) {
            if (account.getName() != null) {
                map.put(account.getName(), account.getId());
            }
        }
        return map;
    }

    private Map<String, Integer> categoryNameToId() {
        Map<String, Integer> map = new HashMap<>();
        for (CategoryDTO category : categoryService.selectAll()) {
            if (category.getName() != null) {
                map.put(category.getName(), category.getId());
            }
        }
        return map;
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        return dateTime == null ? "" : dateTime.format(DATE_TIME);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static LocalDateTime parseDateTime(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String s = text.trim();
        try {
            return LocalDateTime.parse(s, DATE_TIME);
        } catch (Exception ignored) {
            // try next pattern
        }
        try {
            return LocalDate.parse(s, DATE).atStartOfDay();
        } catch (Exception ignored) {
            // try next pattern
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static TransactionType parseType(String text) {
        try {
            return TransactionType.fromValue(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isSqlite(byte[] bytes) {
        if (bytes == null || bytes.length < SQLITE_HEADER.length) {
            return false;
        }
        for (int i = 0; i < SQLITE_HEADER.length; i++) {
            if (bytes[i] != SQLITE_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    /** VACUUM INTO 的路径是 SQL 字符串字面量，单引号需翻倍转义 */
    private static String escapeSqlPath(String path) {
        return path.replace("'", "''");
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }
}
