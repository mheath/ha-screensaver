package net.toadhead.ha.screensaver.kiosk;

import java.util.List;

import net.toadhead.ha.screensaver.config.SceneType;
import net.toadhead.ha.screensaver.config.TextModeColor;

/**
 * Everything one kiosk's browser needs to run its rotation. Sent as the stream's
 * {@code config} event; never contains secrets.
 *
 * @param kiosk the kiosk id
 * @param scenes enabled scenes with their resolved weight and duration
 * @param clock clock scene options
 * @param snakes snakes scene options
 */
public record KioskConfig(String kiosk, List<SceneConfig> scenes, ClockOptions clock, SnakeOptions snakes) {

	public record SceneConfig(SceneType type, double weight, long durationMs) {

	}

	public record ClockOptions(boolean use24Hour) {

	}

	public record SnakeOptions(int guestCount, int minLength, int maxLength, double minSpeed, double maxSpeed,
			List<SnakeDef> pinned, List<SnakeDef> guests) {

	}

	public record SnakeDef(String id, String label, TextModeColor color, String unit, int precision, double min,
			double max) {

	}

}
