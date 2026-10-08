package com.bookkeeping.bill;

import com.bookkeeping.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 导入时用户选择的账户。键是文件里的账户名，值是已有账户 id；小于等于 0 表示按该名称新建。
 */
public final class AccountMaps {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AccountMaps() {
    }

    public static Map<String, Integer> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new BusinessException("B0017", "账户选择无法识别");
            }
            Map<String, Integer> map = new LinkedHashMap<>();
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode value = entry.getValue();
                if (value == null || value.isNull() || value.isNumber()) {
                    map.put(entry.getKey(), value == null || value.isNull() ? 0 : value.intValue());
                } else {
                    throw new BusinessException("B0017", "账户选择无法识别");
                }
            }
            return Map.copyOf(map);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException("B0017", "账户选择无法识别");
        } catch (Exception e) {
            throw new BusinessException("B0017", "账户选择无法识别");
        }
    }
}
