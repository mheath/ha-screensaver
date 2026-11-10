package net.toadhead.ha.screensaver.config;

import net.toadhead.ha.screensaver.access.AllowedAddressesValve;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Restricts Tomcat to {@link AccessProperties#allowedAddresses()} when any are
 * configured.
 */
@Configuration(proxyBeanMethods = false)
class AccessConfiguration {

	@Bean
	WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> allowedAddressesCustomizer(
			AccessProperties properties) {
		return (factory) -> {
			if (!properties.allowedAddresses().isEmpty()) {
				factory.addEngineValves(new AllowedAddressesValve(properties.allowedAddresses()));
			}
		};
	}

}
