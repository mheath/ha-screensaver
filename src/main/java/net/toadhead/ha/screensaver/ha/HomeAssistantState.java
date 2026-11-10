package net.toadhead.ha.screensaver.ha;

import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Last known state of every Home Assistant entity, plus whether we're connected.
 * States, and the device registry, are kept while disconnected so screens keep
 * showing last-known values.
 */
@Component
public class HomeAssistantState {

	private final Map<String, EntityState> states = new ConcurrentHashMap<>();

	private volatile boolean connected;

	private volatile ZoneId timeZone = ZoneId.systemDefault();

	private volatile DeviceRegistry registry = DeviceRegistry.EMPTY;

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

	/**
	 * Every entity's last known state.
	 * @return a snapshot copy
	 */
	public Collection<EntityState> all() {
		return List.copyOf(this.states.values());
	}

	/**
	 * Home Assistant's device registry, or {@link DeviceRegistry#EMPTY} until it
	 * has loaded.
	 * @return the registry
	 */
	public DeviceRegistry registry() {
		return this.registry;
	}

	public void setRegistry(DeviceRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Home Assistant's time zone, or the JVM's until Home Assistant reports one.
	 * @return the time zone
	 */
	public ZoneId timeZone() {
		return this.timeZone;
	}

	public void setTimeZone(ZoneId timeZone) {
		this.timeZone = timeZone;
	}

}
