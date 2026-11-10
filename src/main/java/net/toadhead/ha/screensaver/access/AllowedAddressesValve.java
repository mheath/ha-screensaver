package net.toadhead.ha.screensaver.access;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.NetMask;
import org.apache.catalina.valves.ValveBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses requests whose TCP peer is not on the allow list, so only the reverse proxy
 * can reach the app. Refused requests get a 403 that says why, and the connection is
 * closed.
 * <p>
 * Checks the socket's peer address rather than the remote address, which
 * {@code X-Forwarded-For} handling may have replaced with the client behind the proxy.
 */
public class AllowedAddressesValve extends ValveBase {

	private static final Logger logger = LoggerFactory.getLogger(AllowedAddressesValve.class);

	private final List<NetMask> allowed;

	public AllowedAddressesValve(List<String> allowedAddresses) {
		// The kiosks' event stream is asynchronous; a valve that isn't would break it.
		super(true);
		this.allowed = allowedAddresses.stream().map(NetMask::new).toList();
	}

	@Override
	public void invoke(Request request, Response response) throws IOException, ServletException {
		String peer = request.getPeerAddr();
		if (isAllowed(peer)) {
			getNext().invoke(request, response);
			return;
		}
		logger.warn("Refused {} {} from {}: not in screensaver.access.allowed-addresses", request.getMethod(),
				request.getRequestURI(), peer);
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType("text/plain;charset=UTF-8");
		response.setHeader("Connection", "close");
		response.getWriter()
			.print("403 Forbidden: connections from " + peer + " are not allowed.\n");
	}

	private boolean isAllowed(String peer) {
		InetAddress address;
		try {
			address = InetAddress.ofLiteral(peer);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
		return this.allowed.stream().anyMatch((mask) -> mask.matches(address));
	}

}
