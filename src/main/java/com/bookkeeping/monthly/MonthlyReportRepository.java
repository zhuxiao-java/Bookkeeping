package com.bookkeeping.monthly;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bookkeeping.dao.entity.AccountEntity;
import com.bookkeeping.dao.entity.BudgetEntity;
import com.bookkeeping.dao.entity.CategoryEntity;
import com.bookkeeping.dao.entity.MessageEntity;
import com.bookkeeping.dao.entity.MonthlyReportEntity;
import com.bookkeeping.dao.entity.PeriodReportEntity;
import com.bookkeeping.dao.mapper.PeriodReportMapper;
import org.springframework.beans.factory.annotation.Autowired;
import com.bookkeeping.dao.entity.TransactionEntity;
import com.bookkeeping.dao.mapper.AccountMapper;
import com.bookkeeping.dao.mapper.BudgetMapper;
import com.bookkeeping.dao.mapper.CategoryMapper;
import com.bookkeeping.dao.mapper.MessageMapper;
import com.bookkeeping.dao.mapper.MonthlyReportMapper;
import com.bookkeeping.dao.mapper.TransactionMapper;
import com.bookkeeping.constant.MessageBizType;
import com.bookkeeping.constant.MessageStatus;
import com.bookkeeping.constant.MessageType;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bookkeeping.monthly.MonthlyReportModels.*;

/**
 * 月报持久化仓储：全部改用 MyBatis-Plus mapper + QueryWrapper，彻底移除 JdbcTemplate。
 * <p>
 * 职责：
 * 1) 实体 {@link MonthlyReportEntity} 与领域模型 {@link StoredReport} 互转，
 *    并负责快照/结果 JSON 的序列化；
 * 2) 组装统计所需的来源数据 {@link Source}（流水 + 账户币种 + 分类 + 预算），复用既有实体的 mapper 读取；
 * 3) 月报关联消息的创建与更新，复用 {@link MessageMapper}。
 *
 * @author zhuxiao
 */
@Repository
public class MonthlyReportRepository {
    private final MonthlyReportMapper reportMapper;
    private final TransactionMapper transactionMapper;
    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;
    private final BudgetMapper budgetMapper;
    private final MessageMapper messageMapper;
    private final ObjectMapper json;
    private final PeriodReportMapper periodMapper;

    public MonthlyReportRepository(MonthlyReportMapper reportMapper,
                                   TransactionMapper transactionMapper, AccountMapper accountMapper,
                                   CategoryMapper categoryMapper, BudgetMapper budgetMapper,
                                   MessageMapper messageMapper, ObjectMapper json) {
        this(reportMapper, transactionMapper, accountMapper, categoryMapper, budgetMapper, messageMapper, json, null);
    }

    @Autowired
    public MonthlyReportRepository(MonthlyReportMapper reportMapper, TransactionMapper transactionMapper,
                                   AccountMapper accountMapper, CategoryMapper categoryMapper, BudgetMapper budgetMapper,
                                   MessageMapper messageMapper, ObjectMapper json, PeriodReportMapper periodMapper) {
        this.periodMapper = periodMapper;
        this.reportMapper = reportMapper;
        this.transactionMapper = transactionMapper;
        this.accountMapper = accountMapper;
        this.categoryMapper = categoryMapper;
        this.budgetMapper = budgetMapper;
        this.messageMapper = messageMapper;
        this.json = json;
    }

    // ==================== JSON 编解码 ====================

    /**
     * 统一序列化入口：快照、来源指纹、AI 结果均经此写入文本列。
     */
    public String encode(Object value) {
        return json.writeValueAsString(value);
    }

    private <T> T decode(String value, Class<T> type) {
        return json.readValue(value, type);
    }

    // ==================== 来源数据组装 ====================

    /**
     * 读取指定月份及其前 3 个月的来源数据（供计算器统计环比/三月均值）。
     * <p>
     * 与原 JdbcTemplate 实现口径一致：流水取 [month-3 月初, month+1 月初) 左闭右开区间，
     * 币种来自账户关联（缺失记为 null→UNKNOWN），并只保留与本次流水/预算相关的分类链，避免上传无关分类。
     */
    public Source source(YearMonth month) {
        return source(ReportPeriod.of("month", month.toString()));
    }

    public Source source(ReportPeriod period) {
        String start = period.sourceStart().toString();
        String end = period.endExclusive().toString();
        //  accountId -> 币种代码：一次性载入账户表，避免逐条 join
        Map<Integer, String> currencyByAccount = new HashMap<>();
        for (AccountEntity account : accountMapper.selectList(null)) {
            currencyByAccount.put(account.getId(), account.getCurrency() == null ? null : account.getCurrency().getValue());
        }
        List<TransactionEntity> txEntities = transactionMapper.selectList(new QueryWrapper<TransactionEntity>()
                .ge("f_date", start).lt("f_date", end).orderByAsc("f_id"));
        List<Tx> transactions = new ArrayList<>(txEntities.size());
        for (TransactionEntity t : txEntities) {
            transactions.add(new Tx(t.getId().longValue(), t.getType().getValue(), plain(t.getAmount()), plain(t.getFee()),
                    t.getCategoryId(), t.getAccountId(), currencyByAccount.get(t.getAccountId()),
                    t.getTransactionDate().toLocalDate().toString()));
        }
        List<CategoryEntity> categoryEntities = categoryMapper.selectList(new QueryWrapper<CategoryEntity>().orderByAsc("f_id"));
        Map<Integer, Category> categoryById = new HashMap<>();
        List<Category> allCategories = new ArrayList<>();
        for (CategoryEntity c : categoryEntities) {
            Category category = new Category(c.getId(), c.getParentId(), c.getName(), c.getType() == null ? null : c.getType().getValue());
            categoryById.put(category.id(), category);
            allCategories.add(category);
        }
        List<BudgetEntity> budgetEntities = "week".equals(period.type()) ? List.of() : budgetMapper.selectList(new QueryWrapper<BudgetEntity>()
                .eq("f_year", period.start().getYear())
                .eq("month".equals(period.type()), "f_month", period.start().getMonthValue()).orderByAsc("f_id"));
        List<Budget> budgets = new ArrayList<>();
        for (BudgetEntity b : budgetEntities) {
            budgets.add(new Budget(b.getId(), b.getCategoryId(), plain(b.getAmount()),
                    "year".equals(period.type()) ? YearMonth.of(b.getYear(), b.getMonth()).toString() : null));
        }
        String firstMonth = "month".equals(period.type()) ? reportMapper.firstTransactionMonth() : null;
        String historyStart = YearMonth.from(period.sourceStart()).toString();
        if (firstMonth != null && firstMonth.compareTo(historyStart) < 0) firstMonth = historyStart;
        // 仅保留被流水/预算引用到的分类及其全部父级，收敛发送给模型的数据面
        List<Category> relevant = filterRelevant(allCategories, categoryById, transactions, budgets);
        return new Source(transactions, relevant, budgets, firstMonth);
    }

    private static List<Category> filterRelevant(List<Category> all, Map<Integer, Category> byId,
                                                 List<Tx> transactions, List<Budget> budgets) {
        Set<Integer> relevant = new HashSet<>();
        List<Integer> references = new ArrayList<>();
        transactions.forEach(t -> references.add(t.categoryId()));
        budgets.forEach(b -> references.add(b.categoryId()));
        for (Integer id : references) {
            while (id != null && relevant.add(id)) {
                Category category = byId.get(id);
                id = category == null ? null : category.parentId();
            }
        }
        return all.stream().filter(c -> relevant.contains(c.id())).toList();
    }

    private static String plain(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * 计算来源数据指纹：规则版本 + 来源序列化结果的 SHA-256。
     * 快照与当前来源指纹不一致即视为「已过期」，用于解读前的乐观校验。
     */
    public String fingerprint(Source source) {
        return fingerprint(null, source);
    }

    public String fingerprint(ReportPeriod period, Source source) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((MonthlyReportCalculator.RULE_VERSION + (period == null ? "" : encode(period)) + encode(source)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 月报快照 CRUD ====================

    public StoredReport byMonth(String month) {
        MonthlyReportEntity entity = reportMapper.selectOne(new QueryWrapper<MonthlyReportEntity>().eq("f_month", month));
        return toStored(entity);
    }

    public StoredReport byId(long id) {
        return toStored(reportMapper.selectById((int) id));
    }

    public List<StoredReport> byYear(int year) {
        List<MonthlyReportEntity> entities = reportMapper.selectList(new QueryWrapper<MonthlyReportEntity>()
                .ge("f_month", year + "-01").lt("f_month", (year + 1) + "-01").orderByDesc("f_month"));
        List<StoredReport> result = new ArrayList<>(entities.size());
        entities.forEach(e -> result.add(toStored(e)));
        return result;
    }

    /**
     * 新增月报快照，返回带自增主键的实体。
     */
    public MonthlyReportEntity insertReport(String month, String sourceHash, String snapshotJson, String generatedAt, String resultJson) {
        MonthlyReportEntity entity = new MonthlyReportEntity();
        entity.setMonth(month);
        entity.setVersion(1);
        entity.setSourceHash(sourceHash);
        entity.setSnapshot(snapshotJson);
        entity.setAiResult(resultJson);
        entity.setGeneratedAt(generatedAt);
        reportMapper.insert(entity);
        return entity;
    }

    /**
     * 更新既有月报：版本自增、覆盖来源指纹/快照/生成时间。
     * 全程用 UpdateWrapper 指定 SET，避免把主键写入 SET 子句；version 用 setSql 自增规避读改写竞态。
     */
    public boolean updateReport(long id, int version, String sourceHash, String snapshotJson, String generatedAt, String resultJson) {
        return reportMapper.update(null, new UpdateWrapper<MonthlyReportEntity>()
                .eq("f_id", id).eq("f_version", version)
                .set("f_source_hash", sourceHash)
                .set("f_snapshot", snapshotJson)
                .set("f_ai_result", resultJson)
                .set("f_generated_at", generatedAt)
                .setSql("f_version = f_version + 1")) == 1;
    }

    public void updateReportMessage(long id, Long messageId) {
        MonthlyReportEntity entity = new MonthlyReportEntity();
        entity.setId((int) id);
        entity.setMessageId(messageId);
        reportMapper.updateById(entity);
    }

    public StoredReport byPeriod(ReportPeriod period) {
        if ("month".equals(period.type())) return byMonth(period.periodKey());
        return toStored(periodMapper.selectOne(new QueryWrapper<PeriodReportEntity>()
                .eq("f_period_type", period.type()).eq("f_period_key", period.periodKey())));
    }

    public StoredReport byId(String type, long id) {
        if ("month".equals(type)) return byId(id);
        return toStored(periodMapper.selectOne(new QueryWrapper<PeriodReportEntity>()
                .eq("f_period_type", type).eq("f_id", id)));
    }

    public List<StoredReport> byYear(String type, int year) {
        if ("month".equals(type)) return byYear(year);
        return periodMapper.selectList(new QueryWrapper<PeriodReportEntity>().eq("f_period_type", type)
                .ge(!"year".equals(type), "f_period_key", year + "-01-01")
                .lt(!"year".equals(type), "f_period_key", (year + 1) + "-01-01")
                .orderByDesc("f_period_key")).stream().map(this::toStored).toList();
    }

    public void savePeriod(ReportPeriod p, StoredReport old, String hash, String snapshot, String now, String result) {
        if ("month".equals(p.type())) {
            if (old == null) insertReport(p.periodKey(), hash, snapshot, now, result);
            else if (!updateReport(old.id(), old.version(), hash, snapshot, now, result)) throw stale();
            return;
        }
        if (old == null) {
            PeriodReportEntity entity = new PeriodReportEntity();
            entity.setPeriodType(p.type()); entity.setPeriodKey(p.periodKey()); entity.setVersion(1);
            entity.setSourceHash(hash); entity.setSnapshot(snapshot); entity.setGeneratedAt(now); entity.setAiResult(result);
            periodMapper.insert(entity);
        } else if (periodMapper.update(null, new UpdateWrapper<PeriodReportEntity>()
                .eq("f_id", old.id()).eq("f_period_type", p.type()).eq("f_version", old.version())
                .set("f_source_hash", hash).set("f_snapshot", snapshot).set("f_generated_at", now)
                .set("f_ai_result", result).setSql("f_version = f_version + 1")) != 1) throw stale();
    }

    public void updateReportMessage(String type, long id, Long messageId) {
        if ("month".equals(type)) updateReportMessage(id, messageId);
        else periodMapper.update(null, new UpdateWrapper<PeriodReportEntity>()
                .eq("f_id", id).eq("f_period_type", type).set("f_message_id", messageId));
    }

    private static com.bookkeeping.exception.BusinessException stale() {
        return new com.bookkeeping.exception.BusinessException(com.bookkeeping.constant.BookkeepingResp.AI_STALE);
    }

    private StoredReport toStored(PeriodReportEntity entity) {
        if (entity == null) return null;
        return new StoredReport(entity.getId(), entity.getPeriodKey(), entity.getVersion(), entity.getSourceHash(),
                entity.getGeneratedAt(), decode(entity.getSnapshot(), Snapshot.class), entity.getMessageId(),
                decode(entity.getAiResult(), JsonNode.class));
    }

    private StoredReport toStored(MonthlyReportEntity entity) {
        if (entity == null) return null;
        return new StoredReport(entity.getId().longValue(), entity.getMonth(), entity.getVersion(),
                entity.getSourceHash(), entity.getGeneratedAt(), decode(entity.getSnapshot(), Snapshot.class), entity.getMessageId(),
                entity.getAiResult() == null ? null : decode(entity.getAiResult(), JsonNode.class));
    }

    /** 只在启动迁移时读取旧档案，正常查询与生成不依赖旧表。 */
    public void migrateLegacyResults() {
        if (reportMapper.hasLegacyAutomatic()) reportMapper.clearLegacyAutomatic();
        if (!reportMapper.hasLegacyJobs()) return;
        for (MonthlyReportEntity entity : reportMapper.selectList(new QueryWrapper<MonthlyReportEntity>().isNull("f_ai_result"))) {
            Snapshot snapshot = decode(entity.getSnapshot(), Snapshot.class);
            for (String text : reportMapper.legacyResults(entity.getId(), entity.getVersion())) {
                JsonNode result;
                try {
                    result = decode(text, JsonNode.class);
                    MonthlyReportAiService.validateResult(result, snapshot);
                } catch (RuntimeException invalid) {
                    // 旧档案可能损坏；不回显正文，继续寻找同快照的最近有效成功结果。
                    continue;
                }
                reportMapper.update(null, new UpdateWrapper<MonthlyReportEntity>()
                        .eq("f_id", entity.getId()).isNull("f_ai_result").set("f_ai_result", encode(result)));
                break;
            }
        }
    }

    // ==================== 关联消息 ====================

    /**
     * 新建一条月报消息（未读），返回其主键。
     */
    public Long createMessage(String title, String content, Long bizId) {
        return createMessage("month", title, content, bizId);
    }

    public Long createMessage(String type, String title, String content, Long bizId) {
        MessageEntity entity = new MessageEntity();
        entity.setTitle(title);
        entity.setContent(content);
        entity.setType(MessageType.fromValue(type.equals("week") ? "weekly_report" : type.equals("year") ? "yearly_report" : "monthly_report"));
        entity.setBizType(MessageBizType.fromValue(entity.getType().getValue()));
        entity.setBizId(bizId == null ? null : bizId.intValue());
        entity.setStatus(MessageStatus.UN_READ);
        messageMapper.insert(entity);
        return entity.getId().longValue();
    }

    /**
     * 更新既有月报消息内容。
     */
    public void updateMessage(Long messageId, String content) {
        if (messageId == null) return;
        MessageEntity entity = new MessageEntity();
        entity.setId(messageId.intValue());
        entity.setContent(content);
        messageMapper.updateById(entity);
    }
}
