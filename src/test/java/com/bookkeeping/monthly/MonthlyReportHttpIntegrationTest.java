package com.bookkeeping.monthly;

import com.bookkeeping.BookkeepingApplication;
import com.bookkeeping.dao.entity.AiConfigEntity;
import com.bookkeeping.service.WeatherService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 临时账本 + 随机本地端口 + 真实 Spring/HTTP/AgentScope，全程只使用虚构密钥。 */
class MonthlyReportHttpIntegrationTest {
    @TempDir Path temp;
    final ObjectMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    String origin;

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalOnlyModels {
        @Bean(name = "weatherServiceImpl")
        WeatherService offlineWeather() { return mock(WeatherService.class); }

        @Bean @Primary
        MonthlyAiModelClient localModel(@Value("${test.model-url}") String url) {
            return new MonthlyAiModelClient() {
                @Override public Mono<String> call(AiConfigEntity saved, String system, String payload, int tokens, Duration timeout) {
                    // 仅测试适配接收地址；真实配置入口仍只允许 HTTPS。
                    AiConfigEntity local = new AiConfigEntity();
                    local.setBaseUrl(url); local.setModel(saved.getModel()); local.setApiKey(saved.getApiKey());
                    return super.call(local, system, payload, tokens, timeout);
                }
            };
        }
    }

    @Test
    void configurationPreviewConsentAndSynchronousDetailOverHttp() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> sent = new AtomicReference<>("");
        AtomicReference<String> output = new AtomicReference<>("{\"summary\":\"本地替身完成解读\",\"limitations\":\"仅代表记录数据\",\"observations\":[],\"actions\":[]}");
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = json.writeValueAsBytes(Map.of("id", "test", "object", "chat.completion", "created", 1,
                    "model", "test", "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                            "message", Map.of("role", "assistant", "content", output.get())))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        model.start();
        try (var context = new SpringApplicationBuilder(BookkeepingApplication.class, LocalOnlyModels.class).run(
                "--bookkeeping.dir=" + temp, "--server.address=127.0.0.1", "--server.port=0",
                "--spring.main.allow-bean-definition-overriding=true", "--spring.datasource.hikari.maximum-pool-size=1",
                "--test.model-url=http://127.0.0.1:" + model.getAddress().getPort() + "/v1")) {
            origin = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port") + "/api";
            try (var connection = context.getBean(DataSource.class).getConnection(); var statement = connection.createStatement()) {
                assertTrue(connection.getMetaData().getURL().contains(temp.toString()));
                try (var tables = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='t_monthly_report_ai_job'")) {
                    assertTrue(tables.next()); assertEquals(0, tables.getInt(1));
                }
                assertFalse(context.containsBean("monthlyReportTask"));
                assertFalse(context.containsBean("monthlyAiCoordinator"));
                statement.executeUpdate("INSERT INTO t_account(f_id,f_name,f_type,f_currency) VALUES(999,'私密测试账户','cash','CNY')");
                statement.executeUpdate("INSERT INTO t_transaction(f_type,f_amount,f_fee,f_account_id,f_date,f_note) VALUES('expense','20','0',999,'2024-01-15T12:00:00','私密逐笔备注')");
            }
            JsonNode config = data("PUT", "/monthly-report/ai/config", Map.of("baseUrl", "https://example.invalid/v1", "model", "test", "apiKey", "integration-fake-key", "includeNames", false));
            assertTrue(config.path("hasKey").asBoolean()); assertFalse(config.has("apiKey"));
            assertFalse(config.has("automatic")); assertEquals(0, requests.get());
            data("GET", "/monthly-report?year=2024", null);
            assertNotEquals("S0806", body("POST", "/monthly-report/generate", Map.of("month", "2024-01")).path("code").asString());
            assertNotEquals("S0806", body("POST", "/monthly-report/ai/config/authorize", Map.of("confirmed", true)).path("code").asString());
            JsonNode preview = data("GET", "/monthly-report/ai/preview?month=2024-01", null);
            assertEquals(0, preview.path("baseVersion").asInt());
            assertEquals(config.path("configVersion"), preview.path("configVersion"));
            assertEquals("https://example.invalid/v1", preview.path("baseUrl").asString());
            assertFalse(preview.toString().contains("私密")); assertEquals(0, requests.get());
            Map<String, Object> consent = consent(preview);
            JsonNode completed = data("POST", "/monthly-report/ai/generate", consent);
            long id = completed.path("id").asLong();
            assertEquals("本地替身完成解读", completed.path("result").path("summary").asString());
            assertFalse(completed.has("ai")); assertFalse(completed.has("successfulAi"));
            assertEquals(completed, data("GET", "/monthly-report/" + id, null));
            assertNotEquals("S0806", body("POST", "/monthly-report/ai/generate", consent).path("code").asString());
            assertEquals(1, requests.get()); assertFalse(sent.get().contains("私密")); assertFalse(sent.get().contains("integration-fake-key"));

            Map<String, Object> refresh = consent(data("GET", "/monthly-report/ai/preview?month=2024-01", null));
            output.set("不是 JSON");
            assertEquals("INVALID_JSON", body("POST", "/monthly-report/ai/generate", refresh).path("code").asString());
            output.set("{\"summary\":\"无效事实\",\"limitations\":\"测试\",\"observations\":[{\"text\":\"测试\",\"factIds\":[\"missing-fact\"]}],\"actions\":[]}");
            assertEquals("INVALID_OUTPUT", body("POST", "/monthly-report/ai/generate", refresh).path("code").asString());
            assertEquals(completed, data("GET", "/monthly-report/" + id, null));
            assertEquals(3, requests.get());
            String logs = Files.readString(temp.resolve("backend.log"));
            assertFalse(logs.contains("integration-fake-key"));
            assertFalse(logs.contains("私密逐笔备注"));
            assertFalse(logs.contains("本地替身完成解读"));
        } finally { model.stop(0); }
    }

    private Map<String, Object> consent(JsonNode preview) {
        return Map.of("month", preview.path("month").asString(), "sourceHash", preview.path("sourceHash").asString(),
                "baseVersion", preview.path("baseVersion").asInt(), "configVersion", preview.path("configVersion").asString(), "confirmed", true);
    }

    private JsonNode body(String method, String path, Object payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body());
    }

    private JsonNode data(String method, String path, Object payload) throws Exception {
        JsonNode result = body(method, path, payload);
        assertEquals("S0806", result.path("code").asString(), result.path("msg").asString());
        return result.path("data");
    }
}
