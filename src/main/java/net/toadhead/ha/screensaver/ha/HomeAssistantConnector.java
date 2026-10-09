package net.toadhead.ha.screensaver.ha;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import net.toadhead.ha.screensaver.config.ScreensaverProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Keeps one WebSocket connection to Home Assistant open, reconnecting with
 * exponential backoff, and mirrors entity states into {@link HomeAssistantState}.
 */
@Component
@ConditionalOnBooleanProperty(name = "screensaver.home-assistant.enabled", matchIfMissing = true)
public class HomeAssistantConnector implements SmartLifecycle {

	private static final Logger logger = LoggerFactory.getLogger(HomeAssistantConnector.class);

	private final ScreensaverProperties.HomeAssistant config;

	private final HomeAssistantState state;

	private final ApplicationEventPublisher events;

	private final JsonMapper mapper;

	private final HttpClient http = HttpClient.newHttpClient();

	private final ScheduledExecutorService reconnects = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ha-reconnect").factory());

	private volatile boolean running;

	private volatile Connection connection;

	private Duration nextBackoff;

	@Autowired
	public HomeAssistantConnector(ScreensaverProperties properties, HomeAssistantState state,
			ApplicationEventPublisher events, JsonMapper mapper) {
		this(properties.homeAssistant(), state, events, mapper);
	}

	HomeAssistantConnector(ScreensaverProperties.HomeAssistant config, HomeAssistantState state,
			ApplicationEventPublisher events, JsonMapper mapper) {
		this.config = config;
		this.state = state;
		this.events = events;
		this.mapper = mapper;
		this.nextBackoff = config.initialBackoff();
	}

	@Override
	public void start() {
		this.running = true;
		connect();
	}

	@Override
	public void stop() {
		this.running = false;
		this.reconnects.shutdownNow();
		Connection current = this.connection;
		if (current != null) {
			current.abort("stopped");
		}
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	private void connect() {
		if (!this.running) {
			return;
		}
		Connection attempt = new Connection();
		this.connection = attempt;
		this.http.newWebSocketBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.buildAsync(this.config.url(), attempt)
			.whenComplete((socket, error) -> {
				if (error != null) {
					attempt.closed("could not connect: " + error.getMessage());
				}
			});
	}

	private synchronized void scheduleReconnect() {
		if (!this.running) {
			return;
		}
		Duration delay = this.nextBackoff;
		Duration doubled = this.nextBackoff.multipliedBy(2);
		this.nextBackoff = (doubled.compareTo(this.config.maxBackoff()) > 0) ? this.config.maxBackoff() : doubled;
		logger.info("Reconnecting to Home Assistant in {}", delay);
		try {
			this.reconnects.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException ex) {
			// Shutting down.
		}
	}

	private synchronized void resetBackoff() {
		this.nextBackoff = this.config.initialBackoff();
	}

	private void publish() {
		this.events.publishEvent(new HomeAssistantUpdated());
	}

	/**
	 * One connection attempt: the WebSocket listener plus its protocol session.
	 */
	private final class Connection implements WebSocket.Listener, HaSession.Listener {

		private final HaSession session = new HaSession(HomeAssistantConnector.this.config.token(),
				HomeAssistantConnector.this.mapper, this::send, this);

		/** Home Assistant splits large messages into several frames. */
		private final StringBuilder buffer = new StringBuilder();

		private final AtomicBoolean closed = new AtomicBoolean();

		private volatile WebSocket socket;

		/** The JDK WebSocket rejects a send while another is in flight, so sends are chained. */
		private CompletableFuture<WebSocket> sending = CompletableFuture.completedFuture(null);

		/**
		 * Detects a connection that stays open but goes quiet (for example Home
		 * Assistant losing power without closing the socket): first an
		 * authentication deadline, then periodic pings.
		 */
		private volatile ScheduledFuture<?> watchdog;

		private volatile boolean authenticated;

		@Override
		public void onOpen(WebSocket webSocket) {
			this.socket = webSocket;
			this.watchdog = schedule(() -> {
				if (!this.authenticated) {
					abort("no authentication reply within " + HomeAssistantConnector.this.config.pingTimeout());
				}
			}, HomeAssistantConnector.this.config.pingTimeout());
			webSocket.request(1);
		}

		private void ping() {
			this.session.request(HomeAssistantConnector.this.mapper.createObjectNode().put("type", "ping"))
				.orTimeout(HomeAssistantConnector.this.config.pingTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.whenComplete((reply, error) -> {
					if (error != null) {
						abort("no ping reply within " + HomeAssistantConnector.this.config.pingTimeout());
					}
				});
		}

		private ScheduledFuture<?> schedule(Runnable task, Duration delay) {
			try {
				return HomeAssistantConnector.this.reconnects.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (RejectedExecutionException ex) {
				return null;
			}
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			this.buffer.append(data);
			if (last) {
				String message = this.buffer.toString();
				this.buffer.setLength(0);
				try {
					this.session.onMessage(message);
				}
				catch (RuntimeException ex) {
					logger.warn("Could not handle a Home Assistant message", ex);
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			closed("closed by Home Assistant (" + statusCode + " " + reason + ")");
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			closed(error.toString());
		}

		private synchronized void send(String text) {
			WebSocket target = this.socket;
			this.sending = this.sending.exceptionally((ex) -> null).thenCompose((ignored) -> target.sendText(text, true));
		}

		void abort(String why) {
			WebSocket target = this.socket;
			if (target != null) {
				target.abort();
			}
			closed(why);
		}

		void closed(String why) {
			if (!this.closed.compareAndSet(false, true)) {
				return;
			}
			ScheduledFuture<?> pending = this.watchdog;
			if (pending != null) {
				pending.cancel(false);
			}
			this.session.close();
			if (HomeAssistantConnector.this.connection == this && HomeAssistantConnector.this.state.connected()) {
				HomeAssistantConnector.this.state.markDisconnected();
				publish();
			}
			if (HomeAssistantConnector.this.running) {
				logger.warn("Home Assistant connection lost: {}", why);
				scheduleReconnect();
			}
		}

		@Override
		public void onAuthenticated() {
			this.authenticated = true;
			Duration interval = HomeAssistantConnector.this.config.pingInterval();
			ScheduledFuture<?> deadline = this.watchdog;
			if (deadline != null) {
				deadline.cancel(false);
			}
			try {
				this.watchdog = HomeAssistantConnector.this.reconnects.scheduleWithFixedDelay(this::ping,
						interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (RejectedExecutionException ex) {
				// Shutting down.
			}
			resetBackoff();
			logger.info("Authenticated with Home Assistant at {}", HomeAssistantConnector.this.config.url());
		}

		@Override
		public void onAuthFailed(String message) {
			logger.error("Home Assistant rejected the access token: {}", message);
			abort("authentication failed");
		}

		@Override
		public void onStates(List<EntityState> states) {
			HomeAssistantConnector.this.state.markConnected(states);
			publish();
		}

		@Override
		public void onStateChanged(String entityId, EntityState newState) {
			if (newState == null) {
				HomeAssistantConnector.this.state.remove(entityId);
			}
			else {
				HomeAssistantConnector.this.state.put(newState);
			}
			publish();
		}

	}

}
