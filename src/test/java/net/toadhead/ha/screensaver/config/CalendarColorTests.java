package net.toadhead.ha.screensaver.config;

import java.util.LinkedHashMap;
import java.util.Map;

import net.toadhead.ha.screensaver.config.ScreensaverProperties.CalendarEntry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class CalendarColorTests {

	@Test
	void keepsConfiguredColorsAndFillsTheRestInOrder() {
		Map<String, CalendarEntry> calendars = new LinkedHashMap<>();
		calendars.put("family", calendar(null));
		calendars.put("school", calendar(CalendarColor.BLUE));
		calendars.put("work", calendar(null));
		assertThat(CalendarColor.assign(calendars)).containsExactly(entry("family", CalendarColor.ORANGE),
				entry("school", CalendarColor.BLUE), entry("work", CalendarColor.GREEN));
	}

	@Test
	void twentyCalendarsUseEveryColorOnce() {
		Map<String, CalendarEntry> calendars = new LinkedHashMap<>();
		for (int i = 0; i < 20; i++) {
			calendars.put("c" + i, calendar(null));
		}
		assertThat(CalendarColor.assign(calendars).values()).containsExactly(CalendarColor.values());
	}

	@Test
	void idsAreLowerCaseNames() {
		assertThat(CalendarColor.LAVENDER.id()).isEqualTo("lavender");
	}

	@Test
	void lightColorsGetDarkText() {
		assertThat(CalendarColor.YELLOW.background()).isEqualTo("#facc15");
		assertThat(CalendarColor.YELLOW.text()).isEqualTo("#1f1a05");
		assertThat(CalendarColor.BLUE.text()).isEqualTo("#ffffff");
	}

	private static CalendarEntry calendar(CalendarColor color) {
		return new CalendarEntry("calendar.x", "X", color);
	}

}
