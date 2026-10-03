package space.gilberthg.springvirtualthreadsimpl.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import space.gilberthg.springvirtualthreadsimpl.model.Report;
import space.gilberthg.springvirtualthreadsimpl.model.Section;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {

    @Test
    void fanOutRunsOnVirtualThreadsAndOverlapsBlockingCalls() {
        SimpleAsyncTaskExecutor inventoryExecutor = channelExecutor("inventory-", 2);
        SimpleAsyncTaskExecutor pricingExecutor = channelExecutor("pricing-", 2);
        SimpleAsyncTaskExecutor shippingExecutor = channelExecutor("shipping-", 2);
        try {
            ReportService service = new ReportService(inventoryExecutor, pricingExecutor, shippingExecutor,
                    new DownstreamClient());

            long startedAt = System.nanoTime();
            Report report = service.load();
            long wallClockMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertEquals(List.of("inventory", "pricing", "shipping"),
                    report.sections().stream().map(Section::source).toList());
            assertEquals(List.of("42 units in stock", "USD 19.00", "2 days"),
                    report.sections().stream().map(Section::value).toList());
            for (Section section : report.sections()) {
                assertTrue(section.thread().virtual(), section.source());
                assertTrue(section.thread().name().startsWith(section.source() + "-"), section.thread().name());
            }

            long parallelBoundMillis = DownstreamClient.BLOCK_MILLIS * 2 + 100;
            assertTrue(report.elapsedMillis() < parallelBoundMillis,
                    "fan-out took " + report.elapsedMillis() + " ms");
            assertTrue(wallClockMillis < parallelBoundMillis, "wall clock took " + wallClockMillis + " ms");
        }
        finally {
            inventoryExecutor.close();
            pricingExecutor.close();
            shippingExecutor.close();
        }
    }

    @Test
    void inventoryChannelRejectsWorkBeyondItsConcurrencyLimit() throws Exception {
        SimpleAsyncTaskExecutor inventoryExecutor = channelExecutor("inventory-", 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Void> inFlight = inventoryExecutor.submitCompletable(() -> {
                release.await();
                return null;
            });

            TaskRejectedException rejected = assertThrows(TaskRejectedException.class,
                    () -> inventoryExecutor.submitCompletable(() -> "second"));
            assertTrue(rejected.getMessage().contains("Concurrency limit reached"), rejected.getMessage());

            release.countDown();
            inFlight.get(2, TimeUnit.SECONDS);
        }
        finally {
            release.countDown();
            inventoryExecutor.close();
        }
    }

    private static SimpleAsyncTaskExecutor channelExecutor(String threadNamePrefix, int concurrencyLimit) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadNamePrefix);
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(concurrencyLimit);
        executor.setRejectTasksWhenLimitReached(true);
        return executor;
    }

}
