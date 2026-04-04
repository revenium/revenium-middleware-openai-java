<!-- GSD:project-start source:PROJECT.md -->
## Project

**Revenium Middleware for OpenAI Java SDK**

A Java middleware library that wraps the OpenAI Java SDK's service interfaces to automatically capture and report AI usage metrics (token counts, costs, timing, model info, and business metadata) to Revenium's AI metering API. It provides transparent instrumentation — developers use familiar OpenAI SDK patterns while all metering happens asynchronously in the background.

**Core Value:** Every OpenAI API call (chat completions, embeddings, responses) is automatically metered to Revenium with zero changes to application logic — just wrap the client.

### Constraints

- **Java version**: Java 11 minimum — broadest compatibility
- **Build system**: Gradle with Kotlin DSL (build.gradle.kts)
- **HTTP client for metering**: Use java.net.HttpClient (available since Java 11) for Revenium API calls — no additional HTTP dependency
- **Dependencies**: openai-java SDK, minimal additional dependencies
- **Thread safety**: All wrappers must be thread-safe — middleware will be used in concurrent applications
- **Performance**: Async metering must add <50ms overhead to API calls
<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->
## Technology Stack

## Recommended Stack
### Core Technologies
| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| openai-java | 4.30.0 | The SDK being wrapped | Official OpenAI SDK; exposes clean Kotlin interfaces (`ChatService`, `EmbeddingService`, `ResponseService`) each implementing a consistent decorator-friendly pattern with `withRawResponse()` and `withOptions()` methods. Compiles to Java bytecode, fully usable from Java. |
| Java | 11 (minimum target) | Language/runtime baseline | `java.net.HttpClient` ships in Java 11, eliminating any HTTP dependency for Revenium API calls. Java 11 is still in wide enterprise use (2026) and provides the broadest library consumer compatibility. JDK 17+ is required to **build** via Gradle toolchains but the output bytecode targets Java 11. |
| Gradle | 9.4.1 (wrapper) | Build system | Kotlin DSL is now Gradle's default; Gradle 9.x is the current stable release. The project already specifies Gradle + Kotlin DSL in PROJECT.md constraints. Gradle Toolchains let you build with JDK 17+ while emitting Java 11 bytecode — required because Gradle 8+ itself needs JVM 17+ to run. |
| Kotlin DSL (build.gradle.kts) | bundled with Gradle 9.4.1 | Build script language | Better IDE support, type safety, and first-class Gradle API access vs Groovy DSL. Officially recommended as the default for new Gradle projects since Gradle 7.0. |
### Supporting Libraries
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| jackson-databind | 2.21.0 | Serialize request/response POJOs to JSON for Revenium metering API calls | **Required.** The Revenium API payload (`model`, `tokenCounts`, `provider`, `stopReason`, `usageMetadata`) needs JSON serialization. Use Jackson 2.21.x (LTS, Java 8+ compatible) — **not** Jackson 3.x (requires Java 17, breaks this library's Java 11 target). |
| SLF4J API | 2.0.17 | Logging facade | **Required.** Middleware libraries must not bind a logging implementation — they declare `slf4j-api` as a `compileOnly`/`api` dependency and leave implementation choice (Logback, Log4j2, etc.) to the consuming application. Log async metering errors without crashing the caller. |
| com.vanniktech.maven.publish | 0.28.0 | Publish artifacts to Maven Central | **Required for distribution.** `vanniktech/gradle-maven-publish-plugin` is the community-standard Gradle plugin for publishing Java/Kotlin libraries to Maven Central. Note: version 0.36.0 requires Gradle 9 + JDK 17 for the plugin itself — use 0.28.x if you need Gradle 8 compatibility, or use 0.36.0 with Gradle 9.4.x (recommended). Handles sources JAR, javadoc JAR, POM generation, GPG signing, and Sonatype upload. |
### Development / Test Tools
| Tool | Version | Purpose | Notes |
|------|---------|---------|-------|
| JUnit 5 (Jupiter) | 5.14.3 | Unit test framework | `testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")`. Do not use JUnit 4. JUnit 6.0.3 is available but is the next major version — JUnit 5.14.x is the current stable LTS line and appropriate for a production Java 11 library. |
| Mockito | 5.23.0 | Mock OpenAI SDK service interfaces in unit tests | `testImplementation("org.mockito:mockito-core:5.23.0")`. Mock `ChatService`, `EmbeddingService`, `ResponseService` and their nested `WithRawResponse` interfaces without hitting OpenAI. Mockito 5.x requires Java 11+, which matches the project target. |
| WireMock | 3.13.2 | Mock Revenium HTTP metering API in integration tests | `testImplementation("org.wiremock:wiremock:3.13.2")`. Use `@WireMockTest` JUnit 5 annotation for simple cases. Verifies that the library sends correctly formatted POST requests to the Revenium completions endpoint, including headers, JSON body shape, and error handling. |
| AssertJ | 3.27.7 | Fluent test assertions | `testImplementation("org.assertj:assertj-core:3.27.7")`. Significantly more readable than JUnit's built-in `assertEquals`. Use for asserting captured metrics, JSON payloads, and async CompletableFuture results. |
## Gradle Dependency Declarations
## Alternatives Considered
| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| `java.net.HttpClient` (JDK 11) | OkHttp | OkHttp is already a transitive dependency of `openai-java-client-okhttp`, but adding it as a direct dependency for Revenium calls adds weight and coupling. Use `java.net.HttpClient` — it ships in Java 11+, has no version conflict risk, and is sufficient for simple fire-and-forget POSTs. |
| Jackson 2.21.x | Gson 2.x | Gson is fine for simple cases. Choose Jackson because `openai-java` already uses it transitively — aligning avoids classpath conflicts and adds zero new dependency weight. |
| Jackson 2.21.x | Jackson 3.x | Jackson 3.0 (Oct 2025) requires Java 17, which would raise this library's minimum target and break Java 11 consumers. Stay on 2.21.x (LTS) until you're ready to drop Java 11. |
| Mockito 5.x | EasyMock | Mockito is the industry standard; it has excellent JUnit 5 integration via `MockitoExtension`. EasyMock offers no advantages here. |
| WireMock 3.x | MockWebServer (OkHttp) | WireMock has richer request verification, better JUnit 5 integration (`@WireMockTest`), and clear stubbing DSL. MockWebServer is acceptable if you want zero extra test dependencies, but WireMock's request verification is critical for confirming the Revenium JSON payload structure. |
| `vanniktech/gradle-maven-publish-plugin` | JReleaser | JReleaser is more powerful but more complex to configure. For a single-module library publishing to Maven Central, `vanniktech` plugin is simpler and better documented for this exact use case. |
| CompletableFuture (JDK) | Project Reactor / RxJava | Reactive libraries add a significant dependency for a use case (fire-and-forget async POST) that CompletableFuture handles directly with no extra dependency. |
## What NOT to Use
| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Lombok | Java 11 records aren't available (records are Java 16+), but Lombok adds annotation processing complexity and can conflict with Kotlin compilation when build.gradle.kts is involved. The openai-java SDK itself uses Kotlin data classes/builders — the wrapper only needs POJOs for the Revenium payload, which manual builders handle cleanly. | Plain Java with builders; Jackson's `@JsonProperty` for serialization |
| `spring-web` / `RestTemplate` | Pulling in Spring just for HTTP calls bloats the dependency chain by hundreds of MBs and creates a hard Spring version coupling for every consumer. | `java.net.HttpClient` (JDK 11) |
| `TheoKanning/openai-java` (community SDK) | Deprecated unofficial SDK. The official `com.openai:openai-java` is the correct target — it has the clean service interfaces needed for wrapping. | `com.openai:openai-java:4.30.0` |
| Jackson 3.0+ | Requires Java 17 minimum. Breaking this library's Java 11 compatibility target. | `com.fasterxml.jackson.core:jackson-databind:2.21.0` |
| ForkJoinPool.commonPool for async metering | The default executor for `CompletableFuture.runAsync()` is the common pool, which is shared with all application code. Metering errors and backpressure can starve application threads. | Dedicated single-thread or bounded `ExecutorService` injected at construction time, with `CompletableFuture.runAsync(task, dedicatedExecutor)` |
| Groovy DSL (build.gradle) | The project constraint specifies Kotlin DSL. Groovy DSL also has weaker IDE support and no compile-time build script verification. | `build.gradle.kts` (Kotlin DSL) |
## Architecture Pattern: Service Delegation
## Version Compatibility
| Dependency | Compatible With | Notes |
|------------|-----------------|-------|
| `openai-java:4.30.0` | Java 8+ | SDK declares Java 8 minimum; uses Kotlin stdlib under the hood |
| `jackson-databind:2.21.0` | Java 8+ (LTS until 2.22+ supersedes) | Jackson 2.x and 3.x are not binary compatible — keep 2.x |
| `mockito-core:5.23.0` | Java 11+ | Mockito 5.x dropped Java 8 support. This aligns with project's Java 11 minimum. |
| `wiremock:3.13.2` | Java 11+ | WireMock 3.x requires Java 11 |
| `junit-jupiter:5.14.3` | Java 8+ | JUnit 5 test-scope only, no runtime impact on library consumers |
| `gradle:9.4.1` (wrapper) | JVM 17+ to run Gradle | Build machine needs JDK 17+; output targets Java 11 via Toolchain config |
## Sources
- `github.com/openai/openai-java` README + service interface files (ChatService.kt, EmbeddingService.kt, ResponseService.kt) — HIGH confidence
- `github.com/openai/openai-java/releases` — confirmed 4.30.0 as latest, March 25, 2026 — HIGH confidence
- `gradle.org/releases/` — confirmed Gradle 9.4.1, Mar 19, 2026 — HIGH confidence
- `github.com/mockito/mockito/releases` — confirmed Mockito 5.23.0, March 11, 2026 — HIGH confidence
- `github.com/junit-team/junit5/releases` — confirmed JUnit 5.14.3, Feb 15, 2025 — HIGH confidence
- `github.com/assertj/assertj/releases` — confirmed AssertJ 3.27.7, Jan 24, 2025 — HIGH confidence
- `github.com/wiremock/wiremock/releases` — confirmed WireMock 3.13.2, Nov 14, 2024 — HIGH confidence
- `github.com/vanniktech/gradle-maven-publish-plugin/releases` — confirmed 0.36.0, Jan 18, 2025 — HIGH confidence
- `slf4j.org/news.html` — confirmed SLF4J 2.0.17, Feb 25, 2025 — HIGH confidence
- `github.com/FasterXML/jackson/wiki/Jackson-Releases` — confirmed Jackson 2.21.0 (LTS, Jan 18, 2026); Jackson 3.0 requires Java 17 — HIGH confidence
- WebSearch: Gradle Java toolchain pattern for Java 11 target with JDK 17+ build — MEDIUM confidence (verified against Gradle docs)
- WebSearch: SLF4J compileOnly pattern for libraries — MEDIUM confidence (well-established Java library convention)
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd:quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd:debug` for investigation and bug fixing
- `/gsd:execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd:profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
