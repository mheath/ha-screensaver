package net.toadhead.ha.screensaver.ha;

import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.JsonNode;

/**
 * Calls Home Assistant services that return data.
 */
public interface HomeAssistantServices {

	/**
	 * Calls a service with {@code return_response}.
	 * @param domain the service domain, for example {@code calendar}
	 * @param service the service, for example {@code get_events}
	 * @param target the service target, for example
	 * {@code {"entity_id": "calendar.family"}}
	 * @param data the service data
	 * @return the reply's {@code response} object; completes exceptionally with
	 * {@link HaRequestException} when Home Assistant reports an error or isn't
	 * connected, or with a timeout when no reply arrives in 30 seconds
	 */
	CompletableFuture<JsonNode> callService(String domain, String service, JsonNode target, JsonNode data);

}
