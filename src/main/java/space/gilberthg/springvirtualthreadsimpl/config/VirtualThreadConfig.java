package space.gilberthg.springvirtualthreadsimpl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration
public class VirtualThreadConfig {

    static final int CHANNEL_CONCURRENCY_LIMIT = 2;

    @Bean(destroyMethod = "close")
    SimpleAsyncTaskExecutor inventoryExecutor() {
        return channelExecutor("inventory-", CHANNEL_CONCURRENCY_LIMIT);
    }

    @Bean(destroyMethod = "close")
    SimpleAsyncTaskExecutor pricingExecutor() {
        return channelExecutor("pricing-", CHANNEL_CONCURRENCY_LIMIT);
    }

    @Bean(destroyMethod = "close")
    SimpleAsyncTaskExecutor shippingExecutor() {
        return channelExecutor("shipping-", CHANNEL_CONCURRENCY_LIMIT);
    }

    private static SimpleAsyncTaskExecutor channelExecutor(String threadNamePrefix, int concurrencyLimit) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadNamePrefix);
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(concurrencyLimit);
        executor.setRejectTasksWhenLimitReached(true);
        return executor;
    }

}
