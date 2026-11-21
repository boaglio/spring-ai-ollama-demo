package demo;

final class Section {

	private Section() {
	}

	static void title(String text) {
		System.out.println();
		System.out.println("=".repeat(78));
		System.out.println("  " + text);
		System.out.println("=".repeat(78));
	}

	static void line(String label, Object value) {
		System.out.printf("  %-10s %s%n", label, value);
	}

}
