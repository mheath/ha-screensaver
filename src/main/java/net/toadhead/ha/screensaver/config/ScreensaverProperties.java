package net.toadhead.ha.screensaver.config;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * All screensaver configuration, bound once at startup from the {@code screensaver}
 * prefix.
 *
 * @param homeAssistant connection to Home Assistant
 * @param clock clock scene options
 * @param snakes snakes scene options
 * @param rotation default weight, duration and enablement for each scene type
 * @param kiosks per-kiosk overrides keyed by the {@code ?kiosk=} id
 */
@ConfigurationProperties("screensaver")
@Validated
public record ScreensaverProperties(@Valid @NotNull HomeAssistant homeAssistant, @Valid @DefaultValue Clock clock,
		@Valid @DefaultValue Snakes snakes, @Valid @DefaultValue Rotation rotation, Map<String, @Valid Kiosk> kiosks) {

	/** Every palette color except black can be a snake. */
	static final int MAX_SNAKES = 15;

	public ScreensaverProperties {
		kiosks = (kiosks != null) ? kiosks : Map.of();
	}

	public record HomeAssistant(@NotNull URI url, @NotBlank String token, @DefaultValue("true") boolean enabled,
			@DefaultValue("1s") Duration initialBackoff, @DefaultValue("60s") Duration maxBackoff) {

	}

	public record Clock(@DefaultValue("false") boolean use24Hour) {

	}

	public record Snakes(@DefaultValue("0") @Min(0) int guestCount, @DefaultValue("4") @Min(1) int minLength,
			@DefaultValue("30") @Min(1) int maxLength, @DefaultValue("2") @Positive double minSpeed,
			@DefaultValue("20") @Positive double maxSpeed, Map<String, @Valid Snake> pinned,
			Map<String, @Valid Snake> guests) {

		public Snakes {
			// Keep the bound map as-is: it preserves configuration order for the legend.
			pinned = (pinned != null) ? pinned : Map.of();
			guests = (guests != null) ? guests : Map.of();
		}

		@AssertTrue(message = "min-length must not exceed max-length")
		public boolean isLengthRangeValid() {
			return this.minLength <= this.maxLength;
		}

		@AssertTrue(message = "min-speed must not exceed max-speed")
		public boolean isSpeedRangeValid() {
			return this.minSpeed <= this.maxSpeed;
		}

		@AssertTrue(message = "pinned snakes must have distinct colors")
		public boolean isPinnedColorsDistinct() {
			List<TextModeColor> colors = this.pinned.values().stream().map(Snake::color).filter(Objects::nonNull).toList();
			return colors.size() == new HashSet<>(colors).size();
		}

		@AssertTrue(message = "pinned snakes plus guest-count must not exceed 15 (one per palette color)")
		public boolean isWithinPalette() {
			return this.pinned.size() + this.guestCount <= MAX_SNAKES;
		}

		@AssertTrue(message = "guest-count must not exceed the number of guest snakes")
		public boolean isGuestCountWithinPool() {
			return this.guestCount <= this.guests.size();
		}

		@AssertTrue(message = "snake ids must be unique across pinned and guests")
		public boolean isIdsUnique() {
			Set<String> ids = new HashSet<>(this.pinned.keySet());
			ids.retainAll(this.guests.keySet());
			return ids.isEmpty();
		}

	}

	/**
	 * One snake: a numeric entity mapped onto the snake's length and speed.
	 *
	 * @param entity the Home Assistant entity id
	 * @param label legend label
	 * @param min value at which the snake is shortest and slowest
	 * @param max value at which the snake is longest and fastest
	 * @param color palette color, or {@code null} to assign one automatically
	 * @param unit legend unit, or {@code null} to use the entity's unit_of_measurement
	 * @param precision maximum decimals shown in the legend
	 */
	public record Snake(@NotBlank String entity, @NotBlank String label, @NotNull Double min, @NotNull Double max,
			TextModeColor color, String unit, @DefaultValue("1") @Min(0) @Max(4) int precision) {

		@AssertTrue(message = "min must be less than max")
		public boolean isRangeValid() {
			return this.min == null || this.max == null || this.min < this.max;
		}

		@AssertTrue(message = "black is the background color and cannot be used for a snake")
		public boolean isColorVisible() {
			return this.color != TextModeColor.BLACK;
		}

	}

	public record Rotation(Map<SceneType, @Valid SceneSettings> scenes) {

		public Rotation {
			scenes = (scenes != null) ? scenes : Map.of();
		}

	}

	/**
	 * Scene settings. Every field is optional so the same shape works for defaults
	 * and per-kiosk overrides.
	 *
	 * @param enabled whether the scene is in the rotation
	 * @param weight relative chance of being picked
	 * @param duration how long the scene stays on screen
	 */
	public record SceneSettings(Boolean enabled, @Positive Double weight, Duration duration) {

		@AssertTrue(message = "duration must be positive")
		public boolean isDurationPositive() {
			return this.duration == null || (!this.duration.isNegative() && !this.duration.isZero());
		}

	}

	public record Kiosk(Map<SceneType, @Valid SceneSettings> scenes) {

		public Kiosk {
			scenes = (scenes != null) ? scenes : Map.of();
		}

	}

}
