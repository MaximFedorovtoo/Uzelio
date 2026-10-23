package org.uzel.core;

import java.util.*;

/** Two adjacent discovery links; only the lower fingerprint initiates an edge. */
public final class BackupPeerSelector {
    private BackupPeerSelector() {}
    public static DiscoveryPresence next(String own,List<DiscoveryPresence> catalog,Set<String> connected,long now) {
        return next(own,catalog,connected,now,Collections.emptySet());
    }
    public static DiscoveryPresence next(String own,List<DiscoveryPresence> catalog,Set<String> connected,long now,Set<String> unavailable) {
        if(connected.size()>=2)return null;
        SortedMap<String,DiscoveryPresence> live=new TreeMap<>();
        for(DiscoveryPresence peer:catalog)if(peer.expiresAt>now && peer.upload>0)live.put(peer.fingerprint,peer);
        live.put(own,null);
        List<String> ids=new ArrayList<>(live.keySet());
        if(ids.size()<2)return null;
        int self=ids.indexOf(own);
        String[] adjacent={ids.get((self+1)%ids.size()),ids.get((self+ids.size()-1)%ids.size())};
        for(String id:adjacent){
            DiscoveryPresence peer=live.get(id);
            if(id.compareTo(own)>0 && !connected.contains(id) && !unavailable.contains(id) && peer.clients<3)return peer;
        }
        return null;
    }
}
