package org.uzel.app;

import android.content.Context;
import android.test.InstrumentationTestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Joins the network programmatically: starts sharing, waits for a discovered peer and
 * connects to it as a node, then reports the resulting network state.
 */
public final class ConnectProbeTest extends InstrumentationTestCase {
    public void testConnectToDiscoveredPeer() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Path ext = context.getExternalFilesDir(null).toPath();
        Path result = ext.resolve("connect-result.txt");
        Files.deleteIfExists(result);
        AppSelection.saveAllApplications(context, true);
        NodeController node = NodeController.get(context);

        StringBuilder out = new StringBuilder();
        if (!node.snapshot().sharing) node.startSharing();
        await(() -> node.snapshot().sharing, 30, "sharing");
        await(() -> !node.snapshot().peers.isEmpty(), 90, "a discovered peer");
        for (NodeController.Peer peer : node.snapshot().peers) {
            out.append("peer id=").append(peer.id).append(" name=").append(peer.name)
                    .append(" host=").append(peer.host).append(" reachable=").append(peer.reachable)
                    .append(" paired=").append(peer.paired).append(" auto=").append(peer.autoDiscovered)
                    .append('\n');
        }
        NodeController.Peer target = null;
        for (NodeController.Peer peer : node.snapshot().peers) if (peer.reachable || !peer.host.isEmpty()) { target = peer; break; }
        if (target == null) target = node.snapshot().peers.get(0);
        out.append("connecting to ").append(target.id).append(" (").append(target.name).append(")\n");

        final NodeController.Peer chosen = target;
        node.connect(chosen.id);
        boolean connected = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            NodeController.Snapshot now = node.snapshot();
            if (now.vpnConnected) { connected = true; break; }
            if ("ERROR".equals(now.status) || !now.errorDetail.isEmpty()) break;
            Thread.sleep(200);
        }
        NodeController.Snapshot snapshot = node.snapshot();
        out.append("connected=").append(connected)
                .append("\nvpnConnected=").append(snapshot.vpnConnected)
                .append("\nstatus=").append(snapshot.status)
                .append("\nerrorDetail=").append(snapshot.errorDetail)
                .append("\npairingState=").append(snapshot.pairingState)
                .append("\nadvertisedHost=").append(snapshot.advertisedHost)
                .append("\nnetworkDetail=").append(snapshot.networkDetail)
                .append("\npublicIp=").append(snapshot.publicIp)
                .append("\ncountry=").append(snapshot.country)
                .append("\ndiscoveryState=").append(snapshot.discoveryState)
                .append("\nconnectedPeer=").append(snapshot.connectedCountry);
        Files.write(result, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private interface Condition { boolean get() throws Exception; }

    private static void await(Condition condition, int seconds, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for " + what);
            Thread.sleep(200);
        }
    }
}
