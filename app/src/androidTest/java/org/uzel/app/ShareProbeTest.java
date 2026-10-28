package org.uzel.app;

import android.content.Context;
import android.test.InstrumentationTestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Enables network sharing programmatically so the device becomes a participant of the
 * Uzelio network without any taps. The VPN consent must already be granted
 * (adb shell appops set org.uzel.app ACTIVATE_VPN allow).
 */
public final class ShareProbeTest extends InstrumentationTestCase {
    public void testStartSharingAndJoinTheCatalog() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Path ext = context.getExternalFilesDir(null).toPath();
        Path result = ext.resolve("share-result.txt");
        Files.deleteIfExists(result);
        NodeController node = NodeController.get(context);
        if (!node.snapshot().sharing) node.startSharing();
        boolean sharing = false;
        for (int i = 0; i < 300 && !sharing; i++) {
            sharing = node.snapshot().sharing;
            if (!sharing) Thread.sleep(100);
        }
        Thread.sleep(20_000);
        NodeController.Snapshot snapshot = node.snapshot();
        String text = "sharing=" + sharing
                + "\nsnapshotSharing=" + snapshot.sharing
                + "\ndiscoveryState=" + snapshot.discoveryState
                + "\ndiscoveryUrl=" + snapshot.discoveryURL
                + "\ndirectoryContacts=" + snapshot.directoryContacts
                + "\nsharedBytes=" + snapshot.sharedBytes
                + "\nreceivedBytes=" + snapshot.receivedBytes
                + "\npeers=" + snapshot.peers.size();
        Files.write(result, text.getBytes(StandardCharsets.UTF_8));
    }
}
