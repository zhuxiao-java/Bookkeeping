package com.bookkeeping.monthly;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Predicate;
import static com.bookkeeping.monthly.MonthlyReportModels.*;

/** 无数据库副作用的月报计算器；阈值仅用于提示，不给用户打财务健康分。 */
@Component
public class MonthlyReportCalculator {
    public static final String RULE_VERSION = "1";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public Snapshot calculate(YearMonth month, Source source) {
        Map<Integer, Category> categories = new HashMap<>();
        source.categories().forEach(c -> categories.put(c.id(), c));
        List<Tx> current = inMonth(source.transactions(), month);
        List<Tx> previous = inMonth(source.transactions(), month.minusMonths(1));
        boolean hasHistory = source.firstMonth() != null
                && source.firstMonth().compareTo(month.minusMonths(3).toString()) <= 0;
        List<Tx> history = source.transactions().stream().filter(t ->
                t.date().substring(0, 7).compareTo(month.minusMonths(3).toString()) >= 0
                        && t.date().substring(0, 7).compareTo(month.toString()) < 0).toList();
        Set<String> currencies = new TreeSet<>();
        current.forEach(t -> currencies.add(currency(t)));
        previous.forEach(t -> currencies.add(currency(t)));
        List<CurrencySummary> summaries = new ArrayList<>();
        List<Fact> facts = new ArrayList<>();
        for (String currency : currencies) {
            boolean hasPrevious = previous.stream().anyMatch(t -> currency(t).equals(currency));
            List<Tx> rows = current.stream().filter(t -> currency(t).equals(currency)).toList();
            List<Tx> expenses = expenses(rows);
            List<Tx> prevExpenses = expenses(previous.stream().filter(t -> currency(t).equals(currency)).toList());
            List<Tx> histExpenses = expenses(history.stream().filter(t -> currency(t).equals(currency)).toList());
            BigDecimal income = sum(rows, t -> t.type().equals("income"));
            BigDecimal expense = sum(expenses);
            BigDecimal prevExpense = sum(prevExpenses);
            BigDecimal fees = rows.stream().filter(t -> t.type().equals("transfer"))
                    .map(t -> decimal(t.fee())).reduce(ZERO, BigDecimal::add);
            Map<Integer, List<Tx>> groups = group(expenses, categories);
            Map<Integer, List<Tx>> prevGroups = group(prevExpenses, categories);
            Map<Integer, List<Tx>> histGroups = group(histExpenses, categories);
            Set<Integer> ids = new TreeSet<>(groups.keySet());
            ids.addAll(prevGroups.keySet());
            List<CategoryStat> stats = new ArrayList<>();
            for (int id : ids) {
                List<Tx> txs = groups.getOrDefault(id, List.of());
                List<Tx> prev = prevGroups.getOrDefault(id, List.of());
                BigDecimal amount = sum(txs), prevAmount = sum(prev);
                String name = id == 0 ? "未分类/分类异常" : categories.get(id).name();
                Map<Integer, List<Tx>> children = new TreeMap<>();
                for (Tx tx : txs) children.computeIfAbsent(id == 0 ? 0 : tx.categoryId(), k -> new ArrayList<>()).add(tx);
                List<Breakdown> breakdown = new ArrayList<>();
                children.forEach((childId, childRows) -> breakdown.add(new Breakdown(childId,
                        childId == 0 ? name : categories.get(childId).name(), money(sum(childRows)), childRows.size())));
                breakdown.sort(Comparator.comparing((Breakdown b) -> decimal(b.amount())).reversed());
                List<LargeExpense> large = txs.stream().filter(t -> amount.signum() > 0
                                && decimal(t.amount()).compareTo(amount.multiply(new BigDecimal("0.20"))) >= 0)
                        .sorted(Comparator.comparing((Tx t) -> decimal(t.amount())).reversed().thenComparingLong(Tx::id))
                        .limit(3).map(t -> new LargeExpense(t.id(), t.date(), money(decimal(t.amount())))).toList();
                CategoryStat stat = new CategoryStat(id, name, money(amount), txs.size(), average(amount, txs.size()),
                        percentage(amount, expense), hasPrevious ? money(prevAmount) : null,
                        hasPrevious ? prev.size() : null, hasPrevious ? average(prevAmount, prev.size()) : null,
                        hasPrevious ? growth(amount, prevAmount) : null,
                        hasHistory ? money(sum(histGroups.getOrDefault(id, List.of())).divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP)) : null,
                        breakdown, large);
                stats.add(stat);
                String prefix = currency + ":category:" + id;
                BigDecimal increase = amount.subtract(prevAmount);
                if (hasPrevious && prevAmount.signum() > 0 && increase.compareTo(prevAmount.multiply(new BigDecimal("0.20"))) >= 0
                        && increase.compareTo(expense.multiply(new BigDecimal("0.05"))) >= 0) {
                    facts.add(new Fact(prefix + ":growth", "growth", currency, id, name + "支出增长明显",
                            Map.of("amount", money(amount), "previousAmount", money(prevAmount), "increase", money(increase), "growth", growth(amount, prevAmount)),
                            "先核对是否有计划内的一次性开支；若属于可调整消费，再考虑设置周限额。"));
                }
                if (hasPrevious && prev.size() > 0 && txs.size() >= 8 && txs.size() >= prev.size() * 1.2) {
                    facts.add(new Fact(prefix + ":frequency", "frequency", currency, id, name + "记账频次增加",
                            Map.of("count", String.valueOf(txs.size()), "previousCount", String.valueOf(prev.size()), "average", stat.average(), "previousAverage", stat.previousAverage()),
                            "查看重复发生的支出，确认是否确有需要；可以对可调整部分试算减少次数，不必削减必要消费。"));
                }
                if (!large.isEmpty()) {
                    BigDecimal largeSum = large.stream().map(l -> decimal(l.amount())).reduce(ZERO, BigDecimal::add);
                    facts.add(new Fact(prefix + ":large", "large", currency, id, name + "存在少数大额记录",
                            Map.of("count", String.valueOf(large.size()), "combinedAmount", money(largeSum), "share", percentage(largeSum, amount)),
                            "核对这些记录是否为一次性、计划内支出，避免直接把本月金额作为长期消费习惯。"));
                }
            }
            stats.sort(Comparator.comparing((CategoryStat c) -> decimal(c.amount())).reversed().thenComparingInt(CategoryStat::categoryId));
            stats.stream().filter(c -> decimal(c.amount()).signum() > 0).limit(5).forEach(c -> facts.add(new Fact(
                    currency + ":category:" + c.categoryId() + ":top", "top", currency, c.categoryId(), c.name() + "是主要支出之一",
                    Map.of("amount", c.amount(), "share", c.share(), "count", String.valueOf(c.count()), "average", c.average()),
                    "先确认这类支出中哪些是必要或计划内开支，再为可调整部分设定目标；占比高不代表浪费。")));
            facts.add(new Fact(currency + ":overview", "overview", currency, null, "月度收支概览",
                    Map.of("income", money(income), "expense", money(expense), "fees", money(fees),
                            "balance", money(income.subtract(expense).subtract(fees)), "count", String.valueOf(rows.size())),
                    "先检查记账是否完整，再结合实际情况规划下月收支。"));
            summaries.add(new CurrencySummary(currency, money(income), money(expense), money(fees), money(income.subtract(expense).subtract(fees)), rows.size(),
                    hasPrevious ? money(prevExpense) : null, hasPrevious ? growth(expense, prevExpense) : null,
                    hasHistory ? money(sum(histExpenses).divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP)) : null, stats));
        }
        List<Tx> cnyExpense = expenses(current.stream().filter(t -> currency(t).equals("CNY")).toList());
        List<BudgetComparison> budgets = new ArrayList<>();
        for (Budget budget : source.budgets()) {
            BigDecimal limit = decimal(budget.amount());
            if (limit.signum() < 0) continue;
            BigDecimal used = sum(cnyExpense, t -> budget.categoryId() == null || belongs(t.categoryId(), budget.categoryId(), categories));
            String name = budget.categoryId() == null ? "总预算" : Optional.ofNullable(categories.get(budget.categoryId())).map(Category::name).orElse("已删除分类");
            BigDecimal excess = used.subtract(limit).max(ZERO);
            budgets.add(new BudgetComparison(budget.id(), budget.categoryId(), name, money(limit), money(used), money(excess), percentage(used, limit)));
            if (excess.signum() > 0) facts.add(new Fact("CNY:budget:" + budget.id(), "budget", "CNY", budget.categoryId(), name + "超预算",
                    Map.of("amount", money(limit), "used", money(used), "excess", money(excess),
                                                "excessPercentage", limit.signum() > 0 ? percentage(excess, limit) : "不适用"),
                    "先核对预算是否覆盖必要支出；必要时调整预算结构，对非必要部分设定每周上限。"));
        }
        facts.sort(Comparator.comparingInt(f -> switch (f.kind()) { case "budget" -> 0; case "growth" -> 1; case "frequency" -> 2; case "large" -> 3; default -> 4; }));
        return new Snapshot(month.toString(), RULE_VERSION, current.size(), summaries, budgets, facts, List.of(
                "仅分析已记录账单，不代表全部收入或消费；一笔流水不等于一件商品。",
                "转账本金不计收支，手续费单列；结余已扣除转账手续费，与原报表口径略有不同。",
                "币种独立计算，不换汇；预算仅比较人民币消费，总预算与分类预算不相加。",
                "缺少历史时不输出环比或三月均值；无记录月份不代表实际没有消费。"));
    }

    public static int root(Integer id, Map<Integer, Category> categories) {
        Set<Integer> seen = new HashSet<>();
        while (id != null && seen.add(id)) {
            Category c = categories.get(id);
            if (c == null || !"expense".equals(c.type())) return 0;
            if (c.parentId() == null) return id;
            id = c.parentId();
        }
        return 0;
    }
    private static boolean belongs(Integer id, int parent, Map<Integer, Category> categories) {
        if (root(id, categories) == 0) return false;
        Set<Integer> seen = new HashSet<>();
        while (id != null && seen.add(id)) {
            if (id == parent) return true;
            Category c = categories.get(id);
            id = c == null ? null : c.parentId();
        }
        return false;
    }
    private static Map<Integer, List<Tx>> group(List<Tx> rows, Map<Integer, Category> categories) {
        Map<Integer, List<Tx>> groups = new TreeMap<>();
        rows.forEach(t -> groups.computeIfAbsent(root(t.categoryId(), categories), k -> new ArrayList<>()).add(t));
        return groups;
    }
    private static List<Tx> inMonth(List<Tx> rows, YearMonth month) { return rows.stream().filter(t -> t.date().startsWith(month.toString())).toList(); }
    private static List<Tx> expenses(List<Tx> rows) { return rows.stream().filter(t -> "expense".equals(t.type())).toList(); }
    private static String currency(Tx t) { return t.currency() == null || t.currency().trim().isEmpty() ? "UNKNOWN" : t.currency().trim(); }
    private static BigDecimal sum(List<Tx> rows) { return sum(rows, t -> true); }
    private static BigDecimal sum(List<Tx> rows, Predicate<Tx> predicate) { return rows.stream().filter(predicate).map(t -> decimal(t.amount())).reduce(ZERO, BigDecimal::add); }
    private static BigDecimal decimal(String s) { return s == null ? ZERO : new BigDecimal(s); }
    private static String money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static String average(BigDecimal v, int n) { return n == 0 ? "0.00" : money(v.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP)); }
    private static String percentage(BigDecimal v, BigDecimal total) { return total.signum() <= 0 ? null : v.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP).toPlainString(); }
    private static String growth(BigDecimal v, BigDecimal prev) { return percentage(v.subtract(prev), prev); }
}
