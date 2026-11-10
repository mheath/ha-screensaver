package net.toadhead.ha.screensaver.stream;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

import net.toadhead.ha.screensaver.calendar.CalendarEvents;
import net.toadhead.ha.screensaver.calendar.CalendarUpdated;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.ha.HomeAssistantUpdated;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Holds the current {@link WorldState} and a version that increases each time it
 * actually changes.
 */
@Service
public class WorldStateService {

	private final ScreensaverProperties properties;

	private final HomeAssistantState ha;

	private final CalendarEvents calendar;

	private final Clock clock;

	private volatile Versioned current;

	@Autowired
	public WorldStateService(ScreensaverProperties properties, HomeAssistantState ha, CalendarEvents calendar) {
		this(properties, ha, calendar, Clock.systemUTC());
	}

	WorldStateService(ScreensaverProperties properties, HomeAssistantState ha, CalendarEvents calendar,
			Clock clock) {
		this.properties = properties;
		this.ha = ha;
		this.calendar = calendar;
		this.clock = clock;
		this.current = new Versioned(0,
				WorldStateAssembler.assemble(properties, ha, calendar.events(), clock.instant()));
	}

	public Versioned current() {
		return this.current;
	}

	@EventListener
	public void onHomeAssistantUpdated(HomeAssistantUpdated event) {
		refresh();
	}

	@EventListener
	public void onCalendarUpdated(CalendarUpdated event) {
		refresh();
	}

	/**
	 * Re-derives the state once a minute so a battery that has been unavailable long
	 * enough becomes dead without any new Home Assistant event.
	 */
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	public void tick() {
		refresh();
	}

	synchronized void refresh() {
		WorldState next = WorldStateAssembler.assemble(this.properties, this.ha, this.calendar.events(),
				this.clock.instant());
		if (!next.equals(this.current.state())) {
			this.current = new Versioned(this.current.version() + 1, next);
		}
	}

	public record Versioned(long version, WorldState state) {

	}

}
