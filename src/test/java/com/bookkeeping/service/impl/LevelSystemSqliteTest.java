package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.bookkeeping.constant.ExpTransactionType;
import com.bookkeeping.constant.TransactionType;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.dao.mapper.*;
import com.bookkeeping.dao.mapping.*;
import com.bookkeeping.service.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.sf.dao.config.SelfMetaObjectHandler;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用正式建表脚本、MyBatis 查询及 Spring 事务；仅连接临时 SQLite，不访问用户账本。 */
class LevelSystemSqliteTest {
    @TempDir
    Path directory;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private LevelConfigServiceImpl configs;
    private UserLevelService levels;
    private CheckInService checkins;
    private TransactionServiceImpl transactions;
    private BudgetServiceImpl budget;
    private MessageService messages;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("levels.db") + "?busy_timeout=10000");
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource("init.sql"));
        schema.setSqlScriptEncoding("UTF-8");
        schema.setContinueOnError(true);
        schema.execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig()
                .setDbConfig(new GlobalConfig.DbConfig()).setMetaObjectHandler(new SelfMetaObjectHandler()));
        configuration.addMapper(LevelConfigMapper.class);
        configuration.addMapper(UserLevelMapper.class);
        configuration.addMapper(ExpTransactionMapper.class);
        configuration.addMapper(ExperienceLogMapper.class);
        configuration.addMapper(CheckInMapper.class);
        configuration.addMapper(TransactionMapper.class);
        configuration.addMapper(BudgetMapper.class);
        configuration.addMapper(AccountMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        configs = new LevelConfigServiceImpl(Mappers.getMapper(LevelConfigMapping.class));
        ReflectionTestUtils.setField(configs, "baseMapper", session.getMapper(LevelConfigMapper.class));
        ExpTransactionServiceImpl exp = new ExpTransactionServiceImpl(Mappers.getMapper(ExpTransactionMapping.class));
        ReflectionTestUtils.setField(exp, "baseMapper", session.getMapper(ExpTransactionMapper.class));
        ExperienceLogServiceImpl logs = new ExperienceLogServiceImpl(Mappers.getMapper(ExperienceLogMapping.class));
        ReflectionTestUtils.setField(logs, "baseMapper", session.getMapper(ExperienceLogMapper.class));
        transactions = new TransactionServiceImpl(Mappers.getMapper(TransactionMapping.class));
        ReflectionTestUtils.setField(transactions, "baseMapper", session.getMapper(TransactionMapper.class));
        budget = new BudgetServiceImpl(Mappers.getMapper(BudgetMapping.class));
        ReflectionTestUtils.setField(budget, "baseMapper", session.getMapper(BudgetMapper.class));
        UserLevelServiceImpl target = new UserLevelServiceImpl(Mappers.getMapper(UserLevelMapping.class));
        ReflectionTestUtils.setField(target, "baseMapper", session.getMapper(UserLevelMapper.class));
        ReflectionTestUtils.setField(target, "configService", configs);
        ReflectionTestUtils.setField(target, "expTransactionService", exp);
        ReflectionTestUtils.setField(target, "experienceLogService", logs);
        ReflectionTestUtils.setField(target, "budgetService", budget);
        ReflectionTestUtils.setField(target, "transactionService", transactions);
        messages = mock(MessageService.class);
        ReflectionTestUtils.setField(target, "messageService", messages);
        levels = transactional(target);
        CheckInServiceImpl checkinTarget = new CheckInServiceImpl(Mappers.getMapper(CheckInMapping.class));
        ReflectionTestUtils.setField(checkinTarget, "baseMapper", session.getMapper(CheckInMapper.class));
        ReflectionTestUtils.setField(checkinTarget, "levelService", levels);
        ReflectionTestUtils.setField(checkinTarget, "messageService", messages);
        checkins = transactional(checkinTarget);
        AccountServiceImpl accounts = new AccountServiceImpl(Mappers.getMapper(AccountMapping.class));
        ReflectionTestUtils.setField(accounts, "baseMapper", session.getMapper(AccountMapper.class));
        ReflectionTestUtils.setField(transactions, "accountService", accounts);
        ReflectionTestUtils.setField(transactions, "budgetService", budget);
        ReflectionTestUtils.setField(transactions, "userLevelService", levels);
        transactions = transactional(transactions);
        ReflectionTestUtils.setField(budget, "transactionService", transactions);
        ReflectionTestUtils.setField(budget, "categoryService", mock(CategoryService.class));
        ReflectionTestUtils.setField(budget, "messageService", messages);
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    @Test
    void thresholds_useNumericOrder_atEveryBoundary() {
        int[] thresholds = {0, 100, 300, 600, 1000, 1500, 2100, 2800, 3600, 4500,
                5500, 6600, 7800, 9100, 10500, 12000, 13600, 15300, 17100, 19000};
        assertEquals(1, configs.promotionLevel(20).getLevel());
        for (int i = 0; i < thresholds.length; i++) {
            assertEquals(i + 1, configs.promotionLevel(thresholds[i]).getLevel());
            if (i > 0) assertEquals(i, configs.promotionLevel(thresholds[i] - 1).getLevel());
        }
        assertEquals(1, configs.promotionLevel(null).getLevel());
        assertEquals(1, configs.promotionLevel(-200).getLevel());
        assertEquals(20, configs.promotionLevel(Integer.MAX_VALUE).getLevel());
    }

    @Test
    void holder_doesNotDependOnDatabaseRowOrder() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.execute("PRAGMA reverse_unordered_selects = ON");
            var holder = configs.selectLevelHolder(2);
            assertEquals(2, holder.currentLevel());
            assertEquals(3, holder.nextLevel());
            assertNull(configs.selectLevelHolder(20).nextLevel());
            assertEquals(1, configs.selectAll().get(0).getLevel());
            assertThrows(IllegalStateException.class, () -> configs.selectLevelHolder(99));
        });
    }

    @Test
    void experience_capAndFloor_keepTotalsAndLedgersConsistent() {
        jdbc.update("UPDATE t_user_level SET f_level=19, f_experience=18998, f_total_earned='18998'");
        levels.gainExperience(ExpTransactionType.CHECK_IN, 0, "签到", 60);
        assertUser(20, 19000, 19000, 0);
        assertEquals(2, jdbc.queryForObject("SELECT f_exp_change FROM t_exp_transaction ORDER BY f_id DESC LIMIT 1", Integer.class));
        levels.gainExperience(ExpTransactionType.BUDGET, 0, "扣除", -20000);
        assertUser(1, 0, 19000, 19000);
        assertEquals(-19000, jdbc.queryForObject("SELECT f_exp_change FROM t_exp_transaction ORDER BY f_id DESC LIMIT 1", Integer.class));
    }

    @Test
    void recordReward_doesNotTrustHistoricallyIncorrectLevel() {
        jdbc.update("UPDATE t_user_level SET f_level=20, f_experience=20");
        assertFalse(levels.hitMaxLevel());
        assertEquals(1, levels.selectUserLevel().getLevel());
        assertEquals(8, levels.gainRecordExperience());
        assertUser(1, 28, 8, 0);
    }

    @Test
    void recordReward_returnsActualAmountNearCap() {
        jdbc.update("UPDATE t_user_level SET f_level=19, f_experience=18998");
        assertEquals(2, levels.gainRecordExperience());
        assertEquals(0, levels.gainRecordExperience());
        assertEquals(2, jdbc.queryForObject("SELECT SUM(f_exp_change) FROM t_exp_transaction", Integer.class));
    }

    @Test
    void rewardFailure_rollsBackWithoutPoisoningBookkeepingTransaction() {
        jdbc.update("UPDATE t_user_level SET f_experience=99");
        doThrow(new IllegalStateException("模拟消息落库失败")).when(messages)
                .pushMessage(anyString(), anyString(), isNull(), any(), any());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO t_tag(f_name) VALUES ('保留外层写入')");
            assertThrows(IllegalStateException.class, () -> levels.gainRecordExperience());
        });
        assertUser(1, 99, 0, 0);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_exp_transaction", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_tag WHERE f_name='保留外层写入'", Integer.class));
    }

    @Test
    void monthlyExpense_coversWholeLeapMonthAndExcludesIncomeAndTransfers() {
        jdbc.update("INSERT INTO t_account(f_id, f_name, f_type) VALUES (1, '测试账户', 'cash')");
        addTransaction("expense", "10.10", "2024-02-01 00:00:00");
        addTransaction("expense", "20.20", "2024-02-29 23:59:59");
        addTransaction("expense", "500", "2024-01-31 23:59:59");
        addTransaction("expense", "500", "2024-03-01 00:00:00");
        addTransaction("income", "5000", "2024-02-10 10:00:00");
        addTransaction("transfer", "5000", "2024-02-10 10:00:00");
        assertEquals(0, new BigDecimal("30.30").compareTo(transactions.selectBeforeExpenseAmount(LocalDate.of(2024, 2, 10))));
    }

    @Test
    void checkinNearCap_recordsActualRewardAndIsIdempotent() {
        jdbc.update("UPDATE t_user_level SET f_level=19, f_experience=18998");
        checkins.dailyCheckIn();
        checkins.dailyCheckIn();
        assertEquals(2, jdbc.queryForObject("SELECT f_exp_reward FROM t_check_in", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_check_in", Integer.class));
        assertUser(20, 19000, 2, 0);
    }

    @Test
    void checkinFailure_rollsBackCheckinAndExperienceTogether() {
        doThrow(new IllegalStateException("模拟消息落库失败")).when(messages)
                .pushMessage(anyString(), anyString(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> checkins.dailyCheckIn());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_check_in", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_exp_transaction", Integer.class));
        assertUser(1, 0, 0, 0);
    }

    @Test
    void monthlySettlement_usesTotalBudgetOnly_andRecordsActualDeductionOnce() {
        prepareMonth("1000", "1200");
        LocalDate month = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        jdbc.update("INSERT INTO t_budget(f_category_id,f_amount,f_year,f_month) VALUES (1,'500',?,?)", month.getYear(), month.getMonthValue());
        jdbc.update("UPDATE t_user_level SET f_level=2,f_experience=100,f_total_earned='100'");
        levels.monthlyLevelChange();
        levels.monthlyLevelChange();
        assertUser(1, 0, 100, 100);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_experience_log", Integer.class));
        assertEquals(-100, jdbc.queryForObject("SELECT f_exp_change FROM t_experience_log", Integer.class));
        assertEquals("1000", jdbc.queryForObject("SELECT f_budget_amount FROM t_experience_log", String.class));
        verify(messages).pushMessage(eq("月末预算超支"), contains("已扣除 100 经验"), isNull(), any(), any());
    }

    @Test
    void monthlySettlement_savingRewards500() {
        prepareMonth("1000", "900");
        levels.monthlyLevelChange();
        assertUser(3, 500, 500, 0);
        assertEquals(500, jdbc.queryForObject("SELECT f_exp_change FROM t_experience_log", Integer.class));
    }

    @Test
    void monthlySettlement_exactBudget_stillRecordsSettlementWithoutReward() {
        prepareMonth("1000", "1000");
        levels.monthlyLevelChange();
        levels.monthlyLevelChange();
        assertUser(1, 0, 0, 0);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_experience_log", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT f_exp_change FROM t_experience_log", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_exp_transaction", Integer.class));
    }

    @Test
    void monthlySettlement_logFailureRollsBackReward() {
        prepareMonth("1000", "900");
        jdbc.execute("CREATE TRIGGER reject_month BEFORE INSERT ON t_experience_log BEGIN SELECT RAISE(ABORT, '模拟结算失败'); END");
        assertThrows(RuntimeException.class, () -> levels.monthlyLevelChange());
        assertUser(1, 0, 0, 0);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_exp_transaction", Integer.class));
    }

    @Test
    void recordReward_dailyLimit_andYesterdayDoesNotCount() {
        jdbc.update("INSERT INTO t_exp_transaction(f_source,f_exp_change,f_create_time) VALUES ('record','15',?)", LocalDate.now().minusDays(1).atStartOfDay().toString());
        assertEquals(8, levels.gainRecordExperience());
        for (int i = 0; i < 7; i++) assertEquals(1, levels.gainRecordExperience());
        assertEquals(0, levels.gainRecordExperience());
        assertUser(1, 15, 15, 0);
    }

    @Test
    void startup_reconcilesWrongStoredLevelWithoutReward() {
        jdbc.update("UPDATE t_user_level SET f_level=20, f_experience=20");
        levels.gainExperience(ExpTransactionType.OTHER, 0, "等级校准", 0);
        assertUser(1, 20, 0, 0);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_exp_transaction", Integer.class));
        verifyNoInteractions(messages);
    }

    @Test
    void applicationReady_runsReconciliationSettlementAndCheckinThroughProxies() {
        jdbc.update("UPDATE t_user_level SET f_level=20, f_experience=20");
        try (var context = new AnnotationConfigApplicationContext()) {
            // 复用已经装配完依赖的事务代理，避免测试容器再次注入其内部字段。
            context.getBeanFactory().registerSingleton("userLevelService", levels);
            context.getBeanFactory().registerSingleton("checkInService", checkins);
            context.registerBean(WeatherService.class, () -> mock(WeatherService.class));
            context.registerBean(GreetingService.class, () -> mock(GreetingService.class));
            context.registerBean(TaskServiceImpl.class);
            context.refresh();
            var ready = new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO);
            context.publishEvent(ready);
            context.publishEvent(ready);
        }
        assertUser(1, 30, 10, 0);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_check_in", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_exp_transaction", Integer.class));
    }

    @Test
    void concurrentRecordRewards_doNotExceedDailyLimit() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        var rewards = new ArrayList<Future<Integer>>();
        try {
            for (int i = 0; i < 20; i++) {
                rewards.add(executor.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return levels.gainRecordExperience();
                }));
            }
            start.countDown();
            int total = 0;
            for (var reward : rewards) total += reward.get(20, TimeUnit.SECONDS);
            assertEquals(15, total);
            assertUser(1, 15, 15, 0);
            assertEquals(15, jdbc.queryForObject("SELECT SUM(f_exp_change) FROM t_exp_transaction", Integer.class));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentMonthlySettlement_onlyRewardsOnce() throws Exception {
        prepareMonth("1000", "900");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Runnable settle = () -> {
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                levels.monthlyLevelChange();
            };
            var first = executor.submit(settle);
            var second = executor.submit(settle);
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
            assertUser(3, 500, 500, 0);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_experience_log", Integer.class));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void backfill_preservesHistoricalDates_andAppliesAllBalanceEffects() {
        prepareAccounts();
        LocalDateTime occurred = LocalDateTime.of(2024, 2, 29, 23, 59, 58);
        TransactionDTO expense = backfill(TransactionType.EXPENSE, "30.30", occurred);
        TransactionDTO income = backfill(TransactionType.INCOME, "100.10", occurred.minusDays(1));
        TransactionDTO transfer = backfill(TransactionType.TRANSFER, "20", occurred.minusMonths(2));
        transfer.setToAccountId(2);
        transfer.setFee(new BigDecimal("0.50"));
        assertEquals(8, transactions.saveWithReward(expense));
        assertEquals(1, transactions.saveWithReward(income));
        assertEquals(1, transactions.saveWithReward(transfer));
        assertEquals(occurred, transactions.detail(1).getTransactionDate());
        assertEquals(income.getTransactionDate(), transactions.detail(2).getTransactionDate());
        assertEquals(transfer.getTransactionDate(), transactions.detail(3).getTransactionDate());
        assertBalance(1, "1049.30");
        assertBalance(2, "520");
        assertEquals(0, new BigDecimal("30.30").compareTo(transactions.selectBeforeExpenseAmount(occurred.toLocalDate())));
        var summary = transactions.summary(occurred.toLocalDate().withDayOfMonth(1).atStartOfDay(), occurred);
        assertEquals(0, new BigDecimal("100.10").compareTo(summary.income()));
        assertEquals(0, new BigDecimal("30.30").compareTo(summary.expense()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_check_in", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_experience_log", Integer.class));
    }

    @Test
    void backfill_sharesTodaysRewardLimitWithOrdinaryRecords() {
        prepareAccounts();
        LocalDateTime historical = LocalDate.now().minusYears(1).atTime(12, 30);
        jdbc.update("INSERT INTO t_exp_transaction(f_source,f_exp_change,f_create_time) VALUES ('record','15',?)", historical.toString());
        assertEquals(8, transactions.saveWithReward(backfill(TransactionType.INCOME, "1", LocalDateTime.now())));
        for (int i = 0; i < 7; i++) {
            assertEquals(1, transactions.saveWithReward(backfill(TransactionType.INCOME, "1", historical.minusDays(i))));
        }
        assertEquals(0, transactions.saveWithReward(backfill(TransactionType.INCOME, "1", historical.minusMonths(1))));
        assertEquals(0, transactions.saveWithReward(backfill(TransactionType.INCOME, "1", LocalDateTime.now())));
        assertUser(1, 15, 15, 0);
        assertBalance(1, "1010");
        assertEquals(15, jdbc.queryForObject("SELECT SUM(f_exp_change) FROM t_exp_transaction WHERE f_create_time >= ? AND f_create_time < ?",
                Integer.class, LocalDate.now().toString(), LocalDate.now().plusDays(1).toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_exp_transaction WHERE f_create_time < ?",
                Integer.class, LocalDate.now().toString()));
    }

    @Test
    void backfill_updatesBudgetUsageWithoutRewritingClosedSettlement() {
        prepareMonth("1000", "900");
        LocalDate month = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        levels.monthlyLevelChange();
        var closedLog = jdbc.queryForList("SELECT * FROM t_experience_log");
        var closedExperience = jdbc.queryForList("SELECT * FROM t_exp_transaction WHERE f_source='budget'");
        assertEquals(8, transactions.saveWithReward(backfill(TransactionType.EXPENSE, "200", month.atTime(12, 30))));
        assertEquals(closedLog, jdbc.queryForList("SELECT * FROM t_experience_log"));
        assertEquals(closedExperience, jdbc.queryForList("SELECT * FROM t_exp_transaction WHERE f_source='budget'"));
        levels.monthlyLevelChange();
        assertEquals(closedLog, jdbc.queryForList("SELECT * FROM t_experience_log"));
        assertEquals(closedExperience, jdbc.queryForList("SELECT * FROM t_exp_transaction WHERE f_source='budget'"));
        assertUser(3, 508, 508, 0);
        assertEquals(0, new BigDecimal("1100").compareTo(budget.selectBudgetInfoByYearMonth(month.getYear(), month.getMonthValue()).get(0).amountUsed()));
        assertBalance(1, "-200");
    }

    @Test
    void backfill_isIncludedWhenMonthHasNotYetSettled() {
        prepareMonth("1000", "900");
        LocalDate month = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        assertEquals(8, transactions.saveWithReward(backfill(TransactionType.EXPENSE, "200", month.atTime(12, 30))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_experience_log", Integer.class));
        levels.monthlyLevelChange();
        assertEquals("1100", jdbc.queryForObject("SELECT f_actual_amount FROM t_experience_log", String.class));
        assertEquals(-8, jdbc.queryForObject("SELECT f_exp_change FROM t_experience_log", Integer.class));
        assertUser(1, 0, 8, 8);
    }

    @Test
    void backfill_failedBalanceWriteRollsBackRecord_withoutReward() {
        prepareAccounts();
        jdbc.execute("CREATE TRIGGER reject_balance BEFORE UPDATE ON t_account BEGIN SELECT RAISE(ABORT, '模拟余额写入失败'); END");
        assertThrows(RuntimeException.class, () -> transactions.saveWithReward(
                backfill(TransactionType.EXPENSE, "20", LocalDate.now().minusMonths(1).atTime(12, 30))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_transaction", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_exp_transaction", Integer.class));
        assertBalance(1, "1000");
        assertUser(1, 0, 0, 0);
    }

    private TransactionDTO backfill(TransactionType type, String amount, LocalDateTime occurred) {
        TransactionDTO dto = new TransactionDTO();
        dto.setType(type);
        dto.setAmount(new BigDecimal(amount));
        dto.setFee(BigDecimal.ZERO);
        dto.setAccountId(1);
        dto.setTransactionDate(occurred);
        dto.setNote("原始备注");
        dto.setTags("[]");
        if (type != TransactionType.TRANSFER) {
            dto.setCategoryId(jdbc.queryForObject("SELECT f_id FROM t_category WHERE f_type=? LIMIT 1", Integer.class, type.getValue()));
        }
        return dto;
    }

    private void prepareAccounts() {
        jdbc.update("INSERT INTO t_account(f_id,f_name,f_type,f_current_balance) VALUES (1,'补录账户','cash','1000'),(2,'转入账户','bank','500')");
    }

    private void assertBalance(int id, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(jdbc.queryForObject("SELECT f_current_balance FROM t_account WHERE f_id=?", BigDecimal.class, id)));
    }

    private void prepareMonth(String budget, String expense) {
        LocalDate month = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        jdbc.update("INSERT INTO t_account(f_id, f_name, f_type) VALUES (1, '测试账户', 'cash')");
        jdbc.update("INSERT INTO t_budget(f_amount,f_year,f_month) VALUES (?,?,?)", budget, month.getYear(), month.getMonthValue());
        addTransaction("expense", expense, month.atStartOfDay().toString());
    }

    private void addTransaction(String type, String amount, String date) {
        jdbc.update("INSERT INTO t_transaction(f_type,f_amount,f_account_id,f_date) VALUES (?,?,1,?)", type, amount, date);
    }

    private void assertUser(int level, int experience, int earned, int spent) {
        var row = jdbc.queryForMap("SELECT * FROM t_user_level WHERE f_id=1");
        assertEquals(level, ((Number) row.get("f_level")).intValue());
        assertEquals(experience, ((Number) row.get("f_experience")).intValue());
        assertEquals(earned, Integer.parseInt(row.get("f_total_earned").toString()));
        assertEquals(spent, Integer.parseInt(row.get("f_total_spent").toString()));
    }
}
