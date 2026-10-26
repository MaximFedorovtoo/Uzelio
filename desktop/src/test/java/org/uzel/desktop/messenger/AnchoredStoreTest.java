package org.uzel.desktop.messenger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.uzel.messenger.persistence.AnchoredStore;
import java.nio.file.*;
import org.uzel.messenger.store.TransactionalStore;
import org.uzel.messenger.keys.SecretProtector;
import static org.junit.Assert.*;

public class AnchoredStoreTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void databaseOnlyRollbackAndMissingAnchorFailClosed()throws Exception{
        Path root=temp.newFolder().toPath(),db=root.resolve("db"),anchor=root.resolve("anchor"),old=root.resolve("old");byte[] key=new byte[32];var protector=new WindowsKeyProtector();
        try(var store=new AnchoredStore(new JdbcMessageStore(db,key),anchor,protector,true)) {store.write(r->{r.put("test","message",new byte[]{1});return null;});}
        Files.copy(db,old);
        try(var store=new AnchoredStore(new JdbcMessageStore(db,key),anchor,protector,false)){store.write(r->{r.put("test","message",new byte[]{2});return null;});}
        Files.copy(old,db,StandardCopyOption.REPLACE_EXISTING);
        assertThrows(SecurityException.class,()->new AnchoredStore(new JdbcMessageStore(db,key),anchor,protector,false));
        Files.delete(anchor);
        assertThrows(SecurityException.class,()->new AnchoredStore(new JdbcMessageStore(db,key),anchor,protector,false));
    }
    @Test public void callbackFailureRestoresAnchorAfterProvenRollback()throws Exception{
        Path root=temp.newFolder().toPath();byte[] key=new byte[32];var protector=new WindowsKeyProtector();
        try(var store=new AnchoredStore(new JdbcMessageStore(root.resolve("db"),key),root.resolve("anchor"),protector,true)){
            assertThrows(java.io.IOException.class,()->store.write(r->{r.put("message","one",new byte[]{1});throw new java.io.IOException("failure after mutation");}));
            assertNull(store.read(r->r.get("message","one")));
            store.write(r->{r.put("message","two",new byte[]{2});return null;});
        }
        try(var store=new AnchoredStore(new JdbcMessageStore(root.resolve("db"),key),root.resolve("anchor"),protector,false)){assertArrayEquals(new byte[]{2},store.read(r->r.get("message","two")));}
    }
    @Test public void actualProcessCrashPendingOldBlocksAndPendingNewReconciles()throws Exception{
        for(String mode:new String[]{"before","after"}){
            Path root=temp.newFolder(mode).toPath();
            try(var store=new AnchoredStore(new JdbcMessageStore(root.resolve("db"),new byte[32]),root.resolve("anchor"),new WindowsKeyProtector(),true)){}
            java.util.Set<String> cp=new java.util.LinkedHashSet<>();cp.add(System.getProperty("java.class.path"));
            for(Class<?> type:new Class<?>[]{getClass(),AnchoredStore.class,JdbcMessageStore.class,org.sqlite.JDBC.class,org.slf4j.Logger.class,com.sun.jna.Native.class,org.junit.Test.class})
                cp.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",String.join(java.io.File.pathSeparator,cp),getClass().getName(),mode,root.toString()).redirectErrorStream(true).start();
            if(!child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();fail("child timeout");}
            assertEquals(new String(child.getInputStream().readAllBytes()),73,child.exitValue());
            if(mode.equals("before"))assertThrows(SecurityException.class,()->new AnchoredStore(new JdbcMessageStore(root.resolve("db"),new byte[32]),root.resolve("anchor"),new WindowsKeyProtector(),false));
            else try(var store=new AnchoredStore(new JdbcMessageStore(root.resolve("db"),new byte[32]),root.resolve("anchor"),new WindowsKeyProtector(),false)){assertArrayEquals(new byte[]{3},store.read(r->r.get("message","crash")));store.write(r->{r.put("message","next",new byte[]{4});return null;});}
        }
    }
    @Test public void failedFinalAnchorReturnsNoResultButCommittedDataRecovers()throws Exception{
        Path root=temp.newFolder().toPath();var real=new WindowsKeyProtector();boolean[] fail={false};
        SecretProtector faulty=new SecretProtector(){public byte[] protect(byte[] b)throws Exception{if(fail[0]&&b[4]==0)throw new java.io.IOException("final anchor write failed");return real.protect(b);}public byte[] unprotect(byte[] b)throws Exception{return real.unprotect(b);}};
        try(var store=new AnchoredStore(new JdbcMessageStore(root.resolve("db"),new byte[32]),root.resolve("anchor"),faulty,true)){
            fail[0]=true;assertThrows(java.io.IOException.class,()->store.write(r->{r.put("message","committed",new byte[]{1});return "ACK must not escape";}));
            assertThrows(IllegalStateException.class,()->store.read(r->r.get("message","committed")));
        }
        try(var store=new AnchoredStore(new JdbcMessageStore(root.resolve("db"),new byte[32]),root.resolve("anchor"),real,false)){assertArrayEquals(new byte[]{1},store.read(r->r.get("message","committed")));}
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[1]);var db=new JdbcMessageStore(root.resolve("db"),new byte[32]);
        TransactionalStore wrapper=new TransactionalStore(){
            public <T>T read(Work<T>w)throws Exception{return db.read(w);}
            public <T>T write(Work<T>w)throws Exception{T result=db.write(r->{T value=w.run(r);if(args[0].equals("before"))Runtime.getRuntime().halt(73);return value;});Runtime.getRuntime().halt(73);return result;}
            public void close()throws Exception{db.close();}
        };
        try(var store=new AnchoredStore(wrapper,root.resolve("anchor"),new WindowsKeyProtector(),false)){store.write(r->{r.put("message","crash",new byte[]{3});return null;});}
    }
}
