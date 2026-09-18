package com.bookkeeping.controller;

import com.bookkeeping.service.BackupService;
import jakarta.servlet.http.HttpServletResponse;
import org.sf.model.response.DataResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 备份 / 恢复 / CSV 导入导出 controller（GAP-09）。
 * 经 WebConfig 加 /api 前缀后路径为 /api/backup/**。
 *
 * @author zhuxiao
 */
@RestController
@RequestMapping("backup")
public class BackupController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    /** 展示后端当前使用的数据目录，兼容开发环境与桌面安装环境。 */
    @GetMapping("storagePath")
    public DataResponse<String> storagePath() {
        return DataResponse.of(backupService.storagePath());
    }

    /**
     * 导出整库快照（.db，SQLite VACUUM INTO 一致性快照）供下载。
     */
    @GetMapping("export")
    public void export(HttpServletResponse response) throws IOException {
        byte[] data = backupService.createSnapshot();
        writeAttachment(response, "bookkeeping-backup-" + stamp() + ".db",
                "application/octet-stream", data);
    }

    /**
     * 从上传的 .db 备份恢复：覆盖 accounts.db。活动连接仍指向旧库，
     * 故返回 restartRequired=true，由前端触发 Electron 重启后端进程重新加载。
     */
    @PostMapping("import")
    public DataResponse<Map<String, Object>> importBackup(@RequestParam("file") MultipartFile file) throws IOException {
        backupService.restore(file.getBytes());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("restartRequired", true);
        return DataResponse.of(body);
    }

    /**
     * 导出全部流水为 CSV。
     */
    @GetMapping("csv/transactions")
    public void exportTransactionsCsv(HttpServletResponse response) throws IOException {
        writeAttachment(response, "transactions-" + stamp() + ".csv",
                "text/csv; charset=UTF-8", backupService.exportTransactionsCsv().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 导出全部账户为 CSV。
     */
    @GetMapping("csv/accounts")
    public void exportAccountsCsv(HttpServletResponse response) throws IOException {
        writeAttachment(response, "accounts-" + stamp() + ".csv",
                "text/csv; charset=UTF-8", backupService.exportAccountsCsv().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 预览流水 CSV 导入结果（NEW-11）：不落库，逐行标注 valid / invalid / duplicate 与原因。
     */
    @PostMapping("csv/transactions/preview")
    public DataResponse<BackupService.CsvPreviewResult> previewTransactionsCsv(
            @RequestParam("file") MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return DataResponse.of(backupService.previewTransactionsCsv(content));
    }

    /**
     * 导入流水 CSV：按账户名/分类名解析、仅新增，返回导入统计。
     * skipDuplicates=true（默认）时跳过与库中或文件内重复的流水。
     */
    @PostMapping("csv/transactions")
    public DataResponse<BackupService.CsvImportResult> importTransactionsCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "skipDuplicates", defaultValue = "true") boolean skipDuplicates) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return DataResponse.of(backupService.importTransactionsCsv(content, skipDuplicates));
    }

    private static String stamp() {
        return LocalDateTime.now(ZoneId.systemDefault()).format(STAMP);
    }

    private static void writeAttachment(HttpServletResponse response, String filename,
                                        String contentType, byte[] data) throws IOException {
        response.setHeader("Content-type", contentType);
        response.setHeader("Content-disposition", "attachment;filename=" + filename);
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }
}
