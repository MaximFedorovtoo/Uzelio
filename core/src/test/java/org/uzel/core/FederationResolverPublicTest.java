package org.uzel.core;

import java.io.IOException;

import java.net.SocketTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class FederationResolverPublicTest {
    private void resolvePublic(FederationContact contact,long deadline)throws IOException {
        FederationResolver.resolvePublic(contact,deadline);
    }
    @Test public void publicFacadeRejectsPrivateAnswers()throws Exception {
        long now=System.currentTimeMillis();FederationContact contact=FederationContact.create(FederationTestIdentities.create(),"127.0.0.1",9443,now);
        try{resolvePublic(contact,now+2000);fail("Private destination accepted");}catch(IOException expected){assertEquals("Contact address is not allowed",expected.getMessage());}
    }
    @Test public void publicFacadeHonorsAbsoluteExpiredDeadline()throws Exception {
        long now=System.currentTimeMillis();FederationContact contact=FederationContact.create(FederationTestIdentities.create(),"127.0.0.1",9443,now);
        try{resolvePublic(contact,now-1);fail("Expired deadline accepted");}catch(SocketTimeoutException expected){assertEquals("DNS deadline",expected.getMessage());}
    }
}
