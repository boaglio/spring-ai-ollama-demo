# Spring AI 1.1 → 2.1, side by side (with Ollama)

Two small Spring Boot apps run the same **flight booking assistant** against a local Ollama:

| Module | Spring AI | Spring Boot | Spring Framework | Jackson |
|---|---|---|---|---|
| [`booking-1.1`](booking-1.1) | 1.1.1 | 3.5.16 | 6.2 | 2 (`com.fasterxml.jackson`) |
| [`booking-2.1`](booking-2.1) | 2.1.0 | 4.2.0 | 7.1 | 3 (`tools.jackson`) |

Both apps use the same tools ([`BookingTools`](booking-2.1/src/main/java/demo/BookingTools.java): `searchFlights`, `bookFlight`, `cancelBooking`) and the same prompts. The flights are domestic, from São Paulo (GRU) to Recife, Salvador and Florianópolis, on LATAM, GOL and Azul, with prices in reais (R$). The user prompts are in **Brazilian Portuguese**. The system prompt ([`Prompts`](booking-2.1/src/main/java/demo/common/Prompts.java)) is in **English**, because small models follow English instructions more reliably and the tool descriptions are English too, and it tells the model to always reply in Brazilian Portuguese. The approval prompt in 2.1 is `[s/N]`, where `s` means *sim* (`y` also works). Every time a tool really runs, it prints `🔧 TOOL EXECUTED`. Each app is a `CommandLineRunner` that works through numbered scenarios, and each scenario shows one difference between the versions.

### Project layout

Both modules use the same packages:

```
src/main/java/demo/
├── BookingApp.java      the scenarios: this is where 1.1 and 2.1 differ
├── BookingTools.java    @Tool methods (2.1 adds requiresConfirmation on cancelBooking)
├── common/              identical in both modules
│   ├── Prompts.java     the system prompt, so both apps send the same text
│   ├── Section.java     console output for the scenarios
│   └── Text.java        abbreviate() for one-line output
└── domain/              identical in both modules
    ├── Booking.java
    └── Flight.java
```

`common` and `domain` are byte-for-byte the same in both modules, so a diff of the two modules shows only the Spring AI changes:

```bash
diff -r booking-1.1/src/main/java/demo booking-2.1/src/main/java/demo
```

## Running it

```bash
./run.sh            # both apps
./run.sh 2.1        # just one
AUTO_APPROVE=true ./run.sh 2.1          # approve the "cancel" without being asked
CHAT_MODEL=llama3.1:8b ./run.sh         # use another tool-capable model
```

On Windows, use `run.cmd` from Command Prompt or `run.ps1` from PowerShell. `run.cmd` starts `run.ps1` without changing the PowerShell execution policy.

```powershell
.\run.cmd                                      # both apps (Command Prompt or PowerShell)
.\run.cmd 2.1                                  # just one
$env:AUTO_APPROVE = "true"; .\run.ps1 2.1      # PowerShell: approve the cancel without being asked
$env:CHAT_MODEL = "llama3.1:8b"; .\run.ps1     # PowerShell: another model
```

In Command Prompt, set variables first, for example `set AUTO_APPROVE=true` and then `run.cmd`. On Windows the log files are written only with `AUTO_APPROVE=true`. PowerShell passes a program's output on line by line, so logging the interactive run would hide the `[s/N]` prompt until you had answered it.

You need Java 25+, Maven, and Ollama running on `localhost:11434` (or set `OLLAMA_URL`). The models are `qwen3:8b` for chat (it handles tools and thinking) and `d1-omni:600m` for the 2.1 decision model. `run.sh` offers to pull any model you don't have. Output goes to `run-1.1.log` and `run-2.1.log`.

---

## The main differences

### 1. The tool loop moved out of `ChatModel` (the biggest behaviour change)

In **1.1**, `ChatModel.call()` with tools runs the whole loop by itself: the model asks for a tool, the tool runs, the result goes back to the model, and you get the final answer. In **2.x**, `ChatModel` only *returns* the tool calls. The loop now lives in `ToolCallingAdvisor`, which `ChatClient` registers automatically. If you call `ChatModel` directly, you run the loop yourself with `ToolCallingManager`.

This code still compiles after the upgrade, but the tools quietly stop running.

| | Code | Output |
|---|---|---|
| 1.1 | [`BookingApp.java#L42-L50`](booking-1.1/src/main/java/demo/BookingApp.java#L42-L50) | `tools run 1`, final answer |
| 2.1 | [`BookingApp.java#L72-L83`](booking-2.1/src/main/java/demo/BookingApp.java#L72-L83) | `tools run 0`, `toolCalls [searchFlights{...}] <- returned, not executed`, empty answer |
| 2.1 manual loop | [`runToolLoop` `#L225-L254`](booking-2.1/src/main/java/demo/BookingApp.java#L225-L254) | `manager.executeToolCalls(prompt, response)`, then call the model again |

With `ChatClient` (scenarios 2 to 5) the loop still runs automatically in both versions.

### 2. Options are immutable, and a new `builder()` is not empty

In 2.x, options objects have no setters (`setTemperature()` won't compile). For `OllamaChatOptions`, a new `builder()` also comes with the library's default model, **`mistral`**, which **overrides** the model you configured. In 1.1, unset fields in request options were filled in from the configured defaults. So the same line of code:

```java
OllamaChatOptions.builder().toolCallbacks(...).build()
```

works in 1.1 ([`#L45`](booking-1.1/src/main/java/demo/BookingApp.java#L45)), but in 2.1 it fails with `HTTP 404 - model 'mistral' not found`. In 2.x, start from the model's configured defaults and change them with `mutate()` ([`#L74-L77`](booking-2.1/src/main/java/demo/BookingApp.java#L74-L77)):

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
| 1.1 | [`#L51-L62`](booking-1.1/src/main/java/demo/BookingApp.java#L51-L62) | `r.getResult().getMetadata().get("thinking")`: an Ollama-specific key in the *generation* metadata |
| 2.1 | [`#L90-L101`](booking-2.1/src/main/java/demo/BookingApp.java#L90-L101) | `out.getReasoning()` → `List<ReasoningPart>`; `out.getParts()` → `[ReasoningPart, TextPart]` |

### 5. Chat memory needs an explicit conversation id, and `ChatMemory` is deprecated

In 1.1, `MessageChatMemoryAdvisor` used a default conversation id when you didn't pass one. 1.1 scenario 5 relies on that ([`#L103-L113`](booking-1.1/src/main/java/demo/BookingApp.java#L103-L113)). In 2.x the same call throws `IllegalArgumentException: conversationId cannot be null`, so every call has to pass `ChatMemory.CONVERSATION_ID` ([`#L161`](booking-2.1/src/main/java/demo/BookingApp.java#L161)).

2.1 also deprecates `ChatMemory`, `MessageWindowChatMemory` and `MessageChatMemoryAdvisor`, to be removed in 3.0. Their replacement is the new **Session API**, shown in [section 9](#9-session-api-the-replacement-for-chatmemory-new-in-21). Scenarios 2 to 5 still use `ChatMemory` in both apps so the two stay comparable.

### 6. Tools that need a person's approval (new in 2.1)

```java
@Tool(description = "Cancel an existing booking by its booking id", requiresConfirmation = true)
```
([`BookingTools.java#L38`](booking-2.1/src/main/java/demo/BookingTools.java#L38))

| | Code | Output |
|---|---|---|
| 1.1 | [`#L92-L102`](booking-1.1/src/main/java/demo/BookingApp.java#L92-L102) | `cancelBooking` runs immediately, no questions asked |
| 2.1 ChatClient | [`#L131-L142`](booking-2.1/src/main/java/demo/BookingApp.java#L131-L142) | the loop **pauses**: `tools run 0`, `awaiting [call_…]` (`ToolCallDecisions.isAwaitingDecision`) |
| 2.1 approve/deny | [`#L144-L152`](booking-2.1/src/main/java/demo/BookingApp.java#L144-L152), [`#L233-L248`](booking-2.1/src/main/java/demo/BookingApp.java#L233-L248) | `executeToolCalls` throws `ToolCallConfirmationRequiredException`; you collect `ToolCallDecision.allow(..)` / `deny(..)` and call `executeToolCalls(prompt, response, decisions)`. A denied call goes back to the model as a refusal, and the model explains it. |

### 7. Tool call limits (new in 2.1)

2.1 caps tool calls by default: 40 per tool and 150 per turn (`spring.ai.tools.limits.*`). The demo lowers the limit to 3 for `searchFlights`, then asks for 7 searches.

| | Code | Output |
|---|---|---|
| 1.1 | [`#L103-L113`](booking-1.1/src/main/java/demo/BookingApp.java#L103-L113) | every search the model asks for runs (7 in most runs, 6 in one) |
| 2.1 | [`#L154-L171`](booking-2.1/src/main/java/demo/BookingApp.java#L154-L171) + `max-calls-per-tool.searchFlights=3` | 3 searches, then `finish toolCallLimitExceeded` |

Through `ChatClient`, going over the limit ends the turn with that finish reason and a final message. It does **not** throw `ToolCallLimitExceededException` (`on-limit-exceeded=throw` is the property's default). Check the finish reason.

### 8. `DecisionModel` (new in 2.1, nothing like it in 1.x)

`DecisionModel` is a small classifier API that answers `YesNoQuestion`, `ScoreQuestion` and `ChoiceQuestion` with probabilities. `OllamaDecisionModel` uses a ~600M model (`d1-omni:600m`), so a tiny local model can make routing decisions in front of the large chat model. 2.1 also builds `DecisionDocumentReranker`, `DecisionDocumentFilter` and `DecisionToolIndex` on top of it.

[`#L173-L181`](booking-2.1/src/main/java/demo/BookingApp.java#L173-L181):

The question (*"O usuário quer cancelar uma reserva?"*) and the three messages are in Brazilian Portuguese. Output from a real run:

```
"Por favor, cancela minha viagem para Recife"           cancel? true  (p=1.00)
"Como está o tempo em Recife?"                          cancel? false (p=0.00)
"Não quero mais viajar, pode desfazer a reserva"        cancel? true  (p=0.99)
```

The 600M model handles Portuguese well. In an earlier run with English messages, the weather question scored p=0.43, so the separation was weaker.

### 9. Session API: the replacement for `ChatMemory` (new in 2.1)

`ChatMemory` keeps a sliding window of messages. Anything that falls out of the window is **gone**. The Session API stores the conversation as an **append-only event log** instead, and separates two things that `ChatMemory` mixes together:

- **What is stored:** every message, kept for good.
- **What the model sees:** the *active* messages, a view of the log that **compaction** keeps short.

| | 1.1 `ChatMemory` | 2.1 Session API |
|---|---|---|
| Storage | `ChatMemoryRepository` (in-memory, JDBC, Cassandra, Mongo, Neo4j, Redis) | `SessionRepository` (`InMemorySessionRepository`; JDBC via `spring-ai-starter-model-session-jdbc`) |
| Service | `MessageWindowChatMemory` | `SessionService` (`DefaultSessionService`) |
| Advisor | `MessageChatMemoryAdvisor.builder(memory).build()` | `SessionAdvisors.builder(sessions).build()` (returns a list of advisors) |
| Conversation id | `a.param(ChatMemory.CONVERSATION_ID, id)` | `SessionAdvisors.session(id)`, which uses the same `chat_memory_conversation_id` key; the session is created on first use |
| Old messages | dropped once they fall out of the window | kept in the log; compaction only changes what the model sees |
| Compaction | fixed window size | a `CompactionTrigger` (`TurnCountTrigger`, `TokenCountTrigger`) decides **when**, a `CompactionStrategy` (`WindowCompactionStrategy`: `lastTurns`, `lastEvents`, `tokenBudget`, optionally with an LLM `summarizer`) decides **how** |
| Recall | none | `SessionRecallTools` gives the model a `conversation_search` tool over the **whole** log |
| Extras | — | session metadata and time-to-live (`CreateSessionRequest`), and `findByMetadata` |

Scenario 7 ([`#L183-L222`](booking-2.1/src/main/java/demo/BookingApp.java#L183-L222)) wires it up like this:

```java
SessionService sessions = DefaultSessionService.builder()
    .sessionRepository(InMemorySessionRepository.builder().build())
    .build();

ChatClient sessionClient = builder.clone()
    .defaultTools(tools, SessionRecallTools.builder(sessions).build())   // conversation_search
    .defaultAdvisors(SessionAdvisors.builder(sessions)
        .compaction(new TurnCountTrigger(3), WindowCompactionStrategy.builder().lastTurns(2).build())
        .sessionIdInToolContext(true)                                    // so conversation_search knows the session
        .build())
    .build();

sessionClient.prompt().user(question).advisors(SessionAdvisors.session("trip-42")).call().content();
```

It runs five turns: search Salvador, book the cheapest, search Florianópolis, ask which Florianópolis flight leaves earliest, then ask *"Qual era o código da reserva do meu voo para Salvador?"* ("What was the booking id of my Salvador flight?"). After the 4th turn there are more than 3 turns, so compaction keeps only the last 2 turns for the model and the Salvador booking drops out of the prompt. The model then has to look it up with `conversation_search`. With `ChatMemory` and a 2-turn window, that booking would simply be lost. Each turn prints how many events are stored, how many messages are sent to the model, and how many compactions have run. Each `conversation_search` call is printed as `🔎 TOOL EXECUTED`, with its keywords and result, through a small `ToolCallback` wrapper.

The compaction behaviour above was checked directly against `SessionService` without an LLM. After 4 turns there were 9 events stored (8 messages plus 1 `COMPACTION` event), 4 active messages, and `searchEvents("BK-…")` still found the old booking. In a real run, after turn 4 there were 15 events stored but only 6 messages sent to the model. In turn 5 the model searched the log and found the booking that compaction had removed from its view:

```
      🔎 TOOL EXECUTED: conversation_search{"query":"BK-, Salvador"} -> {"results":[...]}
  > Qual era o código da reserva do meu voo para Salvador?
  answer     O código da sua reserva para Salvador é **BK-G31842-001**. ...
  session    19 events stored, 10 messages sent to the model, 1 compaction(s)
```

`conversation_search` matches **literal text**, so the system prompt for scenario 7 tells the model to search for short keywords such as `BK-, Salvador`. Without that hint, an earlier run searched badly and answered that there was no booking for Salvador, because the message with the booking id never contains the word "Salvador".

A note on `ChatClient.Builder`: the auto-configured builder is **mutable**. Calling `defaultSystem(...)` or `defaultAdvisors(...)` on it changes it for everyone who uses it later, so use `builder.clone()` whenever you build more than one client from it ([`#L67`](booking-2.1/src/main/java/demo/BookingApp.java#L67)).

---

## Other 1.1 → 2.x changes not covered by this demo

- **Platform:** Boot 4.x, Framework 7, Jackson 3 (`JsonHelper` replaces `JsonParser`), and JSpecify null-safety annotations.
- **Jackson 3 is stricter with structured output.** If the model leaves out a field that maps to a primitive (`int priceBrl`), Jackson 2 (1.1) quietly sets `0`. Jackson 3 (2.1) throws `MismatchedInputException: Cannot map null into type int` (`FAIL_ON_NULL_FOR_PRIMITIVES` is now on by default). Both apps catch that in scenario 3 and print a warning instead of stopping. Use wrapper types (`Integer`) or validate the result if the model might leave fields out.
- **Don't ask for a tool call and a typed result in the same turn (seen in both versions).** With `.entity(Booking.class)` on the turn that should run `bookFlight`, qwen3 replied with JSON straight away and never called the tool. Its "booking" was invented, copying the id pattern from an example in the tool description. Scenario 3 now books with `.content()`, so the tool runs, and asks for the `Booking` record in a second turn, from memory. Keep example values that look like real data out of tool descriptions.
- **Providers:** the OpenAI and Anthropic integrations now use the official vendor SDKs. `OpenAiApi` and `AnthropicApi` are removed. Azure OpenAI, `spring-ai-openai-sdk`, OCI GenAI, HanaDB and Cosmos DB modules are removed or now maintained by their vendors.
- **Renames:** `FunctionCallback` → `ToolCallback`, `defaultFunctions()` → `defaultTools()`, `ToolCallAdvisor` → `ToolCallingAdvisor`. `internalToolExecutionEnabled` is removed, and `PromptChatMemoryAdvisor` is removed.
- **MCP:** Java SDK 2.x. The annotations moved to `org.springframework.ai.mcp.annotation`, and Streamable HTTP is the default transport. 2.1 adds the stateless 2026-07-28 protocol (`protocol=MODERN`).
- **Also new in 2.1:** OpenAI Responses API (`spring.ai.openai.chat.api=responses`), streaming of tool-call arguments as they arrive, `FinishReasonCategory`, guardrail refusals (`ContentFilteredException`), `VectorStore.upsert()` with your own embeddings, and 13 CVE fixes (also in 2.0.3).

Before migrating a real app, read the official [upgrade notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html). The release posts are [2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/) and [2.1.0 GA](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/).
