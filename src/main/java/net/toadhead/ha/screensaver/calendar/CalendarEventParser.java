package net.toadhead.ha.screensaver.calendar;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Reads events from a {@code calendar.get_events} response, which is keyed by
 * entity id: {@code {"calendar.family": {"events": [{"start", "end", "summary"}]}}}.
 */
final class CalendarEventParser {

	private static final Logger logger = LoggerFactory.getLogger(CalendarEventParser.class);

	static final String NO_TITLE = "(No title)";

	private CalendarEventParser() {
	}

	/**
	 * Reads one calendar's events, skipping (and logging) any whose dates can't be
	 * read.
	 * @param response the service response
	 * @param entityId the calendar entity the response is for
	 * @param calendarId the configured calendar id to tag events with
	 * @return the events, in response order
	 */
	static List<CalendarEvent> parse(JsonNode response, String entityId, String calendarId) {
		List<CalendarEvent> events = new ArrayList<>();
		response.path(entityId).path("events").forEach((node) -> {
			CalendarEvent event = parseEvent(node, calendarId);
			if (event != null) {
				events.add(event);
			}
			else {
				logger.warn("Skipping an event in {} with unreadable dates: {}", entityId, node);
			}
		});
		return events;
	}

	private static CalendarEvent parseEvent(JsonNode node, String calendarId) {
		String start = node.path("start").asString("");
		String end = node.path("end").asString("");
		String summary = node.path("summary").asString("").strip();
		String title = summary.isEmpty() ? NO_TITLE : summary;
		if (isDate(start) && isDate(end)) {
			return new CalendarEvent(title, start, end, true, calendarId);
		}
		if (isDateTime(start) && isDateTime(end)) {
			return new CalendarEvent(title, start, end, false, calendarId);
		}
		return null;
	}

	private static boolean isDate(String value) {
		try {
			LocalDate.parse(value);
			return true;
		}
		catch (DateTimeParseException ex) {
			return false;
		}
	}

	private static boolean isDateTime(String value) {
		try {
			OffsetDateTime.parse(value);
			return true;
		}
		catch (DateTimeParseException ex) {
			return false;
		}
	}

}
