# Spring AI 1.1 → 2.1, side by side (with Ollama)

Two small Spring Boot apps run the same **flight booking assistant** against a local Ollama:

| Module | Spring AI | Spring Boot | Spring Framework | Jackson |
|---|---|---|---|---|
| [`booking-1.1`](booking-1.1) | 1.1.1 | 3.5.16 | 6.2 | 2 (`com.fasterxml.jackson`) |
| [`booking-2.1`](booking-2.1) | 2.1.0 | 4.2.0 | 7.1 | 3 (`tools.jackson`) |

Both apps use the same tools ([`BookingTools`](booking-2.1/src/main/java/demo/BookingTools.java): `searchFlights`, `bookFlight`, `cancelBooking`) and the same prompts. Every time a tool really runs, it prints `🔧 TOOL EXECUTED`. Each app is a `CommandLineRunner` that works through numbered scenarios, and each scenario shows one difference between the versions.

## Running it

```bash
./run.sh            # both apps
./run.sh 2.1        # just one
AUTO_APPROVE=true ./run.sh 2.1          # approve the "cancel" without being asked
CHAT_MODEL=llama3.1:8b ./run.sh         # use another tool-capable model
```

You need Java 21+, Maven, and Ollama running on `localhost:11434` (or set `OLLAMA_URL`). The models are `qwen3:8b` for chat (it handles tools and thinking) and `d1-omni:600m` for the 2.1 decision model. `run.sh` offers to pull any model you don't have. Output goes to `run-1.1.log` and `run-2.1.log`.

---

## The main differences

### 1. The tool loop moved out of `ChatModel` (the biggest behaviour change)

In **1.1**, `ChatModel.call()` with tools runs the whole loop by itself: the model asks for a tool, the tool runs, the result goes back to the model, and you get the final answer. In **2.x**, `ChatModel` only *returns* the tool calls. The loop now lives in `ToolCallingAdvisor`, which `ChatClient` registers automatically. If you call `ChatModel` directly, you run the loop yourself with `ToolCallingManager`.

This code still compiles after the upgrade, but the tools quietly stop running.

| | Code | Output |
|---|---|---|
| 1.1 | [`BookingApp.java#L40-L48`](booking-1.1/src/main/java/demo/BookingApp.java#L40-L48) | `tools run 1`, final answer |
| 2.1 | [`BookingApp.java#L61-L71`](booking-2.1/src/main/java/demo/BookingApp.java#L61-L71) | `tools run 0`, `toolCalls [searchFlights{...}] <- returned, not executed`, empty answer |
| 2.1 manual loop | [`runToolLoop` `#L153-L183`](booking-2.1/src/main/java/demo/BookingApp.java#L153-L183) | `manager.executeToolCalls(prompt, response)`, then call the model again |

With `ChatClient` (scenarios 2 to 5) the loop still runs automatically in both versions.

### 2. Options are immutable, and a new `builder()` is not empty

In 2.x, options objects have no setters (`setTemperature()` won't compile). For `OllamaChatOptions`, a new `builder()` also comes with the library's default model, **`mistral`**, which **overrides** the model you configured. In 1.1, unset fields in request options were filled in from the configured defaults. So the same line of code:

```java
OllamaChatOptions.builder().toolCallbacks(...).build()
```

works in 1.1 ([`#L43`](booking-1.1/src/main/java/demo/BookingApp.java#L43)), but in 2.1 it fails with `HTTP 404 - model 'mistral' not found`. In 2.x, start from the model's configured defaults and change them with `mutate()` ([`#L62-L65`](booking-2.1/src/main/java/demo/BookingApp.java#L62-L65)):

```java
chatModel.getOptions().mutate().toolCallbacks(ToolCallbacks.from(tools)).build();
```

`ChatClient.prompt().options(...)` also takes an options **builder** in 2.x, not a built `ChatOptions`.

### 3. Configuration properties

| | 1.1 ([`application.properties`](booking-1.1/src/main/resources/application.properties)) | 2.1 ([`application.properties`](booking-2.1/src/main/resources/application.properties)) |
|---|---|---|
| Model | `spring.ai.ollama.chat.options.model` | `spring.ai.ollama.chat.model` (the `.options` segment is gone) |
| Thinking | `...chat.options.think-option` or `enableThinking()` in code | `spring.ai.ollama.chat.think=true\|false\|low\|medium\|high` (`enableThinking()` no longer exists) |
| Temperature | 0.7 when you set nothing | **no default**, so the model's own default applies |

In 2.1 the old `...chat.options.*` keys still work, but they're marked deprecated and point to the new names.

### 4. Reasoning ("thinking") is now a standard part of the message

In 2.1, messages are made of ordered **parts**: `TextPart`, `ReasoningPart`, `ToolCallPart`, `ToolResultPart`, `MediaPart`. You read reasoning the same way for every provider.

| | Code | How you read it |
|---|---|---|
| 1.1 | [`#L49-L60`](booking-1.1/src/main/java/demo/BookingApp.java#L49-L60) | `r.getResult().getMetadata().get("thinking")`: an Ollama-specific key in the *generation* metadata |
| 2.1 | [`#L78-L89`](booking-2.1/src/main/java/demo/BookingApp.java#L78-L89) | `out.getReasoning()` → `List<ReasoningPart>`; `out.getParts()` → `[ReasoningPart, TextPart]` |

### 5. Chat memory needs an explicit conversation id, and `ChatMemory` is deprecated

In 1.1, `MessageChatMemoryAdvisor` used a default conversation id when you didn't pass one. 1.1 scenario 5 relies on that ([`#L82-L93`](booking-1.1/src/main/java/demo/BookingApp.java#L82-L93)). In 2.x the same call throws `IllegalArgumentException: conversationId cannot be null`, so every call has to pass `ChatMemory.CONVERSATION_ID` ([`#L129`](booking-2.1/src/main/java/demo/BookingApp.java#L129)).

2.1 also deprecates `ChatMemory`, `MessageWindowChatMemory` and `MessageChatMemoryAdvisor`, to be removed in 3.0. Their replacement is the new **Session API** (`SessionService`, `SessionAdvisors`, JDBC repository, compaction). This demo still uses `ChatMemory` in both apps so the two stay comparable.

### 6. Tools that need a person's approval (new in 2.1)

```java
@Tool(description = "Cancel an existing booking by its booking id", requiresConfirmation = true)
```
([`BookingTools.java#L35`](booking-2.1/src/main/java/demo/BookingTools.java#L35))

| | Code | Output |
|---|---|---|
| 1.1 | [`#L71-L81`](booking-1.1/src/main/java/demo/BookingApp.java#L71-L81) | `cancelBooking` runs immediately, no questions asked |
| 2.1 ChatClient | [`#L100-L111`](booking-2.1/src/main/java/demo/BookingApp.java#L100-L111) | the loop **pauses**: `tools run 0`, `awaiting [call_…]` (`ToolCallDecisions.isAwaitingDecision`) |
| 2.1 approve/deny | [`#L113-L120`](booking-2.1/src/main/java/demo/BookingApp.java#L113-L120), [`#L160-L176`](booking-2.1/src/main/java/demo/BookingApp.java#L160-L176) | `executeToolCalls` throws `ToolCallConfirmationRequiredException`; you collect `ToolCallDecision.allow(..)` / `deny(..)` and call `executeToolCalls(prompt, response, decisions)`. A denied call goes back to the model as a refusal, and the model explains it. |

### 7. Tool call limits (new in 2.1)

2.1 caps tool calls by default: 40 per tool and 150 per turn (`spring.ai.tools.limits.*`). The demo lowers the limit to 3 for `searchFlights`, then asks for 7 searches.

| | Code | Output |
|---|---|---|
| 1.1 | [`#L82-L93`](booking-1.1/src/main/java/demo/BookingApp.java#L82-L93) | all 7 searches run |
| 2.1 | [`#L122-L139`](booking-2.1/src/main/java/demo/BookingApp.java#L122-L139) + `max-calls-per-tool.searchFlights=3` | 3 searches, then `finish toolCallLimitExceeded` |

Through `ChatClient`, going over the limit ends the turn with that finish reason and a final message. It does **not** throw `ToolCallLimitExceededException` (`on-limit-exceeded=throw` is the property's default). Check the finish reason.

### 8. `DecisionModel` (new in 2.1, nothing like it in 1.x)

`DecisionModel` is a small classifier API that answers `YesNoQuestion`, `ScoreQuestion` and `ChoiceQuestion` with probabilities. `OllamaDecisionModel` uses a ~600M model (`d1-omni:600m`), so a tiny local model can make routing decisions in front of the large chat model. 2.1 also builds `DecisionDocumentReranker`, `DecisionDocumentFilter` and `DecisionToolIndex` on top of it.

[`#L141-L151`](booking-2.1/src/main/java/demo/BookingApp.java#L141-L151):

```
"Please get rid of my Lisbon trip"                    cancel? true  (p=1.00)
"What's the weather like in Lisbon?"                  cancel? false (p=0.43)
"I don't want to fly anymore, drop the reservation"   cancel? true  (p=1.00)
```

---

## Other 1.1 → 2.x changes not covered by this demo

- **Platform:** Boot 4.x, Framework 7, Jackson 3 (`JsonHelper` replaces `JsonParser`), and JSpecify null-safety annotations.
- **Providers:** the OpenAI and Anthropic integrations now use the official vendor SDKs. `OpenAiApi` and `AnthropicApi` are removed. Azure OpenAI, `spring-ai-openai-sdk`, OCI GenAI, HanaDB and Cosmos DB modules are removed or now maintained by their vendors.
- **Renames:** `FunctionCallback` → `ToolCallback`, `defaultFunctions()` → `defaultTools()`, `ToolCallAdvisor` → `ToolCallingAdvisor`. `internalToolExecutionEnabled` is removed, and `PromptChatMemoryAdvisor` is removed.
- **MCP:** Java SDK 2.x. The annotations moved to `org.springframework.ai.mcp.annotation`, and Streamable HTTP is the default transport. 2.1 adds the stateless 2026-07-28 protocol (`protocol=MODERN`).
- **Also new in 2.1:** OpenAI Responses API (`spring.ai.openai.chat.api=responses`), streaming of tool-call arguments as they arrive, `FinishReasonCategory`, guardrail refusals (`ContentFilteredException`), `VectorStore.upsert()` with your own embeddings, and 13 CVE fixes (also in 2.0.3).

Before migrating a real app, read the official [upgrade notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html). The release posts are [2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/) and [2.1.0 GA](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/).
