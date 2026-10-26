package org.uzel.desktop.messenger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;
public final class MessengerRuntimeTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void productFactoryProtectsKeysAndKeepsIdentityAcrossRestart()throws Exception{
        Path root=tmp.newFolder().toPath();byte[] identity;
        try(var runtime=DesktopMessenger.open(root)){
            identity=runtime.messenger().identity().serialize();
            assertThrows(Exception.class,()->DesktopMessenger.open(root));
        }
        try(var runtime=DesktopMessenger.open(root)){assertArrayEquals(identity,runtime.messenger().identity().serialize());}
        Files.delete(root.resolve("messenger/messenger-db-key.v1"));
        assertThrows(SecurityException.class,()->DesktopMessenger.open(root));
        assertFalse(Files.exists(root.resolve("messenger/messenger-db-key.v1")));
    }
}
