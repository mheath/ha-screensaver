package net.toadhead.ha.screensaver.access;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
class AllowedAddressesValveTests {

	private static final HttpClient http = HttpClient.newHttpClient();

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
			properties = "screensaver.access.allowed-addresses=192.0.2.1, 198.51.100.0/24")
	class WhenNotAllowed {

		@LocalServerPort
		int port;

		@Test
		void refusesAndExplainsWhy() throws Exception {
			HttpResponse<String> response = get(this.port, "/actuator/health");
			assertThat(response.statusCode()).isEqualTo(403);
			assertThat(response.headers().firstValue("Content-Type"))
				.hasValueSatisfying((value) -> assertThat(value).startsWith("text/plain"));
			assertThat(response.body()).contains("127.0.0.1").contains("not allowed");
		}

		@Test
		void closesTheConnection() throws Exception {
			HttpResponse<String> response = get(this.port, "/actuator/health");
			assertThat(response.headers().firstValue("Connection")).hasValue("close");
		}

	}

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
			properties = "screensaver.access.allowed-addresses=192.0.2.1, 127.0.0.0/8, ::1")
	class WhenAllowed {

		@LocalServerPort
		int port;

		@Test
		void serves() throws Exception {
			assertThat(get(this.port, "/actuator/health").statusCode()).isEqualTo(200);
		}

		@Test
		void streamsEvents() throws Exception {
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/stream"))
				.build();
			HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				assertThat(response.statusCode()).isEqualTo(200);
				assertThat(response.headers().firstValue("Content-Type"))
					.hasValueSatisfying((value) -> assertThat(value).startsWith("text/event-stream"));
				assertThat(body.read()).isNotEqualTo(-1);
			}
		}

	}

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
			properties = { "screensaver.access.allowed-addresses=192.0.2.1", "server.forward-headers-strategy=native" })
	class WhenForwardedForAnAllowedAddress {

		@LocalServerPort
		int port;

		@Test
		void stillRefusesTheRealPeer() throws Exception {
			HttpRequest request = HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/actuator/health"))
				.header("X-Forwarded-For", "192.0.2.1")
				.build();
			assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
		}

	}

	private static HttpResponse<String> get(int port, String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

}
