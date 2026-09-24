package com.bookkeeping.monthly;

import com.bookkeeping.dao.entity.AiConfigEntity;
import com.bookkeeping.exception.BusinessException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.*;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * 单次授权的模型边界：不重试、不跳转、无工具、可取消；供应商正文不得进入业务异常或日志。
 */
@Component
public class MonthlyAiModelClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public Mono<String> call(AiConfigEntity config, String system, String payload, int tokens, Duration timeout) {
        if (payload.length() > 100000) return Mono.error(new AiFailure("SUMMARY_TOO_LARGE"));
        HttpTransport delegate = new JdkHttpTransport(http, HttpTransportConfig.builder()
                .connectTimeout(Duration.ofSeconds(10)).responseTimeout(timeout).readTimeout(timeout).build());
        // 即便 Agent 遇到异常工具响应，也不允许同一授权再次发送模型请求。
        HttpTransport transport = new HttpTransport() {
            @Override
            public HttpResponse execute(HttpRequest request) {
                HttpResponse response;
                try { response = delegate.execute(request); }
                catch (RuntimeException e) { throw new AiFailure(errorCode(e)); }
                int status = response.getStatusCode();
                if (status >= 300 && status < 400) throw new AiFailure("REDIRECT_REJECTED");
                if (status < 200 || status >= 300) throw new AiFailure(httpCode(status));
                if (response.getBody() != null && response.getBody().length() > 200000)
                    throw new AiFailure("OUTPUT_TOO_LARGE");
                return response;
            }

            @Override
            public Flux<String> stream(HttpRequest request) {
                return Flux.error(new AiFailure("INVALID_OUTPUT"));
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
        return Mono.defer(() -> {
            OpenAIChatModel model = OpenAIChatModel.builder().apiKey(config.getApiKey())
                    .baseUrl(config.getBaseUrl()).modelName(config.getModel()).stream(false)
                    .httpTransport(transport).generateOptions(GenerateOptions.builder().maxTokens(tokens).build()).build();
            // SDK 的超时包装会丢失原因；稍后触发其兜底，由外层统一执行实际请求时限。
            ReActAgent agent = ReActAgent.builder().name("monthly_report_interpreter").sysPrompt(system)
                    .model(model).toolkit(new Toolkit()).maxIters(1)
                    .modelExecutionConfig(ExecutionConfig.builder().maxAttempts(1).timeout(timeout.plusSeconds(1)).build()).build();
            return agent.call(List.of(new UserMessage(payload)), RuntimeContext.empty())
                    .flatMap(reply -> reply.getTextContent() == null || reply.getTextContent().isBlank()
                            ? Mono.error(new AiFailure("INVALID_OUTPUT")) : Mono.just(reply.getTextContent()))
                    .switchIfEmpty(Mono.error(new AiFailure("INVALID_OUTPUT")));
        }).timeout(timeout).onErrorMap(error -> new AiFailure(errorCode(error))).doFinally(signal -> transport.close());
    }

    private static String httpCode(int status) {
        return switch (status) {
            case 401 -> "HTTP_401";
            case 403 -> "HTTP_403";
            case 429 -> "HTTP_429";
            default -> status >= 300 && status < 400 ? "REDIRECT_REJECTED" : "REQUEST_FAILED";
        };
    }

    /**
     * SDK 包装异常时仍沿 cause 链分类；不解析供应商文本，防止误报与信息泄露。
     */
    public static String errorCode(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable e = error; e != null && seen.add(e); e = e.getCause()) {
            if (e instanceof AiFailure failure) return failure.code;
            if (e instanceof TimeoutException || e instanceof HttpTimeoutException || e instanceof java.net.SocketTimeoutException)
                return "TIMEOUT";
            if (e instanceof InterruptedException || e instanceof CancellationException) return "CANCELLED";
            if (e instanceof HttpTransportException transport && transport.getStatusCode() != null)
                return httpCode(transport.getStatusCode());
            if (e instanceof ConnectException || e instanceof UnknownHostException || e instanceof java.io.IOException)
                return "NETWORK";
        }
        return "REQUEST_FAILED";
    }

    /**
     * 对外只返回固定安全文案；不携带原始 cause，避免全局异常处理器打印供应商请求。
     */
    public static final class AiFailure extends BusinessException {
        private final String code;

        public AiFailure(String code) {
            super(code, message(code));
            this.code = code;
        }

        private static String message(String code) {
            return switch (code) {
                case "HTTP_401" -> "AI 服务鉴权失败，请检查 API Key。";
                case "HTTP_403" -> "AI 服务拒绝访问，请检查密钥权限。";
                case "HTTP_429" -> "AI 服务额度或频率受限，请核对后手动重试。";
                case "TIMEOUT" -> "AI 请求超时，可能已计费，请核对后手动重试。";
                case "CANCELLED" -> "已停止当前调用；已发送请求无法保证从服务商撤回。";
                case "CONFIG_CHANGED" -> "AI 配置已变化，请重新预览并确认。";
                case "NO_DATA" -> "本月没有可分析的流水，未调用 AI。";
                case "NETWORK" -> "无法连接 AI 服务，请检查网络与服务地址。";
                case "REDIRECT_REJECTED" -> "已拒绝服务重定向，请核对 HTTPS 服务根地址。";
                case "INVALID_JSON" -> "AI 未返回有效 JSON，未保存本次报告，已有报告保持不变。";
                case "INVALID_OUTPUT" -> "AI 响应不符合约定，未保存本次报告，已有报告保持不变。";
                case "SUMMARY_TOO_LARGE" -> "月报摘要超过安全上限，未发送。";
                case "OUTPUT_TOO_LARGE" -> "AI 响应超过安全上限。";
                case "BUSY" -> "已有 AI 请求正在处理，请等待当前请求结束，不会排队。";
                case "STORAGE_FAILED" -> "本地 AI 数据读写失败，请检查磁盘状态后重新读取配置和已保存报告。";
                case "BACKEND" -> "后端正在恢复数据或退出，请重启后再操作。";
                default -> "AI 请求失败，已有报告保持不变，请核对服务后手动重试。";
            };
        }
    }
}
