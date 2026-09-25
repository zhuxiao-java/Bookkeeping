package com.bookkeeping.monthly;

import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import jakarta.annotation.PostConstruct;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Clock clock;

    public MonthlyReportService(MonthlyReportRepository repo, MonthlyReportCalculator calculator,
                                MonthlyAiRequestGuard guard) {
        this(repo, calculator, guard, Clock.systemDefaultZone());
    }

    @Autowired
    public MonthlyReportService(MonthlyReportRepository repo, MonthlyReportCalculator calculator,
                                MonthlyAiRequestGuard guard, Clock clock) {
        this.clock = clock;
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
        return list("month", year).stream().map(e -> new MonthEntry(e.periodKey(), e.id(), e.version(), e.generatedAt(), e.hasReport())).toList();
    }

    public List<PeriodEntry> list(String type, int year) {
        ReportPeriod.requireType(type);
        LocalDate today = LocalDate.now(clock);
        if (year < 1900 || year > today.getYear()) throw ReportPeriod.invalid();
        return guard.mutate(() -> {
            Map<String, StoredReport> reports = new HashMap<>();
            repo.byYear(type, year).forEach(r -> reports.put(r.month(), r));
            List<ReportPeriod> periods = new ArrayList<>();
            if ("year".equals(type)) {
                for (int y = today.getYear() - 1; y >= 1900; y--) periods.add(ReportPeriod.of(type, String.valueOf(y)));
            } else if ("month".equals(type)) {
                for (int m = 12; m >= 1; m--) periods.add(ReportPeriod.of(type, YearMonth.of(year, m).toString()));
            } else {
                for (LocalDate day = ReportPeriod.firstMonday(year); day.getYear() == year; day = day.plusWeeks(1))
                    periods.add(ReportPeriod.of(type, day.toString()));
                java.util.Collections.reverse(periods);
            }
            return periods.stream().filter(p -> !p.endExclusive().isAfter(today)).map(p -> {
                StoredReport r = reports.get(p.periodKey());
                return new PeriodEntry(type, p.periodKey(), p.start().toString(), p.end(), r == null ? null : r.id(),
                        r == null ? null : r.version(), r == null ? null : r.generatedAt(), r != null && r.result() != null);
            }).toList();
        });
    }

    /** 只读查询，过期标识不改变已经保存的报告。 */
    public Detail detail(long id) {
        return detail("month", id);
    }

    public Detail detail(String type, long id) {
        ReportPeriod.requireType(type);
        if (id <= 0 || id > Integer.MAX_VALUE) throw new BusinessException(BookkeepingResp.REPORT_NOT_FOUND);
        return guard.mutate(() -> {
            StoredReport report = repo.byId(type, id);
            if (report == null) throw new BusinessException(BookkeepingResp.REPORT_NOT_FOUND);
            return toDetail(report, isStale(report));
        });
    }

    /** 调用方在短事务内读取一致的数据，仅生成内存候选。 */
    Candidate candidate(String value) {
        return candidate("month", value);
    }

    Candidate candidate(String type, String value) {
        ReportPeriod period = ReportPeriod.closed(type, value, clock);
        Source source = repo.source(period);
        StoredReport old = repo.byPeriod(period);
        return new Candidate(value, repo.fingerprint(period, source), old == null ? 0 : old.version(), calculator.calculate(period, source));
    }

    void requireCurrent(Candidate candidate) {
        ReportPeriod period = candidate.snapshot().resolvedPeriod();
        StoredReport current = repo.byPeriod(period);
        int version = current == null ? 0 : current.version();
        if (version != candidate.baseVersion()
                || !candidate.sourceHash().equals(repo.fingerprint(period, repo.source(period))))
            throw new BusinessException(BookkeepingResp.AI_STALE);
    }

    /** 仅由 AI 服务在结果验证通过后、同一短事务内调用。 */
    Detail saveSuccess(Candidate candidate, JsonNode result) {
        requireCurrent(candidate);
        ReportPeriod period = candidate.snapshot().resolvedPeriod();
        StoredReport old = repo.byPeriod(period);
        String now = LocalDateTime.now(clock).toString();
        String snapshot = repo.encode(candidate.snapshot());
        String resultJson = repo.encode(result);
        repo.savePeriod(period, old, candidate.sourceHash(), snapshot, now, resultJson);
        StoredReport saved = repo.byPeriod(period);
        String message = "AI " + period.label() + "已生成。请结合数据依据评估省钱建议，必要支出不必盲目削减。";
        if (saved.messageId() == null) {
            repo.updateReportMessage(period.type(), saved.id(), repo.createMessage(period.type(),
                    period.periodKey() + " AI " + period.label() + "已生成", message, saved.id()));
        } else repo.updateMessage(saved.messageId(), message);
        return toDetail(repo.byPeriod(period), false);
    }

    private boolean isStale(StoredReport report) {
        ReportPeriod period = report.snapshot().resolvedPeriod();
        return !report.sourceHash().equals(repo.fingerprint(period, repo.source(period)));
    }

    private Detail toDetail(StoredReport r, boolean stale) {
        return new Detail(r.id(), r.month(), r.version(), r.generatedAt(), stale, r.snapshot(), r.result());
    }


}
