package net.toadhead.ha.screensaver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A UTC clock the test moves by hand.
 */
public final class MutableClock extends Clock {

	private Instant instant;

	public MutableClock(Instant instant) {
		this.instant = instant;
	}

	public void set(Instant instant) {
		this.instant = instant;
	}

	public void advance(Duration duration) {
		this.instant = this.instant.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return this.instant;
	}

}
