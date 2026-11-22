package demo;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import demo.domain.Booking;
import demo.domain.Flight;

/** Fake flight backend. Every call prints a line, so you can see when a tool really runs. */
public class BookingTools {

	public static final AtomicInteger CALLS = new AtomicInteger();

	@Tool(description = "Search available flights to a destination city on a given date")
	public List<Flight> searchFlights(@ToolParam(description = "Destination city") String destination,
			@ToolParam(description = "Date in YYYY-MM-DD format") String date) {
		log("searchFlights(" + destination + ", " + date + ")");
		return List.of(new Flight("TP1234", "TAP", date + " 07:15", 189),
				new Flight("FR8812", "Ryanair", date + " 11:40", 74),
				new Flight("IB3170", "Iberia", date + " 18:05", 132));
	}

	@Tool(description = "Book a flight by its flight number and return the booking")
	public Booking bookFlight(@ToolParam(description = "Flight number, e.g. TP1234") String flightNumber) {
		log("bookFlight(" + flightNumber + ")");
		int price = switch (flightNumber) {
			case "TP1234" -> 189;
			case "FR8812" -> 74;
			case "IB3170" -> 132;
			default -> 0;
		};
		return new Booking("BK-" + flightNumber + "-001", flightNumber, price);
	}

	@Tool(description = "Cancel an existing booking by its booking id", requiresConfirmation = true) // 2.1: a human must approve
	public String cancelBooking(@ToolParam(description = "Booking id, e.g. BK-FR8812-001") String bookingId) {
		log("cancelBooking(" + bookingId + ")");
		return "Booking " + bookingId + " cancelled";
	}

	private static void log(String call) {
		CALLS.incrementAndGet();
		IO.println("      🔧 TOOL EXECUTED: " + call);
	}

}
