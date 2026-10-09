package net.toadhead.ha.screensaver.ha;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Last known state of every Home Assistant entity, plus whether we're connected.
 * States are kept while disconnected so screens keep showing last-known values.
 */
@Component
public class HomeAssistantState {

	private final Map<String, EntityState> states = new ConcurrentHashMap<>();

	private volatile boolean connected;

	public boolean connected() {
		return this.connected;
	}

	public void markConnected(Collection<EntityState> all) {
		Map<String, EntityState> fresh = new ConcurrentHashMap<>();
		all.forEach((state) -> fresh.put(state.entityId(), state));
		this.states.keySet().retainAll(fresh.keySet());
		this.states.putAll(fresh);
		this.connected = true;
	}

	public void markDisconnected() {
		this.connected = false;
	}

	public void put(EntityState state) {
		this.states.put(state.entityId(), state);
	}

	public void remove(String entityId) {
		this.states.remove(entityId);
	}

	public Optional<EntityState> get(String entityId) {
		return Optional.ofNullable(this.states.get(entityId));
	}

}
