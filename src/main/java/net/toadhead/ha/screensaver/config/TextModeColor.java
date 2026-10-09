package net.toadhead.ha.screensaver.config;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The 16-color text-mode palette. Configured as kebab-case names such as
 * {@code light-green}.
 */
public enum TextModeColor {

	BLACK, BLUE, GREEN, CYAN, RED, MAGENTA, BROWN, LIGHT_GRAY, DARK_GRAY, LIGHT_BLUE, LIGHT_GREEN, LIGHT_CYAN,
	LIGHT_RED, LIGHT_MAGENTA, YELLOW, WHITE;

	/**
	 * The kebab-case name used in configuration and sent to the browser.
	 * @return the id, for example {@code light-green}
	 */
	@JsonValue
	public String id() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
