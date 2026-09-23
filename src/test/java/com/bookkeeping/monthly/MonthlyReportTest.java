package com.bookkeeping.monthly;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.bookkeeping.dao.mapper.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import reactor.core.publisher.Mono;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.sf.dao.config.SelfMetaObjectHandler;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.bookkeeping.monthly.MonthlyReportModels.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 月报后端集成测试。
 * <p>
 * 持久化层已全部迁移到 MyBatis-Plus，故不再用 JdbcTemplate 手工构造仓储：改为参考
 * {@code LevelSystemSqliteTest} 的做法，用 {@link MybatisSqlSessionFactoryBuilder} 把各 mapper
 * 引导到临时 SQLite，再把真实 mapper 注入 {@link MonthlyReportRepository}/{@link MonthlyTransactionController}。
 * JdbcTemplate 仅用于「灌测试数据 + 断言库表状态」，不参与生产链路。
 * AI 相关覆盖脱敏摘要、结果强校验、同步生成、请求期互斥与取消，以及历史结果迁移，
 * 不真正发起外部模型调用。
 *
 * @author zhuxiao
 */
class MonthlyReportTest {
    @TempDir Path temp;
    DataSource ds;
    JdbcTemplate jdbc;
    ObjectMapper json = JsonMapper.builder().build();
    MonthlyReportRepository repo;
    MonthlyReportService service;
    MonthlyAiRequestGuard guard;
    AiConfigService configService;
    MonthlyAiModelClient client;
    MonthlyReportAiService ai;

    @BeforeEach
    void setup() {
        ds = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db") + "?busy_timeout=10000");
        init();
        jdbc = new JdbcTemplate(ds);
        // 引导 MyBatis-Plus 到临时库：注册月报链路涉及的全部 mapper，注入框架自动填充处理器
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), ds));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig()
                .setDbConfig(new GlobalConfig.DbConfig()).setMetaObjectHandler(new SelfMetaObjectHandler()));
        configuration.addMapper(MonthlyReportMapper.class);
        configuration.addMapper(TransactionMapper.class);
        configuration.addMapper(AccountMapper.class);
        configuration.addMapper(CategoryMapper.class);
        configuration.addMapper(BudgetMapper.class);
        configuration.addMapper(MessageMapper.class);
        configuration.addMapper(AiConfigMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        repo = new MonthlyReportRepository(
                session.getMapper(MonthlyReportMapper.class),
                session.getMapper(TransactionMapper.class),
                session.getMapper(AccountMapper.class),
                session.getMapper(CategoryMapper.class),
                session.getMapper(BudgetMapper.class),
                session.getMapper(MessageMapper.class),
                json);
        guard = new MonthlyAiRequestGuard(new DataSourceTransactionManager(ds));
        service = new MonthlyReportService(repo, new MonthlyReportCalculator(), guard);
        configService = new AiConfigService(session.getMapper(AiConfigMapper.class), guard);
        client = mock(MonthlyAiModelClient.class);
        ai = new MonthlyReportAiService(service, configService, json, guard, client);
        jdbc.update("INSERT INTO t_account(f_id,f_name,f_type,f_currency) VALUES (1,'隐私账户','cash','CNY'),(2,'美元账户','cash','DOLLAR'),(3,'未知','cash',NULL)");
    }

    @AfterEach
    void shutdown() { guard.shutdown(); }

    void init() {
        ResourceDatabasePopulator script = new ResourceDatabasePopulator(new ClassPathResource("init.sql"));
        script.setSqlScriptEncoding("UTF-8");
        script.setContinueOnError(true);
        script.execute(ds);
    }

    /**
     * 灌一条流水：note 固定为敏感文本，用于验证其绝不进入发送给模型的摘要。
     */
    void tx(String month, String type, String amount, int account, Integer category, String fee) {
        jdbc.update("INSERT INTO t_transaction(f_type,f_amount,f_fee,f_account_id,f_category_id,f_date,f_note) VALUES (?,?,?,?,?,?,?)",
                type, amount, fee, account, category, month + "-15T12:00:00", "不应发送的备注");
    }

    // ==================== 统计快照生成 ====================

    @Test
    void precisionCurrenciesFeesBudgetsAndBrokenChains() {
        jdbc.update("INSERT INTO t_category(f_id,f_name,f_parent_id,f_type) VALUES (1001,'一级',NULL,'expense'),(1002,'二级',1001,'expense'),(1003,'三级',1002,'expense'),(1004,'断链',9999,'expense'),(1005,'循环',1005,'expense')");
        tx("2024-02", "expense", "0.10", 1, 1001, "0");
        tx("2024-02", "expense", "0.20", 1, 1003, "0");
        tx("2024-02", "expense", "2.00", 1, 1004, "0");
        tx("2024-02", "expense", "3.00", 1, 1005, "0");
        tx("2024-02", "expense", "4.00", 1, null, "0");
        tx("2024-02", "income", "20.00", 1, null, "0");
        tx("2024-02", "transfer", "1000", 1, null, "0.15");
        tx("2024-02", "expense", "7", 2, 1001, "0");
        tx("2024-02", "expense", "9", 3, 1001, "0");
        jdbc.update("INSERT INTO t_budget(f_category_id,f_amount,f_month,f_year) VALUES (NULL,'10',2,2024),(1001,'0',2,2024),(1002,'1',2,2024)");
        Snapshot s = service.candidate("2024-02").snapshot();
        CurrencySummary cny = s.currencies().stream().filter(c -> c.currency().equals("CNY")).findFirst().orElseThrow();
        assertEquals("9.30", cny.expense());
        assertEquals("10.55", cny.balance());
        assertEquals("0.15", cny.fees());
        assertEquals(3, s.currencies().size());
        assertEquals("9.00", cny.categories().get(0).amount());
        assertEquals(0, cny.categories().get(0).categoryId());
        assertEquals("0.30", cny.categories().get(1).amount());
        assertEquals(2, cny.categories().get(1).children().size());
        assertEquals("9.30", s.budgets().get(0).used());
        assertEquals("0.30", s.budgets().get(1).used());
        assertNull(s.budgets().get(1).percentage());
        assertEquals("0.20", s.budgets().get(2).used());
        assertNull(cny.previousExpense());
        assertNull(cny.historyAverage());
    }

    @Test
    void leapMonthRangeAndThreeCalendarMonths() {
        tx("2023-11", "expense", "30", 1, null, "0");
        tx("2023-12", "expense", "60", 1, null, "0");
        tx("2024-01", "income", "20", 1, null, "0");
        jdbc.update("INSERT INTO t_transaction(f_type,f_amount,f_account_id,f_date) VALUES ('expense','90',1,'2024-02-29T23:59:59.999'),('expense','999',1,'2024-03-01T00:00:00')");
        CurrencySummary c = service.candidate("2024-02").snapshot().currencies().get(0);
        assertEquals("90.00", c.expense());
        assertEquals("0.00", c.previousExpense());
        assertNull(c.growth());
        assertEquals("30.00", c.historyAverage());
        assertEquals(1, service.candidate("2023-12").snapshot().count());
        assertThrows(RuntimeException.class, () -> service.candidate(YearMonth.now().toString()));
    }

    @Test
    void repeatedInitializationAndStaleness() {
        init();
        init();
        Detail d = report("2024-02");
        service.migrateLegacy();
        assertEquals(0, count("SELECT count(*) FROM sqlite_master WHERE name='t_monthly_report_ai_job'"));
        assertEquals(0, count("SELECT count(*) FROM pragma_table_info('t_ai_config') WHERE name='f_automatic'"));
        tx("2024-01", "expense", "10", 1, null, "0");
        assertTrue(service.detail(d.id()).stale());
        assertEquals(2, submit("2024-02").version());
        assertEquals(1, count("SELECT count(*) FROM t_message"));
    }

    @Test
    void emptyMonthDoesNotNotify() {
        configured();
        assertNull(ai.preview("2024-02"));
        Candidate candidate = service.candidate("2024-02");
        assertThrows(RuntimeException.class, () -> ai.generate(new AiGenerateRequest(candidate.month(), candidate.sourceHash(), 0, configService.view().configVersion(), true)));
        assertEquals(0, count("SELECT count(*) FROM t_monthly_report"));
        assertEquals(0, count("SELECT count(*) FROM t_message"));
        verifyNoInteractions(client);
    }

    @Test
    void growthFrequencyThresholdsAndStableFacts() {
        jdbc.update("INSERT INTO t_category(f_id,f_name,f_type) VALUES (1001,'正常消费','expense'),(1002,'小额增长','expense')");
        for (int i = 0; i < 6; i++) tx("2024-01", "expense", "10", 1, 1001, "0");
        for (int i = 0; i < 8; i++) tx("2024-02", "expense", "10", 1, 1001, "0");
        tx("2024-01", "expense", "1", 1, 1002, "0");
        tx("2024-02", "expense", "2", 1, 1002, "0");
        Snapshot s = service.candidate("2024-02").snapshot();
        assertTrue(s.facts().stream().anyMatch(f -> f.id().equals("CNY:category:1001:growth")));
        assertTrue(s.facts().stream().anyMatch(f -> f.id().equals("CNY:category:1001:frequency")));
        assertFalse(s.facts().stream().anyMatch(f -> f.id().equals("CNY:category:1002:growth")));
        assertEquals(s.facts(), new MonthlyReportCalculator().calculate(YearMonth.of(2024, 2), repo.source(YearMonth.of(2024, 2))).facts());
        assertEquals(s.facts().size(), s.facts().stream().map(Fact::id).distinct().count());
    }

    @Test
    void fingerprintOnlyTracksRelevantCategoriesAndCurrency() {
        jdbc.update("INSERT INTO t_category(f_id,f_name,f_type) VALUES (1001,'有关','expense'),(1002,'无关','expense')");
        tx("2023-10", "income", "1", 1, null, "0");
        tx("2024-02", "expense", "20", 1, 1001, "0");
        configured();
        when(client.call(any(), anyString(), anyString(), anyInt(), any())).thenReturn(Mono.just(VALID_RESULT));
        Detail d = submit("2024-02");
        jdbc.update("UPDATE t_category SET f_name='无关改名' WHERE f_id=1002");
        tx("2020-01", "income", "1", 1, null, "0");
        assertFalse(service.detail(d.id()).stale());
        jdbc.update("UPDATE t_category SET f_name='有关改名' WHERE f_id=1001");
        assertTrue(service.detail(d.id()).stale());
    }

    @Test
    void failedRefreshRollsBackSnapshotAndMessageTogether() {
        tx("2024-02", "expense", "20", 1, null, "0");
        configured();
        when(client.call(any(), anyString(), anyString(), anyInt(), any())).thenReturn(Mono.just(VALID_RESULT));
        Detail d = submit("2024-02");
        tx("2024-02", "expense", "5", 1, null, "0");
        jdbc.execute("CREATE TRIGGER fail_message BEFORE UPDATE ON t_message BEGIN SELECT RAISE(ABORT,'test'); END");
        assertThrows(RuntimeException.class, () -> submit("2024-02"));
        assertEquals(1, repo.byId(d.id()).version());
        assertEquals("20.00", repo.byId(d.id()).snapshot().currencies().get(0).expense());
        assertEquals(1, count("SELECT count(*) FROM t_message"));
    }

    // ==================== 流水下钻契约 ====================

    @Test
    void rangeDrillMatchesCurrencyCategoryAndMonthEnd() {
        jdbc.update("INSERT INTO t_category(f_id,f_name,f_parent_id,f_type,f_is_archived) VALUES (1001,'归档一级',NULL,'expense',1),(1002,'归档子类',1001,'expense',1)");
        jdbc.update("UPDATE t_account SET f_currency=' CNY ' WHERE f_id=1");
        tx("2024-02", "expense", "0.10", 1, 1002, "0");
        tx("2024-02", "expense", "0.20", 1, null, "0");
        tx("2024-02", "expense", "90.00", 2, 1002, "0");
        tx("2024-02", "expense", "5.00", 3, null, "0");
        jdbc.update("INSERT INTO t_transaction(f_type,f_amount,f_account_id,f_date) VALUES ('expense','0.30',1,'2024-02-29T23:59:59.999'),('expense','999',1,'2024-03-01T00:00:00')");
        MonthlyTransactionController controller = newController();
        JsonNode response = json.valueToTree(controller.range("2024-02-01", "2024-02-29", "CNY"));
        assertEquals("S0806", response.path("code").asString());
        JsonNode rows = response.path("data");
        assertEquals(3, rows.size());
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode row : rows) {
            assertTrue(row.path("amount").isString());
            total = total.add(new BigDecimal(row.path("amount").asString()));
            if (!row.path("categoryId").isNull() && row.path("categoryId").asInt() == 1002) {
                assertEquals(1001, row.path("monthlyRootId").asInt());
                assertTrue(row.path("categoryPath").toString().contains("1001"));
            } else assertEquals(0, row.path("monthlyRootId").asInt());
        }
        CurrencySummary cny = service.candidate("2024-02").snapshot().currencies().stream()
                .filter(c -> c.currency().equals("CNY")).findFirst().orElseThrow();
        assertEquals(0, total.compareTo(new BigDecimal(cny.expense())));
        assertEquals(1, json.valueToTree(newController().range("2024-02-01", "2024-02-29", "UNKNOWN")).path("data").size());
        assertThrows(RuntimeException.class, () -> newController().range("2023-01-01", "2024-02-01", "CNY"));
        assertThrows(RuntimeException.class, () -> newController().range("2024-02-30", "2024-03-01", "CNY"));
    }

    // ==================== AI 配置、取消与端到端持久化 ====================

    private AiConfigView configured() {
        return configService.save(new AiConfigRequest("https://example.com/v1", "test", false, "test-only-key"));
    }

    private Detail report(String month) {
        configured();
        tx(month, "expense", "20", 1, null, "0");
        when(client.call(any(), anyString(), anyString(), anyInt(), any())).thenReturn(Mono.just(VALID_RESULT));
        return submit(month);
    }

    private AiGenerateRequest request(String month) {
        AiPreviewView p = ai.preview(month);
        return new AiGenerateRequest(p.month(), p.sourceHash(), p.baseVersion(), p.configVersion(), true);
    }

    private Detail submit(String month) { return ai.generate(request(month)); }

    private static final String VALID_RESULT = "{\"summary\":\"月度回顾\",\"limitations\":\"仅代表已记录数据\",\"observations\":[],\"actions\":[]}";

    @Test
    void configurationNormalizesAndNeverReusesKeyAcrossAddresses() {
        AiConfigView first = configured();
        AiConfigView same = configService.save(new AiConfigRequest("HTTPS://EXAMPLE.COM:443/v1/", "test", false, null));
        assertEquals(first.configVersion(), same.configVersion());
        assertTrue(same.hasKey());
        assertFalse(configService.load().toString().contains("test-only-key"));
        AiConfigView changed = configService.save(new AiConfigRequest("https://other.example/v1", "test", false, null));
        assertFalse(changed.hasKey());
        assertNotEquals(first.configVersion(), changed.configVersion());
        AiConfigView withKey = configService.save(new AiConfigRequest(changed.baseUrl(), "test", false, "replacement-test-key"));
        AiConfigView cleared = configService.save(new AiConfigRequest(changed.baseUrl(), "test", false, ""));
        assertFalse(cleared.hasKey());
        assertNotEquals(withKey.configVersion(), cleared.configVersion());
        for (String url : List.of("http://example.com", "https://user@example.com", "https://example.com?x=1", "https://example.com#x", "https://example.com:0", "https://example.com:65536", "https://example.com:"))
            assertThrows(RuntimeException.class, () -> configService.save(new AiConfigRequest(url, "test", false, null)));
    }

    @Test
    void previewIsReadOnlyAndGenerationReturnsCommittedDetail() {
        configured();
        tx("2024-02", "expense", "20", 1, null, "0");
        AiGenerateRequest request = request("2024-02");
        assertEquals(0, request.baseVersion());
        assertEquals(0, count("SELECT count(*) FROM t_monthly_report"));
        assertEquals(0, count("SELECT count(*) FROM t_message"));
        service.list(2024); service.migrateLegacy();
        verifyNoInteractions(client);
        when(client.call(any(), anyString(), anyString(), anyInt(), any())).thenReturn(Mono.just(VALID_RESULT));
        Detail d = ai.generate(request);
        assertEquals("月度回顾", d.result().path("summary").asString());
        assertEquals(d.result(), service.detail(d.id()).result());
        assertTrue(service.list(2024).stream().filter(e -> e.month().equals("2024-02")).findFirst().orElseThrow().hasReport());
        assertTrue(jdbc.queryForObject("SELECT f_content FROM t_message LIMIT 1", String.class).contains("AI 月报已生成"));
        assertThrows(RuntimeException.class, () -> ai.generate(request));
        verify(client, times(1)).call(any(), anyString(), anyString(), eq(2000), eq(java.time.Duration.ofSeconds(120)));
        assertEquals(2, submit("2024-02").version());
        assertEquals(1, count("SELECT count(*) FROM t_message"));
    }

    @Test
    void changedSourceConfigAndMissingConsentRejectBeforeSending() {
        configured();
        tx("2024-02", "expense", "20", 1, null, "0");
        AiGenerateRequest p = request("2024-02");
        assertThrows(RuntimeException.class, () -> ai.generate(new AiGenerateRequest(p.month(), p.sourceHash(), 0, p.configVersion(), false)));
        tx("2024-02", "expense", "1", 1, null, "0");
        assertThrows(RuntimeException.class, () -> ai.generate(p));
        AiGenerateRequest current = request("2024-02");
        configService.revoke(false);
        assertThrows(RuntimeException.class, () -> ai.generate(current));
        verifyNoInteractions(client);
    }

    @Test
    void concurrentCallsAreBusyAndRevokeCancelsWithoutQueue() throws Exception {
        configured();
        tx("2024-01", "expense", "20", 1, null, "0");
        tx("2024-02", "expense", "20", 1, null, "0");
        CountDownLatch started = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        when(client.call(any(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(Mono.<String>never().doOnSubscribe(s -> started.countDown()).doOnCancel(cancelled::countDown));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Detail> first = pool.submit(() -> submit("2024-01"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            assertEquals(0, count("SELECT count(*) FROM t_monthly_report"));
            assertEquals("BUSY", MonthlyAiModelClient.errorCode(assertThrows(RuntimeException.class, () -> submit("2024-02"))));
            assertEquals("BUSY", MonthlyAiModelClient.errorCode(assertThrows(RuntimeException.class, () -> ai.testConnection(new AiConsentRequest(configService.view().configVersion(), true)))));
            configService.revoke(true);
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            assertEquals("CANCELLED", MonthlyAiModelClient.errorCode(assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS)).getCause()));
            assertFalse(configService.view().hasKey());
            assertEquals(0, count("SELECT count(*) FROM t_monthly_report"));
            verify(client, times(1)).call(any(), anyString(), anyString(), anyInt(), any());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void sourceChangeRejectsLateResultAndRetainsOldSuccess() throws Exception {
        Detail old = report("2024-02");
        CompletableFuture<String> response = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        when(client.call(any(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(Mono.fromFuture(response).doOnSubscribe(s -> started.countDown()));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Detail> pending = pool.submit(() -> submit("2024-02"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            tx("2024-02", "expense", "1", 1, null, "0");
            response.complete(VALID_RESULT);
            assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertEquals(old.result(), service.detail(old.id()).result());
            assertEquals(1, service.detail(old.id()).version());
            assertTrue(service.detail(old.id()).stale());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void configurationChangeAndRestorePauseCancelCurrentCall() throws Exception {
        configured();
        tx("2024-02", "expense", "20", 1, null, "0");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            for (boolean restore : List.of(false, true)) {
                CountDownLatch started = new CountDownLatch(1);
                CompletableFuture<String> response = new CompletableFuture<>();
                when(client.call(any(), anyString(), anyString(), anyInt(), any()))
                        .thenReturn(Mono.fromFuture(response, true).doOnSubscribe(s -> started.countDown()));
                Future<Detail> pending = pool.submit(() -> submit("2024-02"));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                if (restore) guard.pauseForRestore();
                else configService.save(new AiConfigRequest("https://example.com/v1", "changed", false, null));
                response.complete(VALID_RESULT);
                assertEquals(restore ? "CANCELLED" : "CONFIG_CHANGED", MonthlyAiModelClient.errorCode(assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS)).getCause()));
                assertEquals(0, count("SELECT count(*) FROM t_monthly_report"));
                if (restore) {
                    assertThrows(RuntimeException.class, () -> submit("2024-02"));
                    guard.resumeAfterRestoreFailure();
                }
            }
            verify(client, times(2)).call(any(), anyString(), anyString(), anyInt(), any());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void timeoutCancelsSubscriptionAndReleasesSlot() {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        MonthlyAiRequestGuard.Handle handle = guard.locked(() -> guard.start(() -> Mono.<String>never().doOnCancel(() -> cancelled.set(true)), java.time.Duration.ofMillis(20)));
        try { assertEquals("TIMEOUT", MonthlyAiModelClient.errorCode(assertThrows(RuntimeException.class, () -> guard.await(handle)))); }
        finally { guard.release(handle); }
        assertTrue(cancelled.get());
        configured();
        when(client.call(any(), anyString(), anyString(), anyInt(), any())).thenReturn(Mono.just("OK"));
        assertTrue(ai.testConnection(new AiConsentRequest(configService.view().configVersion(), true)));
    }

    @Test
    void testConnectionRequiresCurrentConsentAndNonemptyReply() {
        AiConfigView config = configured();
        assertThrows(RuntimeException.class, () -> ai.testConnection(new AiConsentRequest(config.configVersion(), false)));
        assertThrows(RuntimeException.class, () -> ai.testConnection(new AiConsentRequest("old", true)));
        verifyNoInteractions(client);
        when(client.call(any(), anyString(), anyString(), anyInt(), any())).thenReturn(Mono.just(""));
        assertThrows(RuntimeException.class, () -> ai.testConnection(new AiConsentRequest(config.configVersion(), true)));
        verify(client).call(any(), anyString(), anyString(), eq(16), eq(java.time.Duration.ofSeconds(20)));
    }

    @Test
    void failedOutputAndMessageInsertionNeverCreatePartialReport() {
        configured();
        tx("2024-02", "expense", "20", 1, null, "0");
        when(client.call(any(), anyString(), anyString(), anyInt(), any())).thenReturn(Mono.just("invalid"));
        assertEquals("INVALID_JSON", MonthlyAiModelClient.errorCode(assertThrows(RuntimeException.class, () -> submit("2024-02"))));
        assertEquals(0, count("SELECT count(*) FROM t_monthly_report"));
        when(client.call(any(), anyString(), anyString(), anyInt(), any())).thenReturn(Mono.just(VALID_RESULT));
        jdbc.execute("CREATE TRIGGER fail_message BEFORE INSERT ON t_message BEGIN SELECT RAISE(ABORT,'sensitive-test-body'); END");
        RuntimeException error = assertThrows(RuntimeException.class, () -> submit("2024-02"));
        assertEquals("STORAGE_FAILED", MonthlyAiModelClient.errorCode(error));
        assertNull(error.getCause());
        assertEquals(0, count("SELECT count(*) FROM t_monthly_report"));
        assertEquals(0, count("SELECT count(*) FROM t_message"));
        jdbc.execute("CREATE TRIGGER fail_config BEFORE UPDATE ON t_ai_config BEGIN SELECT RAISE(ABORT,'sensitive-test-key'); END");
        RuntimeException saveError = assertThrows(RuntimeException.class, () -> configService.revoke(true));
        assertEquals("STORAGE_FAILED", MonthlyAiModelClient.errorCode(saveError));
        assertNull(saveError.getCause());
        assertTrue(configService.view().hasKey());
    }

    @Test
    void migrationUsesLatestValidSuccessOfCurrentVersionOnly() {
        Detail first = report("2024-01"), second = report("2024-02");
        jdbc.execute("ALTER TABLE t_ai_config ADD COLUMN f_automatic INTEGER DEFAULT 1");
        jdbc.execute("CREATE TABLE t_monthly_report_ai_job(f_report_id INTEGER,f_snapshot_version INTEGER,f_status TEXT,f_result TEXT)");
        jdbc.update("UPDATE t_monthly_report SET f_ai_result=NULL");
        jdbc.update("INSERT INTO t_monthly_report_ai_job VALUES(?,1,'succeeded',?)", first.id(), first.result().toString().replace("月度回顾", "更早结果"));
        jdbc.update("INSERT INTO t_monthly_report_ai_job VALUES(?,1,'succeeded',?)", first.id(), first.result().toString());
        jdbc.update("INSERT INTO t_monthly_report_ai_job VALUES(?,1,'succeeded','invalid')", first.id());
        jdbc.update("INSERT INTO t_monthly_report_ai_job VALUES(?,2,'succeeded',?)", second.id(), second.result().toString());
        jdbc.update("INSERT INTO t_monthly_report_ai_job VALUES(?,1,'running',NULL)", second.id());
        clearInvocations(client);
        // 临时库还原旧表结构，再执行实际初始化脚本，验证加列和重复启动兼容。
        jdbc.execute("ALTER TABLE t_monthly_report DROP COLUMN f_ai_result");
        assertEquals(0, count("SELECT count(*) FROM pragma_table_info('t_monthly_report') WHERE name='f_ai_result'"));
        init(); init();
        assertEquals(1, count("SELECT count(*) FROM pragma_table_info('t_monthly_report') WHERE name='f_ai_result'"));
        service.migrateLegacy(); service.migrateLegacy();
        assertEquals(first.result(), service.detail(first.id()).result());
        assertEquals(1, service.detail(first.id()).version());
        assertNull(service.detail(second.id()).result());
        assertFalse(service.list(2024).stream().filter(e -> e.month().equals("2024-02")).findFirst().orElseThrow().hasReport());
        assertEquals(0, count("SELECT f_automatic FROM t_ai_config"));
        assertEquals(5, count("SELECT count(*) FROM t_monthly_report_ai_job"));
        assertEquals(2, count("SELECT count(*) FROM t_message"));
        verifyNoInteractions(client);
    }

    // ==================== 发送给模型的摘要脱敏 + 结果强校验 ====================

    @Test
    void summaryMasksNamesAndNeverLeaksPrivateFields() throws Exception {
        jdbc.update("INSERT INTO t_category(f_id,f_name,f_type) VALUES (1001,'餐饮美食','expense')");
        tx("2024-02", "expense", "20", 1, 1001, "0");
        Snapshot snapshot = service.candidate("2024-02").snapshot();
        String masked = json.writeValueAsString(MonthlyReportAiService.summaryFor(snapshot, false));
        String named = json.writeValueAsString(MonthlyReportAiService.summaryFor(snapshot, true));
        // 隐私字段永不下发：账户名与备注都不应出现在摘要里
        assertFalse(masked.contains("不应发送的备注"));
        assertFalse(named.contains("不应发送的备注"));
        assertFalse(masked.contains("隐私账户"));
        // 关闭 includeNames 时真实分类名被脱敏为「分类-{id}」
        assertTrue(named.contains("餐饮美食"));
        assertFalse(masked.contains("餐饮美食"));
        assertTrue(masked.contains("分类-1001"));
    }

    @Test
    void validateResultEnforcesContract() {
        jdbc.update("INSERT INTO t_category(f_id,f_name,f_type) VALUES (1001,'餐饮美食','expense')");
        tx("2024-02", "expense", "20", 1, 1001, "0");
        Snapshot snapshot = service.candidate("2024-02").snapshot();
        String factId = snapshot.facts().get(0).id();
        String good = "{\"summary\":\"摘要\",\"limitations\":\"记录有限\",\"observations\":[{\"text\":\"发现\",\"factIds\":[\"" + factId + "\"]}],"
                + "\"actions\":[],\"provider\":\"example.test\",\"model\":\"mock\",\"generatedAt\":\"2024-03-01T00:00:00\"}";
        assertDoesNotThrow(() -> MonthlyReportAiService.validateResult(json.readTree(good), snapshot));
        // 未知字段
        assertThrows(RuntimeException.class, () -> MonthlyReportAiService.validateResult(
                json.readTree(good.replaceFirst("\"summary\"", "\"unknownField\":\"x\",\"summary\"")), snapshot));
        // 引用不存在的事实
        assertThrows(RuntimeException.class, () -> MonthlyReportAiService.validateResult(
                json.readTree(good.replace(factId, "不存在的factId")), snapshot));
        // observations 超过上限（5 条）
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < 6; i++) tooMany.append("{\"text\":\"发现\",\"factIds\":[\"").append(factId).append("\"]}")
                .append(i < 5 ? "," : "");
        assertThrows(RuntimeException.class, () -> MonthlyReportAiService.validateResult(
                json.readTree(good.replaceFirst("\"observations\":\\[[^]]*]", "\"observations\":[" + tooMany + "]")), snapshot));
    }

    // ==================== 辅助 ====================

    private MonthlyTransactionController newController() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("range", new SpringManagedTransactionFactory(), ds));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig()
                .setDbConfig(new GlobalConfig.DbConfig()).setMetaObjectHandler(new SelfMetaObjectHandler()));
        configuration.addMapper(TransactionMapper.class);
        configuration.addMapper(AccountMapper.class);
        configuration.addMapper(CategoryMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        return new MonthlyTransactionController(session.getMapper(TransactionMapper.class),
                session.getMapper(AccountMapper.class), session.getMapper(CategoryMapper.class));
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
