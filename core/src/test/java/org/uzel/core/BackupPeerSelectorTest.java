package org.uzel.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class BackupPeerSelectorTest extends NetworkTest {
    @Test public void ringHasOneInitiatorPerEdgeAndNeverSelectsSelfOrExistingPeer() throws Exception {
        long now=System.currentTimeMillis();
        List<DiscoveryPresence> peers=new ArrayList<>();
        for(int i=0;i<4;i++)peers.add(new DiscoveryIdentity(keys.get(i)).presence("RU",0,1000,now));
        peers.sort(Comparator.comparing(p->p.fingerprint));
        assertEquals(peers.get(1).fingerprint,BackupPeerSelector.next(peers.get(0).fingerprint,peers,Collections.emptySet(),now).fingerprint);
        assertEquals(peers.get(3).fingerprint,BackupPeerSelector.next(peers.get(0).fingerprint,peers,Collections.singleton(peers.get(1).fingerprint),now).fingerprint);
        assertNull(BackupPeerSelector.next(peers.get(3).fingerprint,peers,Collections.emptySet(),now));
        assertNull(BackupPeerSelector.next(peers.get(0).fingerprint,peers,new HashSet<>(Arrays.asList(peers.get(1).fingerprint,peers.get(3).fingerprint)),now));
        assertNull(BackupPeerSelector.next(peers.get(0).fingerprint,peers,Collections.emptySet(),now+120001));
    }
    @Test public void failedAdjacentPeerDoesNotHideOtherBackup() throws Exception {
        long now=System.currentTimeMillis();List<DiscoveryPresence> peers=new ArrayList<>();
        for(int i=0;i<3;i++)peers.add(new DiscoveryIdentity(keys.get(i)).presence("RU",0,1000,now));
        peers.sort(Comparator.comparing(p->p.fingerprint));
        assertEquals(peers.get(2).fingerprint,BackupPeerSelector.next(peers.get(0).fingerprint,peers,Collections.emptySet(),now,Collections.singleton(peers.get(1).fingerprint)).fingerprint);
    }
}
