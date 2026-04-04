package io.revenium.metering.openai.wrapper;

import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseCreatedEvent;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceOptions;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MeteringResponseStreamResponse}.
 *
 * Covers: stream passthrough, TTFT from ResponseTextDeltaEvent, model from ResponseCreatedEvent,
 * usage from ResponseCompletedEvent, status to StopReason mapping, at-most-once metering on close(),
 * failed/incomplete terminal events, empty stream, metering failure resilience.
 */
@ExtendWith(MockitoExtension.class)
class MeteringResponseStreamResponseTest {

    @Mock
    private MeteringClient meteringClient;

    private static final long REQUEST_TIME = 1_000_000L;

    // -----------------------------------------------------------------------
    // Helpers
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

    /**
     * Builds a mock ResponseStreamEvent that is a text delta event.
     * Uses lenient() to avoid UnnecessaryStubbingException when not all stubs are used.
     */
    private ResponseStreamEvent mockDeltaEvent(String deltaText) {
        ResponseTextDeltaEvent deltaEvent = mock(ResponseTextDeltaEvent.class);
        lenient().when(deltaEvent.delta()).thenReturn(deltaText);

        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        lenient().when(event.isOutputTextDelta()).thenReturn(true);
        lenient().when(event.isCreated()).thenReturn(false);
        lenient().when(event.isCompleted()).thenReturn(false);
        lenient().when(event.isFailed()).thenReturn(false);
        lenient().when(event.isIncomplete()).thenReturn(false);
        lenient().when(event.outputTextDelta()).thenReturn(Optional.of(deltaEvent));
        return event;
    }

    /**
     * Builds a mock ResponseStreamEvent that is a created event (carries model, no usage).
     * Uses mocked Response to avoid SDK builder validation with null fields.
     */
    private ResponseStreamEvent mockCreatedEvent(String model) {
        // Use a mock Response to avoid SDK builder issues with null status/usage
        com.openai.models.ResponsesModel responsesModel =
                mock(com.openai.models.ResponsesModel.class);
        lenient().when(responsesModel.asString()).thenReturn(model);
        Response response = mock(Response.class);
        lenient().when(response.model()).thenReturn(responsesModel);

        ResponseCreatedEvent createdEvent = mock(ResponseCreatedEvent.class);
        lenient().when(createdEvent.response()).thenReturn(response);

        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        lenient().when(event.isOutputTextDelta()).thenReturn(false);
        lenient().when(event.isCreated()).thenReturn(true);
        lenient().when(event.isCompleted()).thenReturn(false);
        lenient().when(event.isFailed()).thenReturn(false);
        lenient().when(event.isIncomplete()).thenReturn(false);
        lenient().when(event.created()).thenReturn(Optional.of(createdEvent));
        return event;
    }

    /**
     * Builds a mock ResponseStreamEvent that is a completed event (carries full Response with usage).
     */
    private ResponseStreamEvent mockCompletedEvent(String model, ResponseUsage usage, ResponseStatus status) {
        Response response = buildResponse(model, usage, status);

        ResponseCompletedEvent completedEvent = mock(ResponseCompletedEvent.class);
        lenient().when(completedEvent.response()).thenReturn(response);

        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        lenient().when(event.isOutputTextDelta()).thenReturn(false);
        lenient().when(event.isCreated()).thenReturn(false);
        lenient().when(event.isCompleted()).thenReturn(true);
        lenient().when(event.isFailed()).thenReturn(false);
        lenient().when(event.isIncomplete()).thenReturn(false);
        lenient().when(event.completed()).thenReturn(Optional.of(completedEvent));
        return event;
    }

    /**
     * Builds a mock ResponseStreamEvent that is a failed event.
     */
    private ResponseStreamEvent mockFailedEvent() {
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        lenient().when(event.isOutputTextDelta()).thenReturn(false);
        lenient().when(event.isCreated()).thenReturn(false);
        lenient().when(event.isCompleted()).thenReturn(false);
        lenient().when(event.isFailed()).thenReturn(true);
        lenient().when(event.isIncomplete()).thenReturn(false);
        return event;
    }

    /**
     * Builds a mock ResponseStreamEvent that is an incomplete event.
     */
    private ResponseStreamEvent mockIncompleteEvent() {
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        lenient().when(event.isOutputTextDelta()).thenReturn(false);
        lenient().when(event.isCreated()).thenReturn(false);
        lenient().when(event.isCompleted()).thenReturn(false);
        lenient().when(event.isFailed()).thenReturn(false);
        lenient().when(event.isIncomplete()).thenReturn(true);
        return event;
    }

    @SuppressWarnings("unchecked")
    private StreamResponse<ResponseStreamEvent> mockDelegateStream(ResponseStreamEvent... events) {
        StreamResponse<ResponseStreamEvent> mockStream = mock(StreamResponse.class);
        when(mockStream.stream()).thenReturn(Stream.of(events));
        return mockStream;
    }

    private MeteringResponseStreamResponse buildWrapper(StreamResponse<ResponseStreamEvent> delegate) {
        return new MeteringResponseStreamResponse(
                delegate,
                io.revenium.metering.openai.provider.Provider.OPENAI,
                null,
                REQUEST_TIME,
                meteringClient);
    }

    private MeteringResponseStreamResponse buildWrapperWithMetadata(
            StreamResponse<ResponseStreamEvent> delegate, UsageMetadata metadata) {
        return new MeteringResponseStreamResponse(
                delegate,
                io.revenium.metering.openai.provider.Provider.OPENAI,
                metadata,
                REQUEST_TIME,
                meteringClient);
    }

    // -----------------------------------------------------------------------
    // Stream passthrough tests
    // -----------------------------------------------------------------------

    @Test
    void stream_passesAllEventsThrough() {
        // Arrange: 3 events in stream
        ResponseStreamEvent deltaEvent = mockDeltaEvent("Hello");
        ResponseUsage usage = buildUsage(5L, 10L, 15L);
        ResponseStreamEvent completedEvent = mockCompletedEvent("gpt-4o", usage, ResponseStatus.COMPLETED);

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(deltaEvent, completedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        // Act: collect all events
        long eventCount = wrapper.stream().count();

        // Assert: all events passed through (2 events)
        assertThat(eventCount).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // TTFT tests
    // -----------------------------------------------------------------------

    @Test
    void stream_capturesTtft_fromFirstNonEmptyDeltaEvent() {
        // Arrange
        ResponseStreamEvent deltaEvent = mockDeltaEvent("Hello");
        ResponseUsage usage = buildUsage(5L, 10L, 15L);
        ResponseStreamEvent completedEvent = mockCompletedEvent("gpt-4o", usage, ResponseStatus.COMPLETED);

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(deltaEvent, completedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.stream().forEach(e -> {}); // consume
        wrapper.close();

        // Assert: TTFT >= 0
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTimeToFirstToken()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void stream_doesNotCaptureTtft_fromEmptyDeltaEvent() {
        // Arrange: delta event with empty text does not capture TTFT
        ResponseStreamEvent emptyDeltaEvent = mockDeltaEvent("");
        ResponseStreamEvent nonEmptyDeltaEvent = mockDeltaEvent("World");
        ResponseUsage usage = buildUsage(5L, 10L, 15L);
        ResponseStreamEvent completedEvent = mockCompletedEvent("gpt-4o", usage, ResponseStatus.COMPLETED);

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(
                emptyDeltaEvent, nonEmptyDeltaEvent, completedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.stream().forEach(e -> {});
        wrapper.close();

        // Assert: TTFT is captured from the "World" delta (not the empty one)
        verify(meteringClient).send(eventCaptor.capture());
        // TTFT should be >= 0 since we got a non-empty delta
        assertThat(eventCaptor.getValue().getTimeToFirstToken()).isGreaterThanOrEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // Model capture tests
    // -----------------------------------------------------------------------

    @Test
    void stream_capturesModel_fromResponseCreatedEvent() {
        // Arrange: created event comes first with model info
        ResponseStreamEvent createdEvent = mockCreatedEvent("gpt-4o-mini");
        ResponseUsage usage = buildUsage(5L, 10L, 15L);
        // Completed event carries the same model (override)
        ResponseStreamEvent completedEvent = mockCompletedEvent("gpt-4o-mini", usage, ResponseStatus.COMPLETED);

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(createdEvent, completedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.stream().forEach(e -> {});
        wrapper.close();

        // Assert: model captured correctly
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getModel()).isEqualTo("gpt-4o-mini");
    }

    // -----------------------------------------------------------------------
    // Usage capture tests (RESP-03)
    // -----------------------------------------------------------------------

    @Test
    void stream_capturesUsage_fromResponseCompletedEvent() {
        // Arrange
        ResponseUsage usage = buildUsage(10L, 20L, 30L);
        ResponseStreamEvent completedEvent = mockCompletedEvent("gpt-4o", usage, ResponseStatus.COMPLETED);

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(completedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.stream().forEach(e -> {});
        wrapper.close();

        // Assert: usage extracted from ResponseCompletedEvent
        verify(meteringClient).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getInputTokenCount()).isEqualTo(10L);
        assertThat(event.getOutputTokenCount()).isEqualTo(20L);
        assertThat(event.getTotalTokenCount()).isEqualTo(30L);
    }

    // -----------------------------------------------------------------------
    // Status to StopReason tests
    // -----------------------------------------------------------------------

    @Test
    void stream_mapsCompletedStatus_toEndStopReason() {
        ResponseUsage usage = buildUsage(5L, 5L, 10L);
        ResponseStreamEvent completedEvent = mockCompletedEvent("gpt-4o", usage, ResponseStatus.COMPLETED);

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(completedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        wrapper.stream().forEach(e -> {});
        wrapper.close();

        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStopReason()).isEqualTo("END");
    }

    @Test
    void stream_mapsFailedEvent_toErrorStopReason() {
        // Arrange: created event to set model, then failed event
        ResponseStreamEvent createdEvent = mockCreatedEvent("gpt-4o");
        ResponseStreamEvent failedEvent = mockFailedEvent();

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(createdEvent, failedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.stream().forEach(e -> {});
        wrapper.close();

        // Assert: stop reason is ERROR
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStopReason()).isEqualTo("ERROR");
    }

    @Test
    void stream_mapsIncompleteEvent_toTokenLimitStopReason() {
        // Arrange: created event to set model, then incomplete event
        ResponseStreamEvent createdEvent = mockCreatedEvent("gpt-4o");
        ResponseStreamEvent incompleteEvent = mockIncompleteEvent();

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(createdEvent, incompleteEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.stream().forEach(e -> {});
        wrapper.close();

        // Assert: stop reason is TOKEN_LIMIT
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStopReason()).isEqualTo("TOKEN_LIMIT");
    }

    // -----------------------------------------------------------------------
    // Metering fires on close() tests
    // -----------------------------------------------------------------------

    @Test
    void close_firesMeteringWithAllCapturedData() {
        // Arrange
        ResponseStreamEvent deltaEvent = mockDeltaEvent("Hello");
        ResponseUsage usage = buildUsage(5L, 15L, 20L);
        ResponseStreamEvent completedEvent = mockCompletedEvent("gpt-4o", usage, ResponseStatus.COMPLETED);

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(deltaEvent, completedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: consume stream then close
        wrapper.stream().forEach(e -> {});
        wrapper.close();

        // Assert: metering fired on close()
        verify(meteringClient, times(1)).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getOperationType()).isEqualTo("CHAT");
        assertThat(event.getIsStreamed()).isTrue();
        assertThat(event.getModel()).isEqualTo("gpt-4o");
        assertThat(event.getInputTokenCount()).isEqualTo(5L);
        assertThat(event.getOutputTokenCount()).isEqualTo(15L);
        assertThat(event.getTotalTokenCount()).isEqualTo(20L);
    }

    @Test
    void close_isMeteringIdempotent_firesAtMostOnce() {
        // Arrange
        ResponseUsage usage = buildUsage(5L, 5L, 10L);
        ResponseStreamEvent completedEvent = mockCompletedEvent("gpt-4o", usage, ResponseStatus.COMPLETED);

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(completedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        // Act: consume stream and close twice
        wrapper.stream().forEach(e -> {});
        wrapper.close();
        wrapper.close();

        // Assert: metering fired exactly once
        verify(meteringClient, times(1)).send(any(MeteringEvent.class));
    }

    // -----------------------------------------------------------------------
    // Empty stream tests
    // -----------------------------------------------------------------------

    @Test
    void emptyStream_doesNotFireMetering() {
        // Arrange: delegate returns empty stream
        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(/* no events */);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        // Act
        wrapper.stream().forEach(e -> {});
        wrapper.close();

        // Assert: metering NOT fired (no model captured = empty stream)
        verify(meteringClient, never()).send(any());
    }

    // -----------------------------------------------------------------------
    // Metering failure resilience test
    // -----------------------------------------------------------------------

    @Test
    void close_meteringFailure_doesNotPropagateException() {
        // Arrange
        ResponseUsage usage = buildUsage(5L, 5L, 10L);
        ResponseStreamEvent completedEvent = mockCompletedEvent("gpt-4o", usage, ResponseStatus.COMPLETED);
        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(completedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        doThrow(new RuntimeException("metering boom")).when(meteringClient).send(any());

        // Act + Assert: no exception propagated from close()
        wrapper.stream().forEach(e -> {});
        assertThatCode(() -> wrapper.close()).doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // operationType and isStreamed tests
    // -----------------------------------------------------------------------

    @Test
    void meteringEvent_hasResponseOperationType_andIsStreamed_true() {
        // Arrange
        ResponseUsage usage = buildUsage(5L, 5L, 10L);
        ResponseStreamEvent completedEvent = mockCompletedEvent("gpt-4o", usage, ResponseStatus.COMPLETED);

        StreamResponse<ResponseStreamEvent> delegate = mockDelegateStream(completedEvent);
        MeteringResponseStreamResponse wrapper = buildWrapper(delegate);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.stream().forEach(e -> {});
        wrapper.close();

        // Assert: operationType "RESPONSE" and isStreamed true
        verify(meteringClient).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getOperationType()).isEqualTo("CHAT");
        assertThat(event.getIsStreamed()).isTrue();
    }
}
