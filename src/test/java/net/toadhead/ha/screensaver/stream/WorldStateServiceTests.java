package net.toadhead.ha.screensaver.stream;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.toadhead.ha.screensaver.MutableClock;
import net.toadhead.ha.screensaver.batteries.LowBattery;
import net.toadhead.ha.screensaver.calendar.CalendarEvent;
import net.toadhead.ha.screensaver.calendar.CalendarEvents;
import net.toadhead.ha.screensaver.calendar.CalendarUpdated;
import net.toadhead.ha.screensaver.config.TestProperties;
import net.toadhead.ha.screensaver.ha.EntityState;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.ha.HomeAssistantUpdated;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WorldStateServiceTests {

	private final HomeAssistantState ha = new HomeAssistantState();

	private final CalendarEvents calendar = new CalendarEvents();

	private final WorldStateService service = new WorldStateService(
			TestProperties.withSnakes(Map.of("power", TestProperties.snake("sensor.power", "Power")), Map.of()),
			this.ha, this.calendar);

	@Test
	void startsAtVersionZero() {
		assertThat(this.service.current().version()).isZero();
		assertThat(this.service.current().state().haConnected()).isFalse();
	}

	@Test
	void bumpsVersionWhenStateChanges() {
		this.ha.markConnected(List.of(WorldStateAssemblerTests.entity("sensor.power", "1", "{}")));
		this.service.onHomeAssistantUpdated(new HomeAssistantUpdated());
		assertThat(this.service.current().version()).isEqualTo(1);
		assertThat(this.service.current().state().snakes().get(0).value()).isEqualTo(1.0);
	}

	@Test
	void keepsVersionWhenNothingRelevantChanged() {
		this.ha.markConnected(List.of(WorldStateAssemblerTests.entity("sensor.power", "1", "{}")));
		this.service.onHomeAssistantUpdated(new HomeAssistantUpdated());
		this.ha.put(WorldStateAssemblerTests.entity("light.kitchen", "on", "{}"));
		this.service.onHomeAssistantUpdated(new HomeAssistantUpdated());
		assertThat(this.service.current().version()).isEqualTo(1);
	}

	@Test
	void bumpsVersionWhenCalendarChanges() {
		this.calendar.replace("family", List.of(new CalendarEvent("Birthday", "2026-10-09", "2026-10-10", true, "family")));
		this.service.onCalendarUpdated(new CalendarUpdated());
		assertThat(this.service.current().version()).isEqualTo(1);
		assertThat(this.service.current().state().calendar().events()).extracting(CalendarEvent::title)
			.containsExactly("Birthday");
	}

	@Test
	void tickPicksUpBatteriesThatBecomeDead() {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-10T20:00:00Z"));
		WorldStateService service = new WorldStateService(TestProperties.withSnakes(Map.of(), Map.of()), this.ha,
				this.calendar, clock);
		this.ha.markConnected(List.of(new EntityState("sensor.lock_battery", "unavailable",
				JsonMapper.shared().readTree("{\"device_class\":\"battery\",\"unit_of_measurement\":\"%\"}"),
				clock.instant())));
		service.onHomeAssistantUpdated(new HomeAssistantUpdated());
		assertThat(service.current().state().lowBatteries()).isEmpty();
		clock.advance(Duration.ofMinutes(59));
		service.tick();
		assertThat(service.current().state().lowBatteries()).isEmpty();
		clock.advance(Duration.ofMinutes(1));
		service.tick();
		assertThat(service.current().state().lowBatteries()).extracting(LowBattery::status)
			.containsExactly(LowBattery.Status.DEAD);
	}

}
