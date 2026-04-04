package io.revenium.metering.openai.wrapper;

import com.openai.core.RequestOptions;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingModel;
import com.openai.services.blocking.EmbeddingService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MeteringEmbeddingService}.
 *
 * Covers: delegation + metering with correct model/tokens/duration, UsageMetadata passthrough,
 * resilience (metering failure does not break caller), and withRawResponse delegation.
 */
@ExtendWith(MockitoExtension.class)
class MeteringEmbeddingServiceTest {

    @Mock
    private EmbeddingService delegate;

    @Mock
    private MeteringClient meteringClient;

    private MeteringEmbeddingService wrapper;

    @BeforeEach
    void setUp() {
        ReveniumConfig config = ReveniumConfig.builder().apiKey("test-key").build();
        wrapper = new MeteringEmbeddingService(delegate, config, meteringClient);
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
    // Delegation + metering tests
    // -----------------------------------------------------------------------

    @Test
    void create_delegatesAndMeters() {
        // Arrange
        CreateEmbeddingResponse mockResponse = buildResponse("text-embedding-3-small", 100L, 100L);
        when(delegate.create(any(EmbeddingCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        CreateEmbeddingResponse result = wrapper.create(buildParams(), RequestOptions.none());

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
    void create_capturesRequestDuration() {
        // Arrange
        CreateEmbeddingResponse mockResponse = buildResponse("text-embedding-3-small", 100L, 100L);
        when(delegate.create(any(EmbeddingCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act
        wrapper.create(buildParams(), RequestOptions.none());

        // Assert: requestDuration >= 0 (local variable timing captured)
        verify(meteringClient).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRequestDuration()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    void create_withUsageMetadata() {
        // Arrange
        CreateEmbeddingResponse mockResponse = buildResponse("text-embedding-3-small", 50L, 50L);
        when(delegate.create(any(EmbeddingCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);

        UsageMetadata metadata = UsageMetadata.builder()
                .traceId("embed-trace-001")
                .taskType("semantic-search")
                .build();

        ArgumentCaptor<MeteringEvent> eventCaptor = ArgumentCaptor.forClass(MeteringEvent.class);

        // Act: overloaded create with metadata
        CreateEmbeddingResponse result = wrapper.create(buildParams(), metadata);

        // Assert: metadata fields present in event
        verify(meteringClient).send(eventCaptor.capture());
        MeteringEvent event = eventCaptor.getValue();
        assertThat(event.getTraceId()).isEqualTo("embed-trace-001");
        assertThat(event.getTaskType()).isEqualTo("semantic-search");

        // Assert: response returned normally
        assertThat(result).isSameAs(mockResponse);
    }

    @Test
    void create_meteringFailureDoesNotBreakCaller() {
        // Arrange: configure meteringClient.send() to throw
        CreateEmbeddingResponse mockResponse = buildResponse("text-embedding-3-small", 100L, 100L);
        when(delegate.create(any(EmbeddingCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mockResponse);
        doThrow(new RuntimeException("metering boom")).when(meteringClient).send(any());

        // Act + Assert: no exception propagated, response still returned
        assertThatCode(() -> {
            CreateEmbeddingResponse result = wrapper.create(buildParams(), RequestOptions.none());
            assertThat(result).isSameAs(mockResponse);
        }).doesNotThrowAnyException();
    }

    @Test
    void withRawResponse_delegatesToWrapped() {
        // Arrange
        EmbeddingService.WithRawResponse mockRaw = mock(EmbeddingService.WithRawResponse.class);
        when(delegate.withRawResponse()).thenReturn(mockRaw);

        // Act + Assert: delegation passes through
        assertThat(wrapper.withRawResponse()).isSameAs(mockRaw);
    }
}
