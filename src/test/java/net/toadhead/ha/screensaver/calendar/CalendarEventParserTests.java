package net.toadhead.ha.screensaver.calendar;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventParserTests {

	@Test
	void readsTimedEvents() {
		List<CalendarEvent> events = parse("""
				[{"start":"2026-10-09T15:00:00-06:00","end":"2026-10-09T17:00:00-06:00",
				  "summary":"Soccer practice","description":"Bring water","location":"Park"}]""");
		assertThat(events).containsExactly(new CalendarEvent("Soccer practice", "2026-10-09T15:00:00-06:00",
				"2026-10-09T17:00:00-06:00", false, "school"));
	}

	@Test
	void readsAllDayEvents() {
		List<CalendarEvent> events = parse("""
				[{"start":"2026-10-09","end":"2026-10-10","summary":"Grandma's birthday"}]""");
		assertThat(events)
			.containsExactly(new CalendarEvent("Grandma's birthday", "2026-10-09", "2026-10-10", true, "school"));
	}

	@Test
	void namesUntitledEvents() {
		List<CalendarEvent> events = parse("""
				[{"start":"2026-10-09","end":"2026-10-10"},
				 {"start":"2026-10-09","end":"2026-10-10","summary":"   "}]""");
		assertThat(events).extracting(CalendarEvent::title).containsExactly("(No title)", "(No title)");
	}

	@Test
	void trimsTitles() {
		assertThat(parse("""
				[{"start":"2026-10-09","end":"2026-10-10","summary":"  Trash day  "}]""").get(0).title())
			.isEqualTo("Trash day");
	}

	@Test
	void skipsEventsWithUnreadableOrMixedDates() {
		List<CalendarEvent> events = parse("""
				[{"start":"tomorrow-ish","end":"2026-10-10","summary":"Bad"},
				 {"start":"2026-10-09","end":"2026-10-09T17:00:00-06:00","summary":"Mixed"},
				 {"summary":"Missing"},
				 {"start":"2026-10-09","end":"2026-10-10","summary":"Good"}]""");
		assertThat(events).extracting(CalendarEvent::title).containsExactly("Good");
	}

	@Test
	void returnsNothingWhenTheEntityIsMissingFromTheResponse() {
		JsonNode response = JsonMapper.shared().readTree("{\"calendar.other\":{\"events\":[]}}");
		assertThat(CalendarEventParser.parse(response, "calendar.school", "school")).isEmpty();
	}

	private static List<CalendarEvent> parse(String eventsJson) {
		JsonNode response = JsonMapper.shared().readTree("{\"calendar.school\":{\"events\":" + eventsJson + "}}");
		return CalendarEventParser.parse(response, "calendar.school", "school");
	}

}
