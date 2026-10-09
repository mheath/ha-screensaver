package net.toadhead.ha.screensaver.ha;

/**
 * A Home Assistant WebSocket request that failed or could not complete.
 */
public class HaRequestException extends RuntimeException {

	public HaRequestException(String message) {
		super(message);
	}

}
