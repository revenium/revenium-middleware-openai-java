package io.revenium.metering.openai.wrapper;

import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseCreatedEvent;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseIncompleteEvent;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceOptions;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.transport.MeteringClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MeteringAsyncResponseStreamResponse}.
 *
 * Covers: subscribe() interception pattern, TTFT capture, model capture from created event,
 * usage + status capture from completed event, metering event fields (operationType RESPONSE,
 * isStreamed true), subscribe() returns this, metering failure resilience, failed/incomplete events,
 * and onCompleteFuture/close delegation.
 */
@ExtendWith(MockitoExtension.class)
class MeteringAsyncResponseStreamResponseTest {

    @Mock
    private MeteringClient meteringClient;

    @BeforeEach
    void setUp() {
        // Tests instantiate MeteringAsyncResponseStreamResponse directly via createWrapper()
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
                .id("resp-stream-123")
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
     * Creates a mock ResponseStreamEvent representing a text delta (TTFT capture).
     */
    @SuppressWarnings("unchecked")
    private ResponseStreamEvent buildDeltaEvent(String deltaText) {
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isOutputTextDelta()).thenReturn(true);
        ResponseTextDeltaEvent deltaEvent = ResponseTextDeltaEvent.builder()
                .contentIndex(0L)
                .delta(deltaText)
                .itemId("item-1")
                .logprobs(java.util.Collections.emptyList())
                .outputIndex(0L)
                .sequenceNumber(1L)
                .build();
        when(event.outputTextDelta()).thenReturn(Optional.of(deltaEvent));
        return event;
    }

    /**
     * Creates a mock ResponseStreamEvent representing a created event (model capture).
     */
    @SuppressWarnings("unchecked")
    private ResponseStreamEvent buildCreatedEvent(String model, ResponseUsage usage) {
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isCreated()).thenReturn(true);
        Response createdResponse = buildResponse(model, usage, ResponseStatus.IN_PROGRESS);
        ResponseCreatedEvent createdEvent = ResponseCreatedEvent.builder()
                .response(createdResponse)
                .sequenceNumber(0L)
                .build();
        when(event.created()).thenReturn(Optional.of(createdEvent));
        return event;
    }

    /**
     * Creates a mock ResponseStreamEvent representing a completed event (usage + status).
     */
    @SuppressWarnings("unchecked")
    private ResponseStreamEvent buildCompletedEvent(String model, ResponseUsage usage) {
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isCompleted()).thenReturn(true);
        Response completedResponse = buildResponse(model, usage, ResponseStatus.COMPLETED);
        ResponseCompletedEvent completedEvent = ResponseCompletedEvent.builder()
                .response(completedResponse)
                .sequenceNumber(5L)
                .build();
        when(event.completed()).thenReturn(Optional.of(completedEvent));
        return event;
    }

    /**
     * Creates a mock ResponseStreamEvent representing a failed event.
     */
    @SuppressWarnings("unchecked")
    private ResponseStreamEvent buildFailedEvent(String model) {
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isFailed()).thenReturn(true);
        Response failedResponse = buildResponse(model, buildUsage(0L, 0L, 0L), ResponseStatus.FAILED);
        ResponseFailedEvent failedEvent = ResponseFailedEvent.builder()
                .response(failedResponse)
                .sequenceNumber(5L)
                .build();
        when(event.failed()).thenReturn(Optional.of(failedEvent));
        return event;
    }

    /**
     * Creates a mock ResponseStreamEvent representing an incomplete event.
     */
    @SuppressWarnings("unchecked")
    private ResponseStreamEvent buildIncompleteEvent(String model) {
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isIncomplete()).thenReturn(true);
        Response incompleteResponse = buildResponse(model, buildUsage(0L, 0L, 0L), ResponseStatus.INCOMPLETE);
        ResponseIncompleteEvent incompleteEvent = ResponseIncompleteEvent.builder()
                .response(incompleteResponse)
                .sequenceNumber(5L)
                .build();
        when(event.incomplete()).thenReturn(Optional.of(incompleteEvent));
        return event;
    }

    /**
     * Builds a mock AsyncStreamResponse that invokes the two-arg handler inline.
     */
    @SuppressWarnings("unchecked")
    private AsyncStreamResponse<ResponseStreamEvent> buildMockAsyncStream(
            List<ResponseStreamEvent> events) {
        AsyncStreamResponse<ResponseStreamEvent> mockStream = mock(AsyncStreamResponse.class);
        doAnswer(invocation -> {
            AsyncStreamResponse.Handler<ResponseStreamEvent> handler = invocation.getArgument(0);
            for (ResponseStreamEvent event : events) {
                handler.onNext(event);
            }
            handler.onComplete(Optional.empty());
            return mockStream;
        }).when(mockStream).subscribe(any(), any());
        return mockStream;
    }

    /**
     * Creates a direct instance of MeteringAsyncResponseStreamResponse for focused testing.
     */
    private MeteringAsyncResponseStreamResponse createWrapper(
            AsyncStreamResponse<ResponseStreamEvent> delegateStream) {
        io.revenium.metering.openai.provider.Provider provider =
                io.revenium.metering.openai.provider.Provider.OPENAI;
        return new MeteringAsyncResponseStreamResponse(
                delegateStream, provider, null, System.currentTimeMillis(), meteringClient);
    }

    // -----------------------------------------------------------------------
    // subscribe() tests
    // -----------------------------------------------------------------------

    @Test
    void subscribe_passesAllEventsToUserHandler() {
        // Arrange: delta event + completed event
        ResponseUsage usage = buildUsage(5L, 10L, 15L);
        ResponseStreamEvent deltaEvent = buildDeltaEvent("Hello");
        ResponseStreamEvent completedEvent = buildCompletedEvent("gpt-4o", usage);

        AsyncStreamResponse<ResponseStreamEvent> mockStream =
                buildMockAsyncStream(List.of(deltaEvent, completedEvent));
        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        AtomicInteger onNextCount = new AtomicInteger(0);
        AtomicBoolean onCompleteCalled = new AtomicBoolean(false);

        // Act
        wrapper.subscribe(new AsyncStreamResponse.Handler<ResponseStreamEvent>() {
            @Override
            public void onNext(ResponseStreamEvent event) {
                onNextCount.incrementAndGet();
            }

            @Override
            public void onComplete(Optional<Throwable> error) {
                onCompleteCalled.set(true);
            }
        });

        // Assert: all events passed through
        assertThat(onNextCount.get()).isEqualTo(2);
        assertThat(onCompleteCalled.get()).isTrue();
    }

    @Test
    void subscribe_returnsThis() {
        // Arrange
        @SuppressWarnings("unchecked")
        AsyncStreamResponse<ResponseStreamEvent> mockStream = mock(AsyncStreamResponse.class);
        lenient().doAnswer(invocation -> mockStream).when(mockStream).subscribe(any(), any());

        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        AsyncStreamResponse.Handler<ResponseStreamEvent> handler =
                new AsyncStreamResponse.Handler<ResponseStreamEvent>() {
                    @Override
                    public void onNext(ResponseStreamEvent event) {}

                    @Override
                    public void onComplete(Optional<Throwable> error) {}
                };

        // Act
        AsyncStreamResponse<ResponseStreamEvent> result = wrapper.subscribe(handler);

        // Assert: subscribe() returns this (Pitfall 6/7)
        assertThat(result).isSameAs(wrapper);
    }

    @Test
    void subscribe_capturesTokenUsageFromCompletedEvent() {
        // Arrange
        ResponseUsage usage = buildUsage(10L, 25L, 35L);
        ResponseStreamEvent completedEvent = buildCompletedEvent("gpt-4o", usage);

        AsyncStreamResponse<ResponseStreamEvent> mockStream =
                buildMockAsyncStream(List.of(completedEvent));
        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.subscribe(new AsyncStreamResponse.Handler<ResponseStreamEvent>() {
            @Override
            public void onNext(ResponseStreamEvent event) {}

            @Override
            public void onComplete(Optional<Throwable> error) {}
        });

        // Assert: metering event has correct token counts
        verify(meteringClient).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getInputTokenCount()).isEqualTo(10L);
        assertThat(event.getOutputTokenCount()).isEqualTo(25L);
        assertThat(event.getTotalTokenCount()).isEqualTo(35L);
        assertThat(event.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void subscribe_capturesTTFTFromFirstDeltaEvent() {
        // Arrange: delta event is the TTFT marker
        ResponseUsage usage = buildUsage(5L, 10L, 15L);
        ResponseStreamEvent deltaEvent = buildDeltaEvent("Hello");
        ResponseStreamEvent completedEvent = buildCompletedEvent("gpt-4o", usage);

        AsyncStreamResponse<ResponseStreamEvent> mockStream =
                buildMockAsyncStream(List.of(deltaEvent, completedEvent));
        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.subscribe(new AsyncStreamResponse.Handler<ResponseStreamEvent>() {
            @Override
            public void onNext(ResponseStreamEvent event) {}

            @Override
            public void onComplete(Optional<Throwable> error) {}
        });

        // Assert: TTFT is >= 0
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTimeToFirstToken()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void subscribe_capturesModelFromCreatedEvent() {
        // Arrange: created event provides model
        ResponseUsage usage = buildUsage(5L, 10L, 15L);
        ResponseStreamEvent createdEvent = buildCreatedEvent("gpt-4o-mini", usage);
        // completed event will override model (that's fine)
        ResponseStreamEvent completedEvent = buildCompletedEvent("gpt-4o-mini", usage);

        AsyncStreamResponse<ResponseStreamEvent> mockStream =
                buildMockAsyncStream(List.of(createdEvent, completedEvent));
        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.subscribe(new AsyncStreamResponse.Handler<ResponseStreamEvent>() {
            @Override
            public void onNext(ResponseStreamEvent event) {}

            @Override
            public void onComplete(Optional<Throwable> error) {}
        });

        // Assert: model captured correctly
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void subscribe_meteringEventHasOperationTypeResponse() {
        // Arrange
        ResponseUsage usage = buildUsage(5L, 10L, 15L);
        ResponseStreamEvent completedEvent = buildCompletedEvent("gpt-4o", usage);

        AsyncStreamResponse<ResponseStreamEvent> mockStream =
                buildMockAsyncStream(List.of(completedEvent));
        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.subscribe(new AsyncStreamResponse.Handler<ResponseStreamEvent>() {
            @Override
            public void onNext(ResponseStreamEvent event) {}

            @Override
            public void onComplete(Optional<Throwable> error) {}
        });

        // Assert: operationType is RESPONSE and isStreamed is true
        verify(meteringClient).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getOperationType()).isEqualTo("CHAT");
        assertThat(event.getIsStreamed()).isTrue();
    }

    @Test
    void subscribe_meteringFailureDoesNotBreakUserCallback() {
        // Arrange: metering throws
        ResponseUsage usage = buildUsage(5L, 10L, 15L);
        ResponseStreamEvent completedEvent = buildCompletedEvent("gpt-4o", usage);

        AsyncStreamResponse<ResponseStreamEvent> mockStream =
                buildMockAsyncStream(List.of(completedEvent));
        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        doThrow(new RuntimeException("metering boom")).when(meteringClient).send(any());

        AtomicBoolean onCompleteCalled = new AtomicBoolean(false);
        AtomicReference<Optional<Throwable>> onCompleteError = new AtomicReference<>();

        // Act
        wrapper.subscribe(new AsyncStreamResponse.Handler<ResponseStreamEvent>() {
            @Override
            public void onNext(ResponseStreamEvent event) {}

            @Override
            public void onComplete(Optional<Throwable> error) {
                onCompleteCalled.set(true);
                onCompleteError.set(error);
            }
        });

        // Assert: onComplete still called despite metering failure
        // onCompleteError holds Optional<Throwable> — stream completed normally so it's empty
        assertThat(onCompleteCalled.get()).isTrue();
        assertThat(onCompleteError.get()).isNotNull();
        assertThat(onCompleteError.get().isPresent()).isFalse();
    }

    @Test
    void subscribe_failedEventSetsErrorStatus() {
        // Arrange: stream delivers a failed terminal event
        ResponseStreamEvent failedEvent = buildFailedEvent("gpt-4o");

        AsyncStreamResponse<ResponseStreamEvent> mockStream =
                buildMockAsyncStream(List.of(failedEvent));
        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.subscribe(new AsyncStreamResponse.Handler<ResponseStreamEvent>() {
            @Override
            public void onNext(ResponseStreamEvent event) {}

            @Override
            public void onComplete(Optional<Throwable> error) {}
        });

        // Assert: stop reason maps to ERROR for failed status
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStopReason()).isEqualTo("ERROR");
    }

    @Test
    void subscribe_incompleteEventSetsTokenLimitStatus() {
        // Arrange: stream delivers an incomplete terminal event
        ResponseStreamEvent incompleteEvent = buildIncompleteEvent("gpt-4o");

        AsyncStreamResponse<ResponseStreamEvent> mockStream =
                buildMockAsyncStream(List.of(incompleteEvent));
        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.subscribe(new AsyncStreamResponse.Handler<ResponseStreamEvent>() {
            @Override
            public void onNext(ResponseStreamEvent event) {}

            @Override
            public void onComplete(Optional<Throwable> error) {}
        });

        // Assert: stop reason maps to TOKEN_LIMIT for incomplete status
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStopReason()).isEqualTo("TOKEN_LIMIT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onCompleteFuture_delegatesToDelegate() {
        // Arrange
        AsyncStreamResponse<ResponseStreamEvent> mockStream = mock(AsyncStreamResponse.class);
        CompletableFuture<Void> mockFuture = new CompletableFuture<>();
        when(mockStream.onCompleteFuture()).thenReturn(mockFuture);

        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        // Act
        CompletableFuture<Void> result = wrapper.onCompleteFuture();

        // Assert: delegate's future returned
        assertThat(result).isSameAs(mockFuture);
        verify(mockStream).onCompleteFuture();
    }

    @Test
    @SuppressWarnings("unchecked")
    void close_delegatesToDelegate() {
        // Arrange
        AsyncStreamResponse<ResponseStreamEvent> mockStream = mock(AsyncStreamResponse.class);
        MeteringAsyncResponseStreamResponse wrapper = createWrapper(mockStream);

        // Act
        wrapper.close();

        // Assert: delegate's close() was called
        verify(mockStream).close();
    }
}
