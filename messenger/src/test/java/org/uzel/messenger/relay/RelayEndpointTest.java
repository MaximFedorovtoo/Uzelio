package org.uzel.messenger.relay;

import org.junit.Test;

import static org.junit.Assert.*;

public class RelayEndpointTest {
    private static final String PIN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void roundTripsThroughInvitationText() {
        RelayEndpoint endpoint = RelayEndpoint.parse("relay.example.org:39284:" + PIN);
        assertEquals("relay.example.org", endpoint.host());
        assertEquals(39284, endpoint.port());
        assertEquals(PIN, endpoint.pin());
        assertEquals("relay.example.org:39284:" + PIN, endpoint.encode());
        assertEquals(endpoint, RelayEndpoint.parse(endpoint.encode()));
    }

    @Test public void rejectsMalformedOrIncompleteValues() {
        assertFalse(RelayEndpoint.isValid("relay.example.org:39284"));
        assertFalse(RelayEndpoint.isValid("relay.example.org:notaport:" + PIN));
        assertFalse(RelayEndpoint.isValid("relay.example.org:39284:short"));
        assertFalse(RelayEndpoint.isValid("relay example:39284:" + PIN));
        assertThrows(IllegalArgumentException.class, () -> RelayEndpoint.parse("relay.example.org:0:" + PIN));
    }

    @Test public void emptyValueMeansDirectOnly() {
        assertNull(RelayEndpoint.parse(""));
        assertTrue(RelayEndpoint.isValid(""));
        assertTrue(RelayEndpoint.isValid(null));
    }
}
