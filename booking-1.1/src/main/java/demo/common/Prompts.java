package demo.common;

/**
 * Prompts shared by the 1.1 and 2.1 apps, so both send the model exactly the same text.
 * The instructions are in English (small models follow them more reliably, and the tool
 * descriptions are English too); the user prompts and the answers are in Brazilian Portuguese.
 */
public final class Prompts {

	public static final String SYSTEM = """
			You are a flight booking assistant. Flights depart from São Paulo (GRU). Today is 2026-11-21.
			Always call the matching tool (searchFlights, bookFlight, cancelBooking) before answering;
			never invent booking codes. Prices are in Brazilian reais (R$).
			Always reply in Brazilian Portuguese, briefly.""";

	private Prompts() {
	}

}
