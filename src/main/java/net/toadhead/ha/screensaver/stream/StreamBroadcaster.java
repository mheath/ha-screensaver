package net.toadhead.ha.screensaver.stream;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.toadhead.ha.screensaver.kiosk.KioskConfig;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes world-state changes to connected kiosks, at most once per second per
 * kiosk, and keeps idle connections alive with heartbeats.
 */
@Component
public class StreamBroadcaster {

	private final WorldStateService worldState;

	/** Each client mapped to the last world-state version it was sent. */
	private final Map<Client, Long> clients = new ConcurrentHashMap<>();

	public StreamBroadcaster(WorldStateService worldState) {
		this.worldState = worldState;
	}

	public void register(Client client, KioskConfig config) throws IOException {
		WorldStateService.Versioned current = this.worldState.current();
		client.send("config", config);
		client.send("state", current.state());
		this.clients.put(client, current.version());
	}

	public void unregister(Client client) {
		this.clients.remove(client);
	}

	/**
	 * Ends every open stream when the application starts shutting down. Streams
	 * never time out, so graceful shutdown would otherwise wait for them until it
	 * gives up.
	 */
	@EventListener(ContextClosedEvent.class)
	public void completeAll() {
		this.clients.keySet().forEach((client) -> {
			try {
				client.complete();
			}
			catch (RuntimeException ex) {
				// Already gone.
			}
		});
		this.clients.clear();
	}

	public int clientCount() {
		return this.clients.size();
	}

	@Scheduled(fixedDelay = 1000)
	public void broadcast() {
		WorldStateService.Versioned current = this.worldState.current();
		this.clients.forEach((client, sentVersion) -> {
			if (sentVersion < current.version()) {
				try {
					client.send("state", current.state());
					this.clients.put(client, current.version());
				}
				catch (IOException | IllegalStateException ex) {
					this.clients.remove(client);
				}
			}
		});
	}

	@Scheduled(fixedRate = 15000, initialDelay = 15000)
	public void heartbeat() {
		this.clients.keySet().forEach((client) -> {
			try {
				client.heartbeat();
			}
			catch (IOException | IllegalStateException ex) {
				this.clients.remove(client);
			}
		});
	}

	/**
	 * One kiosk's open stream.
	 */
	public interface Client {

		void send(String event, Object data) throws IOException;

		void heartbeat() throws IOException;

		void complete();

	}

}
