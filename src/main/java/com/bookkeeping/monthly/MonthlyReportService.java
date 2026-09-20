package com.bookkeeping.monthly;

import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import static com.bookkeeping.monthly.MonthlyReportModels.*;

@Service
public class MonthlyReportService {
    private final MonthlyReportRepository repo;
    private final MonthlyReportCalculator calculator;
    private final TransactionTemplate transaction;
    public MonthlyReportService(MonthlyReportRepository repo, MonthlyReportCalculator calculator,
                                PlatformTransactionManager manager) {
        this.repo = repo;
        this.calculator = calculator;
        this.transaction = new TransactionTemplate(manager);
    }

    public List<MonthEntry> list(int year) {
        if (year < 1900 || year > YearMonth.now().getYear()) throw bad("年份无效");
        Map<String, StoredReport> reports = new HashMap<>();
        repo.byYear(year).forEach(r -> reports.put(r.month(), r));
        List<MonthEntry> result = new ArrayList<>();
        for (int m = 12; m >= 1; m--) {
            YearMonth month = YearMonth.of(year, m);
            if (!month.isBefore(YearMonth.now())) continue;
            StoredReport r = reports.get(month.toString());
            AiJob ai = r == null ? null : repo.latestJob(r.id(), r.version());
            result.add(new MonthEntry(month.toString(), r == null ? null : r.id(), r == null ? null : r.version(),
                    r == null ? null : r.generatedAt(), r == null ? "not_generated" : ai == null ? "local" : ai.status()));
        }
        return result;
    }

    public Detail detail(long id) {
        return transaction.execute(status -> {
            StoredReport report = requireReport(id);
            expireJobs();
            return toDetail(report, !report.sourceHash().equals(fingerprint(repo.source(YearMonth.parse(report.month())))));
        });
    }

    /** 本地事务包含快照与提醒，绝不在数据库事务中发起外部 AI 请求。 */
    public synchronized Detail generate(String value, boolean refresh) {
        YearMonth month = parseMonth(value);
        return transaction.execute(status -> {
            StoredReport old = repo.byMonth(value);
            if (old != null && !refresh) return toDetail(old, !old.sourceHash().equals(fingerprint(repo.source(month))));
            Source source = repo.source(month);
            String hash = fingerprint(source);
            if (old != null && hash.equals(old.sourceHash())) return toDetail(old, false);
            Snapshot snapshot = calculator.calculate(month, source);
            String now = now();
            if (old == null) {
                repo.jdbc.update("INSERT INTO t_monthly_report(f_month,f_version,f_source_hash,f_snapshot,f_generated_at) VALUES (?,1,?,?,?)",
                        value, hash, repo.encode(snapshot), now);
            } else {
                repo.jdbc.update("UPDATE t_monthly_report SET f_version=f_version+1,f_source_hash=?,f_snapshot=?,f_generated_at=? WHERE f_id=?",
                        hash, repo.encode(snapshot), now, old.id());
            }
            StoredReport saved = repo.byMonth(value);
            if (saved.messageId() == null && snapshot.count() > 0) {
                repo.jdbc.update("""
                        INSERT INTO t_message(f_title,f_content,f_type,f_biz_type,f_biz_id,f_status)
                        VALUES (?,?,'monthly_report','monthly_report',?,0)
                        """, value + " 月报已生成", "本地统计已完成，可查看主要支出、预算偏差与下月调整建议。", saved.id());
                Long messageId = repo.jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
                repo.jdbc.update("UPDATE t_monthly_report SET f_message_id=? WHERE f_id=?", messageId, saved.id());
            } else if (saved.messageId() != null) {
                repo.jdbc.update("UPDATE t_message SET f_content=? WHERE f_id=?",
                        "月报统计已更新；旧版本 AI 解读不再适用于当前快照。", saved.messageId());
            }
            return toDetail(repo.byMonth(value), false);
        });
    }

    public void generateLatest() {
        String month = YearMonth.now().minusMonths(1).toString();
        if (repo.byMonth(month) != null) return;
        Integer count = repo.jdbc.queryForObject("SELECT count(*) FROM t_transaction WHERE f_date>=? AND f_date<?", Integer.class,
                month + "-01", YearMonth.now().atDay(1).toString());
        if (count != null && count > 0) generate(month, false);
    }

    public synchronized Claim claim(ClaimRequest request) {
        if (request.configVersion() == null || !request.configVersion().matches("[a-zA-Z0-9-]{16,80}")) throw bad("配置版本无效");
        return transaction.execute(status -> {
            expireJobs();
            StoredReport report = requireReport(request.reportId());
            if (report.version() != request.snapshotVersion()) throw bad("统计版本已更新");
            if (request.automatic() && (!report.month().equals(YearMonth.now().minusMonths(1).toString()) || report.version() != 1)) return null;
            if (report.snapshot().count() == 0 || report.snapshot().facts().isEmpty() || !report.sourceHash().equals(fingerprint(repo.source(YearMonth.parse(report.month()))))) return null;
            // 同一月报版本即使配置不同也不能同时占用两个外部调用。
            Integer running = repo.jdbc.queryForObject("SELECT count(*) FROM t_monthly_report_ai_job WHERE f_report_id=? AND f_status='running'", Integer.class, report.id());
            if (running != null && running > 0) return null;
            List<AiJob> jobs = repo.jobs(report.id(), report.version(), request.configVersion());
            if (!jobs.isEmpty() && (request.automatic() || !request.retry())) return null;
            int attempt = jobs.isEmpty() ? 1 : jobs.get(0).attempt() + 1;
            String id = UUID.randomUUID().toString();
            repo.jdbc.update("""
                    INSERT INTO t_monthly_report_ai_job(f_id,f_report_id,f_snapshot_version,f_config_version,f_attempt,f_status,f_created_at)
                    VALUES (?,?,?,?,?,'running',?)
                    """, id, report.id(), report.version(), request.configVersion(), attempt, now());
            return new Claim(repo.job(id), report.snapshot());
        });
    }

    public synchronized boolean complete(Completion completion) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            expireJobs();
            AiJob job = repo.job(completion.jobId());
            if (job == null || !job.status().equals("running") || job.snapshotVersion() != completion.snapshotVersion()
                    || !job.configVersion().equals(completion.configVersion())) return false;
            StoredReport report = requireReport(job.reportId());
            if (report.version() != job.snapshotVersion()) {
                repo.jdbc.update("UPDATE t_monthly_report_ai_job SET f_status='interrupted',f_error_code='STALE' WHERE f_id=?", job.id());
                return false;
            }
            if (completion.result() == null) {
                String code = completion.errorCode();
                if (code == null || !code.matches("[A-Z0-9_]{1,40}")) code = "REQUEST_FAILED";
                repo.jdbc.update("UPDATE t_monthly_report_ai_job SET f_status='failed',f_error_code=? WHERE f_id=?", code, job.id());
                return true;
            }
            validateResult(completion.result(), report.snapshot());
            repo.jdbc.update("UPDATE t_monthly_report_ai_job SET f_status='succeeded',f_result=? WHERE f_id=?",
                    repo.encode(completion.result()), job.id());
            if (report.messageId() != null) repo.jdbc.update("UPDATE t_message SET f_content=? WHERE f_id=?",
                    "月报及 AI 解读已完成。请结合数据依据评估建议，必要支出不必盲目削减。", report.messageId());
            return true;
        }));
    }

    public static void validateResult(JsonNode result, Snapshot snapshot) {
        if (!result.isObject() || result.toString().length() > 20000) throw bad("AI 响应结构无效");
        Set<String> fields = Set.of("summary", "limitations", "observations", "actions", "provider", "model", "generatedAt");
        result.propertyNames().forEach(field -> { if (!fields.contains(field)) throw bad("AI 响应包含未知字段"); });
        if (snapshot.currencies().stream().allMatch(c -> new java.math.BigDecimal(c.expense()).signum() == 0)
                && result.path("actions").size() > 0) throw bad("无消费时不生成削减建议");
        text(result.get("summary"), 1200);
        text(result.get("limitations"), 1200);
        Set<String> ids = new HashSet<>();
        snapshot.facts().forEach(f -> ids.add(f.id()));
        for (String key : List.of("observations", "actions")) {
            JsonNode items = result.get(key);
            if (items == null || !items.isArray() || items.size() > (key.equals("actions") ? 3 : 5)) throw bad("AI 建议结构无效");
            for (JsonNode item : items) {
                if (!item.isObject() || item.size() != 2) throw bad("AI 建议字段无效");
                text(item.get("text"), 1000);
                JsonNode refs = item.get("factIds");
                if (refs == null || !refs.isArray() || refs.isEmpty() || refs.size() > 5) throw bad("AI 缺少数据依据");
                for (JsonNode ref : refs) if (!ref.isString() || !ids.contains(ref.asString())) throw bad("AI 引用了不存在的事实");
            }
        }
        text(result.get("provider"), 300);
        text(result.get("model"), 200);
        text(result.get("generatedAt"), 50);
    }
    private static void text(JsonNode value, int max) {
        if (value == null || !value.isString() || value.asString().isBlank() || value.asString().length() > max) throw bad("AI 文本字段无效");
    }
    private void expireJobs() {
        repo.jdbc.update("UPDATE t_monthly_report_ai_job SET f_status='interrupted',f_error_code='INTERRUPTED' WHERE f_status='running' AND f_created_at<?",
                LocalDateTime.now().minusMinutes(5).toString());
    }
    private Detail toDetail(StoredReport r, boolean stale) {
        return new Detail(r.id(), r.month(), r.version(), r.generatedAt(), stale, r.snapshot(),
                repo.latestJob(r.id(), r.version()), repo.latestSuccess(r.id(), r.version()));
    }
    private StoredReport requireReport(long id) {
        StoredReport r = repo.byId(id);
        if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "月报不存在");
        return r;
    }
    private String fingerprint(Source source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (MonthlyReportCalculator.RULE_VERSION + repo.encode(source)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static YearMonth parseMonth(String value) {
        try {
            if (value == null || !value.matches("\\d{4}-\\d{2}")) throw bad("月份格式应为 yyyy-MM");
            YearMonth month = YearMonth.parse(value);
            if (month.getYear() < 1900 || !month.isBefore(YearMonth.now())) throw bad("只能生成已结束月份的月报");
            return month;
        } catch (java.time.DateTimeException e) { throw bad("月份无效"); }
    }
    private static String now() { return LocalDateTime.now().toString(); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
