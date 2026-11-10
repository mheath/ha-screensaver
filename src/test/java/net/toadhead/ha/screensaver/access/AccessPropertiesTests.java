package net.toadhead.ha.screensaver.access;

import net.toadhead.ha.screensaver.config.AccessProperties;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AccessPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void allowsEveryoneByDefault() {
		this.runner.run((context) -> assertThat(context.getBean(AccessProperties.class).allowedAddresses()).isEmpty());
	}

	@Test
	void bindsAddressesAndRanges() {
		this.runner.withPropertyValues("screensaver.access.allowed-addresses=192.168.1.10, 10.0.0.0/8, ::1")
			.run((context) -> assertThat(context.getBean(AccessProperties.class).allowedAddresses())
				.containsExactly("192.168.1.10", "10.0.0.0/8", "::1"));
	}

	@Test
	void rejectsHostNames() {
		assertFails("haproxy.lan");
	}

	@Test
	void rejectsBadPrefixLength() {
		assertFails("192.168.1.0/33");
	}

	private void assertFails(String address) {
		this.runner.withPropertyValues("screensaver.access.allowed-addresses=" + address).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasStackTraceContaining("allowed-addresses must be IP addresses or CIDR ranges");
		});
	}

	@EnableConfigurationProperties(AccessProperties.class)
	static class Config {

	}

}
