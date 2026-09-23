package com.bookkeeping.monthly;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bookkeeping.dao.entity.AccountEntity;
import com.bookkeeping.dao.entity.CategoryEntity;
import com.bookkeeping.dao.entity.TransactionEntity;
import com.bookkeeping.dao.mapper.AccountMapper;
import com.bookkeeping.dao.mapper.CategoryMapper;
import com.bookkeeping.dao.mapper.TransactionMapper;
import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.exception.BusinessException;
import org.sf.model.response.DataResponse;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.bookkeeping.monthly.MonthlyReportModels.*;

/**
 * 流水下钻范围契约：按自然日半开区间取数，保留归档分类及异常分类归属，不拉全历史。
 * <p>
 * 持久化改用 MyBatis-Plus：分类树与账户币种一次性载入内存做归属计算，币种在应用层按账户映射过滤，
 * 与原 LEFT JOIN + COALESCE(UNKNOWN) 口径一致。
 *
 * @author zhuxiao
 */
@RestController
@RequestMapping("transaction/range")
public class MonthlyTransactionController {
    /**
     * 前端约定 transactionDate 为 ISO 局部日期时间（含秒），故显式格式化，避免 LocalDateTime.toString 省略零秒。
     */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final TransactionMapper transactionMapper;
    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;

    public MonthlyTransactionController(TransactionMapper transactionMapper, AccountMapper accountMapper,
                                        CategoryMapper categoryMapper) {
        this.transactionMapper = transactionMapper;
        this.accountMapper = accountMapper;
        this.categoryMapper = categoryMapper;
    }

    @GetMapping
    public DataResponse<List<Map<String, Object>>> range(@RequestParam("start") String start,
                                                         @RequestParam("end") String end, @RequestParam("currency") String currency) {
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(start);
            to = LocalDate.parse(end);
        } catch (RuntimeException e) {
            throw bad();
        }
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366 || currency.isBlank() || currency.length() > 30)
            throw bad();
        // 分类树：id -> 模型，供 root/父链归属计算
        Map<Integer, Category> categories = new HashMap<>();
        for (CategoryEntity c : categoryMapper.selectList(null)) {
            categories.put(c.getId(), new Category(c.getId(), c.getParentId(), c.getName(),
                    c.getType() == null ? null : c.getType().getValue()));
        }
        // 账户币种映射：accountId -> 币种代码，缺失统一记 UNKNOWN（等价原 COALESCE）
        Map<Integer, String> currencyByAccount = new HashMap<>();
        for (AccountEntity a : accountMapper.selectList(null)) {
            currencyByAccount.put(a.getId(), a.getCurrency() == null ? "UNKNOWN" : a.getCurrency().getValue());
        }
        List<TransactionEntity> entities = transactionMapper.selectList(new QueryWrapper<TransactionEntity>()
                .ge("f_date", from.toString()).lt("f_date", to.plusDays(1).toString())
                .orderByDesc("f_date").orderByDesc("f_id"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TransactionEntity t : entities) {
            if (!currency.equals(currencyByAccount.getOrDefault(t.getAccountId(), "UNKNOWN"))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.getId().longValue());
            row.put("type", t.getType().getValue());
            row.put("amount", plain(t.getAmount()));
            row.put("fee", plain(t.getFee()));
            row.put("accountId", t.getAccountId());
            row.put("toAccountId", t.getToAccountId());
            row.put("categoryId", t.getCategoryId());
            row.put("transactionDate", iso(t.getTransactionDate()));
            row.put("note", t.getNote());
            row.put("tags", t.getTags());
            row.put("monthlyRootId", MonthlyReportCalculator.root(t.getCategoryId(), categories));
            // 自叶子向上收集父链，遇到环或悬空父级即止
            List<Integer> path = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            Integer cursor = t.getCategoryId();
            while (cursor != null && seen.add(cursor)) {
                path.add(cursor);
                Category parent = categories.get(cursor);
                cursor = parent == null ? null : parent.parentId();
            }
            row.put("categoryPath", path);
            rows.add(row);
        }
        return DataResponse.of(rows);
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.format(ISO);
    }

    private static String plain(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static BusinessException bad() {
        return new BusinessException(BookkeepingResp.MONTH_INVALID.getCode(), "币种筛选需要有效日期范围，最长一年");
    }
}
