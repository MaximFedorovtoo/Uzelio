package org.uzel.messenger.persistence;

import org.uzel.messenger.keys.*;
import org.uzel.messenger.store.TransactionalStore;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;

/** Detects older consistent DB snapshots. Not a page-level commitment or hardware counter. */
public final class AnchoredStore implements TransactionalStore {
    private static final String SPACE="_anchor",KEY="token";
    private static final int MAGIC=0x555A4131;
    private final TransactionalStore delegate;
    private final Path file;
    private final SecretProtector protector;
    private boolean closed;
    public AnchoredStore(TransactionalStore delegate,Path file,SecretProtector protector,boolean fresh)throws Exception{
        this.delegate=Objects.requireNonNull(delegate);this.file=file;this.protector=protector;
        try{
            byte[] token=token();
            if(fresh){
                if(Files.exists(file)||token!=null)throw new SecurityException("Profile initialization is inconsistent");
                byte[] first=random();save(new Anchor(true,new byte[32],first));
                delegate.write(r->{r.put(SPACE,KEY,first);return null;});save(new Anchor(false,first,first));
            }else{
                Anchor a=load();
                if(a.pending){
                    if(!Arrays.equals(token,a.next))throw new SecurityException("Interrupted commit or database rollback requires recovery");
                    save(new Anchor(false,a.next,a.next));
                }else if(!Arrays.equals(token,a.old))throw new SecurityException("Database rollback detected");
            }
        }catch(Exception|Error e){closed=true;try{delegate.close();}catch(Exception suppressed){e.addSuppressed(suppressed);}throw e;}
    }
    private byte[] token()throws Exception{return delegate.read(r->r.get(SPACE,KEY));}
    private byte[] verified()throws Exception{
        if(closed)throw new IllegalStateException("Messenger profile is closed");
        Anchor a=load();byte[] token=token();
        if(a.pending||!Arrays.equals(token,a.old))throw new SecurityException("Messenger anchor mismatch; recovery required");
        return token;
    }
    @Override public synchronized <T>T read(Work<T> work)throws Exception{
        verified();return delegate.read(r->work.run(new PublicRecords(r)));
    }
    @Override public synchronized <T>T write(Work<T> work)throws Exception{
        byte[] old=verified(),next=random();
        save(new Anchor(true,old,next));
        T result;
        try{
            result=delegate.write(r->{
                if(!Arrays.equals(r.get(SPACE,KEY),old))throw new SecurityException("Concurrent profile mutation");
                T value=work.run(new PublicRecords(r));r.put(SPACE,KEY,next);return value;
            });
        }catch(Exception|Error failure){
            // A backend with an uncertain commit is closed and cannot pass this proof.
            try{if(!Arrays.equals(token(),old))throw new SecurityException("Uncertain commit");save(new Anchor(false,old,old));}
            catch(Exception|Error uncertain){failure.addSuppressed(uncertain);closed=true;try{delegate.close();}catch(Exception e){failure.addSuppressed(e);}}
            throw failure;
        }
        try{save(new Anchor(false,next,next));}
        catch(Exception|Error failure){closed=true;try{delegate.close();}catch(Exception e){failure.addSuppressed(e);}throw failure;}
        return result; // ACK/ciphertext cannot escape before both persistence operations complete.
    }
    @Override public synchronized void close()throws Exception{closed=true;delegate.close();}
    private static byte[] random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return b;}
    private record Anchor(boolean pending,byte[] old,byte[] next){}
    private Anchor load()throws Exception{
        if(!Files.exists(file))throw new SecurityException("Missing messenger anchor");
        byte[] plain=protector.unprotect(ProtectedFile.readBounded(file,65536));
        try{if(plain.length!=69)throw new SecurityException("Invalid anchor size");ByteBuffer b=ByteBuffer.wrap(plain);if(b.getInt()!=MAGIC)throw new SecurityException("Invalid anchor domain");byte phase=b.get();if(phase!=0&&phase!=1)throw new SecurityException("Invalid anchor phase");byte[] old=new byte[32],next=new byte[32];b.get(old);b.get(next);if(phase==0&&!Arrays.equals(old,next))throw new SecurityException("Invalid clean anchor");return new Anchor(phase==1,old,next);}
        finally{Arrays.fill(plain,(byte)0);}
    }
    private void save(Anchor a)throws Exception{
        byte[] plain=ByteBuffer.allocate(69).putInt(MAGIC).put((byte)(a.pending?1:0)).put(a.old).put(a.next).array();
        try{ProtectedFile.atomicWrite(file,protector.protect(plain));}finally{Arrays.fill(plain,(byte)0);}
    }
    private record PublicRecords(Records raw) implements Records {
        private static void allowed(String space){if(SPACE.equals(space))throw new SecurityException("Reserved anchor namespace");}
        public byte[] get(String s,String k)throws Exception{allowed(s);return raw.get(s,k);}
        public void put(String s,String k,byte[] v)throws Exception{allowed(s);raw.put(s,k,v);}
        public void remove(String s,String k)throws Exception{allowed(s);raw.remove(s,k);}
        public Map<String,byte[]> list(String s)throws Exception{allowed(s);return raw.list(s);}
    }
}
