# Revenium Middleware for OpenAI Java SDK

Transparent metering middleware for the [OpenAI Java SDK](https://github.com/openai/openai-java). Wraps client services to automatically report AI usage metrics (token counts, costs, timing, model info, and business metadata) to [Revenium](https://revenium.ai) with zero changes to your application logic.

## Features

- **Chat Completions** -- metering for sync and async `create()` and `createStreaming()` calls
- **Embeddings** -- metering for sync and async `create()` calls
- **Responses API** -- metering for sync and async `create()` and `createStreaming()` calls
- **Streaming support** -- captures token usage from final stream chunk and time-to-first-token
- **Provider detection** -- automatically identifies OpenAI, Azure OpenAI, and Ollama from the client's base URL
- **Azure model resolution** -- resolves Azure deployment names to canonical model names via regex heuristics
- **Business metadata** -- attach trace IDs, subscriber info, task types, and more to every metering event
- **Fire-and-forget** -- metering runs asynchronously on a dedicated thread pool; never blocks your API calls
- **Graceful degradation** -- metering failures are logged and silently dropped; your application is never affected

## Requirements

- Java 11+
- OpenAI Java SDK 4.x (`com.openai:openai-java`)

## Installation

### Gradle (Kotlin DSL)

```kotlin
implementation("io.revenium.metering:revenium-middleware-openai-java:0.1.0")
```

### Gradle (Groovy DSL)

```groovy
implementation 'io.revenium.metering:revenium-middleware-openai-java:0.1.0'
```

### Maven

```xml
<dependency>
    <groupId>io.revenium.metering</groupId>
    <artifactId>revenium-middleware-openai-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Install from Source (Local Maven)

Clone and install to your local Maven repository:

```bash
git clone https://github.com/revenium/revenium-middleware-openai-java.git
cd revenium-middleware-openai-java
./gradlew publishToMavenLocal
```

The artifact will be available at:

```
~/.m2/repository/io/revenium/metering/revenium-middleware-openai-java/0.1.0/
```

To use the local artifact, add `mavenLocal()` to your project's repositories:

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.revenium.metering:revenium-middleware-openai-java:0.1.0")
}
```

## Quick Start

### 1. Set your Revenium API key

```bash
export REVENIUM_METERING_API_KEY=hak_your_tenant_yourkey
```

### 2. Wrap your OpenAI client

```java
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.revenium.metering.openai.ReveniumOpenAIMiddleware;
import io.revenium.metering.openai.ReveniumInstrumentedClient;

OpenAIClient openai = OpenAIOkHttpClient.fromEnv();

try (ReveniumInstrumentedClient client = ReveniumOpenAIMiddleware.wrap(openai)) {
    // All calls through 'client' are automatically metered
}
```

### 3. Use as normal

```java
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.ChatModel;

ChatCompletion completion = client.chatCompletions().create(
    ChatCompletionCreateParams.builder()
        .model(ChatModel.GPT_4O)
        .addUserMessage("What is the capital of France?")
        .build()
);

System.out.println(completion.choices().get(0).message().content());
// Metering event sent to Revenium in the background
```

## Configuration

### Environment Variables

| Variable | Required | Default                  | Description |
|----------|----------|--------------------------|-------------|
| `REVENIUM_METERING_API_KEY` | Yes | --                       | Your Revenium API key (starts with `hak_`) |
| `REVENIUM_METERING_BASE_URL` | No | `https://api.revenium.ai` | Revenium API base URL |

### Programmatic Configuration

```java
import io.revenium.metering.openai.config.ReveniumConfig;

ReveniumConfig config = ReveniumConfig.builder()
    .apiKey("hak_your_tenant_yourkey")
    .baseUrl("https://api.revenium.ai")  // optional
    .build();

ReveniumInstrumentedClient client = ReveniumOpenAIMiddleware.wrap(openai, config);
```

Programmatic values override environment variables.

## Usage Examples

### Chat Completions (Non-Streaming)

```java
ChatCompletion result = client.chatCompletions().create(
    ChatCompletionCreateParams.builder()
        .model(ChatModel.GPT_4O)
        .addUserMessage("Explain quantum computing in one sentence.")
        .build()
);
```

### Chat Completions (Streaming)

```java
try (StreamResponse<ChatCompletionChunk> stream = client.chatCompletions().createStreaming(
        ChatCompletionCreateParams.builder()
            .model(ChatModel.GPT_4O)
            .addUserMessage("Write a haiku about Java.")
            .build())) {

    stream.stream().forEach(chunk -> {
        // Process chunks as they arrive
        chunk.choices().forEach(choice ->
            choice.delta().content().ifPresent(System.out::print));
    });
}
// Metering fires on stream close with token counts and time-to-first-token
```

### Embeddings

```java
CreateEmbeddingResponse embeddings = client.embeddings().create(
    EmbeddingCreateParams.builder()
        .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
        .addInputOfString("The quick brown fox jumps over the lazy dog.")
        .build()
);
```

### Responses API

```java
Response response = client.responses().create(
    ResponseCreateParams.builder()
        .model(ChatModel.GPT_4O)
        .addInputOfString("Summarize the history of computing.")
        .build()
);
```

### With Business Metadata

Attach business context to any metered call for cost attribution, billing, and analytics in Revenium:

```java
import io.revenium.metering.openai.model.UsageMetadata;
import io.revenium.metering.openai.model.Subscriber;
import io.revenium.metering.openai.model.Credential;

UsageMetadata metadata = UsageMetadata.builder()
    .traceId("session-abc-123")
    .taskType("customer-support")
    .organizationName("Acme Corp")
    .subscriptionId("sub-789")
    .productName("Acme Pro Plan")
    .agent("support-bot-v2")
    .responseQualityScore(0.95)
    .subscriber(Subscriber.builder()
        .id("user-001")
        .email("user@example.com")
        .credential(Credential.builder()
            .name("api-key")
            .value("key-xyz")
            .build())
        .build())
    .build();

// Pass metadata as the second argument to any create() call
ChatCompletion result = client.chatCompletions().create(params, metadata);
```

### Async Usage

```java
CompletableFuture<ChatCompletion> future = client.chatCompletionsAsync()
    .create(params);

future.thenAccept(result -> {
    System.out.println(result.choices().get(0).message().content());
});
// Metering fires automatically when the future completes
```

### Accessing the Original Client

```java
// Get the unwrapped OpenAI client for non-metered operations
OpenAIClient original = client.unwrap();
```

## Provider Detection

The middleware automatically detects the provider from your OpenAI client's base URL:

| Base URL Pattern | Detected Provider |
|-----------------|-------------------|
| `api.openai.com` | `OPENAI` |
| `*.openai.azure.com` | `AZURE` |
| `localhost`, other | `OLLAMA` |
| Unknown | `OPENAI` (default) |

### Azure OpenAI

Azure deployment names are automatically resolved to canonical model names using heuristic regex patterns. For example, a deployment named `my-gpt4o-prod` resolves to `gpt-4o` in the metering payload.

### Ollama

When using Ollama (or other OpenAI-compatible APIs), missing usage fields are handled gracefully with no exceptions. A DEBUG-level log is emitted for diagnostic purposes.

## Architecture

```
ReveniumOpenAIMiddleware.wrap(client)
    |
    v
ReveniumInstrumentedClient
    |-- chatCompletions()      -> MeteringChatCompletionService
    |-- chatCompletionsAsync() -> MeteringChatCompletionServiceAsync
    |-- embeddings()           -> MeteringEmbeddingService
    |-- embeddingsAsync()      -> MeteringEmbeddingServiceAsync
    |-- responses()            -> MeteringResponseService
    |-- responsesAsync()       -> MeteringResponseServiceAsync
    |
    v
MeteringClient (fire-and-forget async HTTP POST)
    |
    v
Revenium AI Metering API (POST /meter/v2/ai/completions)
```

Each wrapper:
1. Delegates the actual API call to the original OpenAI service
2. Captures timing, token usage, model info, and stop reason from the response
3. Builds a `MeteringEvent` and sends it via `MeteringClient.send()` asynchronously
4. Returns the original response unchanged

Metering never blocks your API calls and never propagates errors to your application.

## Thread Safety

All wrapper instances are thread-safe. Instance fields are `private final`, and all per-call state (timing, token accumulation) is captured in local variables. You can safely share a single `ReveniumInstrumentedClient` across threads.

## Resource Management

`ReveniumInstrumentedClient` implements `AutoCloseable`. Calling `close()`:

1. Initiates a graceful shutdown of the metering thread pool
2. Waits up to 5 seconds for in-flight metering events to drain
3. Forces shutdown if events are still pending

**Important:** `close()` does NOT close the underlying `OpenAIClient`. You retain full ownership of the original client's lifecycle.

A JVM shutdown hook is registered as a fallback for cases where `close()` is not called explicitly.

## Building from Source

```bash
# Clone
git clone https://github.com/revenium/revenium-middleware-openai-java.git
cd revenium-middleware-openai-java

# Build
./gradlew build

# Run tests
./gradlew test

# Install to local Maven repository
./gradlew publishToMavenLocal
```

### Build Requirements

- JDK 17+ (for building -- the library targets Java 11 bytecode)
- Gradle 9.x (wrapper included)

## Dependencies

### Runtime

- `com.fasterxml.jackson.core:jackson-databind` -- JSON serialization for metering payloads

### Compile-Only (provided by your application)

- `com.openai:openai-java` -- OpenAI Java SDK (you already have this)
- `org.slf4j:slf4j-api` -- Logging facade (you likely already have this)

The library does not bundle a logging implementation. Add your preferred SLF4J binding (e.g., `slf4j-simple`, `logback-classic`) to your application.

## License

[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)
