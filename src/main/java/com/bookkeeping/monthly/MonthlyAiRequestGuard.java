package com.bookkeeping.monthly;

import com.bookkeeping.monthly.MonthlyAiModelClient.AiFailure;
import jakarta.annotation.PreDestroy;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 仅保护当前 HTTP 请求：无队列、调度、作业编号或持久化运行态。
 * 配置提交、调用启动和结果接受共用短锁；网络等待始终在锁和事务之外。
 */
@Component
public class MonthlyAiRequestGuard {
    private final ReentrantLock lock = new ReentrantLock();
    private final TransactionTemplate transaction;
    private Handle active;
    private boolean paused;

    public MonthlyAiRequestGuard(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
    }

    public <T> T locked(Supplier<T> action) {
        lock.lock();
        try { return action.get(); }
        finally { lock.unlock(); }
    }

    /** 数据库异常可能携带绑定值，只向上暴露安全业务错误。 */
    public <T> T mutate(Supplier<T> action) {
        return locked(() -> {
            try { return transaction.execute(status -> action.get()); }
            catch (DataAccessException | TransactionException e) { throw new AiFailure("STORAGE_FAILED"); }
        });
    }

    public void requireAvailable() {
        if (paused) throw new AiFailure("BACKEND");
    }

    /** 调用方持锁，先占用唯一槽位，再订阅模型；失败不遗留槽位。 */
    public Handle start(Supplier<Mono<String>> action, Duration timeout) {
        requireAvailable();
        if (active != null) throw new AiFailure("BUSY");
        Handle handle = new Handle();
        active = handle;
        try {
            handle.future = action.get().timeout(timeout).toFuture();
            return handle;
        } catch (RuntimeException e) {
            active = null;
            throw new AiFailure(MonthlyAiModelClient.errorCode(e));
        }
    }

    /** 在请求线程等待，不创建应用后台工作线程。 */
    public String await(Handle handle) {
        try {
            String text = handle.future.get();
            locked(() -> { check(handle); return null; });
            if (text == null || text.isBlank()) throw new AiFailure("INVALID_OUTPUT");
            return text;
        } catch (Exception e) {
            handle.future.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            locked(() -> { check(handle); return null; });
            throw new AiFailure(MonthlyAiModelClient.errorCode(e));
        }
    }

    /** 接受结果时必须在短事务及锁内复核，取消后即使模型返回也不落库。 */
    public void check(Handle handle) {
        if (handle.cancelCode != null) throw new AiFailure(handle.cancelCode);
        requireAvailable();
        if (active != handle) throw new AiFailure("CANCELLED");
    }

    public void release(Handle handle) {
        locked(() -> {
            if (active == handle) active = null;
            return null;
        });
    }

    /** 配置事务成功提交后调用；保留槽位直到原请求 finally 释放。 */
    public void cancelCurrent(String code) {
        locked(() -> {
            if (active != null) {
                active.cancelCode = code;
                if (active.future != null) active.future.cancel(true);
            }
            return null;
        });
    }

    public void pauseForRestore() {
        locked(() -> {
            paused = true;
            cancelCurrent("CANCELLED");
            return null;
        });
    }

    public void resumeAfterRestoreFailure() {
        locked(() -> { paused = false; return null; });
    }

    @PreDestroy
    public void shutdown() { pauseForRestore(); }

    public static final class Handle {
        private CompletableFuture<String> future;
        private String cancelCode;
    }
}
