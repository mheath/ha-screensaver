package net.toadhead.ha.screensaver.calendar;

/**
 * One calendar event, as the browser receives it.
 *
 * @param title the event's summary, or {@code (No title)}
 * @param start a date ({@code 2026-10-09}) for all-day events, otherwise an ISO
 * date-time with offset
 * @param end like {@code start}, and exclusive: a one-day all-day event ends the
 * next day
 * @param allDay whether the event is all-day
 * @param calendar the configured calendar id
 */
public record CalendarEvent(String title, String start, String end, boolean allDay, String calendar) {

}
