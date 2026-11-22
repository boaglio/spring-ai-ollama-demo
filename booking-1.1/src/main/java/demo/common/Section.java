package demo.common;

/** Console output for the demo scenarios. */
public final class Section {

	private Section() {
	}

	public static void title(String text) {
		IO.println(); // Java 25: java.lang.IO, no import needed
		IO.println("=".repeat(78));
		IO.println("  " + text);
		IO.println("=".repeat(78));
	}

	public static void line(String label, Object value) {
		IO.println("  %-10s %s".formatted(label, value));
	}

}
