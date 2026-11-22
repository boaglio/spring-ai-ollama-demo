package demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.ai.model.tool.ToolCallConfirmationRequiredException;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
import org.springframework.ai.session.tool.SessionRecallTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import demo.domain.Booking;

/** Spring AI 2.1.x on Spring Boot 4.2: the "after" picture. */
@SpringBootApplication
public class BookingApp {

	static final String SYSTEM = """
			You are a flight booking assistant. Always use the tools to search, book or cancel.
			Today is 2026-11-21. Prices are in EUR. Answer briefly.""";

	public static void main(String[] args) {
		SpringApplication.run(BookingApp.class, args);
	}

	@Bean
	@SuppressWarnings("removal") // ChatMemory & MessageChatMemoryAdvisor: deprecated in 2.1 in favour of the Session API
	CommandLineRunner demo(OllamaChatModel chatModel, ChatClient.Builder builder, ToolCallingManager toolCallingManager,
			DecisionModel decisionModel, @Value("${demo.auto-approve:false}") boolean autoApprove) {
		return args -> {
			BookingTools tools = new BookingTools();
			ChatMemory memory = MessageWindowChatMemory.builder().build();
			ChatClient chatClient = builder.clone().defaultSystem(SYSTEM) // clone(): the Builder is mutable, scenario 7 reuses it
				.defaultTools(tools)
				.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
				.build();

			// 1) ChatModel directly: in 2.x the model only RETURNS tool calls, it does not run them
			Section.title("1) ChatModel.call() with tools  [2.x: tools are NOT run by the ChatModel]");
			BookingTools.CALLS.set(0);
			// 2.x: options are immutable and a fresh builder() is NOT empty (it carries the library
			// default model "mistral"), so start from the model's configured defaults and mutate()
			var options = chatModel.getOptions().mutate().toolCallbacks(ToolCallbacks.from(tools)).build();
			Prompt prompt = new Prompt("Which flights go to Lisbon on 2026-12-04?", options);
			ChatResponse direct = chatModel.call(prompt);
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("toolCalls", describe(direct.getResult().getOutput().getToolCalls()) + "  <- returned, not executed");
			Section.line("answer", blank(direct.getResult().getOutput().getText()));

			// 1b) ...so you run the loop yourself with ToolCallingManager (or use ChatClient, see 2)
			Section.title("1b) Manual loop with ToolCallingManager");
			ChatResponse looped = runToolLoop(chatModel, toolCallingManager, prompt, direct, autoApprove);
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("answer", looped.getResult().getOutput().getText());

			// 2) ChatClient: ToolCallingAdvisor runs the loop; reasoning is a first-class part
			Section.title("2) ChatClient + memory + thinking  [2.1: getReasoning() / ReasoningPart]");
			ChatResponse r = chatClient.prompt()
				.user("Find flights to Lisbon on 2026-12-04")
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "demo")) // 2.x: conversation id is mandatory
				.call()
				.chatResponse();
			AssistantMessage out = r.getResult().getOutput();
			Section.line("thinking", abbreviate(out.getReasoning().stream().map(ReasoningPart::text).collect(Collectors.joining(" "))));
			Section.line("parts", out.getParts().stream().map(p -> p.getClass().getSimpleName()).toList());
			Section.line("finish", r.getResult().getMetadata().getFinishReason());
			Section.line("answer", out.getText());

			// 3) Structured output, relying on the memory of turn 2
			Section.title("3) Book the cheapest (memory) -> Booking record");
			Booking booking = chatClient.prompt()
				.user("Book the cheapest one")
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "demo"))
				.call()
				.entity(Booking.class);
			Section.line("booking", booking);

			// 4) Dangerous tool: @Tool(requiresConfirmation = true) pauses the ChatClient loop
			Section.title("4) Cancel via ChatClient  [2.1: pauses, awaiting a human decision]");
			BookingTools.CALLS.set(0);
			ChatResponse paused = chatClient.prompt()
				.user("Cancel that booking")
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "demo"))
				.call()
				.chatResponse();
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("awaiting", ToolCallDecisions.isAwaitingDecision(paused) ? ToolCallDecisions.awaitingDecision(paused)
					: "nothing (model did not call cancelBooking)");
			Section.line("toolCalls", describe(paused.getResult().getOutput().getToolCalls()));

			// 4b) Approve/deny it: ToolCallingManager.executeToolCalls(prompt, response, decisions)
			Section.title("4b) Human approves or denies the cancel");
			BookingTools.CALLS.set(0);
			Prompt cancelPrompt = new Prompt("Cancel booking " + booking.bookingId(), options);
			ChatResponse cancelled = runToolLoop(chatModel, toolCallingManager, cancelPrompt, chatModel.call(cancelPrompt),
					autoApprove);
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("answer", cancelled.getResult().getOutput().getText());

			// 5) Runaway tool calling: spring.ai.tools.limits.* stops it
			Section.title("5) Many tool calls  [2.1: tool call limits, here searchFlights max 3]");
			BookingTools.CALLS.set(0);
			try {
				ChatResponse many = chatClient.prompt()
					.user("Search flights to Lisbon for EVERY day from 2026-12-01 to 2026-12-07, one search per day, "
							+ "then tell me the cheapest day.")
					.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "limits")) // 2.x: no default id, it throws without one
					.call()
					.chatResponse();
				// ChatClient turns the limit into a final generation instead of throwing
				Section.line("finish", many.getResult().getMetadata().getFinishReason());
				Section.line("answer", many.getResult().getOutput().getText());
			}
			catch (ToolCallLimitExceededException ex) {
				Section.line("STOPPED", ex.getClass().getSimpleName() + ": tool=" + ex.getToolName() + " limit=" + ex.getLimit());
			}
			Section.line("tools run", BookingTools.CALLS.get());

			// 6) DecisionModel: a tiny local classifier in front of the big model (no 1.x equivalent)
			Section.title("6) OllamaDecisionModel  [2.1: yes/no routing with a 600M model]");
			for (String msg : List.of("Please get rid of my Lisbon trip", "What's the weather like in Lisbon?",
					"I don't want to fly anymore, drop the reservation")) {
				DecisionResponse d = decisionModel.call(msg,
						Map.of("cancel", YesNoQuestion.of("Does the user want to cancel a booking?")));
				var yes = d.getYesNo("cancel");
				IO.println("  %-55s cancel? %-5s (p=%.2f)".formatted("\"" + msg + "\"", yes.isYes(), yes.getProbability()));
			}

			// 7) Session API: the 2.1 replacement for ChatMemory (event log + compaction + recall tool)
			Section.title("7) Session API  [2.1: replaces ChatMemory: event log, compaction, recall]");
			SessionService sessions = DefaultSessionService.builder()
				.sessionRepository(InMemorySessionRepository.builder().build()) // database: spring-ai-starter-model-session-jdbc
				.build();
			ChatClient sessionClient = builder.clone()
				.defaultSystem(SYSTEM + " If the user refers to something you cannot see in the conversation, "
						+ "use the conversation_search tool to look it up.")
				.defaultTools(tools, SessionRecallTools.builder(sessions).build()) // gives the model conversation_search
				.defaultAdvisors(SessionAdvisors.builder(sessions)
					// after more than 3 turns, keep only the last 2 turns in the prompt (the log keeps everything)
					.compaction(new TurnCountTrigger(3), WindowCompactionStrategy.builder().lastTurns(2).build())
					.asyncCompaction(false)
					.sessionIdInToolContext(true) // lets conversation_search know which session to search
					.build())
				.build();
			String sessionId = "trip-42";
			for (String question : List.of("Find flights to Porto on 2026-12-10", "Book the cheapest one",
					"Now find flights to Madrid on 2026-12-12", "Which Madrid flight leaves earliest?",
					"What was the booking id of my Porto flight?")) {
				BookingTools.CALLS.set(0);
				String answer = sessionClient.prompt()
					.user(question)
					.advisors(SessionAdvisors.session(sessionId)) // session is created on first use (findOrCreate)
					.call()
					.content();
				long compactions = sessions.getEvents(sessionId)
					.stream()
					.filter(e -> EventType.COMPACTION.equals(e.getType()))
					.count();
				IO.println("  > " + question);
				Section.line("answer", abbreviate(answer));
				Section.line("session", sessions.getEvents(sessionId).size() + " events stored, "
						+ sessions.getActiveMessages(sessionId).size() + " messages sent to the model, "
						+ compactions + " compaction(s)");
			}
		};
	}

	/** The loop ChatModel used to run for you in 1.x, now explicit, with human approval. */
	static ChatResponse runToolLoop(OllamaChatModel chatModel, ToolCallingManager manager, Prompt prompt,
			ChatResponse response, boolean autoApprove) {
		while (response.hasToolCalls()) {
			ToolExecutionResult result;
			try {
				result = manager.executeToolCalls(prompt, response);
			}
			catch (ToolCallConfirmationRequiredException ex) {
				AssistantMessage msg = response.getResult().getOutput();
				List<String> keys = ToolCallDecisions.toolCallKeys(msg);
				List<ToolCallDecision> decisions = new ArrayList<>();
				for (int i = 0; i < keys.size(); i++) {
					String key = keys.get(i);
					if (!ex.getToolCallIds().contains(key)) {
						decisions.add(ToolCallDecision.allow(key));
						continue;
					}
					var call = msg.getToolCalls().get(i);
					boolean ok = ask("  ⚠ Allow " + call.name() + call.arguments() + " ? [y/N] ", autoApprove);
					decisions.add(ok ? ToolCallDecision.allow(key) : ToolCallDecision.deny(key, "The user refused."));
					Section.line("decision", (ok ? "ALLOW " : "DENY ") + call.name());
				}
				result = manager.executeToolCalls(prompt, response, new ToolCallDecisions(decisions));
			}
			prompt = new Prompt(result.conversationHistory(), prompt.getOptions());
			response = chatModel.call(prompt);
		}
		return response;
	}

	static boolean ask(String question, boolean autoApprove) {
		if (autoApprove) {
			IO.println(question + "y (auto-approve)");
			return true;
		}
		String answer = IO.readln(question); // Java 25: prints the prompt, reads a line (null at end of input)
		return answer != null && answer.trim().toLowerCase().startsWith("y");
	}

	static String describe(List<AssistantMessage.ToolCall> calls) {
		return calls.isEmpty() ? "none" : calls.stream().map(c -> c.name() + c.arguments()).toList().toString();
	}

	static String blank(String s) {
		return (s == null || s.isBlank()) ? "(empty: the model is waiting for tool results)" : s;
	}

	static String abbreviate(String s) {
		if (s == null || s.isBlank()) {
			return "(none)";
		}
		s = s.replaceAll("\\s+", " ").trim();
		return s.length() > 200 ? s.substring(0, 200) + "..." : s;
	}

}
