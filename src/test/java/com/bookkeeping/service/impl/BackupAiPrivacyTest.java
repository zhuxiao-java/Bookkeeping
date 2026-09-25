package com.bookkeeping.service.impl;

import com.bookkeeping.monthly.MonthlyAiRequestGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 所有文件位于临时目录，验证净化后的物理文件，而不是只检查 SQL 查询结果。 */
class BackupAiPrivacyTest {
    @TempDir Path temp;
    JdbcTemplate jdbc;
    BackupServiceImpl service;
    MonthlyAiRequestGuard coordinator;
    static final String KEY = "test-only-sensitive-ai-key-1234567890";

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("accounts.db"));
        var script = new ResourceDatabasePopulator(new ClassPathResource("init.sql"));
        script.setSqlScriptEncoding("UTF-8");
        script.setContinueOnError(true);
        script.execute(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO t_account(f_name,f_type,f_currency) VALUES('保留账本','cash','CNY')");
        jdbc.update("INSERT INTO t_ai_config(f_id,f_base_url,f_model,f_api_key,f_config_version,f_update_time) VALUES(1,'https://example.com/v1','test',?,'old-version','2026-01-01T00:00:00')", KEY);
        jdbc.update("INSERT INTO t_monthly_report(f_month,f_version,f_source_hash,f_snapshot,f_generated_at,f_ai_result) VALUES('2024-01',1,'hash','{}','2024-02-01','{\"summary\":\"保留报告\"}')");
        jdbc.update("INSERT INTO t_period_report(f_period_type,f_period_key,f_version,f_source_hash,f_snapshot,f_generated_at,f_ai_result) VALUES('week','2024-01-01',2,'week-hash','{}','2024-01-08','{\"summary\":\"保留周报\"}'),('year','2024',3,'year-hash','{}','2025-01-01','{\"summary\":\"保留年报\"}')");
        service = new BackupServiceImpl();
        coordinator = mock(MonthlyAiRequestGuard.class);
        ReflectionTestUtils.setField(service, "dataSource", ds);
        ReflectionTestUtils.setField(service, "bookkeepingDir", temp.toString());
        ReflectionTestUtils.setField(service, "autoBackupMaxKeep", 5);
        ReflectionTestUtils.setField(service, "monthlyAiRequestGuard", coordinator);
    }

    void legacySchema() {
        jdbc.execute("ALTER TABLE t_ai_config ADD COLUMN f_automatic INTEGER DEFAULT 1");
        jdbc.execute("CREATE TABLE t_monthly_report_ai_job(f_id TEXT,f_status TEXT,f_error_code TEXT,f_result TEXT)");
        jdbc.update("INSERT INTO t_monthly_report_ai_job VALUES('running-job','running',NULL,NULL)");
        jdbc.update("INSERT INTO t_monthly_report_ai_job VALUES('finished-job','succeeded',NULL,'{\"summary\":\"保留解读\"}')");
    }

    void assertClean(Path path) throws Exception {
        assertFalse(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1).contains(KEY));
        JdbcTemplate copy = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + path));
        assertEquals("", copy.queryForObject("SELECT f_api_key FROM t_ai_config", String.class));
        if (copy.queryForObject("SELECT count(*) FROM pragma_table_info('t_ai_config') WHERE name='f_automatic'", Integer.class) > 0) {
            assertEquals(0, copy.queryForObject("SELECT f_automatic FROM t_ai_config", Integer.class));
            assertEquals("interrupted", copy.queryForObject("SELECT f_status FROM t_monthly_report_ai_job WHERE f_id='running-job'", String.class));
            assertEquals("succeeded", copy.queryForObject("SELECT f_status FROM t_monthly_report_ai_job WHERE f_id='finished-job'", String.class));
            assertTrue(copy.queryForObject("SELECT f_result FROM t_monthly_report_ai_job WHERE f_id='finished-job'", String.class).contains("保留解读"));
        }
        assertTrue(copy.queryForObject("SELECT f_ai_result FROM t_monthly_report", String.class).contains("保留报告"));
        assertNotEquals("old-version", copy.queryForObject("SELECT f_config_version FROM t_ai_config", String.class));
        assertEquals("保留账本", copy.queryForObject("SELECT f_name FROM t_account", String.class));
        assertEquals("ok", copy.queryForObject("PRAGMA integrity_check", String.class));
        assertEquals(2, copy.queryForObject("SELECT count(*) FROM t_period_report", Integer.class));
        assertEquals(2, copy.queryForObject("SELECT f_version FROM t_period_report WHERE f_period_type='week'", Integer.class));
        assertEquals(3, copy.queryForObject("SELECT f_version FROM t_period_report WHERE f_period_type='year'", Integer.class));
        assertTrue(copy.queryForObject("SELECT f_ai_result FROM t_period_report WHERE f_period_type='week'", String.class).contains("保留周报"));
        assertTrue(copy.queryForObject("SELECT f_ai_result FROM t_period_report WHERE f_period_type='year'", String.class).contains("保留年报"));
    }

    @Test
    void exportCleansBytesWithoutChangingLiveSettings() throws Exception {
        Path snapshot = temp.resolve("export.db");
        Files.write(snapshot, service.createSnapshot());
        assertClean(snapshot);
        assertEquals(KEY, jdbc.queryForObject("SELECT f_api_key FROM t_ai_config", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE name='t_monthly_report_ai_job'", Integer.class));
    }

    @Test
    void automaticAndRestoreSafetyBackupsAreClean() throws Exception {
        legacySchema();
        service.backupOnStartup();
        try (var files = Files.list(temp.resolve("backups"))) {
            List<Path> backups = files.toList();
            assertEquals(1, backups.size());
            assertClean(backups.get(0));
        }
        byte[] legacy = Files.readAllBytes(temp.resolve("accounts.db"));
        assertTrue(new String(legacy, StandardCharsets.ISO_8859_1).contains(KEY));
        service.restore(legacy);
        assertClean(temp.resolve("accounts.db"));
        try (var files = Files.list(temp)) {
            List<Path> backups = files.filter(p -> p.toString().endsWith(".bak")).toList();
            assertEquals(1, backups.size());
            assertClean(backups.get(0));
        }
        verify(coordinator).pauseForRestore();
        verify(coordinator, never()).resumeAfterRestoreFailure();
    }

    @Test
    void restoresNewSchemaAndKeepsGeneratedResults() throws Exception {
        service.restore(Files.readAllBytes(temp.resolve("accounts.db")));
        assertClean(temp.resolve("accounts.db"));
        verify(coordinator).pauseForRestore();
        verify(coordinator, never()).resumeAfterRestoreFailure();
    }

    @Test
    void acceptsOldBackupsWithoutAiTables() throws Exception {
        Path old = temp.resolve("legacy.db");
        JdbcTemplate legacy = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + old));
        legacy.execute("CREATE TABLE t_account(f_id INTEGER)");
        legacy.execute("CREATE TABLE t_transaction(f_id INTEGER)");
        legacy.execute("CREATE TABLE t_category(f_id INTEGER)");
        assertDoesNotThrow(() -> service.restore(Files.readAllBytes(old)));
        verify(coordinator).pauseForRestore();
    }

    @Test
    void rejectsInvalidBackupBeforeCancellingTasks() {
        assertThrows(RuntimeException.class, () -> service.restore("invalid".getBytes(StandardCharsets.UTF_8)));
        verifyNoInteractions(coordinator);
        assertEquals(KEY, jdbc.queryForObject("SELECT f_api_key FROM t_ai_config", String.class));
    }

    @Test
    void restoreFailureReleasesPauseWithoutReplacingLiveDatabase() {
        byte[] backup = service.createSnapshot();
        jdbc.execute("CREATE TRIGGER fail_safety_backup BEFORE UPDATE ON t_ai_config BEGIN SELECT RAISE(ABORT,'test'); END");
        assertThrows(RuntimeException.class, () -> service.restore(backup));
        verify(coordinator).pauseForRestore();
        verify(coordinator).resumeAfterRestoreFailure();
        assertEquals(KEY, jdbc.queryForObject("SELECT f_api_key FROM t_ai_config", String.class));
    }

    @Test
    void sanitizationFailureNeverExportsRawSnapshot() {
        jdbc.execute("CREATE TRIGGER fail_clean BEFORE UPDATE ON t_ai_config BEGIN SELECT RAISE(ABORT,'test'); END");
        assertThrows(RuntimeException.class, service::createSnapshot);
        assertEquals(KEY, jdbc.queryForObject("SELECT f_api_key FROM t_ai_config", String.class));
    }
}
