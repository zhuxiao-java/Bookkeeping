package com.bookkeeping.controller;

import org.sf.model.response.DataResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 controller：为 Electron 主进程提供轻量、无副作用的就绪探针（OPT-06）。
 * 经 WebConfig 统一加 /api 前缀后实际路径为 GET /api/health。
 *
 * @author zhuxiao
 */
@RestController
@RequestMapping("health")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 就绪探针：HTTP 层始终返回 200（进程存活即可被主进程探活命中），
     * body.status 反映整体状态，body.database 反映 DB 连通（SELECT 1）。
     */
    @GetMapping
    public DataResponse<Map<String, Object>> health() {
        boolean dbUp = checkDatabase();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("database", dbUp ? "UP" : "DOWN");
        body.put("time", LocalDateTime.now(ZoneId.systemDefault()).toString());
        return DataResponse.of(body);
    }

    private boolean checkDatabase() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
