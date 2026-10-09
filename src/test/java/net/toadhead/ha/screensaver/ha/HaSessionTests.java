package net.toadhead.ha.screensaver.ha;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class HaSessionTests {

	private final JsonMapper mapper = JsonMapper.shared();

	private final List<String> sent = new ArrayList<>();

	private final RecordingListener listener = new RecordingListener();

	private final HaSession session = new HaSession("secret", this.mapper, this.sent::add, this.listener);

	@Test
	void answersAuthRequiredWithToken() {
		this.session.onMessage("{\"type\":\"auth_required\",\"ha_version\":\"2026.10.0\"}");
		assertThat(sent(0).path("type").asString("")).isEqualTo("auth");
		assertThat(sent(0).path("access_token").asString("")).isEqualTo("secret");
	}

	@Test
	void subscribesThenLoadsStatesAfterAuthOk() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		assertThat(this.listener.authenticated).isTrue();
		assertThat(sent(0).path("type").asString("")).isEqualTo("subscribe_events");
		assertThat(sent(0).path("event_type").asString("")).isEqualTo("state_changed");
		assertThat(sent(0).path("id").asInt(-1)).isEqualTo(1);
		assertThat(sent(1).path("type").asString("")).isEqualTo("get_states");
		assertThat(sent(1).path("id").asInt(-1)).isEqualTo(2);
	}

	@Test
	void parsesStatesFromGetStatesResult() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":2,"type":"result","success":true,"result":[
				  {"entity_id":"sensor.power","state":"42.5",
				   "attributes":{"unit_of_measurement":"W","friendly_name":"Power"},
				   "last_changed":"2026-10-08T17:05:00.123456+00:00","last_updated":"2026-10-08T17:05:00.123456+00:00"}
				]}""");
		assertThat(this.listener.states).hasSize(1);
		EntityState power = this.listener.states.get(0);
		assertThat(power.entityId()).isEqualTo("sensor.power");
		assertThat(power.state()).isEqualTo("42.5");
		assertThat(power.attribute("unit_of_measurement")).contains("W");
		assertThat(power.attribute("missing")).isEmpty();
		assertThat(power.lastChanged()).isEqualTo(Instant.parse("2026-10-08T17:05:00.123456Z"));
	}

	@Test
	void reportsStateChangesFromTheSubscription() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":1,"type":"event","event":{"event_type":"state_changed","data":{
				  "entity_id":"sensor.power",
				  "new_state":{"entity_id":"sensor.power","state":"7","attributes":{},
				               "last_changed":"2026-10-08T17:06:00+00:00"}}}}""");
		assertThat(this.listener.changedIds).containsExactly("sensor.power");
		assertThat(this.listener.changedStates.get(0).state()).isEqualTo("7");
	}

	@Test
	void reportsRemovalWhenNewStateIsNull() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":1,"type":"event","event":{"event_type":"state_changed","data":{
				  "entity_id":"sensor.gone","new_state":null}}}""");
		assertThat(this.listener.changedIds).containsExactly("sensor.gone");
		assertThat(this.listener.changedStates).containsExactly((EntityState) null);
	}

	@Test
	void ignoresEventsForOtherSubscriptions() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":99,"type":"event","event":{"data":{"entity_id":"sensor.x","new_state":null}}}""");
		assertThat(this.listener.changedIds).isEmpty();
	}

	@Test
	void reportsFailedSubscription() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":1,"type":"result","success":false,"error":{"code":"unauthorized","message":"Unauthorized"}}""");
		assertThat(this.listener.setupFailures).hasSize(1);
	}

	@Test
	void reportsFailedStateLoad() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":2,"type":"result","success":false,"error":{"code":"home_assistant_error","message":"Boom"}}""");
		assertThat(this.listener.setupFailures).hasSize(1);
		assertThat(this.listener.states).isEmpty();
	}

	@Test
	void reportsStatesThatCannotBeParsed() {
		this.session.onMessage("{\"type\":\"auth_ok\"}");
		this.session.onMessage("""
				{"id":2,"type":"result","success":true,"result":[
				  {"entity_id":"sensor.power","state":"1","attributes":{},"last_changed":"not a date"}]}""");
		assertThat(this.listener.setupFailures).hasSize(1);
	}

	@Test
	void reportsInvalidAuth() {
		this.session.onMessage("{\"type\":\"auth_invalid\",\"message\":\"Invalid access token\"}");
		assertThat(this.listener.authFailure).isEqualTo("Invalid access token");
	}

	@Test
	void failedResultCompletesRequestExceptionally() {
		CompletableFuture<JsonNode> reply = this.session.request(this.mapper.createObjectNode().put("type", "ping_me"));
		this.session.onMessage("""
				{"id":1,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""");
		assertThat(reply).isCompletedExceptionally();
		assertThat(reply.exceptionNow()).isInstanceOf(HaRequestException.class).hasMessage("Unknown command.");
	}

	@Test
	void closeFailsPendingRequests() {
		CompletableFuture<JsonNode> reply = this.session.request(this.mapper.createObjectNode().put("type", "get_states"));
		this.session.close();
		assertThat(reply).isCompletedExceptionally();
	}

	private JsonNode sent(int index) {
		return this.mapper.readTree(this.sent.get(index));
	}

	static final class RecordingListener implements HaSession.Listener {

		boolean authenticated;

		String authFailure;

		List<EntityState> states = List.of();

		final List<String> changedIds = new ArrayList<>();

		final List<EntityState> changedStates = new ArrayList<>();

		final List<Throwable> setupFailures = new ArrayList<>();

		@Override
		public void onSetupFailed(Throwable failure) {
			this.setupFailures.add(failure);
		}

		@Override
		public void onAuthenticated() {
			this.authenticated = true;
		}

		@Override
		public void onAuthFailed(String message) {
			this.authFailure = message;
		}

		@Override
		public void onStates(List<EntityState> states) {
			this.states = states;
		}

		@Override
		public void onStateChanged(String entityId, EntityState newState) {
			this.changedIds.add(entityId);
			this.changedStates.add(newState);
		}

	}

}
