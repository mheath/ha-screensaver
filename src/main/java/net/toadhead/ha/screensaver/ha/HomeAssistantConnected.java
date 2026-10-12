package net.toadhead.ha.screensaver.ha;

/**
 * Published each time a connection to Home Assistant becomes ready: authenticated
 * and with every state loaded. Pollers use it to fetch right after (re)connecting.
 */
public record HomeAssistantConnected() {

}
