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

import demo.common.Section;
import demo.domain.Booking;

import static demo.common.Prompts.SYSTEM;
import static demo.common.Text.abbreviate;

/** Spring AI 1.1.x on Spring Boot 3.5: the "before" picture. */
@SpringBootApplication
public class BookingApp {

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
			ChatResponse direct = chatModel.call(new Prompt("Quais voos vão para Recife em 04/12/2026?", options));
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("toolCalls", direct.getResult().getOutput().getToolCalls().size() + " pending (already executed)");
			Section.line("answer", direct.getResult().getOutput().getText());

			// 2) ChatClient + memory + thinking
			Section.title("2) ChatClient + memory + thinking  [1.1: thinking hidden in metadata]");
			ChatResponse r = chatClient.prompt()
				.user("Busque voos para Recife no dia 04/12/2026")
				.options(OllamaChatOptions.builder().enableThinking().build()) // 1.1 builder method
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "demo"))
				.call()
				.chatResponse();
			AssistantMessage out = r.getResult().getOutput();
			// 1.1: Ollama thinking is a provider-specific key in the *generation* metadata
			Section.line("thinking", abbreviate(r.getResult().getMetadata().get("thinking")));
			Section.line("answer", out.getText());

			// 3) Book first (plain text, so the tool can run), then ask for the result as a record.
			// Asking for entity(...) in the same turn made the model answer with JSON straight away,
			// without calling bookFlight: the "booking" it returned was invented.
			Section.title("3) Book the cheapest (memory) -> Booking record");
			BookingTools.CALLS.set(0);
			String booked = chatClient.prompt()
				.user("Reserve o voo mais barato")
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "demo"))
				.call()
				.content();
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("answer", abbreviate(booked));
			Booking booking = null;
			try {
				booking = chatClient.prompt()
					.user("Quais são os dados da minha reserva?")
					.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "demo"))
					.call()
					.entity(Booking.class);
			}
			catch (RuntimeException ex) { // 1.1: Jackson 2 maps a missing int to 0, so no exception here
				Section.line("ERROR", ex.getClass().getSimpleName() + ": " + abbreviate(ex.getMessage()));
			}
			Section.line("booking", booking);
			if (booking == null || booking.bookingId() == null) {
				Section.line("warning", "no booking id in the answer");
			}

			// 4) Dangerous tool: runs with no confirmation
			Section.title("4) Cancel the booking  [1.1: no human-in-the-loop, it just runs]");
			BookingTools.CALLS.set(0);
			String cancel = chatClient.prompt()
				.user("Cancele essa reserva")
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "demo"))
				.call()
				.content();
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("answer", cancel);

			// 5) Runaway tool calling: no built-in limit in 1.1
			Section.title("5) Many tool calls  [1.1: no built-in tool call limit]");
			BookingTools.CALLS.set(0);
			String many = chatClient.prompt()
				.user("Busque voos para Recife para CADA dia de 01/12/2026 a 07/12/2026, uma busca por dia, "
						+ "e depois me diga qual é o dia mais barato.")
				.call()
				.content();
			Section.line("tools run", BookingTools.CALLS.get());
			Section.line("answer", many);
		};
	}

}
