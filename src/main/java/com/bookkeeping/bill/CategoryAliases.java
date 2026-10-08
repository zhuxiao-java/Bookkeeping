package com.bookkeeping.bill;

import com.bookkeeping.constant.CategoryType;
import com.bookkeeping.dao.dto.CategoryDTO;

import java.util.List;
import java.util.Map;

/**
 * 把微信交易类型、支付宝交易分类对到账本里已有的分类名。
 * 对不上时支出记「待整理」、收入记「待整理收入」——分类名全局唯一，不能共用一个名字。
 */
public final class CategoryAliases {

    public static final String EXPENSE_FALLBACK = "待整理";
    public static final String INCOME_FALLBACK = "待整理收入";

    private static final Map<String, List<String>> EXPENSE = Map.ofEntries(
            Map.entry("餐饮美食", List.of("餐饮")),
            Map.entry("美食", List.of("餐饮")),
            Map.entry("交通出行", List.of("交通")),
            Map.entry("出行", List.of("交通")),
            Map.entry("日用百货", List.of("购物")),
            Map.entry("服饰装扮", List.of("购物")),
            Map.entry("购物", List.of("购物")),
            Map.entry("住房物业", List.of("居住")),
            Map.entry("生活缴费", List.of("居住")),
            Map.entry("文化休闲", List.of("娱乐")),
            Map.entry("休闲娱乐", List.of("娱乐")),
            Map.entry("医疗健康", List.of("医疗")),
            Map.entry("教育培训", List.of("教育")),
            Map.entry("充值缴费", List.of("通讯")),
            Map.entry("通讯物流", List.of("通讯")),
            Map.entry("红包", List.of("红包支出")),
            Map.entry("微信红包", List.of("红包支出")),
            Map.entry("发红包", List.of("红包支出"))
    );

    private static final Map<String, List<String>> INCOME = Map.ofEntries(
            Map.entry("工资", List.of("工资")),
            Map.entry("奖金", List.of("奖金")),
            Map.entry("理财", List.of("理财")),
            Map.entry("理财收益", List.of("理财")),
            Map.entry("红包", List.of("微信红包", "红包")),
            Map.entry("微信红包", List.of("微信红包", "红包")),
            Map.entry("收红包", List.of("微信红包", "红包")),
            Map.entry("退款", List.of("报销")),
            Map.entry("兼职", List.of("兼职"))
    );

    private CategoryAliases() {
    }

    public record Match(String name, boolean fallback) {
    }

    /**
     * 先按分类名精确匹配，再查别名。别名指向的分类必须已经存在，否则落入待整理。
     */
    public static Match resolve(String hint, CategoryType want, Map<String, CategoryDTO> byName) {
        String raw = hint == null ? "" : hint.strip();
        if (!raw.isEmpty()) {
            CategoryDTO exact = find(byName, raw);
            if (exact != null && typeOk(exact, want)) {
                return new Match(exact.getName(), false);
            }
            List<String> aliases = (want == CategoryType.INCOME ? INCOME : EXPENSE).get(compact(raw));
            if (aliases != null) {
                for (String candidate : aliases) {
                    CategoryDTO category = find(byName, candidate);
                    if (category != null && typeOk(category, want)) {
                        return new Match(category.getName(), false);
                    }
                }
            }
        }
        String fallback = want == CategoryType.INCOME ? INCOME_FALLBACK : EXPENSE_FALLBACK;
        return new Match(fallback, true);
    }

    private static boolean typeOk(CategoryDTO category, CategoryType want) {
        return category.getType() == null || category.getType() == want;
    }

    private static CategoryDTO find(Map<String, CategoryDTO> byName, String name) {
        CategoryDTO direct = byName.get(name);
        if (direct != null) {
            return direct;
        }
        String key = compact(name);
        for (CategoryDTO category : byName.values()) {
            if (category.getName() != null && compact(category.getName()).equals(key)) {
                return category;
            }
        }
        return null;
    }

    private static String compact(String name) {
        return name.replace(" ", "").replace("\u3000", "").replace("\u00A0", "");
    }
}
