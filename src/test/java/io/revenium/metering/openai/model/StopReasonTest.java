package io.revenium.metering.openai.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StopReasonTest {

    @ParameterizedTest
    @CsvSource({
        "stop,           END",
        "length,         TOKEN_LIMIT",
        "content_filter, ERROR",
        "tool_calls,     END_SEQUENCE",
        "function_call,  END_SEQUENCE",
        "timeout,        TIMEOUT"
    })
    void knownFinishReasonsMapCorrectly(String finishReason, StopReason expected) {
        assertThat(StopReason.fromFinishReason(finishReason.trim())).isEqualTo(expected);
    }

    @Test
    void nullFinishReasonMapsToEnd() {
        assertThat(StopReason.fromFinishReason(null)).isEqualTo(StopReason.END);
    }

    @Test
    void unknownFinishReasonDefaultsToError() {
        assertThat(StopReason.fromFinishReason("some_new_reason")).isEqualTo(StopReason.ERROR);
    }

    @Test
    void allEnumConstantsExist() {
        assertThat(StopReason.values())
            .extracting(Enum::name)
            .containsExactlyInAnyOrder(
                "END", "END_SEQUENCE", "TOKEN_LIMIT", "COST_LIMIT",
                "COMPLETION_LIMIT", "ERROR", "TIMEOUT", "CANCELLED"
            );
    }
}
