package net.toadhead.ha.screensaver.stream;

import net.toadhead.ha.screensaver.calendar.CalendarEvents;
import net.toadhead.ha.screensaver.calendar.CalendarUpdated;
import net.toadhead.ha.screensaver.config.ScreensaverProperties;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.ha.HomeAssistantUpdated;

import org.springframework.context.event.EventListener;
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

	private volatile Versioned current;

	public WorldStateService(ScreensaverProperties properties, HomeAssistantState ha, CalendarEvents calendar) {
		this.properties = properties;
		this.ha = ha;
		this.calendar = calendar;
		this.current = new Versioned(0, WorldStateAssembler.assemble(properties, ha, calendar.events()));
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

	synchronized void refresh() {
		WorldState next = WorldStateAssembler.assemble(this.properties, this.ha, this.calendar.events());
		if (!next.equals(this.current.state())) {
			this.current = new Versioned(this.current.version() + 1, next);
		}
	}

	public record Versioned(long version, WorldState state) {

	}

}
