package space.gilberthg.springvirtualthreadsimpl.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadModelComparisonTest {

    private static final int TASKS = 32;

    private static final long SLEEP_MILLIS = 50;

    private static final int PLATFORM_POOL_SIZE = 4;

    @Test
    void virtualThreadsFinishBlockingWorkInOneWave() throws Exception {
        AtomicBoolean platformSawVirtual = new AtomicBoolean(true);
        ExecutorService platform = Executors.newFixedThreadPool(PLATFORM_POOL_SIZE);
        long platformMillis;
        try {
            platformMillis = runAll(platform, platformSawVirtual);
        }
        finally {
            platform.close();
        }

        AtomicBoolean virtualSawVirtual = new AtomicBoolean(false);
        long virtualMillis;
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            virtualMillis = runAll(virtual, virtualSawVirtual);
        }

        assertFalse(platformSawVirtual.get());
        assertTrue(virtualSawVirtual.get());
        assertTrue(platformMillis > 300, "platform pool took " + platformMillis + " ms");
        assertTrue(virtualMillis < 250, "virtual threads took " + virtualMillis + " ms");
    }

    private static long runAll(ExecutorService executor, AtomicBoolean sawVirtual) throws InterruptedException {
        List<Callable<Void>> calls = new ArrayList<>();
        for (int i = 0; i < TASKS; i++) {
            calls.add(() -> {
                sawVirtual.set(Thread.currentThread().isVirtual());
                Thread.sleep(SLEEP_MILLIS);
                return null;
            });
        }
        long startedAt = System.nanoTime();
        executor.invokeAll(calls);
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

}
