package net.toadhead.ha.screensaver.config;

import java.time.Duration;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The scene types the browser knows how to show, with the weight and duration used
 * when configuration doesn't set them.
 */
public enum SceneType {

	CLOCK(2, Duration.ofSeconds(20)),

	SNAKES(2, Duration.ofSeconds(45));

	private final double defaultWeight;

	private final Duration defaultDuration;

	SceneType(double defaultWeight, Duration defaultDuration) {
		this.defaultWeight = defaultWeight;
		this.defaultDuration = defaultDuration;
	}

	public double defaultWeight() {
		return this.defaultWeight;
	}

	public Duration defaultDuration() {
		return this.defaultDuration;
	}

	@JsonValue
	public String id() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
