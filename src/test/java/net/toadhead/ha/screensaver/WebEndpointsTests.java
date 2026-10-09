package net.toadhead.ha.screensaver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WebEndpointsTests {

	@LocalServerPort
	int port;

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void staticResourcesMustBeRevalidated() throws Exception {
		HttpResponse<String> response = get("/cache-probe.txt");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Cache-Control"))
			.hasValueSatisfying((value) -> assertThat(value).contains("no-cache"));
	}

	@Test
	void healthIsUp() throws Exception {
		HttpResponse<String> response = get("/actuator/health");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"UP\"");
	}

	private HttpResponse<String> get(String path) throws Exception {
		return this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

}
