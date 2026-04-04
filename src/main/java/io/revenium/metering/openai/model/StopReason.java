package io.revenium.metering.openai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps OpenAI {@code finish_reason} values to Revenium's stop reason taxonomy.
 *
 * <p>Use {@link #fromFinishReason(String)} to convert an OpenAI finish reason string
 * (from chat completions or streaming responses) to the corresponding Revenium enum value.
 *
 * <p>Decision references: D-10 (enum + factory), D-11 (full enum set),
 * D-12 (unknown defaults to ERROR), D-13 (null maps to END).
 */
public enum StopReason {

    /** Normal completion — the model finished generating naturally. */
    END,

    /** Completion ended due to a stop sequence or tool/function call boundary. */
    END_SEQUENCE,

    /** Completion truncated due to token limit. */
    TOKEN_LIMIT,

    /** Completion stopped due to cost limit (future use — no current OpenAI mapping). */
    COST_LIMIT,

    /** Completion stopped due to a completion limit (future use — no current OpenAI mapping). */
    COMPLETION_LIMIT,

    /** Completion ended due to an error or content filter. */
    ERROR,

    /** Completion timed out. */
    TIMEOUT,

    /** Completion was cancelled (future use — no current OpenAI mapping). */
    CANCELLED;

    private static final Logger log = LoggerFactory.getLogger(StopReason.class);

    /**
     * Converts an OpenAI {@code finish_reason} string to the corresponding {@link StopReason}.
     *
     * <p>Handles null gracefully — null finish_reason occurs in streaming responses before
     * the final chunk and maps to {@link #END} (D-13).
     *
     * <p>Unknown values are logged at WARN level and return {@link #ERROR} (D-12).
     *
     * @param finishReason the OpenAI finish_reason value, may be null
     * @return the corresponding {@link StopReason}, never null
     */
    public static StopReason fromFinishReason(String finishReason) {
        if (finishReason == null) {
            return END;  // D-13: null is completed streaming
        }
        switch (finishReason) {
            case "stop":           return END;
            case "length":         return TOKEN_LIMIT;
            case "content_filter": return ERROR;
            case "tool_calls":     return END_SEQUENCE;
            case "function_call":  return END_SEQUENCE;
            case "timeout":        return TIMEOUT;
            default:
                log.warn("Unknown OpenAI finish_reason '{}', defaulting to ERROR", finishReason);
                return ERROR;  // D-12
        }
    }
}
