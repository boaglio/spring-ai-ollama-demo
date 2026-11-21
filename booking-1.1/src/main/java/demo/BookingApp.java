package demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Spring AI 1.1.x on Spring Boot 3.5: the "before" picture. */
@SpringBootApplication
public class BookingApp {

	static final String SYSTEM = """
			You are a flight booking assistant. Always use the tools to search, book or cancel.
			Today is 2026-11-21. Prices are in EUR. Answer briefly.""";

	public static void main(String[] args) {
		SpringApplication.run(BookingApp.class, args);
	}

	@Bean
	CommandLineRunner demo(OllamaChatModel chatModel, ChatClient.Builder builder) {
		return args -> {
			BookingTools tools = new BookingTools();
			ChatMemory memory = MessageWindowChatMemory.builder().build();
			ChatClient chatClient = builder.defaultSystem(SYSTEM)
				.defaultTools(tools)
				.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
				.build();

			// 1) ChatModel directly: in 1.1 the model itself runs the tool loop
			Section.title("1) ChatModel.call() with tools  [1.1: tools run INSIDE the ChatModel]");
			BookingTools.CALLS.set(0);
			var options = OllamaChatOptions.builder().toolCallbacks(ToolCallbacks.from(tools)).build();
			ChatResponse direct = chatModel.call(new Prompt("Which flights go to Lisbon on 2026-12-04?", options));
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("toolCalls", direct.getResult().getOutput().getToolCalls().size() + " pending (already executed)");
			Section.line("answer", direct.getResult().getOutput().getText());

			// 2) ChatClient + memory + thinking
			Section.title("2) ChatClient + memory + thinking  [1.1: thinking hidden in metadata]");
			ChatResponse r = chatClient.prompt()
				.user("Find flights to Lisbon on 2026-12-04")
				.options(OllamaChatOptions.builder().enableThinking().build()) // 1.1 builder method
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "demo"))
				.call()
				.chatResponse();
			AssistantMessage out = r.getResult().getOutput();
			// 1.1: Ollama thinking is a provider-specific key in the *generation* metadata
			Section.line("thinking", abbreviate(r.getResult().getMetadata().get("thinking")));
			Section.line("answer", out.getText());

			// 3) Structured output, relying on the memory of turn 2
			Section.title("3) Book the cheapest (memory) -> Booking record");
			Booking booking = chatClient.prompt()
				.user("Book the cheapest one")
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "demo"))
				.call()
				.entity(Booking.class);
			Section.line("booking", booking);

			// 4) Dangerous tool: runs with no confirmation
			Section.title("4) Cancel the booking  [1.1: no human-in-the-loop, it just runs]");
			BookingTools.CALLS.set(0);
			String cancel = chatClient.prompt()
				.user("Cancel that booking")
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "demo"))
				.call()
				.content();
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("answer", cancel);

			// 5) Runaway tool calling: no built-in limit in 1.1
			Section.title("5) Many tool calls  [1.1: no built-in tool call limit]");
			BookingTools.CALLS.set(0);
			String many = chatClient.prompt()
				.user("Search flights to Lisbon for EVERY day from 2026-12-01 to 2026-12-07, one search per day, "
						+ "then tell me the cheapest day.")
				.call()
				.content();
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("answer", many);
		};
	}

	static String abbreviate(Object o) {
		if (o == null) {
			return "(none)";
		}
		String s = o.toString().replaceAll("\\s+", " ").trim();
		return s.length() > 200 ? s.substring(0, 200) + "..." : s;
	}

}
