package org.uzel.messenger.relay;

import org.uzel.core.NetworkAccess;
import org.uzel.core.TlsIdentity;
import org.uzel.messenger.SignalMessenger;
import org.uzel.messenger.chat.ChatWire;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One persistent inbox at the relay plus one short TLS exchange per outgoing frame.
 * Reconnects with bounded backoff; never stores or logs payload.
 */
public final class RelayClient implements AutoCloseable {
    @FunctionalInterface
    public interface Receiver {
        SignalMessenger.Outbound receive(String fromName, SignalMessenger.Outbound frame) throws Exception;
    }

    private static final long CONNECT_TIMEOUT = 10000, IDLE_TIMEOUT = 45000;

    private final RelayEndpoint endpoint;
    private final String ownName;
    private final TlsIdentity identity;
    private final Receiver receiver;
    private final NetworkAccess network;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread inbox;
    private volatile Socket inboxSocket;

    public RelayClient(RelayEndpoint endpoint, String ownName, TlsIdentity identity, Receiver receiver, NetworkAccess network) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.ownName = Objects.requireNonNull(ownName);
        this.identity = Objects.requireNonNull(identity);
        this.receiver = Objects.requireNonNull(receiver);
        this.network = network == null ? new NetworkAccess() : network;
        inbox = new Thread(this::runInbox, "uzelio-relay-inbox");
        inbox.setDaemon(true);
    }

    public void start() {
        inbox.start();
    }

    public RelayEndpoint endpoint() {
        return endpoint;
    }

    /** Sends one opaque frame; returns normally only after the relay accepted it for delivery. */
    public void send(String to, SignalMessenger.Outbound frame) throws Exception {
        if (closed.get()) throw new IOException("Relay client is closed");
        byte[] payload = encode(frame);
        try (Socket raw = network.open(endpoint.host(), endpoint.port())) {
            raw.setSoTimeout((int) CONNECT_TIMEOUT);
            try (SSLSocket tls = (SSLSocket) identity.context(endpoint.pin()).getSocketFactory()
                    .createSocket(raw, endpoint.host(), endpoint.port(), true)) {
                tls.setSoTimeout((int) CONNECT_TIMEOUT);
                tls.startHandshake();
                RelayWire.write(tls.getOutputStream(), new RelayWire.Msg(RelayWire.SEND, ownName, to, payload));
                RelayWire.Msg answer = RelayWire.read(tls.getInputStream());
                if (answer.kind() == RelayWire.OK) return;
                if (answer.kind() == RelayWire.UNREACHABLE) throw new IOException("Recipient is not connected to the relay");
                throw new IOException("Relay rejected the frame");
            }
        }
    }

    private void runInbox() {
        long backoff = 1000;
        while (!closed.get()) {
            try (Socket raw = network.open(endpoint.host(), endpoint.port())) {
                raw.setSoTimeout((int) IDLE_TIMEOUT);
                try (SSLSocket tls = (SSLSocket) identity.context(endpoint.pin()).getSocketFactory()
                        .createSocket(raw, endpoint.host(), endpoint.port(), true)) {
                    tls.setSoTimeout((int) IDLE_TIMEOUT);
                    tls.startHandshake();
                    inboxSocket = tls;
                    RelayWire.write(tls.getOutputStream(), new RelayWire.Msg(RelayWire.REGISTER, ownName, "", null));
                    RelayWire.Msg registered = RelayWire.read(tls.getInputStream());
                    if (registered.kind() != RelayWire.REGISTERED) throw new IOException("Relay registration rejected");
                    backoff = 1000;
                    while (!closed.get()) {
                        RelayWire.Msg message = RelayWire.read(tls.getInputStream());
                        if (message.kind() != RelayWire.INCOMING) continue;
                        deliver(message.from(), message.payload());
                    }
                }
            } catch (Exception failure) {
                if (closed.get()) return;
                sleep(backoff);
                backoff = Math.min(30000, backoff * 2);
            } finally {
                inboxSocket = null;
            }
        }
    }

    private void deliver(String from, byte[] payload) {
        try {
            SignalMessenger.Outbound reply = receiver.receive(from, decode(payload));
            if (reply != null) send(from, reply);
        } catch (Exception undeliverable) {
            // Undecryptable or unknown sender: drop without pretending the frame was accepted.
        }
    }

    static byte[] encode(SignalMessenger.Outbound frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ChatWire.write(bytes, frame);
        return bytes.toByteArray();
    }

    static SignalMessenger.Outbound decode(byte[] payload) throws IOException {
        return ChatWire.read(new ByteArrayInputStream(payload));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Socket socket = inboxSocket;
        if (socket != null) try {
            socket.close();
        } catch (IOException ignored) {
        }
        inbox.interrupt();
    }

    static Socket connect(NetworkAccess network, String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT);
        return socket;
    }
}
