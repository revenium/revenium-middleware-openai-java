package io.revenium.metering.openai.wrapper;

import com.openai.core.RequestOptions;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingModel;
import com.openai.services.async.EmbeddingServiceAsync;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MeteringEmbeddingServiceAsync}.
 *
 * Covers: async delegation + metering with correct model/tokens/duration, UsageMetadata
 * passthrough, resilience (metering failure does not break future), and error handling
 * (failed future does not trigger metering).
 */
@ExtendWith(MockitoExtension.class)
class MeteringEmbeddingServiceAsyncTest {

    @Mock
    private EmbeddingServiceAsync delegate;

    @Mock
    private MeteringClient meteringClient;

    private MeteringEmbeddingServiceAsync wrapper;

    @BeforeEach
    void setUp() {
        ReveniumConfig config = ReveniumConfig.builder().apiKey("test-key").build();
        wrapper = new MeteringEmbeddingServiceAsync(delegate, config, meteringClient);
    }

    // -----------------------------------------------------------------------
    // Helper builders
    // -----------------------------------------------------------------------

    private CreateEmbeddingResponse.Usage buildUsage(long promptTokens, long totalTokens) {
        return CreateEmbeddingResponse.Usage.builder()
                .promptTokens(promptTokens)
                .totalTokens(totalTokens)
                .build();
    }

    private CreateEmbeddingResponse buildResponse(String model, long promptTokens, long totalTokens) {
        return CreateEmbeddingResponse.builder()
                .model(model)
                .usage(buildUsage(promptTokens, totalTokens))
                .data(Collections.emptyList())
                .build();
    }

    private EmbeddingCreateParams buildParams() {
        return EmbeddingCreateParams.builder()
                .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                .input(EmbeddingCreateParams.Input.ofString("test input"))
                .build();
    }

    // -----------------------------------------------------------------------
    // Async delegation + metering tests
    // -----------------------------------------------------------------------

    @Test
    void asyncCreate_delegatesAndMeters() {
        // Arrange
        CreateEmbeddingResponse mockResponse = buildResponse("text-embedding-3-small", 100L, 100L);
        when(delegate.create(any(EmbeddingCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: join to wait for whenComplete callback
        CreateEmbeddingResponse result = wrapper.create(buildParams(), RequestOptions.none()).join();

        // Assert: delegate called once
        verify(delegate, times(1)).create(any(EmbeddingCreateParams.class), any(RequestOptions.class));

        // Assert: metering event sent with correct embedding fields
        verify(meteringClient, times(1)).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getModel()).isEqualTo("text-embedding-3-small");
        assertThat(event.getInputTokenCount()).isEqualTo(100L);
        assertThat(event.getTotalTokenCount()).isEqualTo(100L);
        assertThat(event.getOperationType()).isEqualTo("EMBED");
        assertThat(event.getIsStreamed()).isFalse();

        // Assert: original response returned
        assertThat(result).isSameAs(mockResponse);
    }

    @Test
    void asyncCreate_capturesRequestDuration() {
        // Arrange
        CreateEmbeddingResponse mockResponse = buildResponse("text-embedding-3-small", 75L, 75L);
        when(delegate.create(any(EmbeddingCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.create(buildParams(), RequestOptions.none()).join();

        // Assert: requestDuration >= 0
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRequestDuration()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    void asyncCreate_errorDoesNotMeter() {
        // Arrange: failed future
        RuntimeException apiError = new RuntimeException("api error");
        when(delegate.create(any(EmbeddingCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.failedFuture(apiError));

        // Act: join and handle the failure
        assertThatCode(() -> {
            try {
                wrapper.create(buildParams(), RequestOptions.none()).join();
            } catch (Exception e) {
                // Expected — future completed exceptionally
            }
        }).doesNotThrowAnyException();

        // Assert: no metering event sent when future failed
        verify(meteringClient, never()).send(any());
    }

    @Test
    void asyncCreate_withUsageMetadata() {
        // Arrange
        CreateEmbeddingResponse mockResponse = buildResponse("text-embedding-3-small", 60L, 60L);
        when(delegate.create(any(EmbeddingCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        UsageMetadata metadata = UsageMetadata.builder()
                .traceId("async-embed-trace-001")
                .taskType("vector-search")
                .build();

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: overloaded async create with metadata
        CreateEmbeddingResponse result = wrapper.create(buildParams(), metadata).join();

        // Assert: metadata fields present in event
        verify(meteringClient).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getTraceId()).isEqualTo("async-embed-trace-001");
        assertThat(event.getTaskType()).isEqualTo("vector-search");

        // Assert: response returned normally
        assertThat(result).isSameAs(mockResponse);
    }

    @Test
    void asyncCreate_meteringFailureDoesNotBreakFuture() {
        // Arrange: send() throws but future should still resolve
        CreateEmbeddingResponse mockResponse = buildResponse("text-embedding-3-small", 80L, 80L);
        when(delegate.create(any(EmbeddingCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));
        doThrow(new RuntimeException("metering boom")).when(meteringClient).send(any());

        // Act + Assert: future resolves normally despite metering failure
        assertThatCode(() -> {
            CreateEmbeddingResponse result = wrapper.create(buildParams(), RequestOptions.none()).join();
            assertThat(result).isSameAs(mockResponse);
        }).doesNotThrowAnyException();
    }
}
