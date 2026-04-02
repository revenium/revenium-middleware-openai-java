# Revenium Middleware for OpenAI Java SDK

## What This Is

A Java middleware library that wraps the OpenAI Java SDK's service interfaces to automatically capture and report AI usage metrics (token counts, costs, timing, model info, and business metadata) to Revenium's AI metering API. It provides transparent instrumentation — developers use familiar OpenAI SDK patterns while all metering happens asynchronously in the background.

## Core Value

Every OpenAI API call (chat completions, embeddings, responses) is automatically metered to Revenium with zero changes to application logic — just wrap the client.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Wrap chat completions service (sync and async) to capture model, token counts, timing, and metadata
- [ ] Wrap embeddings service (sync and async) to capture usage metrics
- [ ] Wrap responses service (sync and async) to capture usage metrics
- [ ] Support streaming responses — wrap stream to capture token usage from final chunk, including time-to-first-token
- [ ] Detect provider automatically (OpenAI, Azure OpenAI, Ollama) from client configuration
- [ ] Azure deployment name to model name resolution
- [ ] Accept `usageMetadata` for business context (traceId, taskType, subscriber, organizationId, subscriptionId, productId, agent, responseQualityScore)
- [ ] Fire-and-forget async metering — never block the caller, never propagate metering errors
- [ ] Call Revenium AI completion metering API directly (no revenium-metering-java-sdk dependency)
- [ ] Graceful degradation — middleware failures never break user applications
- [ ] Configuration via environment variables (REVENIUM_METERING_API_KEY, REVENIUM_METERING_BASE_URL) and programmatic builder
- [ ] Publish as Gradle artifact: io.revenium.metering:revenium-middleware-openai-java
- [ ] Java 11 minimum compatibility

### Out of Scope

- LangChain/LangGraph integration — no established Java LangChain ecosystem yet, defer
- Spring Boot starter with @Metered annotation — separate project (revenium-spring-boot-starter already exists)
- Image/video/audio metering — focus on text completions/embeddings for v1
- OAuth/token-based Revenium auth — API key only for v1
- Decorator/annotation-based selective metering — keep it simple, wrap the client

## Context

- **Python reference implementation**: github.com/revenium/revenium-middleware-openai-python (v0.5.0) — uses wrapt monkey-patching, supports chat completions, embeddings, responses, streaming, Azure, Ollama, LangChain
- **OpenAI Java SDK**: github.com/openai/openai-java (v4.30.0) — Kotlin-based, three artifacts (core, okhttp, meta), clean service interfaces for chat completions, embeddings, responses
- **Revenium AI metering API**: POST to completions endpoint with model, token counts, provider, stopReason, timing, usage_metadata. Returns 201 on success. Silently ignores unrecognized fields.
- **Revenium Java SDK exists** (io.revenium.metering:revenium-metering-java-sdk) but this middleware will call the API directly to minimize dependency chain
- **Key API gotcha**: stopReason field is required for completions metering. Valid values: END, END_SEQUENCE, TOKEN_LIMIT, COST_LIMIT, COMPLETION_LIMIT, ERROR, TIMEOUT, CANCELLED

## Constraints

- **Java version**: Java 11 minimum — broadest compatibility
- **Build system**: Gradle with Kotlin DSL (build.gradle.kts)
- **HTTP client for metering**: Use java.net.HttpClient (available since Java 11) for Revenium API calls — no additional HTTP dependency
- **Dependencies**: openai-java SDK, minimal additional dependencies
- **Thread safety**: All wrappers must be thread-safe — middleware will be used in concurrent applications
- **Performance**: Async metering must add <50ms overhead to API calls

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Service wrapper approach (not HttpClient decorator) | Matches Python's method-level interception, gives access to typed request/response objects, cleaner API | — Pending |
| Direct Revenium API calls (no Java SDK dependency) | Minimizes dependency chain, keeps middleware self-contained | — Pending |
| Java 11 minimum | Broadest compatibility, java.net.HttpClient available | — Pending |
| All three providers in v1 (OpenAI, Azure, Ollama) | Matches Python middleware scope | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd:transition`):
1. Requirements invalidated? -> Move to Out of Scope with reason
2. Requirements validated? -> Move to Validated with phase reference
3. New requirements emerged? -> Add to Active
4. Decisions to log? -> Add to Key Decisions
5. "What This Is" still accurate? -> Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-02 after initialization*
