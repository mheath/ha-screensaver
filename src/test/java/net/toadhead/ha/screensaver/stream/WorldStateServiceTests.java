package net.toadhead.ha.screensaver.stream;

import java.util.List;
import java.util.Map;

import net.toadhead.ha.screensaver.config.TestProperties;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.ha.HomeAssistantUpdated;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldStateServiceTests {

	private final HomeAssistantState ha = new HomeAssistantState();

	private final WorldStateService service = new WorldStateService(
			TestProperties.withSnakes(Map.of("power", TestProperties.snake("sensor.power", "Power")), Map.of()),
			this.ha);

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

}
