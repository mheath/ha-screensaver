package net.toadhead.ha.screensaver.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import net.toadhead.ha.screensaver.config.ScreensaverProperties.Clock;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.HomeAssistant;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Rotation;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snake;
import net.toadhead.ha.screensaver.config.ScreensaverProperties.Snakes;

/**
 * Builds {@link ScreensaverProperties} for unit tests without Spring.
 */
public final class TestProperties {

	private TestProperties() {
	}

	public static HomeAssistant homeAssistant() {
		return new HomeAssistant(URI.create("ws://localhost:1/api/websocket"), "test-token", false,
				Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(10));
	}

	public static Snake snake(String entity, String label) {
		return new Snake(entity, label, 0.0, 100.0, null, null, 1);
	}

	public static ScreensaverProperties withSnakes(Map<String, Snake> pinned, Map<String, Snake> guests) {
		return new ScreensaverProperties(homeAssistant(), new Clock(false),
				new Snakes(0, 4, 30, 2, 20, pinned, guests), new Rotation(Map.of()), Map.of());
	}

}
