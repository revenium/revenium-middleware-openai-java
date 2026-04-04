package io.revenium.metering.openai.wrapper;

import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.CompactedResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCancelParams;
import com.openai.models.responses.ResponseCompactParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseDeleteParams;
import com.openai.models.responses.ResponseRetrieveParams;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceOptions;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.responses.InputItemService;
import com.openai.services.blocking.responses.InputTokenService;
import io.revenium.metering.openai.config.ReveniumConfig;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.transport.MeteringClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MeteringResponseService}.
 *
 * Covers: non-streaming delegation + metering, streaming delegation, UsageMetadata passthrough,
 * resilience (metering failure does not break caller), concurrency (50 threads, no race conditions),
 * and all delegate-only methods.
 */
@ExtendWith(MockitoExtension.class)
class MeteringResponseServiceTest {

    @Mock
    private ResponseService delegate;

    @Mock
    private MeteringClient meteringClient;

    private MeteringResponseService wrapper;

    @BeforeEach
    void setUp() {
        ReveniumConfig config = ReveniumConfig.builder().apiKey("test-key").build();
        wrapper = new MeteringResponseService(delegate, config, meteringClient);
    }

    // -----------------------------------------------------------------------
    // Helper builders
    // -----------------------------------------------------------------------

    private ResponseUsage buildUsage(long input, long output, long total) {
        ResponseUsage.InputTokensDetails inputDetails = ResponseUsage.InputTokensDetails.builder()
                .cachedTokens(0L)
                .build();
        ResponseUsage.OutputTokensDetails outputDetails = ResponseUsage.OutputTokensDetails.builder()
                .reasoningTokens(0L)
                .build();
        return ResponseUsage.builder()
                .inputTokens(input)
                .inputTokensDetails(inputDetails)
                .outputTokens(output)
                .outputTokensDetails(outputDetails)
                .totalTokens(total)
                .build();
    }

    private Response buildResponse(String model, ResponseUsage usage, ResponseStatus status) {
        return Response.builder()
                .id("resp-id-123")
                .model(model)
                .createdAt((double) (System.currentTimeMillis() / 1000))
                .error(Optional.empty())
                .incompleteDetails(Optional.empty())
                .instructions(Optional.empty())
                .metadata(Optional.empty())
                .output(Collections.emptyList())
                .parallelToolCalls(true)
                .temperature(Optional.empty())
                .toolChoice(ToolChoiceOptions.AUTO)
                .tools(Collections.emptyList())
                .topP(Optional.empty())
                .status(status)
                .usage(usage)
                .build();
    }

    private ResponseCreateParams buildCreateParams() {
        return ResponseCreateParams.builder()
                .model("gpt-4o")
                .input("Hello")
                .build();
    }

    // -----------------------------------------------------------------------
    // Non-streaming create() tests
    // -----------------------------------------------------------------------

    @Test
    void nonStreamingCreate_delegatesAndReturnsSameResponse() {
        // Arrange
        ResponseUsage usage = buildUsage(10L, 20L, 30L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        // Act
        Response result = wrapper.create(buildCreateParams(), RequestOptions.none());

        // Assert: delegate called and same response returned
        verify(delegate, times(1)).create(any(ResponseCreateParams.class), any(RequestOptions.class));
        assertThat(result).isSameAs(mockResponse);
    }

    @Test
    void nonStreamingCreate_firesMetering_withCorrectTokenCounts() {
        // Arrange
        ResponseUsage usage = buildUsage(10L, 20L, 30L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.create(buildCreateParams(), RequestOptions.none());

        // Assert: metering event sent with correct token counts
        verify(meteringClient, times(1)).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getModel()).isEqualTo("gpt-4o");
        assertThat(event.getInputTokenCount()).isEqualTo(10L);
        assertThat(event.getOutputTokenCount()).isEqualTo(20L);
        assertThat(event.getTotalTokenCount()).isEqualTo(30L);
        assertThat(event.getOperationType()).isEqualTo("CHAT");
        assertThat(event.getIsStreamed()).isFalse();
    }

    @Test
    void nonStreamingCreate_capturesRequestDuration() {
        // Arrange
        ResponseUsage usage = buildUsage(10L, 20L, 30L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.create(buildCreateParams(), RequestOptions.none());

        // Assert: duration captured
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRequestDuration()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    void nonStreamingCreate_mapsCompletedStatusToEnd() {
        // Arrange: "completed" maps to "END"
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.create(buildCreateParams(), RequestOptions.none());

        // Assert: stop reason maps correctly
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStopReason()).isEqualTo("END");
    }

    @Test
    void nonStreamingCreate_withUsageMetadata_passesMetadataToExtractor() {
        // Arrange
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        UsageMetadata metadata = UsageMetadata.builder()
                .traceId("trace-xyz")
                .taskType("classification")
                .build();

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: overloaded create with metadata
        wrapper.create(buildCreateParams(), metadata);

        // Assert: metadata fields in event
        verify(meteringClient).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getTraceId()).isEqualTo("trace-xyz");
        assertThat(event.getTaskType()).isEqualTo("classification");
    }

    @Test
    void meteringFailure_doesNotBreakCaller() {
        // Arrange: meteringClient.send() throws
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);
        doThrow(new RuntimeException("metering boom")).when(meteringClient).send(any());

        // Act + Assert: no exception propagated
        assertThatCode(() -> {
            Response result = wrapper.create(buildCreateParams(), RequestOptions.none());
            assertThat(result).isSameAs(mockResponse);
        }).doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Streaming createStreaming() tests
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void streamingCreate_returnsMeteringResponseStreamResponse() {
        // Arrange
        StreamResponse<ResponseStreamEvent> mockStream = mock(StreamResponse.class);
        when(delegate.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        // Act
        StreamResponse<ResponseStreamEvent> result = wrapper.createStreaming(buildCreateParams(), RequestOptions.none());

        // Assert: returns a non-null wrapper (MeteringResponseStreamResponse)
        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(MeteringResponseStreamResponse.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamingCreate_withMetadata_returnsMeteringResponseStreamResponse() {
        // Arrange
        StreamResponse<ResponseStreamEvent> mockStream = mock(StreamResponse.class);
        when(delegate.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        UsageMetadata metadata = UsageMetadata.builder().traceId("t1").build();

        // Act
        StreamResponse<ResponseStreamEvent> result = wrapper.createStreaming(buildCreateParams(), metadata);

        // Assert
        assertThat(result).isNotNull().isInstanceOf(MeteringResponseStreamResponse.class);
    }

    // -----------------------------------------------------------------------
    // Delegation tests (un-metered methods)
    // -----------------------------------------------------------------------

    @Test
    void retrieve_delegatesUnmetered() {
        ResponseUsage usage = buildUsage(5L, 5L, 10L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        ResponseRetrieveParams params = ResponseRetrieveParams.builder().responseId("resp-id").build();
        when(delegate.retrieve(any(ResponseRetrieveParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        Response result = wrapper.retrieve(params, RequestOptions.none());

        assertThat(result).isSameAs(mockResponse);
        verify(meteringClient, never()).send(any());
    }

    @Test
    void delete_delegatesUnmetered() {
        ResponseDeleteParams params = ResponseDeleteParams.builder().responseId("resp-id").build();

        wrapper.delete(params, RequestOptions.none());

        verify(delegate, times(1)).delete(any(ResponseDeleteParams.class), any(RequestOptions.class));
        verify(meteringClient, never()).send(any());
    }

    @Test
    void cancel_delegatesUnmetered() {
        ResponseUsage usage = buildUsage(5L, 5L, 10L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        ResponseCancelParams params = ResponseCancelParams.builder().responseId("resp-id").build();
        when(delegate.cancel(any(ResponseCancelParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        Response result = wrapper.cancel(params, RequestOptions.none());

        assertThat(result).isSameAs(mockResponse);
        verify(meteringClient, never()).send(any());
    }

    @Test
    void compact_delegatesUnmetered() {
        CompactedResponse mockCompactedResponse = mock(CompactedResponse.class);
        ResponseCompactParams params = mock(ResponseCompactParams.class);
        when(delegate.compact(any(ResponseCompactParams.class), any(RequestOptions.class)))
                .thenReturn(mockCompactedResponse);

        CompactedResponse result = wrapper.compact(params, RequestOptions.none());

        assertThat(result).isSameAs(mockCompactedResponse);
        verify(meteringClient, never()).send(any());
    }

    @Test
    void inputItems_delegatesUnmetered() {
        InputItemService mockInputItemService = mock(InputItemService.class);
        when(delegate.inputItems()).thenReturn(mockInputItemService);

        InputItemService result = wrapper.inputItems();

        assertThat(result).isSameAs(mockInputItemService);
        verify(meteringClient, never()).send(any());
    }

    @Test
    void inputTokens_delegatesUnmetered() {
        InputTokenService mockInputTokenService = mock(InputTokenService.class);
        when(delegate.inputTokens()).thenReturn(mockInputTokenService);

        InputTokenService result = wrapper.inputTokens();

        assertThat(result).isSameAs(mockInputTokenService);
        verify(meteringClient, never()).send(any());
    }

    @Test
    void withRawResponse_delegatesUnmetered() {
        ResponseService.WithRawResponse mockRaw = mock(ResponseService.WithRawResponse.class);
        when(delegate.withRawResponse()).thenReturn(mockRaw);

        ResponseService.WithRawResponse result = wrapper.withRawResponse();

        assertThat(result).isSameAs(mockRaw);
    }

    // -----------------------------------------------------------------------
    // Concurrency tests
    // -----------------------------------------------------------------------

    @Test
    void concurrentCalls_noRaceConditions_allFireMeteringEvents() throws InterruptedException {
        // Arrange: 50 threads each calling create()
        int threadCount = 50;
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    wrapper.create(buildCreateParams(), RequestOptions.none());
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert: all 50 calls completed, no exceptions, 50 metering events sent
        assertThat(completed).isTrue();
        assertThat(exceptionCount.get()).isZero();
        verify(meteringClient, times(threadCount)).send(any(MeteringEvent.class));
    }
}
