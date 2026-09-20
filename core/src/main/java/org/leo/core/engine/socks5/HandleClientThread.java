package org.leo.core.engine.socks5;

import org.leo.core.engine.proxy.ProxyConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class HandleClientThread implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(HandleClientThread.class);
    private final ProxyConnection connection;

    public HandleClientThread(ProxyConnection connection) {
        this.connection = connection;
    }

    @Override
    public void run() {
        try (connection) {
            DataInputStream in = new DataInputStream(connection.socket().getInputStream());
            OutputStream out = connection.socket().getOutputStream();
            if (in.readUnsignedByte() != 5) return;
            int methods = in.readUnsignedByte();
            boolean noAuth = false;
            for (int i = 0; i < methods; i++) {
                if (in.readUnsignedByte() == 0) noAuth = true;
            }
            out.write(new byte[]{5, (byte) (noAuth ? 0 : 255)});
            out.flush();
            if (!noAuth || in.readUnsignedByte() != 5) return;

            int command = in.readUnsignedByte();
            if (in.readUnsignedByte() != 0) return;
            int addressType = in.readUnsignedByte();
            String host;
            if (addressType == 1 || addressType == 4) {
                byte[] address = new byte[addressType == 1 ? 4 : 16];
                in.readFully(address);
                host = InetAddress.getByAddress(address).getHostAddress();
            } else if (addressType == 3) {
                int length = in.readUnsignedByte();
                if (length == 0) return;
                byte[] address = new byte[length];
                in.readFully(address);
                host = new String(address, StandardCharsets.UTF_8);
            } else {
                reply(out, 8);
                return;
            }
            int port = in.readUnsignedShort();
            if (command != 1) {
                reply(out, 7);
                return;
            }
            if (port == 0 || !connection.open(host, port)) {
                reply(out, 1);
                return;
            }
            reply(out, 0);
            connection.relay();
        } catch (Exception error) {
            log.debug("SOCKS5 连接结束: {}", error.getMessage());
        }
    }

    private void reply(OutputStream out, int code) throws Exception {
        out.write(new byte[]{5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
    }
}
