package demo.common;

/** Text helpers for printing model output on one line. */
public final class Text {

	private static final int MAX_LENGTH = 200;

	private Text() {
	}

	/** Collapses whitespace and cuts the text at 200 characters; "(none)" for null or blank. */
	public static String abbreviate(Object value) {
		if (value == null || value.toString().isBlank()) {
			return "(none)";
		}
		String s = value.toString().replaceAll("\\s+", " ").trim();
		return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) + "..." : s;
	}

}
