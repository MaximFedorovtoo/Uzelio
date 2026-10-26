package org.uzel.messenger.relay;

import org.uzel.core.TlsIdentity;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Storage-less relay: forwards opaque Signal frames to a connected device and never writes
 * payload to disk. A frame exists only inside the forwarding call; if the recipient is not
 * connected, the sender gets UNREACHABLE and keeps its own outbox.
 */
public final class RelayServer implements AutoCloseable {
    private static final int MAX_SESSIONS = 512, MAX_CONNECTIONS = 16;

    private final TlsIdentity identity;
    private final SSLContext context;
    private final ServerSocket listener;
    private final Map<String, Session> inboxes = new ConcurrentHashMap<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, MAX_CONNECTIONS, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "uzelio-relay");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean closed = new AtomicBoolean();

    public RelayServer(TlsIdentity identity, InetAddress bind, int port) throws Exception {
        this.identity = Objects.requireNonNull(identity);
        this.context = identity.context(null);
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(bind, port), 16);
        } catch (Exception failure) {
            listener.close();
            workers.shutdownNow();
            throw failure;
        }
        Thread accept = new Thread(this::accept, "uzelio-relay-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public int port() {
        return listener.getLocalPort();
    }

    public String pin() {
        return identity.fingerprint;
    }

    private void accept() {
        while (!closed.get()) {
            try {
                Socket raw = listener.accept();
                raw.setSoTimeout(30000);
                raw.setTcpNoDelay(true);
                sockets.add(raw);
                try {
                    workers.execute(() -> serve(raw));
                } catch (RejectedExecutionException full) {
                    closeRaw(raw);
                }
            } catch (IOException failure) {
                if (!closed.get()) close();
            }
        }
    }

    private void serve(Socket raw) {
        SSLSocket socket = null;
        try {
            socket = (SSLSocket) context.getSocketFactory().createSocket(raw, raw.getInetAddress().getHostAddress(), raw.getPort(), true);
            socket.setUseClientMode(false);
            socket.setNeedClientAuth(true);
            socket.setSoTimeout(30000);
            socket.startHandshake();
            RelayWire.Msg first = RelayWire.read(socket.getInputStream());
            if (first.kind() == RelayWire.REGISTER) {
                ownInbox(socket, first.from());
            } else if (first.kind() == RelayWire.SEND) {
                forward(socket, first);
            } else {
                RelayWire.write(socket.getOutputStream(), new RelayWire.Msg(RelayWire.REJECTED, "", "", null));
            }
        } catch (Exception ignored) {
            // Fail closed: no payload, identity or routing detail is logged.
        } finally {
            if (socket != null) closeRaw(socket);
            closeRaw(raw);
        }
    }

    /** A registered device keeps one connection open; EOF removes it from the routing table. */
    private void ownInbox(SSLSocket socket, String name) throws IOException {
        if (!RelayWire.validName(name) || inboxes.size() >= MAX_SESSIONS) {
            RelayWire.write(socket.getOutputStream(), new RelayWire.Msg(RelayWire.REJECTED, "", "", null));
            return;
        }
        Session session = new Session(name, socket);
        Session previous = inboxes.put(name, session);
        if (previous != null) previous.close();
        RelayWire.write(socket.getOutputStream(), new RelayWire.Msg(RelayWire.REGISTERED, name, "", null));
        // The client sends nothing on its inbox; blocking here detects disconnect.
        while (!closed.get() && !socket.isClosed() && socket.getInputStream().read() >= 0) { /* drain */ }
        inboxes.remove(name, session);
    }

    private void forward(SSLSocket sender, RelayWire.Msg message) throws IOException {
        String to = message.to();
        Session target = to == null ? null : inboxes.get(to);
        if (target == null || !target.deliver(message.from(), message.payload())) {
            RelayWire.write(sender.getOutputStream(), new RelayWire.Msg(RelayWire.UNREACHABLE, "", to, null));
            return;
        }
        RelayWire.write(sender.getOutputStream(), new RelayWire.Msg(RelayWire.OK, "", to, null));
    }

    private void closeRaw(Socket socket) {
        sockets.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        inboxes.values().forEach(Session::close);
        inboxes.clear();
        for (Socket socket : sockets) closeRaw(socket);
        workers.shutdownNow();
    }

    private final class Session {
        private final String name;
        private final SSLSocket socket;
        private final Object lock = new Object();

        Session(String name, SSLSocket socket) {
            this.name = name;
            this.socket = socket;
        }

        boolean deliver(String from, byte[] payload) {
            if (closed.get() || socket.isClosed()) return false;
            synchronized (lock) {
                try {
                    RelayWire.write(socket.getOutputStream(), new RelayWire.Msg(RelayWire.INCOMING, from, name, payload));
                    return true;
                } catch (IOException dead) {
                    close();
                    inboxes.remove(name, this);
                    return false;
                }
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
