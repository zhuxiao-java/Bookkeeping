package com.bookkeeping.monthly;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class MonthlyReportTask implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(MonthlyReportTask.class);
    private final MonthlyReportService service;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "monthly-report");
        t.setDaemon(true);
        return t;
    });
    public MonthlyReportTask(MonthlyReportService service) { this.service = service; }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { worker.submit(this::check); }
    @Scheduled(cron = "0 0 9 * * *")
    public void daily() { worker.submit(this::check); }
    private void check() {
        try { service.generateLatest(); }
        catch (Exception e) { log.warn("本地月报检查失败，下次检查时重试"); }
    }
    @Override
    public void destroy() { worker.shutdownNow(); }
}
