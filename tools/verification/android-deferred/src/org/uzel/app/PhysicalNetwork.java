package org.uzel.app;
import java.io.*;import java.net.*;
final class PhysicalNetwork extends org.uzel.core.NetworkAccess {
 final Object network=new Object();byte[] response;
 HttpURLConnection http(String s)throws IOException{
  if(response==null)throw new IOException("fixture offline");final byte[] body=response;
  return new HttpURLConnection(new URL(s)){
   public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
   public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
   public int getResponseCode(){return 200;}public long getContentLengthLong(){return body.length;}
   public InputStream getInputStream(){return new ByteArrayInputStream(body);}
  };
 }
}
