package org.uzel.desktop;

import org.uzel.core.FederationContact;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Only signed, verified contact hints survive restart. Presence and encrypted SDP remain soft state. */
final class DesktopDiscoveryContacts {
    private static final int MAX_BYTES=400_000,MAX_CONTACTS=8;
    private final Path path;
    private byte[] lastSaved;
    DesktopDiscoveryContacts(Path path){this.path=Objects.requireNonNull(path);}
    List<FederationContact> load()throws IOException {
        if(!Files.exists(path))return List.of();if(Files.size(path)>MAX_BYTES)throw new IOException("Discovery contacts size");
        byte[] bytes=Files.readAllBytes(path);DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes));
        if(input.readInt()!=0x55444331)throw new IOException("Discovery contacts version");int count=input.readInt();if(count<0 || count>MAX_CONTACTS)throw new IOException("Discovery contacts count");
        List<FederationContact> contacts=new ArrayList<>();
        for(int i=0;i<count;i++){String code=input.readUTF();try{contacts.add(FederationContact.parse(code));}catch(IOException expiredOrInvalid){/* A bad hint never prevents using valid remaining hints. */}}
        if(input.read()!=-1)throw new IOException("Discovery contacts trailing bytes");lastSaved=bytes;return contacts;
    }
    void save(List<FederationContact> contacts)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream output=new DataOutputStream(bytes);output.writeInt(0x55444331);
        List<FederationContact> checked=new ArrayList<>();for(FederationContact contact:contacts){if(checked.size()>=MAX_CONTACTS)break;checked.add(FederationContact.parse(contact.encode()));}
        output.writeInt(checked.size());for(FederationContact contact:checked)output.writeUTF(contact.encode());byte[] encoded=bytes.toByteArray();
        if(encoded.length>MAX_BYTES)throw new IOException("Discovery contacts size");if(Arrays.equals(lastSaved,encoded))return;
        Files.createDirectories(path.toAbsolutePath().getParent());Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        Files.write(temporary,encoded);try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}lastSaved=encoded;
    }
}
