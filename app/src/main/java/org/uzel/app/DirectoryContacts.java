package org.uzel.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import org.uzel.core.FederationContact;

/** Public signed bootstrap hints only. Does not store any participant private key. */
final class DirectoryContacts {
    private final SharedPreferences preferences;
    private final Map<String,FederationContact> entries=new LinkedHashMap<>();
    DirectoryContacts(Context context){
        preferences=context.getSharedPreferences("directory_contacts",Context.MODE_PRIVATE);
        for(String code:new HashSet<>(preferences.getStringSet("codes",Collections.emptySet()))) {
            try{accept(FederationContact.parse(code));}catch(IOException ignored){}
        }
    }
    synchronized void add(String code)throws IOException {
        FederationContact contact=FederationContact.parse(code.trim());
        prune();
        if(!entries.containsKey(contact.fingerprint)&&entries.size()>=8)throw new IOException("Contact list full");
        accept(contact);for(FederationContact backup:contact.backups)accept(backup);save();
    }
    synchronized void merge(List<FederationContact> contacts){
        boolean changed=prune();
        for(FederationContact contact:contacts){changed|=accept(contact);for(FederationContact backup:contact.backups)changed|=accept(backup);}
        if(changed)save();
    }
    private boolean accept(FederationContact contact){
        if(contact.expiresAt<=System.currentTimeMillis())return false;
        FederationContact old=entries.get(contact.fingerprint);
        if(old!=null&&old.issuedAt>=contact.issuedAt||old==null&&entries.size()>=8)return false;
        entries.put(contact.fingerprint,contact);return true;
    }
    private boolean prune(){return entries.values().removeIf(c->c.expiresAt<=System.currentTimeMillis());}
    synchronized List<FederationContact> list(){if(prune())save();return new ArrayList<>(entries.values());}
    synchronized void clear(){entries.clear();save();}
    boolean allowPrivate(){return preferences.getBoolean("allowPrivate",false);}
    void setAllowPrivate(boolean enabled){preferences.edit().putBoolean("allowPrivate",enabled).apply();}
    private void save(){Set<String> codes=new HashSet<>();for(FederationContact contact:entries.values())codes.add(contact.encode());preferences.edit().putStringSet("codes",codes).apply();}
}
