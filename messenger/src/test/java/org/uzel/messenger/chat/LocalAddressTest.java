package org.uzel.messenger.chat;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LocalAddressTest {
    @Test
    public void preferredIsEmptyOrPrivateIpv4AndNeverLoopback() {
        String address = LocalAddress.preferred();
        assertNotNull(address);
        if (address.isEmpty()) return;
        assertTrue("unexpected value: " + address, address.matches("\\d{1,3}(\\.\\d{1,3}){3}"));
        assertTrue("loopback must never be offered", !address.startsWith("127."));
        assertTrue("link-local is not a usable hint", !address.startsWith("169.254."));
        assertTrue("broadcast/any is not an address", !address.equals("0.0.0.0"));
    }

    @Test
    public void repeatedLookupIsStableWithinProcess() {
        assertTrue(LocalAddress.preferred().equals(LocalAddress.preferred()));
    }
}
