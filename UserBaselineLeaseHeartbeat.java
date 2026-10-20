package com.vpp.platform.resource.baseline;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 在耗时计算之外独立维护数据库租约。 */
@Slf4j
@Component
public class UserBaselineLeaseHeartbeat {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "user-baseline-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public LeaseHandle start(String description, int leaseSeconds, BooleanSupplier renewAction) {
        int interval = Math.max(1, leaseSeconds / 3);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            try {
                if (!renewAction.getAsBoolean()) {
                    failure.compareAndSet(null, new IllegalStateException(description + "租约续期失败，已失去执行权。"));
                }
            } catch (Exception e) {
                log.error("[用户基线] {}租约心跳异常", description, e);
                failure.compareAndSet(null, new IllegalStateException(description + "租约心跳异常：" + e.getMessage(), e));
            }
        }, interval, interval, TimeUnit.SECONDS);
        return new LeaseHandle(future, failure);
    }

    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }

    public record LeaseHandle(ScheduledFuture<?> future, AtomicReference<RuntimeException> failure)
            implements AutoCloseable {

        public void assertActive() {
            RuntimeException exception = failure.get();
            if (exception != null) {
                throw exception;
            }
        }

        @Override
        public void close() {
            future.cancel(false);
        }
    }
}
