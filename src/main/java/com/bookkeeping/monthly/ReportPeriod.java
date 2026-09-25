package com.bookkeeping.monthly;

import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.exception.BusinessException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

/** 自然周期，结束日期为排他边界；周键始终使用周一日期。 */
public record ReportPeriod(String type, String periodKey, LocalDate start, LocalDate endExclusive) {
    public static void requireType(String type) {
        if (type == null || !Set.of("week", "month", "year").contains(type)) throw invalid();
    }

    public static ReportPeriod of(String type, String key) {
        requireType(type);
        try {
            if (key == null) throw invalid();
            LocalDate start;
            LocalDate end;
            switch (type) {
                case "week" -> {
                    if (!key.matches("\\d{4}-\\d{2}-\\d{2}")) throw invalid();
                    start = LocalDate.parse(key);
                    if (start.getDayOfWeek() != DayOfWeek.MONDAY) throw invalid();
                    end = start.plusWeeks(1);
                }
                case "month" -> {
                    if (!key.matches("\\d{4}-\\d{2}")) throw invalid();
                    start = YearMonth.parse(key).atDay(1);
                    end = start.plusMonths(1);
                }
                default -> {
                    if (!key.matches("\\d{4}")) throw invalid();
                    start = Year.parse(key).atDay(1);
                    end = start.plusYears(1);
                }
            }
            return new ReportPeriod(type, key, start, end);
        } catch (DateTimeException e) { throw invalid(); }
    }

    public static ReportPeriod closed(String type, String key, Clock clock) {
        ReportPeriod p = of(type, key);
        if (p.start.getYear() < 1900 || p.endExclusive.isAfter(LocalDate.now(clock))) throw invalid();
        return p;
    }

    public ReportPeriod previous(int count) {
        return switch (type) {
            case "week" -> of(type, start.minusWeeks(count).toString());
            case "month" -> of(type, YearMonth.from(start.minusMonths(count)).toString());
            default -> of(type, Year.from(start.minusYears(count)).toString());
        };
    }

    public int historyCount() { return "week".equals(type) ? 4 : "month".equals(type) ? 3 : 0; }
    public LocalDate sourceStart() { return previous(Math.max(1, historyCount())).start; }
    public String label() { return switch (type) { case "week" -> "周报"; case "month" -> "月报"; default -> "年报"; }; }
    public String nextLabel() { return switch (type) { case "week" -> "下周"; case "month" -> "下月"; default -> "下一年"; }; }
    public String end() { return endExclusive.minusDays(1).toString(); }
    public boolean contains(String date) { return date.compareTo(start.toString()) >= 0 && date.compareTo(endExclusive.toString()) < 0; }

    public static LocalDate firstMonday(int year) {
        return LocalDate.of(year, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    static BusinessException invalid() {
        return new BusinessException(BookkeepingResp.MONTH_INVALID.getCode(), "请选择有效且已结束的周、月或年，周报日期必须为周一");
    }

    @Configuration
    public static class ClockConfiguration {
        @Bean
        public Clock reportClock() { return Clock.systemDefaultZone(); }
    }
}
