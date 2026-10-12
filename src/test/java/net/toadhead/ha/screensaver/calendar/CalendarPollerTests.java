package net.toadhead.ha.screensaver.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.toadhead.ha.screensaver.config.ScreensaverProperties.CalendarEntry;
import net.toadhead.ha.screensaver.ha.HaRequestException;
import net.toadhead.ha.screensaver.ha.HomeAssistantConnected;
import net.toadhead.ha.screensaver.ha.HomeAssistantServices;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarPollerTests {

	private static final ZoneId DENVER = ZoneId.of("America/Denver");

	private final JsonMapper mapper = JsonMapper.shared();

	private final HomeAssistantState ha = new HomeAssistantState();

	private final CalendarEvents events = new CalendarEvents();

	private final List<Object> published = new ArrayList<>();

	private final List<JsonNode> calls = new ArrayList<>();

	private final Map<String, CompletableFuture<JsonNode>> replies = new HashMap<>();

	/** 14:00 on October 9 in Denver. */
	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T20:00:00Z"));

	private final HomeAssistantServices services = (domain, service, target, data) -> {
		ObjectNode call = this.mapper.createObjectNode().put("domain", domain).put("service", service);
		call.set("target", target);
		call.set("data", data);
		this.calls.add(call);
		return this.replies.getOrDefault(target.path("entity_id").asString(""),
				CompletableFuture.failedFuture(new HaRequestException("Entity not found")));
	};

	@BeforeEach
	void connect() {
		this.ha.markConnected(List.of());
		this.ha.setTimeZone(DENVER);
	}

	@Test
	void requestsTodayAndTomorrowInHomeAssistantsTimeZone() {
		poller("family").poll();
		assertThat(this.calls).hasSize(1);
		JsonNode call = this.calls.get(0);
		assertThat(call.path("domain").asString("")).isEqualTo("calendar");
		assertThat(call.path("service").asString("")).isEqualTo("get_events");
		assertThat(call.path("target").path("entity_id").asString("")).isEqualTo("calendar.family");
		assertThat(call.path("data").path("start_date_time").asString("")).isEqualTo("2026-10-09T00:00:00-06:00");
		assertThat(call.path("data").path("end_date_time").asString("")).isEqualTo("2026-10-11T00:00:00-06:00");
	}

	@Test
	void usesHomeAssistantsDateNotUtcs() {
		this.clock.set(Instant.parse("2026-10-10T03:00:00Z"));
		poller("family").poll();
		assertThat(this.calls.get(0).path("data").path("start_date_time").asString(""))
			.isEqualTo("2026-10-09T00:00:00-06:00");
	}

	@Test
	void mergesCalendarsAndPublishesUpdates() {
		reply("calendar.family", "Dentist", "09:00");
		reply("calendar.school", "Soccer", "15:00");
		poller("family", "school").poll();
		assertThat(this.events.events()).extracting(CalendarEvent::title).containsExactly("Dentist", "Soccer");
		assertThat(this.events.events()).extracting(CalendarEvent::calendar).containsExactly("family", "school");
		assertThat(this.published).hasSize(2).allMatch(CalendarUpdated.class::isInstance);
	}

	@Test
	void doesNotPublishWhenNothingChanged() {
		reply("calendar.family", "Dentist", "09:00");
		CalendarPoller poller = poller("family");
		poller.poll();
		poller.poll();
		assertThat(this.published).hasSize(1);
	}

	@Test
	void oneFailingCalendarDoesNotBlockTheOthers() {
		reply("calendar.school", "Soccer", "15:00");
		poller("typo", "school").poll();
		assertThat(this.events.events()).extracting(CalendarEvent::title).containsExactly("Soccer");
	}

	@Test
	void keepsLastEventsWhenACalendarFails() {
		reply("calendar.family", "Dentist", "09:00");
		CalendarPoller poller = poller("family");
		poller.poll();
		this.replies.put("calendar.family", CompletableFuture.failedFuture(new HaRequestException("Boom")));
		poller.poll();
		assertThat(this.events.events()).extracting(CalendarEvent::title).containsExactly("Dentist");
	}

	@Test
	void keepsLastEventsWhenTheReplyIsForAnotherEntity() {
		reply("calendar.family", "Dentist", "09:00");
		CalendarPoller poller = poller("family");
		poller.poll();
		// For example HA lowercasing a configured "calendar.Family".
		this.replies.put("calendar.family",
				CompletableFuture.completedFuture(this.mapper.readTree("{\"calendar.other\":{\"events\":[]}}")));
		poller.poll();
		assertThat(this.events.events()).extracting(CalendarEvent::title).containsExactly("Dentist");
		assertThat(this.published).hasSize(1);
	}

	@Test
	void skipsWhileDisconnected() {
		this.ha.markDisconnected();
		poller("family").poll();
		assertThat(this.calls).isEmpty();
	}

	@Test
	void doesNothingWithoutCalendars() {
		poller().poll();
		assertThat(this.calls).isEmpty();
	}

	@Test
	void pollsWhenHomeAssistantConnects() {
		poller("family").onConnected(new HomeAssistantConnected());
		assertThat(this.calls).hasSize(1);
	}

	@Test
	void tickPollsAgainOnlyAfterTheInterval() {
		CalendarPoller poller = poller("family");
		poller.tick();
		this.clock.advance(Duration.ofMinutes(14));
		poller.tick();
		assertThat(this.calls).hasSize(1);
		this.clock.advance(Duration.ofMinutes(1));
		poller.tick();
		assertThat(this.calls).hasSize(2);
	}

	@Test
	void tickPollsJustAfterMidnight() {
		this.clock.set(Instant.parse("2026-10-10T05:55:00Z"));
		CalendarPoller poller = poller("family");
		poller.tick();
		this.clock.set(Instant.parse("2026-10-10T06:01:00Z"));
		poller.tick();
		assertThat(this.calls).hasSize(2);
		assertThat(this.calls.get(1).path("data").path("start_date_time").asString(""))
			.isEqualTo("2026-10-10T00:00:00-06:00");
	}

	@Test
	void tickKeepsTryingUntilConnected() {
		this.ha.markDisconnected();
		CalendarPoller poller = poller("family");
		poller.tick();
		this.ha.markConnected(List.of());
		this.clock.advance(Duration.ofMinutes(1));
		poller.tick();
		assertThat(this.calls).hasSize(1);
	}

	private CalendarPoller poller(String... ids) {
		Map<String, CalendarEntry> calendars = new LinkedHashMap<>();
		for (String id : ids) {
			calendars.put(id, new CalendarEntry("calendar." + id, "Calendar " + id, null));
		}
		return new CalendarPoller(calendars, this.services, this.ha, this.events, this.published::add, this.mapper,
				this.clock);
	}

	private void reply(String entityId, String title, String time) {
		JsonNode response = this.mapper.readTree("{\"" + entityId + "\":{\"events\":[{\"summary\":\"" + title
				+ "\",\"start\":\"2026-10-09T" + time + ":00-06:00\",\"end\":\"2026-10-09T23:00:00-06:00\"}]}}");
		this.replies.put(entityId, CompletableFuture.completedFuture(response));
	}

	static final class MutableClock extends Clock {

		private Instant instant;

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		void set(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
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

}
