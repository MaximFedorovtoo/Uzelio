package org.uzel.desktop.messenger;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.uzel.messenger.SignalMessenger;
import org.uzel.messenger.chat.ChatRepository;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import java.util.*;
import java.lang.management.ManagementFactory;

/** Local comparison of existing Signal-only persistence with atomic chat metadata. No timing assertions. */
public class ChatStorageCostTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void reportLocalAtomicMetadataCost()throws Exception{
        try(var a=DesktopMessenger.open(tmp.newFolder().toPath());var b=DesktopMessenger.open(tmp.newFolder().toPath())){
            SignalMessenger alice=a.messenger(),bob=b.messenger();SignalProtocolAddress peer=bob.localAddress();var repo=a.chatRepository();
            alice.trustPeer(peer,bob.identity(),r->repo.addContact(r,new ChatRepository.Contact("peer","Peer","local-benchmark")));
            bob.trustPeer(alice.localAddress(),alice.identity());alice.establish(peer,bob.publishPreKeyBundle());
            byte[] payload="same local payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for(int i=0;i<4;i++){var packet=alice.send(peer,"warm"+i,payload);bob.receive(alice.localAddress(),packet.type(),packet.body());}
            long[][] wall={new long[20],new long[20]};long[] cpu=new long[2];var bean=ManagementFactory.getThreadMXBean();
            for(int i=0;i<20;i++)for(int variant=0;variant<2;variant++){
                String id="v"+variant+"_"+i;long c=bean.getCurrentThreadCpuTime(),start=System.nanoTime();
                if(variant==0)alice.send(peer,id,payload);else{var m=new ChatRepository.Message(id,"peer","same local payload",true,"queued",i);alice.send(peer,id,payload,(r,p)->repo.queueOutgoing(r,m,p));}
                wall[variant][i]=System.nanoTime()-start;cpu[variant]+=bean.getCurrentThreadCpuTime()-c;
            }
            for(int variant=0;variant<2;variant++){Arrays.sort(wall[variant]);long sum=Arrays.stream(wall[variant]).sum();System.out.printf(Locale.ROOT,"CHAT_STORAGE variant=%s n=20 median_ms=%.3f ops_s=%.1f thread_cpu_ms=%.3f%n",variant==0?"existing-signal":"atomic-chat",wall[variant][10]/1e6,20e9/sum,cpu[variant]/1e6);}
        }
    }
}
