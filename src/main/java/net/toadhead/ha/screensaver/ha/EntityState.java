package net.toadhead.ha.screensaver.ha;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * One Home Assistant entity's state.
 *
 * @param entityId the entity id, for example {@code sensor.house_power}
 * @param state the raw state string
 * @param attributes the entity's attributes object
 * @param lastChanged when the state last changed, or {@code null} if unknown
 */
public record EntityState(String entityId, String state, JsonNode attributes, Instant lastChanged) {

	static EntityState fromJson(JsonNode node) {
		String lastChanged = node.path("last_changed").asString("");
		return new EntityState(node.path("entity_id").asString(""), node.path("state").asString(""),
				node.path("attributes"), lastChanged.isEmpty() ? null : OffsetDateTime.parse(lastChanged).toInstant());
	}

	public Optional<String> attribute(String name) {
		JsonNode value = (this.attributes != null) ? this.attributes.get(name) : null;
		return (value == null || value.isNull()) ? Optional.empty() : Optional.of(value.asString(""));
	}

}
