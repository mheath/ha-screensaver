package net.toadhead.ha.screensaver.config;

import java.net.InetAddress;
import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import org.apache.catalina.util.NetMask;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Which hosts may connect, bound from the {@code screensaver.access} prefix.
 *
 * @param allowedAddresses IP addresses (for example {@code 192.168.1.10}) or CIDR ranges
 * (for example {@code 192.168.1.0/24}) that may connect; empty allows everyone
 */
@ConfigurationProperties("screensaver.access")
@Validated
public record AccessProperties(List<String> allowedAddresses) {

	public AccessProperties {
		allowedAddresses = (allowedAddresses != null) ? List.copyOf(allowedAddresses) : List.of();
	}

	@AssertTrue(message = "allowed-addresses must be IP addresses or CIDR ranges, not host names")
	public boolean isAllowedAddressesValid() {
		return this.allowedAddresses.stream().allMatch(AccessProperties::isAddressOrRange);
	}

	private static boolean isAddressOrRange(String entry) {
		try {
			// NetMask would resolve a host name through DNS; insist on a literal first.
			InetAddress.ofLiteral(entry.split("/", 2)[0]);
			new NetMask(entry);
			return true;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

}
