package io.revenium.metering.openai.transport;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.model.MeteringEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fire-and-forget async HTTP transport for the Revenium AI metering API.
 *
 * <p>Accepts {@link MeteringEvent} objects and POSTs them as JSON to the Revenium API on a
 * dedicated bounded executor. {@link #send(MeteringEvent)} returns immediately — callers are
 * never blocked. Metering errors (HTTP 5xx, network failures) are logged at WARN and dropped.
 *
 * <p>This class is thread-safe and reusable across concurrent callers. Use one instance per
 * application lifetime. Implements {@link AutoCloseable} — call {@link #close()} when done
 * to allow a best-effort drain of pending metering tasks.
 *
 * <p>Example usage:
 * <pre>{@code
 * MeteringClient client = new MeteringClient(ReveniumConfig.builder()
 *     .apiKey("your-api-key")
 *     .build());
 * client.send(event);
 * // ...
 * client.close();
 * }</pre>
 */
public final class MeteringClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MeteringClient.class);

    private static final String ENDPOINT_PATH = "/meter/v2/ai/completions";

    private final ReveniumConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    /**
     * Constructs a new {@code MeteringClient} with the given configuration.
     *
     * <p>Registers a JVM shutdown hook to drain any pending metering tasks on exit.
     *
     * @param config Revenium configuration (API key, base URL)
     */
    public MeteringClient(ReveniumConfig config) {
        this.config = config;
        this.mapper = buildObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.executor = buildExecutor();

        // Best-effort drain on JVM shutdown for users who don't call close() explicitly (D-17)
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeQuietly, "revenium-shutdown"));
    }

    /**
     * Submits a metering event for async fire-and-forget delivery to the Revenium API.
     *
     * <p>Returns immediately — the actual HTTP POST runs on the dedicated metering executor.
     * If the executor queue is full, the event is silently discarded (logged at DEBUG).
     * If the executor has been shut down, the event is silently ignored.
     *
     * @param event the metering event to send; must not be null
     */
    public void send(MeteringEvent event) {
        CompletableFuture.runAsync(() -> doPost(event), executor)
                .exceptionally(ex -> {
                    log.warn("Metering failed: {} for model {}", ex.getMessage(), event.getModel());
                    return null;
                });
    }

    /**
     * Serializes the event and performs the synchronous HTTP POST on the background executor thread.
     * All exceptions are caught and logged at WARN (D-08).
     */
    private void doPost(MeteringEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            String url = config.baseUrl() + ENDPOINT_PATH;

            if (log.isDebugEnabled()) {
                log.debug("Metering POST {} {}", url, json);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", config.apiKey())
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() != 201) {
                log.warn("Metering failed: {} for model {}", response.statusCode(), event.getModel());
            }
        } catch (Exception ex) {
            log.warn("Metering failed: {} for model {}", ex.getMessage(), event.getModel());
        }
    }

    /**
     * Shuts down the metering executor with a best-effort 5-second drain (D-16).
     *
     * <p>After {@code close()}, any subsequent {@link #send} calls are silently dropped
     * (the rejected execution handler discards them).
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Closes without propagating exceptions — used by the JVM shutdown hook.
     */
    private void closeQuietly() {
        try {
            close();
        } catch (Exception ignored) {}
    }

    /**
     * Builds a field-visibility ObjectMapper that can serialize Phase 1 classes
     * (Subscriber, Credential) whose accessors are not standard JavaBean getters.
     */
    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        return mapper;
    }

    /**
     * Builds a bounded dedicated {@link ThreadPoolExecutor} for metering (D-11 through D-14).
     *
     * <ul>
     *   <li>2 daemon threads named "revenium-metering-N"</li>
     *   <li>Bounded queue capacity of 1000</li>
     *   <li>DiscardPolicy with DEBUG log when queue is full</li>
     * </ul>
     */
    private static ExecutorService buildExecutor() {
        AtomicInteger counter = new AtomicInteger(0);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1000),
                runnable -> {
                    Thread thread = new Thread(runnable, "revenium-metering-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, pool) -> log.debug("Metering task discarded — executor queue full")
        );

        return executor;
    }
}
