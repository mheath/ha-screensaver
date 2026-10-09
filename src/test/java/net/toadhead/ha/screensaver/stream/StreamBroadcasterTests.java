package net.toadhead.ha.screensaver.stream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.toadhead.ha.screensaver.config.TestProperties;
import net.toadhead.ha.screensaver.ha.HomeAssistantState;
import net.toadhead.ha.screensaver.ha.HomeAssistantUpdated;
import net.toadhead.ha.screensaver.kiosk.KioskConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamBroadcasterTests {

	private final HomeAssistantState ha = new HomeAssistantState();

	private final WorldStateService worldState = new WorldStateService(
			TestProperties.withSnakes(Map.of("power", TestProperties.snake("sensor.power", "Power")), Map.of()),
			this.ha);

	private final StreamBroadcaster broadcaster = new StreamBroadcaster(this.worldState);

	private final KioskConfig config = new KioskConfig("kitchen", List.of(), new KioskConfig.ClockOptions(false),
			new KioskConfig.SnakeOptions(0, 4, 30, 2, 20, List.of(), List.of()));

	@Test
	void registerSendsConfigThenState() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		assertThat(client.events).containsExactly("config", "state");
		assertThat(client.data.get(0)).isEqualTo(this.config);
	}

	@Test
	void broadcastSendsNothingWhenUnchanged() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		this.broadcaster.broadcast();
		assertThat(client.events).containsExactly("config", "state");
	}

	@Test
	void broadcastSendsOneStateForSeveralChangesBetweenTicks() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		change("1");
		change("2");
		change("3");
		this.broadcaster.broadcast();
		this.broadcaster.broadcast();
		assertThat(client.events).containsExactly("config", "state", "state");
		WorldState last = (WorldState) client.data.get(2);
		assertThat(last.snakes().get(0).value()).isEqualTo(3.0);
	}

	@Test
	void dropsClientsThatFail() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		client.fail = true;
		change("1");
		this.broadcaster.broadcast();
		assertThat(this.broadcaster.clientCount()).isZero();
	}

	@Test
	void heartbeatPingsEveryClient() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		this.broadcaster.heartbeat();
		assertThat(client.heartbeats).isEqualTo(1);
	}

	@Test
	void unregisterStopsUpdates() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		this.broadcaster.unregister(client);
		change("1");
		this.broadcaster.broadcast();
		assertThat(client.events).containsExactly("config", "state");
	}

	@Test
	void completeAllEndsEveryStreamAndForgetsIt() throws IOException {
		RecordingClient client = new RecordingClient();
		this.broadcaster.register(client, this.config);
		this.broadcaster.completeAll();
		assertThat(client.completed).isTrue();
		assertThat(this.broadcaster.clientCount()).isZero();
	}

	private void change(String value) {
		this.ha.markConnected(List.of(WorldStateAssemblerTests.entity("sensor.power", value, "{}")));
		this.worldState.onHomeAssistantUpdated(new HomeAssistantUpdated());
	}

	static final class RecordingClient implements StreamBroadcaster.Client {

		final List<String> events = new ArrayList<>();

		final List<Object> data = new ArrayList<>();

		int heartbeats;

		boolean completed;

		boolean fail;

		@Override
		public void send(String event, Object payload) throws IOException {
			if (this.fail) {
				throw new IOException("broken pipe");
			}
			this.events.add(event);
			this.data.add(payload);
		}

		@Override
		public void heartbeat() throws IOException {
			if (this.fail) {
				throw new IOException("broken pipe");
			}
			this.heartbeats++;
		}

		@Override
		public void complete() {
			this.completed = true;
		}

	}

}
