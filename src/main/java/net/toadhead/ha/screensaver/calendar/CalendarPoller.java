package net.toadhead.ha.screensaver.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.CalendarEntry;
import net.toadhead.ha.screensaver.ha.HomeAssistantConnected;
import net.toadhead.ha.screensaver.ha.HomeAssistantServices;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fetches today's and tomorrow's events from every configured calendar: when Home
 * Assistant connects, every 15 minutes, and just after midnight in Home
 * Assistant's time zone.
 */
@Component
@ConditionalOnBooleanProperty(name = "screensaver.home-assistant.enabled", matchIfMissing = true)
public class CalendarPoller {

	static final Duration INTERVAL = Duration.ofMinutes(15);

	private static final Logger logger = LoggerFactory.getLogger(CalendarPoller.class);

	private final Map<String, CalendarEntry> calendars;

	private final HomeAssistantServices services;

	private final HomeAssistantState ha;

	private final CalendarEvents events;

	private final ApplicationEventPublisher publisher;

	private final JsonMapper mapper;

	private final Clock clock;

	private Instant lastPoll;

	private LocalDate lastPollDate;

	@Autowired
	public CalendarPoller(ScreensaverProperties properties, HomeAssistantServices services, HomeAssistantState ha,
			CalendarEvents events, ApplicationEventPublisher publisher, JsonMapper mapper) {
		this(properties.calendar().calendars(), services, ha, events, publisher, mapper, Clock.systemUTC());
	}

	CalendarPoller(Map<String, CalendarEntry> calendars, HomeAssistantServices services, HomeAssistantState ha,
			CalendarEvents events, ApplicationEventPublisher publisher, JsonMapper mapper, Clock clock) {
		this.calendars = calendars;
		this.services = services;
		this.ha = ha;
		this.events = events;
		this.publisher = publisher;
		this.mapper = mapper;
		this.clock = clock;
	}

	@EventListener
	public void onConnected(HomeAssistantConnected event) {
		poll();
	}

	/**
	 * Polls once the interval has passed or the day has changed since the last poll.
	 */
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	public synchronized void tick() {
		Instant now = this.clock.instant();
		LocalDate today = LocalDate.ofInstant(now, this.ha.timeZone());
		if (this.lastPoll == null || !today.equals(this.lastPollDate) || !now.isBefore(this.lastPoll.plus(INTERVAL))) {
			poll();
		}
	}

	synchronized void poll() {
		if (this.calendars.isEmpty() || !this.ha.connected()) {
			return;
		}
		ZoneId zone = this.ha.timeZone();
		Instant now = this.clock.instant();
		LocalDate today = LocalDate.ofInstant(now, zone);
		this.lastPoll = now;
		this.lastPollDate = today;
		ObjectNode data = this.mapper.createObjectNode()
			.put("start_date_time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(today.atStartOfDay(zone)))
			.put("end_date_time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(today.plusDays(2).atStartOfDay(zone)));
		this.calendars.forEach((id, calendar) -> fetch(id, calendar, data));
	}

	private void fetch(String id, CalendarEntry calendar, ObjectNode data) {
		ObjectNode target = this.mapper.createObjectNode().put("entity_id", calendar.entity());
		this.services.callService("calendar", "get_events", target, data).whenComplete((response, failure) -> {
			if (failure != null) {
				logger.warn("Could not read calendar {} ({}), keeping its last events: {}", calendar.label(),
						calendar.entity(), failure.getMessage());
				return;
			}
			if (!response.has(calendar.entity())) {
				// Home Assistant keys the reply by its own entity id, which differs from ours
				// when, for example, the configured id isn't all lower case.
				logger.warn("Home Assistant's reply for calendar {} has no entry for {}, keeping its last events; "
						+ "check the entity id", calendar.label(), calendar.entity());
				return;
			}
			if (this.events.replace(id, CalendarEventParser.parse(response, calendar.entity(), id))) {
				this.publisher.publishEvent(new CalendarUpdated());
			}
		});
	}

}
