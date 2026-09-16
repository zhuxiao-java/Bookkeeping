package com.bookkeeping.service;

import java.util.List;

/**
 * 备份 / 恢复 / CSV 导入导出 service（GAP-09）。
 *
 * @author zhuxiao
 */
public interface BackupService {

    /**
     * 用 SQLite {@code VACUUM INTO} 生成当前库的一致性单文件快照。
     *
     * @return 快照 .db 文件字节
     */
    byte[] createSnapshot();

    /**
     * 用上传的 .db 快照覆盖当前库：先落暂存文件并校验 SQLite 头，安全备份现库为 .bak，
     * 再原子替换 accounts.db 并清理旧连接的 sidecar 文件。活动连接仍指向旧 inode，
     * 故恢复后必须重启后端进程重新加载。
     *
     * @param dbBytes 上传的 .db 文件字节
     */
    void restore(byte[] dbBytes);

    /**
     * 导出全部流水为 CSV（含 BOM，账户/分类以名称呈现）。
     */
    String exportTransactionsCsv();

    /**
     * 导出全部账户为 CSV（含 BOM）。
     */
    String exportAccountsCsv();

    /**
     * 预览流水 CSV 导入结果（NEW-11）：不落库，仅逐行解析并标注
     * valid / invalid / duplicate 状态与失败原因，供导入向导展示。
     *
     * @param csvContent CSV 文本（UTF-8，可含 BOM）
     * @return 预览结果（最多返回前 {@code MAX_PREVIEW_ROWS} 行明细）
     */
    CsvPreviewResult previewTransactionsCsv(String csvContent);

    /**
     * 导入流水 CSV：按账户名/分类名解析为 id，仅新增（走余额联动），
     * 逐行容错——字段非法、账户/分类名无法解析的行跳过，不影响其余行。
     *
     * @param csvContent     CSV 文本（UTF-8，可含 BOM）
     * @param skipDuplicates 是否跳过与库中或文件内已存在的重复流水（NEW-11）
     * @return 导入统计
     */
    CsvImportResult importTransactionsCsv(String csvContent, boolean skipDuplicates);

    /**
     * CSV 导入统计。
     *
     * @param total      数据行总数（不含表头、不含空行）
     * @param imported   成功新增条数
     * @param skipped    跳过条数（字段非法 / 账户或分类名无法解析 / 插入异常）
     * @param duplicates 因重复而跳过的条数（skipDuplicates=true 时统计）
     * @param failures   失败明细（行号 + 原因），最多 {@code MAX_FAILURES} 条
     */
    record CsvImportResult(int total, int imported, int skipped, int duplicates, List<CsvFailure> failures) {
    }

    /**
     * CSV 单行失败明细。
     *
     * @param line   CSV 中的物理行号（1 起，含表头行计数）
     * @param reason 失败 / 跳过原因
     */
    record CsvFailure(int line, String reason) {
    }

    /**
     * CSV 预览行。
     *
     * @param line          物理行号（1 起）
     * @param date          日期原文
     * @param type          类型原文
     * @param amount        金额原文
     * @param accountName   账户名原文
     * @param toAccountName 转入账户名原文
     * @param categoryName  分类名原文
     * @param note          备注原文
     * @param status        valid / invalid / duplicate
     * @param reason        invalid / duplicate 时的原因说明，valid 为空串
     */
    record CsvPreviewRow(int line, String date, String type, String amount, String accountName,
                         String toAccountName, String categoryName, String note, String status, String reason) {
    }

    /**
     * CSV 预览汇总。
     *
     * @param total          数据行总数（不含表头、不含空行）
     * @param validCount     可成功导入的行数
     * @param invalidCount   无法解析的行数
     * @param duplicateCount 与库中或文件内重复的行数
     * @param rows           预览明细（最多 {@code MAX_PREVIEW_ROWS} 行）
     */
    record CsvPreviewResult(int total, int validCount, int invalidCount, int duplicateCount, List<CsvPreviewRow> rows) {
    }
}
