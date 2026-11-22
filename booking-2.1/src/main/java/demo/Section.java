package demo;

final class Section {

	private Section() {
	}

	static void title(String text) {
		IO.println(); // Java 25: java.lang.IO, no import needed
		IO.println("=".repeat(78));
		IO.println("  " + text);
		IO.println("=".repeat(78));
	}

	static void line(String label, Object value) {
		IO.println("  %-10s %s".formatted(label, value));
	}

}
