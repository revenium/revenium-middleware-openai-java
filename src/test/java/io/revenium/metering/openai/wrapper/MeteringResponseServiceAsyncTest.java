package io.revenium.metering.openai.wrapper;

import com.openai.core.RequestOptions;
import com.openai.core.http.AsyncStreamResponse;
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
import com.openai.services.async.ResponseServiceAsync;
import com.openai.services.async.responses.InputItemServiceAsync;
import com.openai.services.async.responses.InputTokenServiceAsync;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MeteringResponseServiceAsync}.
 *
 * Covers: async non-streaming create() metering via whenComplete(), failed future (no metering),
 * UsageMetadata passthrough, createStreaming() returning MeteringAsyncResponseStreamResponse,
 * metering failure resilience, and delegation of all un-metered interface methods.
 */
@ExtendWith(MockitoExtension.class)
class MeteringResponseServiceAsyncTest {

    @Mock
    private ResponseServiceAsync delegate;

    @Mock
    private MeteringClient meteringClient;

    private MeteringResponseServiceAsync wrapper;

    @BeforeEach
    void setUp() {
        ReveniumConfig config = ReveniumConfig.builder().apiKey("test-key").build();
        wrapper = new MeteringResponseServiceAsync(delegate, config, meteringClient);
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
                .id("resp-async-123")
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

    private ResponseCreateParams buildParams() {
        return ResponseCreateParams.builder()
                .model("gpt-4o")
                .input("Hello")
                .build();
    }

    // -----------------------------------------------------------------------
    // Non-streaming async create() tests
    // -----------------------------------------------------------------------

    @Test
    void asyncNonStreamingCreate_delegatesAndMeters() {
        // Arrange
        ResponseUsage usage = buildUsage(10L, 20L, 30L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: join() to wait for future and whenComplete callback to fire
        Response result = wrapper.create(buildParams(), RequestOptions.none()).join();

        // Assert: delegate was called once
        verify(delegate, times(1)).create(any(ResponseCreateParams.class), any(RequestOptions.class));

        // Assert: meteringClient.send() was called once with correct data
        verify(meteringClient, times(1)).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getModel()).isEqualTo("gpt-4o");
        assertThat(event.getInputTokenCount()).isEqualTo(10L);
        assertThat(event.getOutputTokenCount()).isEqualTo(20L);
        assertThat(event.getTotalTokenCount()).isEqualTo(30L);
        assertThat(event.getOperationType()).isEqualTo("CHAT");
        assertThat(event.getIsStreamed()).isFalse();

        // Assert: original response returned
        assertThat(result).isSameAs(mockResponse);
    }

    @Test
    void asyncNonStreamingCreate_errorDoesNotMeter() {
        // Arrange: delegate returns failed future
        RuntimeException boom = new RuntimeException("API error");
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.failedFuture(boom));

        // Act: call create — the future will fail
        CompletableFuture<Response> future = wrapper.create(buildParams(), RequestOptions.none());

        // Wait for future to complete (it will be exceptional)
        try {
            future.join();
        } catch (Exception ignored) {
            // Expected — future failed
        }

        // Assert: meteringClient.send() was NOT called (no response to meter)
        verify(meteringClient, never()).send(any());
    }

    @Test
    void asyncNonStreamingCreate_withUsageMetadata() {
        // Arrange
        ResponseUsage usage = buildUsage(10L, 5L, 15L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        UsageMetadata metadata = UsageMetadata.builder()
                .traceId("trace-resp-async-456")
                .taskType("summarization")
                .build();

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: use overloaded create with metadata
        wrapper.create(buildParams(), metadata).join();

        // Assert: metadata fields pass through to MeteringEvent
        verify(meteringClient).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getTraceId()).isEqualTo("trace-resp-async-456");
        assertThat(event.getTaskType()).isEqualTo("summarization");
    }

    @Test
    void asyncNonStreamingCreate_meteringFailureDoesNotBreakFuture() {
        // Arrange: configure send() to throw
        ResponseUsage usage = buildUsage(5L, 10L, 15L);
        Response mockResponse = buildResponse("gpt-4o", usage, ResponseStatus.COMPLETED);
        when(delegate.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));
        doThrow(new RuntimeException("metering boom")).when(meteringClient).send(any());

        // Act: join() — metering throws but future must still resolve
        Response result = wrapper.create(buildParams(), RequestOptions.none()).join();

        // Assert: response returned despite metering failure
        assertThat(result).isSameAs(mockResponse);
    }

    // -----------------------------------------------------------------------
    // createStreaming() tests
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void asyncStreamingCreate_returnsNonNull() {
        // Arrange: minimal setup — just verify the wrapper is created
        AsyncStreamResponse<ResponseStreamEvent> mockStream = mock(AsyncStreamResponse.class);
        when(delegate.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        // Act
        AsyncStreamResponse<ResponseStreamEvent> result =
                wrapper.createStreaming(buildParams(), RequestOptions.none());

        // Assert: result is not null
        assertThat(result).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncStreamingCreate_returnsMeteringWrapper() {
        // Arrange
        AsyncStreamResponse<ResponseStreamEvent> mockStream = mock(AsyncStreamResponse.class);
        when(delegate.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        // Act
        AsyncStreamResponse<ResponseStreamEvent> result =
                wrapper.createStreaming(buildParams(), RequestOptions.none());

        // Assert: result is a MeteringAsyncResponseStreamResponse instance
        assertThat(result).isInstanceOf(MeteringAsyncResponseStreamResponse.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncStreamingCreate_withUsageMetadata_returnsWrapper() {
        // Arrange
        AsyncStreamResponse<ResponseStreamEvent> mockStream = mock(AsyncStreamResponse.class);
        when(delegate.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockStream);

        UsageMetadata metadata = UsageMetadata.builder().traceId("trace-stream").build();

        // Act
        AsyncStreamResponse<ResponseStreamEvent> result =
                wrapper.createStreaming(buildParams(), metadata);

        // Assert: result is not null
        assertThat(result).isNotNull().isInstanceOf(MeteringAsyncResponseStreamResponse.class);
    }

    // -----------------------------------------------------------------------
    // Delegate method passthrough tests
    // -----------------------------------------------------------------------

    @Test
    void retrieve_delegatesUnmetered() {
        // Arrange
        Response mockResponse = buildResponse("gpt-4o", buildUsage(1L, 1L, 2L), ResponseStatus.COMPLETED);
        ResponseRetrieveParams params = ResponseRetrieveParams.builder().responseId("resp-123").build();
        when(delegate.retrieve(any(ResponseRetrieveParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        // Act
        Response result = wrapper.retrieve(params, RequestOptions.none()).join();

        // Assert: delegate called, no metering
        verify(delegate).retrieve(params, RequestOptions.none());
        verify(meteringClient, never()).send(any());
        assertThat(result).isSameAs(mockResponse);
    }

    @Test
    void delete_delegatesUnmetered() {
        // Arrange
        ResponseDeleteParams params = ResponseDeleteParams.builder().responseId("resp-123").build();
        when(delegate.delete(any(ResponseDeleteParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        wrapper.delete(params, RequestOptions.none()).join();

        // Assert: delegate called, no metering
        verify(delegate).delete(params, RequestOptions.none());
        verify(meteringClient, never()).send(any());
    }

    @Test
    void cancel_delegatesUnmetered() {
        // Arrange
        ResponseCancelParams params = ResponseCancelParams.builder().responseId("resp-123").build();
        Response mockResponse = buildResponse("gpt-4o", buildUsage(1L, 1L, 2L), ResponseStatus.CANCELLED);
        when(delegate.cancel(any(ResponseCancelParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        // Act
        Response result = wrapper.cancel(params, RequestOptions.none()).join();

        // Assert: delegate called, no metering
        verify(delegate).cancel(params, RequestOptions.none());
        verify(meteringClient, never()).send(any());
        assertThat(result).isSameAs(mockResponse);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputItems_delegatesUnmetered() {
        // Arrange
        InputItemServiceAsync mockInputItems = mock(InputItemServiceAsync.class);
        when(delegate.inputItems()).thenReturn(mockInputItems);

        // Act
        InputItemServiceAsync result = wrapper.inputItems();

        // Assert: delegate's service returned
        verify(delegate).inputItems();
        assertThat(result).isSameAs(mockInputItems);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputTokens_delegatesUnmetered() {
        // Arrange
        InputTokenServiceAsync mockInputTokens = mock(InputTokenServiceAsync.class);
        when(delegate.inputTokens()).thenReturn(mockInputTokens);

        // Act
        InputTokenServiceAsync result = wrapper.inputTokens();

        // Assert: delegate's service returned
        verify(delegate).inputTokens();
        assertThat(result).isSameAs(mockInputTokens);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withRawResponse_delegatesUnmetered() {
        // Arrange
        ResponseServiceAsync.WithRawResponse mockRaw = mock(ResponseServiceAsync.WithRawResponse.class);
        when(delegate.withRawResponse()).thenReturn(mockRaw);

        // Act
        ResponseServiceAsync.WithRawResponse result = wrapper.withRawResponse();

        // Assert: delegate's raw response returned
        verify(delegate).withRawResponse();
        assertThat(result).isSameAs(mockRaw);
    }

    @Test
    void withOptions_delegatesUnmetered() {
        // Arrange
        ResponseServiceAsync mockModified = mock(ResponseServiceAsync.class);
        when(delegate.withOptions(any())).thenReturn(mockModified);

        // Act
        ResponseServiceAsync result = wrapper.withOptions(builder -> {});

        // Assert: delegate's modified service returned
        verify(delegate).withOptions(any());
        assertThat(result).isSameAs(mockModified);
    }
}
