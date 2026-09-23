package com.bookkeeping.monthly;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 月报数据契约：所有金额均为十进制字符串，不含账户名称、备注或标签。
 */
public final class MonthlyReportModels {
    private MonthlyReportModels() {
    }

    public record Tx(long id, String type, String amount, String fee, Integer categoryId,
                     Integer accountId, String currency, String date) {
    }

    public record Category(int id, Integer parentId, String name, String type) {
    }

    public record Budget(int id, Integer categoryId, String amount) {
    }

    public record Source(List<Tx> transactions, List<Category> categories, List<Budget> budgets,
                         String firstMonth) {
    }

    public record Breakdown(int categoryId, String name, String amount, int count) {
    }

    public record LargeExpense(long id, String date, String amount) {
    }

    public record CategoryStat(int categoryId, String name, String amount, int count,
                               String average, String share, String previousAmount, Integer previousCount,
                               String previousAverage, String growth, String historyAverage,
                               List<Breakdown> children, List<LargeExpense> largeExpenses) {
    }

    public record CurrencySummary(String currency, String income, String expense, String fees,
                                  String balance, int count, String previousExpense, String growth,
                                  String historyAverage, List<CategoryStat> categories) {
    }

    public record BudgetComparison(int id, Integer categoryId, String name, String amount,
                                   String used, String excess, String percentage) {
    }

    public record Fact(String id, String kind, String currency, Integer categoryId,
                       String title, Map<String, String> values, String suggestion) {
    }

    public record Snapshot(String month, String ruleVersion, int count,
                           List<CurrencySummary> currencies, List<BudgetComparison> budgets,
                           List<Fact> facts, List<String> limitations) {
    }

    public record StoredReport(long id, String month, int version, String sourceHash,
                               String generatedAt, Snapshot snapshot, Long messageId, JsonNode result) {
    }

    /** 尚未保存的本地计算结果，仅作为本次 AI 调用依据。 */
    public record Candidate(String month, String sourceHash, int baseVersion, Snapshot snapshot) {
    }

    public record Detail(long id, String month, int version, String generatedAt,
                         boolean stale, Snapshot snapshot, JsonNode result) {
    }

    public record MonthEntry(String month, Long id, Integer version, String generatedAt, boolean hasReport) {
    }

    /**
     * AI 配置回显视图：绝不含明文密钥，仅以 hasKey 标识是否已配置。
     * available 表示后端是否具备发起 AI 调用的条件（已配置 baseUrl/model/key）。
     */
    public record AiConfigView(String baseUrl, String model, boolean includeNames,
                               boolean hasKey, boolean available, String configVersion) {
    }

    /**
     * 保存 AI 配置的入参；apiKey 缺省仅在地址未变化时保留旧密钥，空串表示清除。
     */
    public record AiConfigRequest(String baseUrl, String model, boolean includeNames, String apiKey) {
    }

    /**
     * 单次连接测试的确认信息，防止使用已经变化的配置。
     */
    public record AiConsentRequest(String configVersion, boolean confirmed) {
    }

    /**
     * 单次生成确认：来源和报告版本必须与预览一致，不接受客户端提供的统计正文。
     */
    public record AiGenerateRequest(String month, String sourceHash, int baseVersion, String configVersion,
                                    boolean confirmed) {
    }

    /**
     * 授权前预览：返回某月报「实际会发送给模型」的脱敏摘要，供用户确认发送字段后再授权。
     * summary 由后端白名单构造（含 includeNames 脱敏），绝不含备注/账户名等隐私。
     */
    public record AiPreviewView(String month, String sourceHash, int baseVersion, String configVersion,
                                String baseUrl, String model, boolean includeNames, Map<String, Object> summary) {
    }
}
