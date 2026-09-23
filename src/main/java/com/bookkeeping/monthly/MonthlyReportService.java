package com.bookkeeping.monthly;

import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import jakarta.annotation.PostConstruct;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bookkeeping.monthly.MonthlyReportModels.*;

/**
 * 月报查询、事实计算及成功结果保存，持久化全部经 MyBatis-Plus。
 * 本地候选快照不落库；只有经校验的 AI 结果才能与快照、消息一起提交。
 * 数据库事务中绝不发起任何外部 AI 调用。
 *
 * @author zhuxiao
 */
@Service
@DependsOnDatabaseInitialization
public class MonthlyReportService {
    private final MonthlyReportRepository repo;
    private final MonthlyReportCalculator calculator;
    private final MonthlyAiRequestGuard guard;

    public MonthlyReportService(MonthlyReportRepository repo, MonthlyReportCalculator calculator,
                                MonthlyAiRequestGuard guard) {
        this.repo = repo;
        this.calculator = calculator;
        this.guard = guard;
    }

    /** 只迁移旧数据，不补生成报告、不发起任何模型请求。 */
    @PostConstruct
    public void migrateLegacy() {
        guard.mutate(() -> { repo.migrateLegacyResults(); return null; });
    }

    /**
     * 列出某年 12 个月及其月报状态（倒序，仅返回已结束的月份）。
     * hasReport 只表示是否已保存 AI 结果，不暴露任何请求运行态。
     */
    public List<MonthEntry> list(int year) {
        if (year < 1900 || year > YearMonth.now().getYear()) throw new BusinessException(BookkeepingResp.MONTH_INVALID);
        Map<String, StoredReport> reports = new HashMap<>();
        repo.byYear(year).forEach(r -> reports.put(r.month(), r));
        List<MonthEntry> result = new ArrayList<>();
        for (int m = 12; m >= 1; m--) {
            YearMonth month = YearMonth.of(year, m);
            if (!month.isBefore(YearMonth.now())) continue;
            StoredReport r = reports.get(month.toString());
            result.add(new MonthEntry(month.toString(), r == null ? null : r.id(), r == null ? null : r.version(),
                    r == null ? null : r.generatedAt(), r != null && r.result() != null));
        }
        return result;
    }

    /** 只读查询，过期标识不改变已经保存的报告。 */
    public Detail detail(long id) {
        return guard.mutate(() -> {
            StoredReport report = requireReport(id);
            return toDetail(report, isStale(report));
        });
    }

    /** 调用方在短事务内读取一致的数据，仅生成内存候选。 */
    Candidate candidate(String value) {
        YearMonth month = parseMonth(value);
        Source source = repo.source(month);
        StoredReport old = repo.byMonth(value);
        return new Candidate(value, repo.fingerprint(source), old == null ? 0 : old.version(), calculator.calculate(month, source));
    }

    void requireCurrent(Candidate candidate) {
        StoredReport current = repo.byMonth(candidate.month());
        int version = current == null ? 0 : current.version();
        if (version != candidate.baseVersion()
                || !candidate.sourceHash().equals(repo.fingerprint(repo.source(YearMonth.parse(candidate.month())))))
            throw new BusinessException(BookkeepingResp.AI_STALE);
    }

    /** 仅由 AI 服务在结果验证通过后、同一短事务内调用。 */
    Detail saveSuccess(Candidate candidate, JsonNode result) {
        requireCurrent(candidate);
        StoredReport old = repo.byMonth(candidate.month());
        String now = now();
        String snapshot = repo.encode(candidate.snapshot());
        String resultJson = repo.encode(result);
        if (old == null) repo.insertReport(candidate.month(), candidate.sourceHash(), snapshot, now, resultJson);
        else if (!repo.updateReport(old.id(), old.version(), candidate.sourceHash(), snapshot, now, resultJson))
            throw new BusinessException(BookkeepingResp.AI_STALE);
        StoredReport saved = repo.byMonth(candidate.month());
        String message = "AI 月报已生成。请结合数据依据评估建议，必要支出不必盲目削减。";
        if (saved.messageId() == null) {
            repo.updateReportMessage(saved.id(), repo.createMessage(candidate.month() + " AI 月报已生成", message, saved.id()));
        } else repo.updateMessage(saved.messageId(), message);
        return toDetail(repo.byMonth(candidate.month()), false);
    }

    private boolean isStale(StoredReport report) {
        return !report.sourceHash().equals(repo.fingerprint(repo.source(YearMonth.parse(report.month()))));
    }

    private Detail toDetail(StoredReport r, boolean stale) {
        return new Detail(r.id(), r.month(), r.version(), r.generatedAt(), stale, r.snapshot(), r.result());
    }

    private StoredReport requireReport(long id) {
        StoredReport r = repo.byId(id);
        if (r == null) throw new BusinessException(BookkeepingResp.REPORT_NOT_FOUND);
        return r;
    }

    private static YearMonth parseMonth(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}")) {
            throw new BusinessException(BookkeepingResp.MONTH_INVALID);
        }
        YearMonth month;
        try {
            month = YearMonth.parse(value);
        } catch (java.time.DateTimeException e) {
            throw new BusinessException(BookkeepingResp.MONTH_INVALID);
        }
        if (month.getYear() < 1900 || !month.isBefore(YearMonth.now(ZoneId.systemDefault()))) {
            throw new BusinessException(BookkeepingResp.MONTH_INVALID);
        }
        return month;
    }

    private static String now() {
        return LocalDateTime.now().toString();
    }
}
