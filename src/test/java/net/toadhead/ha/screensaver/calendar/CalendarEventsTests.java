package net.toadhead.ha.screensaver.calendar;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventsTests {

	private final CalendarEvents events = new CalendarEvents();

	@Test
	void startsEmpty() {
		assertThat(this.events.events()).isEmpty();
	}

	@Test
	void mergesCalendarsInStartOrderWithAllDayEventsFirst() {
		this.events.replace("school", List.of(timed("Soccer", "15:00", "school"), timed("Piano", "15:30", "school")));
		this.events.replace("family", List.of(timed("Dentist", "09:00", "family"),
				new CalendarEvent("Birthday", "2026-10-09", "2026-10-10", true, "family")));
		assertThat(this.events.events()).extracting(CalendarEvent::title)
			.containsExactly("Birthday", "Dentist", "Soccer", "Piano");
	}

	@Test
	void replacingACalendarDropsItsOldEvents() {
		this.events.replace("family", List.of(timed("Dentist", "09:00", "family")));
		this.events.replace("family", List.of(timed("Lunch", "12:00", "family")));
		assertThat(this.events.events()).extracting(CalendarEvent::title).containsExactly("Lunch");
	}

	@Test
	void reportsWhetherTheMergedListChanged() {
		assertThat(this.events.replace("family", List.of(timed("Dentist", "09:00", "family")))).isTrue();
		assertThat(this.events.replace("family", List.of(timed("Dentist", "09:00", "family")))).isFalse();
		assertThat(this.events.replace("school", List.of())).isFalse();
	}

	private static CalendarEvent timed(String title, String time, String calendar) {
		return new CalendarEvent(title, "2026-10-09T" + time + ":00-06:00", "2026-10-09T23:00:00-06:00", false,
				calendar);
	}

}
