package com.bookkeeping.monthly;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** 月报数据契约：所有金额均为十进制字符串，不含账户名称、备注或标签。 */
public final class MonthlyReportModels {
    private MonthlyReportModels() {}

    public record Tx(long id, String type, String amount, String fee, Integer categoryId,
                     Integer accountId, String currency, String date) {}
    public record Category(int id, Integer parentId, String name, String type) {}
    public record Budget(int id, Integer categoryId, String amount) {}
    public record Source(List<Tx> transactions, List<Category> categories, List<Budget> budgets,
                         String firstMonth) {}
    public record Breakdown(int categoryId, String name, String amount, int count) {}
    public record LargeExpense(long id, String date, String amount) {}
    public record CategoryStat(int categoryId, String name, String amount, int count,
                               String average, String share, String previousAmount, Integer previousCount,
                               String previousAverage, String growth, String historyAverage,
                               List<Breakdown> children, List<LargeExpense> largeExpenses) {}
    public record CurrencySummary(String currency, String income, String expense, String fees,
                                  String balance, int count, String previousExpense, String growth,
                                  String historyAverage, List<CategoryStat> categories) {}
    public record BudgetComparison(int id, Integer categoryId, String name, String amount,
                                   String used, String excess, String percentage) {}
    public record Fact(String id, String kind, String currency, Integer categoryId,
                       String title, Map<String, String> values, String suggestion) {}
    public record Snapshot(String month, String ruleVersion, int count,
                           List<CurrencySummary> currencies, List<BudgetComparison> budgets,
                           List<Fact> facts, List<String> limitations) {}
    public record StoredReport(long id, String month, int version, String sourceHash,
                               String generatedAt, Snapshot snapshot, Long messageId) {}
    public record AiJob(String id, long reportId, int snapshotVersion, String configVersion,
                        int attempt, String status, String createdAt, String errorCode, JsonNode result) {}
    public record Detail(long id, String month, int version, String generatedAt,
                         boolean stale, Snapshot snapshot, AiJob ai, AiJob successfulAi) {}
    public record MonthEntry(String month, Long id, Integer version, String generatedAt, String status) {}
    public record ClaimRequest(long reportId, int snapshotVersion, String configVersion,
                               boolean automatic, boolean retry) {}
    public record Completion(String jobId, int snapshotVersion, String configVersion,
                             JsonNode result, String errorCode) {}
    public record Claim(AiJob job, Snapshot snapshot) {}
}
