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
	public List<Flight> searchFlights(@ToolParam(description = "Destination city in Brazil") String destination,
			@ToolParam(description = "Date in YYYY-MM-DD format (convert dates like 04/12/2026 to 2026-12-04)") String date) {
		log("searchFlights(" + destination + ", " + date + ")");
		return List.of(new Flight("LA3350", "LATAM", date + " 07:15", 689),
				new Flight("G31842", "GOL", date + " 11:40", 412),
				new Flight("AD4107", "Azul", date + " 18:05", 538));
	}

	@Tool(description = "Book a flight by its flight number and return the booking")
	public Booking bookFlight(@ToolParam(description = "Flight number, e.g. LA3350") String flightNumber) {
		log("bookFlight(" + flightNumber + ")");
		int price = switch (flightNumber) {
			case "LA3350" -> 689;
			case "G31842" -> 412;
			case "AD4107" -> 538;
			default -> 0;
		};
		return new Booking("BK-" + flightNumber + "-001", flightNumber, price);
	}

	@Tool(description = "Cancel an existing booking by its booking id", requiresConfirmation = true) // 2.1: a human must approve
	public String cancelBooking(@ToolParam(description = "The booking id returned by bookFlight") String bookingId) {
		log("cancelBooking(" + bookingId + ")");
		return "Booking " + bookingId + " cancelled";
	}

	private static void log(String call) {
		CALLS.incrementAndGet();
		IO.println("      🔧 TOOL EXECUTED: " + call);
	}

}
