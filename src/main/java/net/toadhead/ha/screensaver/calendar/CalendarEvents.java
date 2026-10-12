package net.toadhead.ha.screensaver.calendar;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The latest events of every calendar, merged into one list. A calendar that fails
 * to update keeps its previous events.
 */
@Component
public class CalendarEvents {

	/**
	 * ISO strings from one time zone sort chronologically, and a day's all-day date
	 * sorts before its times. The browser lays out each day itself; this order just
	 * keeps the world state stable.
	 */
	private static final Comparator<CalendarEvent> ORDER = Comparator.comparing(CalendarEvent::start)
		.thenComparing(CalendarEvent::title)
		.thenComparing(CalendarEvent::calendar);

	private final Map<String, List<CalendarEvent>> byCalendar = new ConcurrentHashMap<>();

	private volatile List<CalendarEvent> merged = List.of();

	public List<CalendarEvent> events() {
		return this.merged;
	}

	/**
	 * Replaces one calendar's events.
	 * @param calendarId the configured calendar id
	 * @param events its current events
	 * @return whether the merged list changed
	 */
	public synchronized boolean replace(String calendarId, List<CalendarEvent> events) {
		this.byCalendar.put(calendarId, List.copyOf(events));
		List<CalendarEvent> next = this.byCalendar.values().stream().flatMap(List::stream).sorted(ORDER).toList();
		boolean changed = !next.equals(this.merged);
		this.merged = next;
		return changed;
	}

}
