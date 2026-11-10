package net.toadhead.ha.screensaver.ha;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Home Assistant WebSocket protocol for one connection, independent of the
 * transport: feed it incoming messages, and it writes replies to {@code outbound}
 * and reports what happened to the {@link Listener}.
 */
final class HaSession {

	private static final Logger logger = LoggerFactory.getLogger(HaSession.class);

	private final String token;

	private final JsonMapper mapper;

	private final Consumer<String> outbound;

	private final Listener listener;

	private final AtomicInteger ids = new AtomicInteger();

	private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

	private volatile int subscriptionId = -1;

	private final Set<Integer> registrySubscriptionIds = ConcurrentHashMap.newKeySet();

	/**
	 * Guards the registry load flags: loads finish on the message thread, but also
	 * on whichever thread calls {@link #close()}.
	 */
	private final Object registryLock = new Object();

	private boolean registryLoading;

	private boolean registryReloadRequested;

	private boolean closed;

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
			case "pong" -> {
				CompletableFuture<JsonNode> reply = this.pending.remove(message.path("id").asInt(-1));
				if (reply != null) {
					reply.complete(message);
				}
			}
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
		synchronized (this.registryLock) {
			this.closed = true;
		}
		this.pending.values().forEach((reply) -> reply.completeExceptionally(new HaRequestException("connection closed")));
		this.pending.clear();
	}

	private void subscribeAndLoad() {
		ObjectNode subscribe = this.mapper.createObjectNode()
			.put("type", "subscribe_events")
			.put("event_type", "state_changed");
		this.subscriptionId = this.ids.get() + 1;
		request(subscribe).whenComplete(this::reportSetupFailure);
		// Home Assistant answers in order, so the time zone is known before states load.
		request(this.mapper.createObjectNode().put("type", "get_config")).whenComplete(this::onConfig);
		request(this.mapper.createObjectNode().put("type", "get_states")).thenAccept((result) -> {
			List<EntityState> states = new ArrayList<>();
			result.forEach((node) -> states.add(EntityState.fromJson(node)));
			this.listener.onStates(states);
		}).whenComplete(this::reportSetupFailure);
		subscribeToRegistry("entity_registry_updated");
		subscribeToRegistry("device_registry_updated");
		loadRegistries();
	}

	/** Device names are a nicety, so registry problems are logged, not fatal. */
	private void subscribeToRegistry(String eventType) {
		ObjectNode subscribe = this.mapper.createObjectNode()
			.put("type", "subscribe_events")
			.put("event_type", eventType);
		request(subscribe).whenComplete((result, failure) -> {
			if (failure != null) {
				logger.warn("Could not subscribe to Home Assistant's {} events: {}", eventType,
						unwrap(failure).getMessage());
			}
		});
		// Events are read on the thread running this, so none can arrive before the id is known.
		this.registrySubscriptionIds.add(subscribe.path("id").asInt(-1));
	}

	/**
	 * Loads both registries, or, if a load is already in flight, loads them again
	 * once it finishes, so a burst of change events costs at most one more load.
	 */
	private void loadRegistries() {
		synchronized (this.registryLock) {
			if (this.closed) {
				return;
			}
			if (this.registryLoading) {
				this.registryReloadRequested = true;
				return;
			}
			this.registryLoading = true;
		}
		CompletableFuture<JsonNode> entities = request(
				this.mapper.createObjectNode().put("type", "config/entity_registry/list"));
		CompletableFuture<JsonNode> devices = request(
				this.mapper.createObjectNode().put("type", "config/device_registry/list"));
		CompletableFuture.allOf(entities, devices)
			.thenRun(() -> this.listener.onRegistry(DeviceRegistry.fromJson(entities.join(), devices.join())))
			.whenComplete((ignored, failure) -> {
				if (failure != null) {
					logger.warn("Could not read Home Assistant's device registry: {}", unwrap(failure).getMessage());
				}
				registriesLoaded();
			});
	}

	private void registriesLoaded() {
		synchronized (this.registryLock) {
			this.registryLoading = false;
			if (!this.registryReloadRequested) {
				return;
			}
			this.registryReloadRequested = false;
		}
		loadRegistries();
	}

	private static Throwable unwrap(Throwable failure) {
		return (failure instanceof CompletionException && failure.getCause() != null) ? failure.getCause() : failure;
	}

	/** The time zone only moves calendar day boundaries, so problems are logged, not fatal. */
	private void onConfig(JsonNode config, Throwable failure) {
		if (failure != null) {
			logger.warn("Could not read Home Assistant's configuration: {}", failure.getMessage());
			return;
		}
		String zone = config.path("time_zone").asString("");
		try {
			this.listener.onTimeZone(ZoneId.of(zone));
		}
		catch (DateTimeException ex) {
			logger.warn("Ignoring Home Assistant's unknown time zone '{}'", zone);
		}
	}

	private void reportSetupFailure(Object ignored, Throwable failure) {
		if (failure != null) {
			this.listener.onSetupFailed(failure);
		}
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
		int id = message.path("id").asInt(-1);
		if (this.registrySubscriptionIds.contains(id)) {
			loadRegistries();
			return;
		}
		if (id != this.subscriptionId) {
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
		 * Called with Home Assistant's configured time zone.
		 * @param zone the time zone
		 */
		void onTimeZone(ZoneId zone);

		/**
		 * Called for every state change.
		 * @param entityId the entity that changed
		 * @param newState the new state, or {@code null} if the entity was removed
		 */
		void onStateChanged(String entityId, EntityState newState);

		/**
		 * Called when Home Assistant's device and entity registries have loaded, and
		 * again whenever they change.
		 * @param registry the registries
		 */
		void onRegistry(DeviceRegistry registry);

		/**
		 * Called when subscribing or loading states fails, so the connection can be
		 * retried instead of staying half set up.
		 * @param failure what went wrong
		 */
		void onSetupFailed(Throwable failure);

	}

}
