package com.bookkeeping.monthly;

import org.sf.model.response.DataResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.bookkeeping.monthly.MonthlyReportModels.*;

/** 流水下钻范围契约：按自然日半开区间取数，保留归档分类及异常分类归属，不拉全历史。 */
@RestController
@RequestMapping("transaction/range")
public class MonthlyTransactionController {
    private final JdbcTemplate jdbc;
    public MonthlyTransactionController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping
    public DataResponse<List<Map<String, Object>>> range(@RequestParam("start") String start,
            @RequestParam("end") String end, @RequestParam("currency") String currency) {
        LocalDate from, to;
        try { from = LocalDate.parse(start); to = LocalDate.parse(end); }
        catch (Exception e) { throw bad(); }
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366 || currency.isBlank() || currency.length() > 30) throw bad();
        Map<Integer, Category> categories = new HashMap<>();
        jdbc.query("SELECT f_id,f_parent_id,f_name,f_type FROM t_category", rs -> {
            int parent = rs.getInt(2);
            Integer parentId = rs.wasNull() ? null : parent;
            categories.put(rs.getInt(1), new Category(rs.getInt(1), parentId, rs.getString(3), rs.getString(4)));
        });
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT t.* FROM t_transaction t LEFT JOIN t_account a ON a.f_id=t.f_account_id
                WHERE t.f_date>=? AND t.f_date<? AND COALESCE(NULLIF(trim(a.f_currency),''),'UNKNOWN')=?
                ORDER BY t.f_date DESC,t.f_id DESC
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("f_id")); row.put("type", rs.getString("f_type"));
            row.put("amount", rs.getString("f_amount")); row.put("fee", rs.getString("f_fee"));
            row.put("accountId", rs.getObject("f_account_id")); row.put("toAccountId", rs.getObject("f_to_account_id"));
            int c = rs.getInt("f_category_id"); Integer category = rs.wasNull() ? null : c;
            row.put("categoryId", category); row.put("transactionDate", rs.getString("f_date"));
            row.put("note", rs.getString("f_note")); row.put("tags", rs.getString("f_tags"));
            row.put("monthlyRootId", MonthlyReportCalculator.root(category, categories));
            Set<Integer> chain = new LinkedHashSet<>();
            while (category != null && chain.add(category)) {
                Category parent = categories.get(category);
                category = parent == null ? null : parent.parentId();
            }
            row.put("categoryPath", chain);
            return row;
        }, from.toString(), to.plusDays(1).toString(), currency);
        return DataResponse.of(rows);
    }
    private static ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种筛选需要有效日期范围，最长一年"); }
}
