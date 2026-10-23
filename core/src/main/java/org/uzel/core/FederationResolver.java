package org.uzel.core;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A process-wide instance bounds native DNS work even when the platform resolver ignores interrupts. */
public final class FederationResolver implements AutoCloseable {
 private static final class PublicResolver {private static final FederationResolver INSTANCE=new FederationResolver();}
 /** Resolve a public endpoint with bounded shared DNS workers and an absolute wall-clock deadline. */
 public static InetSocketAddress resolvePublic(FederationContact contact,long deadline)throws IOException {
  return PublicResolver.INSTANCE.resolve(contact,false,deadline);
 }
 private final ThreadPoolExecutor workers;
 private final Function<String,InetAddress[]> lookup;
 FederationResolver() {this(host->{try{return InetAddress.getAllByName(host);}catch(UnknownHostException e){throw new UncheckedIOException(e);}});}
 FederationResolver(Function<String,InetAddress[]> lookup) {
  this.lookup=lookup;AtomicInteger number=new AtomicInteger();
  workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"peernet-dns-"+number.incrementAndGet());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
 }
 InetSocketAddress resolve(FederationContact contact,boolean allowPrivate,long deadline)throws IOException {
  long remaining=deadline-System.currentTimeMillis();if(remaining<=0)throw new SocketTimeoutException("DNS deadline");Future<InetAddress[]> task;
  try{task=workers.submit(()->lookup.apply(contact.host));}catch(RejectedExecutionException e){throw new IOException("DNS resolver capacity",e);}
  try {
   InetAddress[] addresses=task.get(remaining,TimeUnit.MILLISECONDS);if(System.currentTimeMillis()>=deadline)throw new SocketTimeoutException("DNS deadline");
   return contact.address(addresses,allowPrivate);
  }catch(TimeoutException e){throw new SocketTimeoutException("DNS deadline");}
  catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("DNS interrupted");}
  catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof UncheckedIOException)throw ((UncheckedIOException)cause).getCause();throw new IOException("DNS lookup failed",cause);}
  finally{task.cancel(true);workers.remove((Runnable)task);}
 }
 @Override public void close(){List<Runnable> pending=workers.shutdownNow();for(Runnable task:pending)if(task instanceof Future<?>)((Future<?>)task).cancel(true);}
}
