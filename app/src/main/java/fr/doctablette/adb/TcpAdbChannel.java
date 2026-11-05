package fr.doctablette.adb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Canal ADB par le Wi-Fi (la tablette doit écouter sur le port 5555). */
public final class TcpAdbChannel implements AdbChannel {
    private final Socket s;
    private final InputStream in;
    private final OutputStream out;

    public TcpAdbChannel(String host, int port) throws IOException {
        s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), 5000);
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        out.flush();
    }

    @Override
    public int read(byte[] b, int off, int len, int timeoutMs) throws IOException {
        s.setSoTimeout(Math.max(1, timeoutMs));
        try {
            return in.read(b, off, len);
        } catch (SocketTimeoutException e) {
            return 0;
        }
    }

    @Override
    public int maxChunk() {
        return 65536;
    }

    @Override
    public void close() throws IOException {
        s.close();
    }
}
