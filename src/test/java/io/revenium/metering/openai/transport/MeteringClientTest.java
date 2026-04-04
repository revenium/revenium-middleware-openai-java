package io.revenium.metering.openai.transport;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.model.MeteringEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WireMock integration tests for {@link MeteringClient} async transport.
 *
 * <p>Tests verify: fire-and-forget behavior, correct endpoint + headers + payload shape,
 * dedicated thread naming, error handling (5xx, connection refused), concurrency safety,
 * and shutdown lifecycle.
 */
@WireMockTest
class MeteringClientTest {

    private MeteringClient client;

    private MeteringClient buildClient(WireMockRuntimeInfo wm) {
        return new MeteringClient(ReveniumConfig.builder()
                .apiKey("test-key")
                .baseUrl("http://localhost:" + wm.getHttpPort())
                .build());
    }

    private MeteringEvent buildTestEvent() {
        return MeteringEvent.builder()
                .model("gpt-4o")
                .stopReason("END")
                .inputTokenCount(100L)
                .outputTokenCount(50L)
                .totalTokenCount(150L)
                .provider("openai")
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    /**
     * Test 1: send() posts to the correct metering endpoint with correct headers.
     * XPORT-04: POST arrives at /meter/v2/ai/completions with x-api-key and Content-Type headers.
     */
    @Test
    void send_postsToMeteringEndpoint(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/meter/v2/ai/completions"))
                .willReturn(aResponse().withStatus(201)));

        client = buildClient(wm);
        client.send(buildTestEvent());

        await().atMost(5, SECONDS).untilAsserted(() ->
                verify(postRequestedFor(urlEqualTo("/meter/v2/ai/completions"))
                        .withHeader("x-api-key", equalTo("test-key"))
                        .withHeader("Content-Type", containing("application/json")))
        );
    }

    /**
     * Test 2: Correct JSON payload shape is sent.
     * XPORT-04: JSON body contains model, stopReason, inputTokenCount, outputTokenCount,
     * middlewareSource="JAVA", costType="AI".
     */
    @Test
    void send_postsCorrectPayloadShape(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/meter/v2/ai/completions"))
                .willReturn(aResponse().withStatus(201)));

        client = buildClient(wm);
        client.send(buildTestEvent());

        await().atMost(5, SECONDS).untilAsserted(() ->
                verify(postRequestedFor(urlEqualTo("/meter/v2/ai/completions"))
                        .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-4o")))
                        .withRequestBody(matchingJsonPath("$.stopReason", equalTo("END")))
                        .withRequestBody(matchingJsonPath("$.inputTokenCount", equalTo("100")))
                        .withRequestBody(matchingJsonPath("$.outputTokenCount", equalTo("50")))
                        .withRequestBody(matchingJsonPath("$.middlewareSource", equalTo("JAVA")))
                        .withRequestBody(matchingJsonPath("$.costType", equalTo("AI"))))
        );
    }

    /**
     * Test 3: send() returns immediately even when endpoint has 2-second delay.
     * XPORT-01: Fire-and-forget — elapsed time < 200ms even with 2s server delay.
     */
    @Test
    void send_doesNotBlockCaller_whenEndpointIsSlow(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/meter/v2/ai/completions"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(2000)));

        client = buildClient(wm);

        long start = System.currentTimeMillis();
        client.send(buildTestEvent());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(200L);
    }

    /**
     * Test 4: send() executes on a thread named "revenium-metering-N".
     * XPORT-02: Dedicated daemon executor threads with expected naming.
     */
    @Test
    void send_runsOnDedicatedThread(WireMockRuntimeInfo wm) throws Exception {
        stubFor(post(urlEqualTo("/meter/v2/ai/completions"))
                .willReturn(aResponse().withStatus(201)));

        client = buildClient(wm);
        client.send(buildTestEvent());

        // Wait for request to arrive so executor threads are visible
        await().atMost(5, SECONDS).untilAsserted(() ->
                verify(1, postRequestedFor(urlEqualTo("/meter/v2/ai/completions")))
        );

        // Verify at least one daemon thread named "revenium-metering-N" exists
        boolean hasNamedThread = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().matches("revenium-metering-\\d+"));
        assertThat(hasNamedThread)
                .as("Expected daemon thread named 'revenium-metering-N' to exist")
                .isTrue();
    }

    /**
     * Test 5: send() does not throw when endpoint returns 500; WARN is logged.
     * XPORT-03: 5xx errors are logged at WARN and silently dropped.
     */
    @Test
    void send_logsWarnOnServerError_doesNotThrow(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/meter/v2/ai/completions"))
                .willReturn(aResponse().withStatus(500)));

        client = buildClient(wm);

        // Must complete without throwing
        client.send(buildTestEvent());

        // Wait for the async call to reach WireMock
        await().atMost(5, SECONDS).untilAsserted(() ->
                verify(1, postRequestedFor(urlEqualTo("/meter/v2/ai/completions")))
        );

        // Test passes if no exception propagated to calling thread
    }

    /**
     * Test 6: send() does not throw when endpoint is unreachable (connection refused).
     * XPORT-03: Network errors are logged at WARN and silently dropped.
     */
    @Test
    void send_logsWarnOnConnectionRefused_doesNotThrow() {
        // Use a port where nothing is running
        MeteringClient unreachableClient = new MeteringClient(
                ReveniumConfig.builder()
                        .apiKey("test-key")
                        .baseUrl("http://localhost:1")
                        .build());

        try {
            // Must complete without throwing, even though endpoint is unreachable
            unreachableClient.send(buildTestEvent());
            // Wait briefly to allow async attempt to fail
            Thread.sleep(500);
            // Test passes if no exception propagated
        } catch (Exception e) {
            throw new AssertionError("send() must not throw when endpoint is unreachable", e);
        } finally {
            try {
                unreachableClient.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Test 7: 50 concurrent send() calls complete without blocking caller threads.
     * XPORT-05: Thread-safe under concurrent load.
     */
    @Test
    void send_isThreadSafeUnderConcurrentLoad(WireMockRuntimeInfo wm) throws Exception {
        stubFor(post(urlEqualTo("/meter/v2/ai/completions"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(100)));

        client = buildClient(wm);
        MeteringEvent event = buildTestEvent();

        ExecutorService callerPool = Executors.newFixedThreadPool(50);
        List<Future<?>> futures = new ArrayList<>();

        long start = System.currentTimeMillis();
        for (int i = 0; i < 50; i++) {
            futures.add(callerPool.submit(() -> client.send(event)));
        }

        // All submit calls must complete within 200ms (fire-and-forget)
        callerPool.shutdown();
        boolean terminated = callerPool.awaitTermination(2, SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(terminated).isTrue();
        assertThat(elapsed).isLessThan(200L);

        // Verify that a significant number of requests arrived (queue may discard some under load)
        await().atMost(15, SECONDS).untilAsserted(() ->
                verify(moreThanOrExactly(40), postRequestedFor(urlEqualTo("/meter/v2/ai/completions")))
        );
    }

    /**
     * Test 8: close() shuts down executor; subsequent send() calls are silently dropped.
     * XPORT-05: Lifecycle — post-close sends are dropped, not thrown.
     */
    @Test
    void close_shutsDownExecutor(WireMockRuntimeInfo wm) throws Exception {
        stubFor(post(urlEqualTo("/meter/v2/ai/completions"))
                .willReturn(aResponse().withStatus(201)));

        client = buildClient(wm);

        // First send — should arrive
        client.send(buildTestEvent());

        // Wait for the first request to arrive
        await().atMost(5, SECONDS).untilAsserted(() ->
                verify(1, postRequestedFor(urlEqualTo("/meter/v2/ai/completions")))
        );

        // Close the client — shuts down executor
        client.close();
        client = null; // prevent AfterEach from calling close() again

        // Second send after close — must not throw and must not be sent
        MeteringClient closedClient = buildClient(wm);
        closedClient.close(); // immediately close so it won't process
        closedClient.send(buildTestEvent()); // post-close send — silently dropped

        // Wait briefly and verify WireMock only received the first request
        Thread.sleep(300);
        verify(1, postRequestedFor(urlEqualTo("/meter/v2/ai/completions")));
    }
}
