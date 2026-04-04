package io.revenium.metering.openai.provider.extraction;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.completions.CompletionUsage;
import io.revenium.metering.openai.model.MeteringEvent;
import io.revenium.metering.openai.model.StopReason;
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.provider.AzureModelResolver;
import io.revenium.metering.openai.provider.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts a {@link MeteringEvent} from an OpenAI SDK {@link ChatCompletion} response.
 *
 * <p>Stateless utility class — all fields are extracted from the response and caller-supplied
 * parameters. Usage fields ({@code promptTokens}, {@code completionTokens}, {@code totalTokens})
 * are null-safe: if the response contains no usage data (e.g., Ollama), token counts are null
 * and a DEBUG log is emitted (decisions D-07, D-08, D-09).
 *
 * <p>When provider is {@link Provider#AZURE}, the model name is resolved from Azure deployment
 * name to canonical OpenAI model name via {@link AzureModelResolver#resolve(String)}.
 *
 * <p>Provider string is always lowercase (e.g., "openai", "azure", "ollama") to match the
 * Python middleware convention and Revenium API expectations (Pitfall 5).
 *
 * <p>Thread-safe: no mutable state. Suitable for concurrent use.
 */
public final class ChatCompletionUsageExtractor {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionUsageExtractor.class);

    private ChatCompletionUsageExtractor() {
        // Utility class — not instantiable
    }

    /**
     * Extracts a {@link MeteringEvent} from a {@link ChatCompletion} SDK response.
     *
     * <p>If usage is absent ({@code Optional.empty()}), token counts are null and a DEBUG log
     * is emitted. This is expected for Ollama and other providers that omit usage data.
     *
     * @param params       the SDK request params (for input message capture); may be null
     * @param response     the SDK ChatCompletion response (never null)
     * @param provider     the detected AI provider (never null)
     * @param metadata     optional business context; may be null
     * @param requestTime  epoch millis at start of the SDK call
     * @param responseTime epoch millis at end of the SDK call
     * @return a populated {@link MeteringEvent} ready for {@code MeteringClient.send()}
     */
    public static MeteringEvent extract(
            ChatCompletionCreateParams params,
            ChatCompletion response,
            Provider provider,
            UsageMetadata metadata,
            long requestTime,
            long responseTime) {

        // Extract token counts — Optional-based, null-safe (D-09)
        Optional<CompletionUsage> usageOpt = response.usage();
        Long inputTokens = null;
        Long outputTokens = null;
        Long totalTokens = null;

        if (usageOpt.isPresent()) {
            CompletionUsage usage = usageOpt.get();
            inputTokens = usage.promptTokens();       // long → Long autobox
            outputTokens = usage.completionTokens();  // long → Long autobox
            totalTokens = usage.totalTokens();        // long → Long autobox
        } else {
            log.debug("ChatCompletion response has no usage field — provider may be Ollama or usage was omitted");
        }

        // Extract finish reason — guard against empty choices list (Pitfall 2: use asString() not known())
        String finishReasonStr = response.choices().isEmpty()
                ? null
                : response.choices().get(0).finishReason().asString();
        String stopReason = StopReason.fromFinishReason(finishReasonStr).name();

        // Model resolution — Azure deployment names → canonical model names
        String resolvedModel = response.model();
        if (provider == Provider.AZURE) {
            resolvedModel = AzureModelResolver.resolve(resolvedModel);
        }

        // Provider string must be lowercase (Pitfall 5)
        String providerStr = provider.name().toLowerCase(Locale.ROOT);

        MeteringEvent.Builder builder = MeteringEvent.builder()
                .model(resolvedModel)
                .provider(providerStr)
                .modelSource(providerStr)
                .inputTokenCount(inputTokens)
                .outputTokenCount(outputTokens)
                .totalTokenCount(totalTokens)
                .stopReason(stopReason)
                .requestTimeMillis(requestTime)
                .responseTimeMillis(responseTime)
                .completionStartTimeMillis(responseTime)
                .requestDuration(responseTime - requestTime)
                .isStreamed(false)
                .operationType("CHAT");

        // Extract prompt/response content for Revenium capture (opt-in on server side)
        if (params != null) {
            try {
                builder.inputMessages(extractInputMessages(params));
                builder.systemPrompt(extractSystemPrompt(params));
            } catch (Exception e) {
                log.debug("Failed to extract input messages: {}", e.getMessage());
            }
        }
        try {
            builder.outputResponse(extractOutputResponse(response));
        } catch (Exception e) {
            log.debug("Failed to extract output response: {}", e.getMessage());
        }

        // Map metadata fields — null-safe guard (D-08)
        if (metadata != null) {
            builder.traceId(metadata.traceId())
                   .taskType(metadata.taskType())
                   .organizationId(metadata.organizationId())
                   .subscriptionId(metadata.subscriptionId())
                   .productId(metadata.productId())
                   .agent(metadata.agent())
                   .responseQualityScore(metadata.responseQualityScore())
                   .subscriber(metadata.subscriber());
        }

        return builder.build();
    }

    /**
     * Serializes input messages as a JSON array of {role, content} objects.
     * Uses toString() on union-type content objects for readable representation.
     */
    static String extractInputMessages(ChatCompletionCreateParams params) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatCompletionMessageParam msg : params.messages()) {
            Map<String, String> entry = new LinkedHashMap<>();
            if (msg.isUser()) {
                entry.put("role", "user");
                // Content is a union type (text or array of content parts) — use toString()
                entry.put("content", contentToString(msg.asUser().content()));
            } else if (msg.isAssistant()) {
                entry.put("role", "assistant");
                msg.asAssistant().content().ifPresent(c ->
                    entry.put("content", c.toString()));
            } else if (msg.isSystem()) {
                entry.put("role", "system");
                entry.put("content", contentToString(msg.asSystem().content()));
            } else if (msg.isDeveloper()) {
                entry.put("role", "developer");
                entry.put("content", contentToString(msg.asDeveloper().content()));
            } else if (msg.isTool()) {
                entry.put("role", "tool");
                entry.put("content", contentToString(msg.asTool().content()));
            }
            if (!entry.isEmpty()) {
                messages.add(entry);
            }
        }
        if (messages.isEmpty()) {
            return null;
        }
        // Simple JSON serialization
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            int j = 0;
            for (Map.Entry<String, String> e : messages.get(i).entrySet()) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":\"")
                  .append(escapeJson(e.getValue())).append("\"");
                j++;
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Extracts the system prompt from the first system message, if any.
     */
    static String extractSystemPrompt(ChatCompletionCreateParams params) {
        for (ChatCompletionMessageParam msg : params.messages()) {
            if (msg.isSystem()) {
                return contentToString(msg.asSystem().content());
            }
        }
        return null;
    }

    /**
     * Converts any content union type to a string. For text content, extracts the text directly.
     * For other content types, falls back to toString().
     */
    private static String contentToString(Object content) {
        if (content == null) return null;
        return content.toString();
    }

    /**
     * Extracts the assistant's response content from the first choice.
     */
    static String extractOutputResponse(ChatCompletion response) {
        if (response.choices().isEmpty()) {
            return null;
        }
        return response.choices().get(0).message().content().orElse(null);
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
