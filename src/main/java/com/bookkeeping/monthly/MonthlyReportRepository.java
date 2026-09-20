package com.bookkeeping.monthly;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.*;
import static com.bookkeeping.monthly.MonthlyReportModels.*;

/** 月报独立的 JDBC 映射层，和原 MyBatis 业务共享数据源与事务。 */
@Repository
public class MonthlyReportRepository {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    public MonthlyReportRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Source source(YearMonth month) {
        List<Tx> transactions = jdbc.query("""
                SELECT t.f_id, t.f_type, t.f_amount, t.f_fee, t.f_category_id,
                       t.f_account_id, a.f_currency, t.f_date
                FROM t_transaction t LEFT JOIN t_account a ON a.f_id=t.f_account_id
                WHERE t.f_date >= ? AND t.f_date < ? ORDER BY t.f_id
                """, (rs, n) -> new Tx(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                nullableInt(rs, 5), nullableInt(rs, 6), rs.getString(7), rs.getString(8)),
                month.minusMonths(3).atDay(1).toString(), month.plusMonths(1).atDay(1).toString());
        List<Category> categories = jdbc.query("SELECT f_id,f_parent_id,f_name,f_type FROM t_category ORDER BY f_id",
                (rs, n) -> new Category(rs.getInt(1), nullableInt(rs, 2), rs.getString(3), rs.getString(4)));
        List<Budget> budgets = jdbc.query("SELECT f_id,f_category_id,f_amount FROM t_budget WHERE f_year=? AND f_month=? ORDER BY f_id",
                (rs, n) -> new Budget(rs.getInt(1), nullableInt(rs, 2), rs.getString(3)), month.getYear(), month.getMonthValue());
        String firstMonth = jdbc.queryForObject("SELECT substr(MIN(f_date),1,7) FROM t_transaction", String.class);
        String historyStart = month.minusMonths(3).toString();
        if (firstMonth != null && firstMonth.compareTo(historyStart) < 0) firstMonth = historyStart;
        Map<Integer, Category> byId = new HashMap<>();
        categories.forEach(c -> byId.put(c.id(), c));
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
        return new Source(transactions, categories.stream().filter(c -> relevant.contains(c.id())).toList(), budgets, firstMonth);
    }

    public StoredReport byMonth(String month) {
        return jdbc.query("SELECT * FROM t_monthly_report WHERE f_month=?", this::report, month).stream().findFirst().orElse(null);
    }
    public StoredReport byId(long id) {
        return jdbc.query("SELECT * FROM t_monthly_report WHERE f_id=?", this::report, id).stream().findFirst().orElse(null);
    }
    public List<StoredReport> byYear(int year) {
        return jdbc.query("SELECT * FROM t_monthly_report WHERE f_month>=? AND f_month<? ORDER BY f_month DESC",
                this::report, year + "-01", (year + 1) + "-01");
    }
    public AiJob latestJob(long reportId, int version) {
        return jdbc.query("SELECT * FROM t_monthly_report_ai_job WHERE f_report_id=? AND f_snapshot_version=? ORDER BY rowid DESC LIMIT 1",
                this::mapJob, reportId, version).stream().findFirst().orElse(null);
    }
    public AiJob latestSuccess(long reportId, int version) {
        return jdbc.query("SELECT * FROM t_monthly_report_ai_job WHERE f_report_id=? AND f_snapshot_version=? AND f_status='succeeded' ORDER BY rowid DESC LIMIT 1",
                this::mapJob, reportId, version).stream().findFirst().orElse(null);
    }
    public List<AiJob> jobs(long reportId, int version, String config) {
        return jdbc.query("SELECT * FROM t_monthly_report_ai_job WHERE f_report_id=? AND f_snapshot_version=? AND f_config_version=? ORDER BY f_attempt DESC",
                this::mapJob, reportId, version, config);
    }
    public AiJob job(String id) {
        return jdbc.query("SELECT * FROM t_monthly_report_ai_job WHERE f_id=?", this::mapJob, id).stream().findFirst().orElse(null);
    }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException e) { throw new IllegalStateException("月报序列化失败"); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JacksonException e) { throw new IllegalStateException("月报数据损坏"); }
    }
    private StoredReport report(ResultSet rs, int row) throws SQLException {
        long message = rs.getLong("f_message_id");
        Long messageId = rs.wasNull() ? null : message;
        return new StoredReport(rs.getLong("f_id"), rs.getString("f_month"), rs.getInt("f_version"),
                rs.getString("f_source_hash"), rs.getString("f_generated_at"), decode(rs.getString("f_snapshot"), Snapshot.class), messageId);
    }
    private AiJob mapJob(ResultSet rs, int row) throws SQLException {
        String result = rs.getString("f_result");
        return new AiJob(rs.getString("f_id"), rs.getLong("f_report_id"), rs.getInt("f_snapshot_version"),
                rs.getString("f_config_version"), rs.getInt("f_attempt"), rs.getString("f_status"),
                rs.getString("f_created_at"), rs.getString("f_error_code"), result == null ? null : decode(result, tools.jackson.databind.JsonNode.class));
    }
    private static Integer nullableInt(ResultSet rs, int col) throws SQLException {
        int value = rs.getInt(col);
        return rs.wasNull() ? null : value;
    }
}
