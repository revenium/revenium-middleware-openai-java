package io.revenium.metering.openai.provider.extraction;

import com.openai.models.completions.CompletionUsage;
import com.openai.models.responses.ResponseUsage;

/**
 * Extracts cache-read and reasoning token counts from OpenAI SDK usage objects.
 *
 * <p>Centralizes the accessor-shape differences between the Chat Completions and Responses API
 * usage types so no call site reads {@code cachedTokens()} / {@code reasoningTokens()} directly.
 * This exact bug class — cache/reasoning tokens declared on the wire model but never populated
 * by any extraction call site — has now recurred across six language/surface implementations
 * (BACK-1873, BACK-1919, BACK-1920, BACK-1925, BACK-2391, BACK-2409); every occurrence traced
 * back to the extraction logic being duplicated per call site instead of shared. All six
 * metering paths in this middleware ({@link ChatCompletionUsageExtractor},
 * {@link ResponseUsageExtractor}, and the four streaming wrappers) route through here.
 *
 * <p><b>Cache-creation tokens are intentionally never populated.</b> OpenAI's API — both Chat
 * Completions and Responses — only reports cache <i>reads</i> ({@code cached_tokens}); there is
 * no cache-write/creation concept comparable to Anthropic's {@code cache_creation_input_tokens}.
 * {@code MeteringEvent.cacheCreationTokenCount} stays null on every path this class feeds — a
 * real API limitation, not an oversight (the same kind of deliberate exclusion documented on
 * {@link EmbeddingUsageExtractor}'s {@code outputTokenCount(0L)}).
 *
 * <p><b>Input-token semantics are untouched by this class.</b> {@code inputTokenCount} continues
 * to come from {@code promptTokens()} / {@code inputTokens()} gross, exactly as before this class
 * existed. OpenAI's {@code cached_tokens} is a <i>subset</i> of that gross count, not additive —
 * callers must not subtract it. That normalization is handled in exactly one place, backend-side
 * (BACK-2412); subtracting it here too would double-subtract and under-charge the customer.
 *
 * <p>Accessor shapes differ between the two APIs and are handled explicitly rather than papered
 * over:
 * <ul>
 *   <li>{@link CompletionUsage#promptTokensDetails()} / {@link CompletionUsage#completionTokensDetails()}
 *       are themselves {@code Optional} — the nested detail object may be entirely absent — and
 *       their {@code cachedTokens()} / {@code reasoningTokens()} fields are {@code Optional<Long>}.</li>
 *   <li>{@link ResponseUsage#inputTokensDetails()} / {@link ResponseUsage#outputTokensDetails()}
 *       are non-{@code Optional} (always present once the parent {@code ResponseUsage} is
 *       present), and their {@code cachedTokens()} / {@code reasoningTokens()} fields are
 *       primitive {@code long} (always present, defaulting to 0 when no cache/reasoning tokens
 *       were used).</li>
 * </ul>
 * Both APIs otherwise use {@code cached_tokens} the same way — as a subset of the gross prompt /
 * input token count — so the same BACK-2412 backend normalization applies uniformly regardless
 * of which extraction path produced the event.
 *
 * <p>Thread-safe: no mutable state. Suitable for concurrent use.
 */
public final class UsageDetailsExtractor {

    private UsageDetailsExtractor() {
        // Utility class — not instantiable
    }

    /**
     * Extracts cache-read and reasoning token counts from a Chat Completions {@link CompletionUsage}.
     *
     * <p>Null-safe: if the nested detail object or field is absent, the corresponding
     * {@link UsageDetails} field is null (matches the null-safe convention used for the
     * top-level token counts elsewhere in this package).
     *
     * @param usage the SDK CompletionUsage (never null)
     * @return the extracted cache/reasoning token counts, never null
     */
    public static UsageDetails from(CompletionUsage usage) {
        Long cacheReadTokens = usage.promptTokensDetails()
                .flatMap(CompletionUsage.PromptTokensDetails::cachedTokens)
                .orElse(null);
        Long reasoningTokens = usage.completionTokensDetails()
                .flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens)
                .orElse(null);
        return new UsageDetails(cacheReadTokens, reasoningTokens);
    }

    /**
     * Extracts cache-read and reasoning token counts from a Responses API {@link ResponseUsage}.
     *
     * <p>Unlike {@link #from(CompletionUsage)}, the nested detail objects and their fields are
     * never {@code Optional} on this type — they are read directly and autoboxed.
     *
     * @param usage the SDK ResponseUsage (never null)
     * @return the extracted cache/reasoning token counts, never null
     */
    public static UsageDetails from(ResponseUsage usage) {
        Long cacheReadTokens = usage.inputTokensDetails().cachedTokens();
        Long reasoningTokens = usage.outputTokensDetails().reasoningTokens();
        return new UsageDetails(cacheReadTokens, reasoningTokens);
    }

    /** Immutable holder for the cache-read and reasoning token counts this helper extracts. */
    public static final class UsageDetails {
        private final Long cacheReadTokenCount;
        private final Long reasoningTokenCount;

        UsageDetails(Long cacheReadTokenCount, Long reasoningTokenCount) {
            this.cacheReadTokenCount = cacheReadTokenCount;
            this.reasoningTokenCount = reasoningTokenCount;
        }

        public Long cacheReadTokenCount() {
            return cacheReadTokenCount;
        }

        public Long reasoningTokenCount() {
            return reasoningTokenCount;
        }
    }
}
