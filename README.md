# Spring virtual threads

A small Spring Boot 4.1 application on Java 21 that shows where virtual threads help: handling many requests that spend their time waiting on I/O.

`GET /api/reports` builds one response from three slow downstream calls (inventory, pricing, and shipping). Those calls block for 300 ms each. They run at the same time on virtual threads, so the response returns in about 300 ms instead of about 900 ms.

## What a virtual thread is

A virtual thread is a Java thread scheduled by the JVM, not a thread the operating system creates for you. While it runs Java code, it sits on a small pool of ordinary platform threads called carrier threads. When it blocks on I/O, `Thread.sleep`, or a lock that the JVM knows how to park, the JVM unmounts it from the carrier. The carrier is then free to run another virtual thread.

Creating a virtual thread is cheap, so you can use one per task. You do not pool them.

## How this app uses them

Two separate mechanisms are in play.

**Each HTTP request runs on a virtual thread.** [`application.properties`](src/main/resources/application.properties) sets:

```properties
spring.threads.virtual.enabled=true
```

Spring Boot then uses virtual threads for Tomcat request handling and for the auto-configured `applicationTaskExecutor` (the executor behind `@Async`). This app also defines its own `Executor` beans, which would otherwise replace that auto-configured executor. `spring.task.execution.mode=force` keeps both.

**Each downstream channel has its own executor.** [`VirtualThreadConfig`](src/main/java/space/gilberthg/springvirtualthreadsimpl/config/VirtualThreadConfig.java) builds three `SimpleAsyncTaskExecutor` beans: `inventoryExecutor`, `pricingExecutor`, and `shippingExecutor`. Each one starts a virtual thread per task (`setVirtualThreads(true)`) and names that thread with its channel prefix (`inventory-1`, `pricing-1`, `shipping-1`). [`ReportService`](src/main/java/space/gilberthg/springvirtualthreadsimpl/service/ReportService.java) submits one call to each executor with `submitCompletable`, then waits until all three finish. [`DownstreamClient`](src/main/java/space/gilberthg/springvirtualthreadsimpl/service/DownstreamClient.java) stands in for a real HTTP or JDBC call by sleeping on the calling thread.

A separate executor does not give a channel its own carrier threads. Every virtual thread in the JVM still mounts on the same small set of platform carriers. The per-channel control is the concurrency limit: each executor allows 2 in-flight calls and rejects the next one with `TaskRejectedException`. One report only submits one task per channel, so `GET /api/reports` stays under the limit. A third overlapping call on the same channel is the one that fails. That limit is the bulkhead. It protects the remote system, which is the scarce resource, while the virtual threads only decide which thread waits.

The JSON response tells you which thread did the work:

- `requestThread` is the thread that entered `ReportService.load()`. With the property above, a live HTTP request shows `virtual: true`.
- Each `sections[].thread` is the virtual thread from that channel's executor. The name starts with `inventory-`, `pricing-`, or `shipping-`.

## Best case

Virtual threads pay off when a lot of tasks are runnable only for a short time and then wait. The usual shape is a backend-for-frontend or API endpoint that must call several slow systems and combine the results:

1. The client calls `GET /api/reports`.
2. Tomcat runs that request on a virtual thread.
3. The service submits each downstream call to that channel's executor, which starts a virtual thread.
4. Each call blocks. Those virtual threads park, and the few carrier threads stay available for other requests.
5. When the slowest call returns, the service writes one response.

On a platform-thread pool (Tomcat's default is on the order of 200 threads), every blocked call occupies one of those threads. Enough concurrent reports exhaust the pool and new requests queue even though the CPU is idle. Virtual threads remove that ceiling for waiting work. Thousands of blocked calls can park while a handful of carrier threads run whatever is actually ready.

## When not to use them

- CPU-bound work does not get faster. A tight calculation still needs a carrier the whole time, and a crowd of virtual threads can add scheduling overhead.
- On Java 21, a virtual thread that blocks inside a `synchronized` block can pin its carrier thread so the carrier cannot run anything else. Prefer `java.util.concurrent` locks for long critical sections, or move blocking calls outside `synchronized`. This sample only blocks in `Thread.sleep`, which does not pin.
- Do not put virtual threads in a pool. Create one per task and let it finish.

`StructuredTaskScope` is a preview API on Java 21 and needs `--enable-preview`. This project uses `SimpleAsyncTaskExecutor`, which can start a virtual thread per task and cap how many of those tasks run at once.

## Run

```bash
./mvnw spring-boot:run
```

```bash
curl http://localhost:8093/api/reports
```

A response looks like this (elapsed time varies):

```json
{
  "sections": [
    {
      "source": "inventory",
      "value": "42 units in stock",
      "thread": { "virtual": true, "name": "inventory-1" }
    },
    {
      "source": "pricing",
      "value": "USD 19.00",
      "thread": { "virtual": true, "name": "pricing-1" }
    },
    {
      "source": "shipping",
      "value": "2 days",
      "thread": { "virtual": true, "name": "shipping-1" }
    }
  ],
  "requestThread": { "virtual": true, "name": "tomcat-handler-0" },
  "elapsedMillis": 310
}
```

`requestThread.name` comes from Tomcat. With virtual threads enabled, Spring Boot sets Tomcat's executor to `VirtualThreadExecutor("tomcat-handler-")`, so the name is `tomcat-handler-` plus a counter. Downstream names come from the channel executor prefix plus a counter. The unit test calls `ReportService` directly, so its `requestThread` is the JUnit platform thread rather than a Tomcat thread.

## Test

[`ReportServiceTest`](src/test/java/space/gilberthg/springvirtualthreadsimpl/service/ReportServiceTest.java) does not start Spring. It builds the three channel executors itself.

The fan-out test checks two things:

- every section ran on a virtual thread whose name starts with that channel's prefix
- three 300 ms blocking calls finish in under 700 ms, which only happens if they overlap

The bulkhead test uses an inventory executor with a limit of 1. It submits one call that stays blocked, then asserts the next submit throws `TaskRejectedException`.

[`SpringVirtualThreadsImplApplicationTests`](src/test/java/space/gilberthg/springvirtualthreadsimpl/SpringVirtualThreadsImplApplicationTests.java) still checks that the application context starts with the virtual-thread property and the channel executor beans.

```bash
./mvnw test
```
