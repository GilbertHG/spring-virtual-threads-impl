package space.gilberthg.springvirtualthreadsimpl.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import space.gilberthg.springvirtualthreadsimpl.model.Report;
import space.gilberthg.springvirtualthreadsimpl.model.Section;
import space.gilberthg.springvirtualthreadsimpl.model.ThreadInfo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class ReportService {

    private final SimpleAsyncTaskExecutor inventoryExecutor;

    private final SimpleAsyncTaskExecutor pricingExecutor;

    private final SimpleAsyncTaskExecutor shippingExecutor;

    private final DownstreamClient downstreamClient;

    public ReportService(
            @Qualifier("inventoryExecutor") SimpleAsyncTaskExecutor inventoryExecutor,
            @Qualifier("pricingExecutor") SimpleAsyncTaskExecutor pricingExecutor,
            @Qualifier("shippingExecutor") SimpleAsyncTaskExecutor shippingExecutor,
            DownstreamClient downstreamClient) {
        this.inventoryExecutor = inventoryExecutor;
        this.pricingExecutor = pricingExecutor;
        this.shippingExecutor = shippingExecutor;
        this.downstreamClient = downstreamClient;
    }

    public Report load() {
        ThreadInfo requestThread = ThreadInfo.current();
        long startedAt = System.nanoTime();
        List<Section> sections = fetchAll();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return new Report(sections, requestThread, elapsedMillis);
    }

    private List<Section> fetchAll() {
        List<CompletableFuture<Section>> futures = List.of(
                submit(inventoryExecutor, "inventory", downstreamClient::inventory),
                submit(pricingExecutor, "pricing", downstreamClient::pricing),
                submit(shippingExecutor, "shipping", downstreamClient::shipping));
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for downstream calls", ex);
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new IllegalStateException("Downstream call failed", cause);
        }
        return futures.stream().map(ReportService::valueOf).toList();
    }

    private static CompletableFuture<Section> submit(SimpleAsyncTaskExecutor executor, String source, Supplier<String> call) {
        return executor.submitCompletable(() -> section(source, call));
    }

    private static Section section(String source, Supplier<String> call) {
        return new Section(source, call.get(), ThreadInfo.current());
    }

    private static Section valueOf(CompletableFuture<Section> future) {
        try {
            return future.get();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading a downstream result", ex);
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new IllegalStateException("Downstream call failed", cause);
        }
    }

}
