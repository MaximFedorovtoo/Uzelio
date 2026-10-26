package org.uzel.messenger.relay;

import java.io.*;

/** Bounded binary framing for the storage-less relay. Payload is always Signal ciphertext. */
final class RelayWire {
    static final int MAGIC = 0x55524c31;
    static final int REGISTER = 1, REGISTERED = 2, SEND = 3, OK = 4, UNREACHABLE = 5, INCOMING = 6, REJECTED = 7;
    static final int MAX_PAYLOAD = 262144;

    record Msg(int kind, String from, String to, byte[] payload) {}

    private RelayWire() {}

    static void write(OutputStream output, Msg message) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        byte[] payload = message.payload() == null ? new byte[0] : message.payload();
        out.writeInt(MAGIC);
        out.writeByte(message.kind());
        out.writeUTF(message.from() == null ? "" : message.from());
        out.writeUTF(message.to() == null ? "" : message.to());
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    static Msg read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        if (in.readInt() != MAGIC) throw new IOException("Unknown relay frame");
        int kind = in.readUnsignedByte();
        String from = in.readUTF(), to = in.readUTF();
        int length = in.readInt();
        if (length < 0 || length > MAX_PAYLOAD) throw new IOException("Invalid relay payload");
        byte[] payload = new byte[length];
        in.readFully(payload);
        return new Msg(kind, from, to, payload);
    }

    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,128}");
    }
}
