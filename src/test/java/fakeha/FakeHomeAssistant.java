package fakeha;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * A scripted stand-in for Home Assistant's WebSocket API, served by its own
 * embedded Tomcat on a random port.
 */
public final class FakeHomeAssistant implements AutoCloseable {

	public static final String TOKEN = "good-token";

	private final ConfigurableApplicationContext context;

	private final Handler handler;

	private FakeHomeAssistant(ConfigurableApplicationContext context) {
		this.context = context;
		this.handler = context.getBean(Handler.class);
	}

	public static FakeHomeAssistant start(List<Map<String, Object>> states) {
		ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
			.properties("server.port=0", "spring.main.banner-mode=off",
					"spring.config.location=optional:classpath:/fakeha-none/")
			.run();
		FakeHomeAssistant fake = new FakeHomeAssistant(context);
		fake.handler.states = states;
		return fake;
	}

	public static Map<String, Object> entity(String entityId, String state, Map<String, Object> attributes) {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("entity_id", entityId);
		entity.put("state", state);
		entity.put("attributes", attributes);
		entity.put("last_changed", "2026-10-08T17:05:00.000000+00:00");
		entity.put("last_updated", "2026-10-08T17:05:00.000000+00:00");
		return entity;
	}

	public URI uri() {
		return URI.create("ws://localhost:" + this.context.getEnvironment().getProperty("local.server.port")
				+ "/api/websocket");
	}

	public int authAttempts() {
		return this.handler.authAttempts.get();
	}

	public void pushStateChange(String entityId, String state) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("id", this.handler.subscriptionId);
		event.put("type", "event");
		event.put("event", Map.of("event_type", "state_changed", "data",
				Map.of("entity_id", entityId, "new_state", entity(entityId, state, Map.of()))));
		this.handler.sessions.forEach((session) -> this.handler.send(session, event));
	}

	/** Stops answering anything, like a host that lost power without closing sockets. */
	public void goSilent() {
		this.handler.silent = true;
	}

	public void resume() {
		this.handler.silent = false;
	}

	public void dropConnections() throws IOException {
		for (WebSocketSession session : this.handler.sessions) {
			session.close(CloseStatus.GOING_AWAY);
		}
	}

	@Override
	public void close() {
		this.context.close();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableWebSocket
	static class App implements WebSocketConfigurer {

		private final Handler handler = new Handler();

		@Bean
		Handler handler() {
			return this.handler;
		}

		@Override
		public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
			registry.addHandler(this.handler, "/api/websocket");
		}

	}

	static final class Handler extends TextWebSocketHandler {

		private final JsonMapper mapper = JsonMapper.shared();

		final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

		final AtomicInteger authAttempts = new AtomicInteger();

		volatile List<Map<String, Object>> states = List.of();

		volatile int subscriptionId = -1;

		volatile boolean silent;

		@Override
		public void afterConnectionEstablished(WebSocketSession session) {
			this.sessions.add(session);
			send(session, Map.of("type", "auth_required", "ha_version", "2026.10.0"));
		}

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
			if (this.silent) {
				return;
			}
			JsonNode request = this.mapper.readTree(message.getPayload());
			int id = request.path("id").asInt(0);
			switch (request.path("type").asString("")) {
				case "auth" -> {
					this.authAttempts.incrementAndGet();
					if (TOKEN.equals(request.path("access_token").asString(""))) {
						send(session, Map.of("type", "auth_ok", "ha_version", "2026.10.0"));
					}
					else {
						send(session, Map.of("type", "auth_invalid", "message", "Invalid access token"));
						session.close(CloseStatus.POLICY_VIOLATION);
					}
				}
				case "subscribe_events" -> {
					this.subscriptionId = id;
					send(session, result(id, null));
				}
				case "get_states" -> send(session, result(id, this.states));
				case "ping" -> send(session, Map.of("id", id, "type", "pong"));
				default -> send(session, Map.of("id", id, "type", "result", "success", false, "error",
						Map.of("code", "unknown_command", "message", "Unknown command.")));
			}
		}

		@Override
		public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
			this.sessions.remove(session);
		}

		synchronized void send(WebSocketSession session, Object payload) {
			try {
				session.sendMessage(new TextMessage(this.mapper.writeValueAsString(payload)));
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}

		private static Map<String, Object> result(int id, Object result) {
			Map<String, Object> reply = new LinkedHashMap<>();
			reply.put("id", id);
			reply.put("type", "result");
			reply.put("success", true);
			reply.put("result", result);
			return reply;
		}

	}

}
