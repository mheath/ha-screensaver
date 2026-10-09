package net.toadhead.ha.screensaver.ha;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Home Assistant WebSocket protocol for one connection, independent of the
 * transport: feed it incoming messages, and it writes replies to {@code outbound}
 * and reports what happened to the {@link Listener}.
 */
final class HaSession {

	private final String token;

	private final JsonMapper mapper;

	private final Consumer<String> outbound;

	private final Listener listener;

	private final AtomicInteger ids = new AtomicInteger();

	private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

	private volatile int subscriptionId = -1;

	HaSession(String token, JsonMapper mapper, Consumer<String> outbound, Listener listener) {
		this.token = token;
		this.mapper = mapper;
		this.outbound = outbound;
		this.listener = listener;
	}

	void onMessage(String text) {
		JsonNode message = this.mapper.readTree(text);
		switch (message.path("type").asString("")) {
			case "auth_required" -> send(this.mapper.createObjectNode().put("type", "auth").put("access_token", this.token));
			case "auth_ok" -> {
				this.listener.onAuthenticated();
				subscribeAndLoad();
			}
			case "auth_invalid" -> this.listener.onAuthFailed(message.path("message").asString(""));
			case "result" -> complete(message);
			case "event" -> onEvent(message);
			default -> {
			}
		}
	}

	CompletableFuture<JsonNode> request(ObjectNode message) {
		int id = this.ids.incrementAndGet();
		message.put("id", id);
		CompletableFuture<JsonNode> reply = new CompletableFuture<>();
		this.pending.put(id, reply);
		send(message);
		return reply;
	}

	void close() {
		this.pending.values().forEach((reply) -> reply.completeExceptionally(new HaRequestException("connection closed")));
		this.pending.clear();
	}

	private void subscribeAndLoad() {
		ObjectNode subscribe = this.mapper.createObjectNode()
			.put("type", "subscribe_events")
			.put("event_type", "state_changed");
		this.subscriptionId = this.ids.get() + 1;
		request(subscribe);
		request(this.mapper.createObjectNode().put("type", "get_states")).thenAccept((result) -> {
			List<EntityState> states = new ArrayList<>();
			result.forEach((node) -> states.add(EntityState.fromJson(node)));
			this.listener.onStates(states);
		});
	}

	private void complete(JsonNode message) {
		CompletableFuture<JsonNode> reply = this.pending.remove(message.path("id").asInt(-1));
		if (reply == null) {
			return;
		}
		if (message.path("success").asBoolean(false)) {
			reply.complete(message.path("result"));
		}
		else {
			reply.completeExceptionally(
					new HaRequestException(message.path("error").path("message").asString("request failed")));
		}
	}

	private void onEvent(JsonNode message) {
		if (message.path("id").asInt(-1) != this.subscriptionId) {
			return;
		}
		JsonNode data = message.path("event").path("data");
		JsonNode newState = data.path("new_state");
		this.listener.onStateChanged(data.path("entity_id").asString(""),
				newState.isObject() ? EntityState.fromJson(newState) : null);
	}

	private void send(ObjectNode message) {
		this.outbound.accept(this.mapper.writeValueAsString(message));
	}

	interface Listener {

		void onAuthenticated();

		void onAuthFailed(String message);

		void onStates(List<EntityState> states);

		/**
		 * Called for every state change.
		 * @param entityId the entity that changed
		 * @param newState the new state, or {@code null} if the entity was removed
		 */
		void onStateChanged(String entityId, EntityState newState);

	}

}
