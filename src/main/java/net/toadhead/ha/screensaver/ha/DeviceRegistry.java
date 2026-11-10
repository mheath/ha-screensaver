package net.toadhead.ha.screensaver.ha;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * Which device each Home Assistant entity belongs to, and what each device is
 * called, from Home Assistant's entity and device registries.
 *
 * @param deviceIds entity id to device id, for entities that belong to a device
 * @param deviceNames device id to display name, for devices that have a name
 */
public record DeviceRegistry(Map<String, String> deviceIds, Map<String, String> deviceNames) {

	/** Knows no devices, used until Home Assistant's registries have loaded. */
	public static final DeviceRegistry EMPTY = new DeviceRegistry(Map.of(), Map.of());

	public DeviceRegistry {
		deviceIds = Map.copyOf(deviceIds);
		deviceNames = Map.copyOf(deviceNames);
	}

	public Optional<String> deviceId(String entityId) {
		return Optional.ofNullable(this.deviceIds.get(entityId));
	}

	/**
	 * The name the user gave the device, or else the name its integration gave it.
	 * @param deviceId the device
	 * @return the name, or empty if the device is unknown or unnamed
	 */
	public Optional<String> deviceName(String deviceId) {
		return Optional.ofNullable(this.deviceNames.get(deviceId));
	}

	/**
	 * Combines the results of {@code config/entity_registry/list} and
	 * {@code config/device_registry/list}.
	 * @param entities the entity registry entries
	 * @param devices the device registry entries
	 * @return the registry
	 */
	static DeviceRegistry fromJson(JsonNode entities, JsonNode devices) {
		Map<String, String> deviceIds = new HashMap<>();
		entities.forEach((entity) -> {
			String entityId = text(entity.path("entity_id"));
			String deviceId = text(entity.path("device_id"));
			if (!entityId.isBlank() && !deviceId.isBlank()) {
				deviceIds.put(entityId, deviceId);
			}
		});
		Map<String, String> deviceNames = new HashMap<>();
		devices.forEach((device) -> {
			String id = text(device.path("id"));
			String byUser = text(device.path("name_by_user"));
			String name = byUser.isBlank() ? text(device.path("name")) : byUser;
			if (!id.isBlank() && !name.isBlank()) {
				deviceNames.put(id, name);
			}
		});
		return new DeviceRegistry(deviceIds, deviceNames);
	}

	/** Registry fields are often {@code null}, which must not read as the text "null". */
	private static String text(JsonNode node) {
		return node.isString() ? node.asString("") : "";
	}

}
