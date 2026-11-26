package io.github.privatednsfallback;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Collections;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Connect by address so a broken system resolver cannot prevent recovery. */
final class DotProbe {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TIMEOUT_MS = 3000;
    private static final byte[] QUESTION = {7,'e','x','a','m','p','l','e',3,'o','r','g',0,0,1,0,1};

    static boolean resolves(Socket raw, String address, String hostname) throws IOException {
        raw.connect(new InetSocketAddress(address, 853), TIMEOUT_MS);
        raw.setSoTimeout(TIMEOUT_MS);
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket tls = (SSLSocket) factory.createSocket(raw, hostname, 853, true)) {
            SSLParameters options = tls.getSSLParameters();
            options.setEndpointIdentificationAlgorithm("HTTPS");
            options.setServerNames(Collections.singletonList(new SNIHostName(hostname)));
            tls.setSSLParameters(options);
            tls.startHandshake();
            int id = RANDOM.nextInt(65536);
            DataOutputStream out = new DataOutputStream(tls.getOutputStream());
            out.writeShort(12 + QUESTION.length);
            out.writeShort(id);
            out.writeShort(0x0100);
            out.writeShort(1);
            out.write(new byte[6]);
            out.write(QUESTION);
            out.flush();
            DataInputStream in = new DataInputStream(tls.getInputStream());
            int length = in.readUnsignedShort();
            if (length < 12 + QUESTION.length) return false;
            byte[] msg = new byte[length];
            in.readFully(msg);
            return validAnswer(msg, id);
        }
    }

    static boolean validAnswer(byte[] msg, int id) {
        if (msg.length < 12 + QUESTION.length || word(msg, 0) != id
                || (word(msg, 2) & 0xfa0f) != 0x8000
                || word(msg, 4) != 1 || word(msg, 6) == 0) return false;
        for (int i = 0; i < QUESTION.length; i++) {
            if (msg[12+i] != QUESTION[i]) return false;
        }
        int pos = 12 + QUESTION.length;
        for (int i = 0; i < word(msg, 6); i++) {
            while (true) {
                if (pos >= msg.length) return false;
                int label = Byte.toUnsignedInt(msg[pos++]);
                if (label == 0) break;
                if ((label & 0xc0) == 0xc0) { pos++; break; }
                if (label > 63) return false;
                pos += label;
            }
            if (pos + 10 > msg.length) return false;
            int type = word(msg, pos);
            int klass = word(msg, pos + 2);
            int size = word(msg, pos + 8);
            pos += 10;
            if (pos + size > msg.length) return false;
            if (type == 1 && klass == 1 && size == 4) return true;
            pos += size;
        }
        return false;
    }

    private static int word(byte[] data, int offset) {
        return (Byte.toUnsignedInt(data[offset]) << 8) | Byte.toUnsignedInt(data[offset + 1]);
    }
}
