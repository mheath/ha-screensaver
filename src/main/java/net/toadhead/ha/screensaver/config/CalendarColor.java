package net.toadhead.ha.screensaver.config;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

import net.toadhead.ha.screensaver.config.ScreensaverProperties.CalendarEntry;

/**
 * Colors for calendar events, in auto-assignment order: the most distinct come
 * first. Each pairs a block color with a text color that stays readable on it.
 * {@code lavender} stands in for indigo, which looked the same as violet to a
 * color-blind viewer.
 */
public enum CalendarColor {

	BLUE("#3b82f6", "#ffffff"), ORANGE("#f97316", "#ffffff"), GREEN("#22c55e", "#ffffff"),
	PINK("#ec4899", "#ffffff"), VIOLET("#8b5cf6", "#ffffff"), YELLOW("#facc15", "#1f1a05"),
	TEAL("#14b8a6", "#ffffff"), RED("#ef4444", "#ffffff"), SKY("#38bdf8", "#06243a"), LIME("#a3e635", "#1a2805"),
	FUCHSIA("#d946ef", "#ffffff"), AMBER("#f59e0b", "#2a1a02"), LAVENDER("#c4b5fd", "#1e1438"),
	EMERALD("#10b981", "#ffffff"), ROSE("#f43f5e", "#ffffff"), CYAN("#22d3ee", "#05282e"),
	PURPLE("#a855f7", "#ffffff"), BROWN("#a16207", "#ffffff"), SLATE("#64748b", "#ffffff"),
	STONE("#a8a29e", "#1c1917");

	private final String background;

	private final String text;

	CalendarColor(String background, String text) {
		this.background = background;
		this.text = text;
	}

	public String background() {
		return this.background;
	}

	public String text() {
		return this.text;
	}

	/**
	 * The name used in configuration.
	 * @return the id, for example {@code lavender}
	 */
	@JsonValue
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Gives every calendar a color: its configured one, or else the first color that
	 * no calendar has yet, taking calendars in configuration order.
	 * @param calendars calendars by id, in configuration order, with distinct
	 * configured colors and at most one calendar per color
	 * @return each calendar id's color, in the same order
	 */
	public static Map<String, CalendarColor> assign(Map<String, CalendarEntry> calendars) {
		Set<CalendarColor> used = EnumSet.noneOf(CalendarColor.class);
		calendars.values().stream().map(CalendarEntry::color).filter(Objects::nonNull).forEach(used::add);
		Map<String, CalendarColor> assigned = new LinkedHashMap<>();
		for (Map.Entry<String, CalendarEntry> entry : calendars.entrySet()) {
			CalendarColor color = entry.getValue().color();
			if (color == null) {
				color = Arrays.stream(values()).filter((candidate) -> !used.contains(candidate)).findFirst().orElseThrow();
				used.add(color);
			}
			assigned.put(entry.getKey(), color);
		}
		return assigned;
	}

}
