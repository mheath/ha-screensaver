package net.toadhead.ha.screensaver.stream;

import java.io.IOException;

import net.toadhead.ha.screensaver.kiosk.KioskConfigService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The kiosk stream: a {@code config} event, then {@code state} events.
 */
@RestController
public class StreamController {

	/** Zero means the servlet container never times the stream out. */
	private static final long NO_TIMEOUT = 0L;

	private final StreamBroadcaster broadcaster;

	private final KioskConfigService kioskConfigs;

	public StreamController(StreamBroadcaster broadcaster, KioskConfigService kioskConfigs) {
		this.broadcaster = broadcaster;
		this.kioskConfigs = kioskConfigs;
	}

	@GetMapping(path = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@RequestParam(defaultValue = "default") String kiosk) throws IOException {
		SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
		EmitterClient client = new EmitterClient(emitter);
		emitter.onCompletion(() -> this.broadcaster.unregister(client));
		emitter.onTimeout(() -> this.broadcaster.unregister(client));
		emitter.onError((ex) -> this.broadcaster.unregister(client));
		this.broadcaster.register(client, this.kioskConfigs.resolve(kiosk));
		return emitter;
	}

	private record EmitterClient(SseEmitter emitter) implements StreamBroadcaster.Client {

		@Override
		public void send(String event, Object data) throws IOException {
			this.emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
		}

		@Override
		public void heartbeat() throws IOException {
			this.emitter.send(SseEmitter.event().comment("ping"));
		}

		@Override
		public void complete() {
			this.emitter.complete();
		}

	}

}
