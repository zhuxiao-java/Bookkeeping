package com.bookkeeping.monthly;

import com.bookkeeping.dao.entity.AiConfigEntity;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 使用本机模型替身走真实 SDK/HTTP 传输，不使用供应商密钥，也不放宽生产 HTTPS 校验。 */
class MonthlyAiModelClientTest {
    HttpServer server;
    ExecutorService executor;
    AiConfigEntity config;
    AtomicInteger requests;
    AtomicInteger redirected;
    CountDownLatch received;
    MonthlyAiModelClient client = new MonthlyAiModelClient();
    volatile int status = 200;
    volatile String content = "OK";
    volatile long delay;
    volatile String requestPath;
    volatile String requestBody;
    static final String TEST_KEY = "test-only-not-a-real-key";

    @BeforeEach
    void setup() throws Exception {
        requests = new AtomicInteger();
        redirected = new AtomicInteger();
        received = new CountDownLatch(1);
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            received.countDown();
            requestPath = exchange.getRequestURI().getPath();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(delay);
                String json = "{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + content
                        + "\"},\"finish_reason\":\"stop\"}]}";
                byte[] body = (status == 200 ? json : "供应商正文不得外泄-" + TEST_KEY).getBytes(StandardCharsets.UTF_8);
                if (status >= 300 && status < 400) exchange.getResponseHeaders().set("Location", "/redirect-target");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/redirect-target", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.start();
        config = new AiConfigEntity();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setApiKey(TEST_KEY);
        config.setModel("test-model");
    }

    @AfterEach
    void stop() { server.stop(0); executor.shutdownNow(); }

    @Test
    void singleRequestWithRealAgent() {
        assertEquals("OK", client.call(config, "仅回复 OK", "连接测试", 16, Duration.ofSeconds(5)).block());
        assertEquals(1, requests.get());
        assertEquals("/v1/chat/completions", requestPath);
        assertTrue(requestBody.contains("test-model"));
        assertFalse(requestBody.contains(TEST_KEY));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 307, 308})
    void neverRetriesRedirectsOrExposesErrorBody(int code) {
        status = code;
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client.call(config, "测试", "测试", 16, Duration.ofSeconds(5)).block());
        String expected = code == 500 ? "REQUEST_FAILED" : code >= 300 && code < 400 ? "REDIRECT_REJECTED" : "HTTP_" + code;
        assertEquals(expected, MonthlyAiModelClient.errorCode(error));
        assertEquals(1, requests.get());
        assertFalse(error.toString().contains(TEST_KEY));
        assertFalse(error.toString().contains("供应商正文"));
        assertEquals(0, redirected.get());
    }

    @Test
    void emptyOutputIsNotSuccessful() {
        content = "";
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client.call(config, "测试", "测试", 16, Duration.ofSeconds(5)).block());
        assertEquals("INVALID_OUTPUT", MonthlyAiModelClient.errorCode(error));
        assertEquals(1, requests.get());
    }

    @org.junit.jupiter.api.RepeatedTest(10)
    void timeoutDoesNotRetry() {
        delay = 1500;
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client.call(config, "测试", "测试", 16, Duration.ofMillis(400)).block());
        assertEquals("TIMEOUT", MonthlyAiModelClient.errorCode(error));
        assertTrue(requests.get() <= 1);
    }

    @Test
    void cancellationStopsSubscriptionWithoutResending() throws Exception {
        delay = 500;
        CountDownLatch cancelled = new CountDownLatch(1);
        var pending = client.call(config, "测试", "测试", 16, Duration.ofSeconds(5))
                .doOnCancel(cancelled::countDown).toFuture();
        assertTrue(received.await(2, TimeUnit.SECONDS));
        assertTrue(pending.cancel(true));
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        Thread.sleep(700);
        assertTrue(pending.isCancelled());
        assertEquals(1, requests.get());
    }

    @Test
    void productionConfigurationStillRejectsHttp() {
        assertThrows(RuntimeException.class, () -> AiConfigService.normalizeBaseUrl(config.getBaseUrl()));
    }
}
