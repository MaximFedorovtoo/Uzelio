package org.uzel.app;

import android.content.Context;
import android.test.InstrumentationTestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Waits for the automatic mesh session without touching the UI. */
public final class AutoMeshProbeTest extends InstrumentationTestCase {
    public void testMeshConnectsWithoutATap() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Path ext = context.getExternalFilesDir(null).toPath();
        Path result = ext.resolve("mesh-result.txt");
        Files.deleteIfExists(result);
        NodeController node = NodeController.get(context);
        StringBuilder out = new StringBuilder();
        out.append("sharing=").append(node.snapshot().sharing).append('\n');
        boolean connected = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
        while (System.nanoTime() < deadline) {
            NodeController.Snapshot snapshot = node.snapshot();
            out.append("state ").append(snapshot.status).append(" vpn=").append(snapshot.vpnConnected)
                    .append(" peers=").append(snapshot.peers.size())
                    .append(" discovery=").append(snapshot.discoveryState).append('\n');
            if (snapshot.vpnConnected) { connected = true; break; }
            Thread.sleep(5000);
        }
        NodeController.Snapshot finalState = node.snapshot();
        out.append("connected=").append(connected)
                .append("\nfinalStatus=").append(finalState.status)
                .append("\nnetworkDetail=").append(finalState.networkDetail)
                .append("\npublicIp=").append(finalState.publicIp)
                .append("\npeerCount=").append(finalState.peers.size());
        Files.write(result, out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
