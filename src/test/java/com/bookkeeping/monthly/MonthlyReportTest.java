package com.bookkeeping.monthly;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bookkeeping.monthly.MonthlyReportModels.*;

class MonthlyReportTest {
    @TempDir Path temp;
    JdbcTemplate jdbc;
    MonthlyReportService service;
    MonthlyReportRepository repo;
    DriverManagerDataSource ds;
    ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
    final String config = "0123456789abcdef-config";
    @BeforeEach void setup() {
        ds = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("test.db"));
        init();
        jdbc = new JdbcTemplate(ds);
        repo = new MonthlyReportRepository(jdbc, json);
        service = new MonthlyReportService(repo, new MonthlyReportCalculator(), new DataSourceTransactionManager(ds));
        jdbc.update("INSERT INTO t_account(f_id,f_name,f_type,f_currency) VALUES (1,'隐私账户','cash','CNY'),(2,'美元账户','cash','DOLLAR'),(3,'未知','cash',NULL)");
    }
    void init() {
        ResourceDatabasePopulator script = new ResourceDatabasePopulator(new ClassPathResource("init.sql"));
        script.setContinueOnError(true);
        script.execute(ds);
    }
    void tx(String month, String type, String amount, int account, Integer category, String fee) {
        jdbc.update("INSERT INTO t_transaction(f_type,f_amount,f_fee,f_account_id,f_category_id,f_date,f_note) VALUES (?,?,?,?,?,?,?)",
                type, amount, fee, account, category, month + "-15T12:00:00", "不应发送的备注");
    }
    @Test void precisionCurrenciesFeesBudgetsAndBrokenChains() {
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
        Snapshot s = service.generate("2024-02", false).snapshot();
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
    @Test void leapMonthRangeAndThreeCalendarMonths() {
        tx("2023-11", "expense", "30", 1, null, "0");
        tx("2023-12", "expense", "60", 1, null, "0");
        tx("2024-01", "income", "20", 1, null, "0");
        jdbc.update("INSERT INTO t_transaction(f_type,f_amount,f_account_id,f_date) VALUES ('expense','90',1,'2024-02-29T23:59:59.999'),('expense','999',1,'2024-03-01T00:00:00')");
        CurrencySummary c = service.generate("2024-02", false).snapshot().currencies().get(0);
        assertEquals("90.00", c.expense());
        assertEquals("0.00", c.previousExpense());
        assertNull(c.growth());
        assertEquals("30.00", c.historyAverage());
        assertEquals(1, service.generate("2023-12", false).snapshot().count());
        assertThrows(RuntimeException.class, () -> service.generate(YearMonth.now().toString(), false));
    }
    @Test void migrationRepeatConcurrentGenerateAndStaleness() throws Exception {
        tx("2024-02", "expense", "20", 1, null, "0");
        init(); init();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Detail>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> service.generate("2024-02", false)));
            for (Future<Detail> f : tasks) assertEquals(1, f.get().version());
        } finally { pool.shutdownNow(); }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM t_monthly_report", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM t_message WHERE f_type='monthly_report'", Integer.class));
        Detail d = service.generate("2024-02", false);
        tx("2024-01", "expense", "10", 1, null, "0");
        assertTrue(service.detail(d.id()).stale());
        assertEquals(1, service.generate("2024-02", false).version());
        assertEquals(2, service.generate("2024-02", true).version());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM t_message", Integer.class));
        assertEquals(2, service.generate("2024-02", true).version());
    }
    @Test void jobsCannotReplayOrOverwriteNewSnapshot() {
        String month = YearMonth.now().minusMonths(1).toString();
        tx(month, "expense", "20", 1, null, "0");
        service.generateLatest(); service.generateLatest();
        Detail d = service.generate(month, false);
        ClaimRequest req = new ClaimRequest(d.id(), 1, config, true, false);
        Claim first = service.claim(req);
        assertNotNull(first);
        assertNull(service.claim(req));
        assertFalse(service.complete(new Completion(first.job().id(), 1, config + "other", null, "HTTP_401")));
        assertTrue(service.complete(new Completion(first.job().id(), 1, config, null, "TIMEOUT")));
        assertNull(service.claim(req));
        Claim retry = service.claim(new ClaimRequest(d.id(), 1, config, false, true));
        assertEquals(2, retry.job().attempt());
        tx(month, "expense", "5", 1, null, "0");
        service.generate(month, true);
        assertFalse(service.complete(new Completion(retry.job().id(), 1, config, null, "HTTP_500")));
        assertEquals("interrupted", repo.job(retry.job().id()).status());
        assertNull(service.claim(new ClaimRequest(d.id(), 2, config, true, false)));
    }
    @Test void crashExpiresAndInvalidFactsRejected() throws Exception {
        tx("2024-02", "expense", "20", 1, null, "0");
        Detail d = service.generate("2024-02", false);
        Claim c = service.claim(new ClaimRequest(d.id(), 1, config, false, false));
        var result = json.readTree("{\"summary\":\"摘要\",\"limitations\":\"记录有限\",\"observations\":[{\"text\":\"发现\",\"factIds\":[\"不存在\"]}],\"actions\":[],\"provider\":\"example.test\",\"model\":\"mock\",\"generatedAt\":\"2024-03-01\"}");
        assertThrows(RuntimeException.class, () -> service.complete(new Completion(c.job().id(), 1, config, result, null)));
        ((tools.jackson.databind.node.ArrayNode) result.at("/observations/0/factIds")).set(0, json.getNodeFactory().stringNode(d.snapshot().facts().get(0).id()));
        assertTrue(service.complete(new Completion(c.job().id(), 1, config, result, null)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM t_message", Integer.class));
        Claim retry = service.claim(new ClaimRequest(d.id(), 1, config, false, true));
        jdbc.update("UPDATE t_monthly_report_ai_job SET f_created_at='2000-01-01' WHERE f_id=?", retry.job().id());
        assertEquals("interrupted", service.detail(d.id()).ai().status());
        assertFalse(service.complete(new Completion(retry.job().id(), 1, config, result, null)));
        assertEquals(c.job().id(), service.detail(d.id()).successfulAi().id());
        tx("2024-02", "expense", "5", 1, null, "0");
        assertNull(service.generate("2024-02", true).successfulAi());
    }
    @Test void emptyMonthDoesNotNotifyOrSubmit() {
        Detail d = service.generate("2024-02", false);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM t_message", Integer.class));
        assertNull(service.claim(new ClaimRequest(d.id(), 1, config, false, false)));
    }
    @Test void rangeDrillMatchesCurrencyCategoryAndMonthEnd() {
        jdbc.update("INSERT INTO t_category(f_id,f_name,f_parent_id,f_type,f_is_archived) VALUES (1001,'归档一级',NULL,'expense',1),(1002,'归档子类',1001,'expense',1)");
        jdbc.update("UPDATE t_account SET f_currency=' CNY ' WHERE f_id=1");
        tx("2024-02", "expense", "0.10", 1, 1002, "0");
        tx("2024-02", "expense", "0.20", 1, null, "0");
        tx("2024-02", "expense", "90.00", 2, 1002, "0");
        tx("2024-02", "expense", "5.00", 3, null, "0");
        jdbc.update("INSERT INTO t_transaction(f_type,f_amount,f_account_id,f_date) VALUES ('expense','0.30',1,'2024-02-29T23:59:59.999'),('expense','999',1,'2024-03-01T00:00:00')");
        MonthlyTransactionController controller = new MonthlyTransactionController(jdbc);
        var response = json.valueToTree(controller.range("2024-02-01", "2024-02-29", "CNY"));
        assertEquals("S0806", response.path("code").asString());
        var rows = response.path("data");
        assertEquals(3, rows.size());
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (var row : rows) {
            assertTrue(row.path("amount").isString());
            total = total.add(new java.math.BigDecimal(row.path("amount").asString()));
            if (!row.path("categoryId").isNull() && row.path("categoryId").asInt() == 1002) {
                assertEquals(1001, row.path("monthlyRootId").asInt());
                assertTrue(row.path("categoryPath").toString().contains("1001"));
            } else assertEquals(0, row.path("monthlyRootId").asInt());
        }
        var cny = service.generate("2024-02", false).snapshot().currencies().stream().filter(c -> c.currency().equals("CNY")).findFirst().orElseThrow();
        assertEquals(0, total.compareTo(new java.math.BigDecimal(cny.expense())));
        assertEquals(1, json.valueToTree(controller.range("2024-02-01", "2024-02-29", "UNKNOWN")).path("data").size());
        assertThrows(RuntimeException.class, () -> controller.range("2023-01-01", "2024-02-01", "CNY"));
        assertThrows(RuntimeException.class, () -> controller.range("2024-02-30", "2024-03-01", "CNY"));
    }
    @Test void growthFrequencyThresholdsAndStableFacts() {
        jdbc.update("INSERT INTO t_category(f_id,f_name,f_type) VALUES (1001,'正常消费','expense'),(1002,'小额增长','expense')");
        for (int i = 0; i < 6; i++) tx("2024-01", "expense", "10", 1, 1001, "0");
        for (int i = 0; i < 8; i++) tx("2024-02", "expense", "10", 1, 1001, "0");
        tx("2024-01", "expense", "1", 1, 1002, "0");
        tx("2024-02", "expense", "2", 1, 1002, "0");
        var s = service.generate("2024-02", false).snapshot();
        assertTrue(s.facts().stream().anyMatch(f -> f.id().equals("CNY:category:1001:growth")));
        assertTrue(s.facts().stream().anyMatch(f -> f.id().equals("CNY:category:1001:frequency")));
        assertFalse(s.facts().stream().anyMatch(f -> f.id().equals("CNY:category:1002:growth")));
        assertEquals(s.facts(), new MonthlyReportCalculator().calculate(YearMonth.of(2024, 2), repo.source(YearMonth.of(2024, 2))).facts());
        assertEquals(s.facts().size(), s.facts().stream().map(Fact::id).distinct().count());
        assertTrue(s.currencies().get(0).categories().get(0).largeExpenses().isEmpty());
    }
    @Test void fingerprintOnlyTracksRelevantCategoriesAndCurrency() {
        jdbc.update("INSERT INTO t_category(f_id,f_name,f_type) VALUES (1001,'有关','expense'),(1002,'无关','expense')");
        tx("2023-10", "income", "1", 1, null, "0");
        tx("2024-02", "expense", "20", 1, 1001, "0");
        Detail d = service.generate("2024-02", false);
        jdbc.update("UPDATE t_category SET f_name='无关改名' WHERE f_id=1002");
        tx("2020-01", "income", "1", 1, null, "0");
        assertFalse(service.detail(d.id()).stale());
        jdbc.update("UPDATE t_category SET f_name='有关改名' WHERE f_id=1001");
        assertTrue(service.detail(d.id()).stale());
        service.generate("2024-02", true);
        jdbc.update("UPDATE t_account SET f_currency='DOLLAR' WHERE f_id=1");
        assertTrue(service.detail(d.id()).stale());
    }
    @Test void failedRefreshRollsBackSnapshotAndMessageTogether() {
        tx("2024-02", "expense", "20", 1, null, "0");
        Detail d = service.generate("2024-02", false);
        tx("2024-02", "expense", "5", 1, null, "0");
        jdbc.execute("CREATE TRIGGER fail_message BEFORE UPDATE ON t_message BEGIN SELECT RAISE(ABORT,'test'); END");
        assertThrows(RuntimeException.class, () -> service.generate("2024-02", true));
        assertEquals(1, repo.byId(d.id()).version());
        assertEquals("20.00", repo.byId(d.id()).snapshot().currencies().get(0).expense());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM t_message", Integer.class));
    }
    @Test void internalGuardRejectsMissingSecretOriginAndRemote() throws Exception {
        String secret = "s".repeat(64);
        for (String scenario : List.of("ok", "missing", "origin", "remote", "disabled")) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/internal/monthly-report/latest");
            req.setRemoteAddr(scenario.equals("remote") ? "192.168.1.1" : "127.0.0.1");
            if (!scenario.equals("missing")) req.addHeader("X-Monthly-Session", secret);
            if (scenario.equals("origin")) req.addHeader("Origin", "http://127.0.0.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            new MonthlyReportInternalGuard(scenario.equals("disabled") ? "" : secret).doFilter(req, resp, (a, b) -> b.getWriter().write("ok"));
            assertEquals(scenario.equals("ok") ? 200 : 403, resp.getStatus());
        }
    }
}
