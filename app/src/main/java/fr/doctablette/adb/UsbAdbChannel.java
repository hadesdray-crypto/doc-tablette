package fr.doctablette.adb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/** Canal ADB par le câble USB : le téléphone joue le rôle d'ordinateur. */
public final class UsbAdbChannel implements AdbChannel {
    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint in, out;
    private volatile boolean closed;

    public static boolean hasAdbInterface(UsbDevice d) {
        return findInterface(d) != null;
    }

    public static boolean hasFastbootInterface(UsbDevice d) {
        return findInterface(d, 3) != null;
    }

    static UsbInterface findInterface(UsbDevice d) {
        return findInterface(d, 1);
    }

    static UsbInterface findInterface(UsbDevice d, int protocol) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 255 && f.getInterfaceSubclass() == 66 && f.getInterfaceProtocol() == protocol) return f;
        }
        return null;
    }

    public UsbAdbChannel(UsbManager mgr, UsbDevice dev) throws IOException {
        this(mgr, dev, 1);
    }

    /** Mode Téléchargement Samsung : interface « données CDC » (classe 10) avec deux points d'échange. */
    public static boolean hasCdcDataInterface(UsbDevice d) {
        return findCdc(d) != null;
    }

    static UsbInterface findCdc(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 10 && f.getEndpointCount() >= 2) return f;
        }
        return null;
    }

    /** Modes de chargement des puces (MediaTek, Rockchip…) : première interface avec une entrée et une sortie en vrac. */
    static UsbInterface findBulk(UsbDevice d) {
        UsbInterface best = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            boolean in = false, out = false;
            for (int j = 0; j < f.getEndpointCount(); j++) {
                UsbEndpoint e = f.getEndpoint(j);
                if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (e.getDirection() == UsbConstants.USB_DIR_IN) in = true;
                else out = true;
            }
            if (in && out && (best == null || f.getInterfaceClass() == 10)) best = f;
        }
        return best;
    }

    public static boolean hasBulkInterface(UsbDevice d) {
        return findBulk(d) != null;
    }

    public static UsbAdbChannel openBulk(UsbManager mgr, UsbDevice dev) throws IOException {
        return new UsbAdbChannel(mgr, dev, -2);
    }

    public static UsbAdbChannel openCdc(UsbManager mgr, UsbDevice dev) throws IOException {
        return new UsbAdbChannel(mgr, dev, -1);
    }

    @Override
    public void writeEmpty() {
        try {
            conn.bulkTransfer(out, new byte[0], 0, 100);
        } catch (Exception ignored) {
        }
    }

    public UsbAdbChannel(UsbManager mgr, UsbDevice dev, int protocol) throws IOException {
        intf = protocol == -2 ? findBulk(dev) : protocol < 0 ? findCdc(dev) : findInterface(dev, protocol);
        if (intf == null) throw new IOException(protocol < 0 ? "Ce n'est pas un mode Téléchargement" : "Cette connexion USB n'est pas en mode débogage");
        UsbEndpoint ei = null, eo = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.getDirection() == UsbConstants.USB_DIR_IN) ei = e;
                else eo = e;
            }
        }
        if (ei == null || eo == null) throw new IOException("Port USB incomplet");
        in = ei;
        out = eo;
        conn = mgr.openDevice(dev);
        if (conn == null) throw new IOException("Accès USB non accordé");
        if (!conn.claimInterface(intf, true)) {
            conn.close();
            throw new IOException("Port USB occupé par une autre appli");
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = Math.min(16384, len - done);
            int r = conn.bulkTransfer(out, b, off + done, n, 5000);
            if (r < 0) {
                closed = true;
                throw new IOException("Écriture USB impossible");
            }
            done += r;
        }
    }

    @Override
    public int read(byte[] b, int off, int len, int timeoutMs) {
        if (closed) return -1;
        long t0 = System.currentTimeMillis();
        int r = conn.bulkTransfer(in, b, off, Math.min(len, 16384), timeoutMs);
        if (r >= 0) return r;
        // Échec : délai normal, ou câble débranché si l'échec est immédiat
        if (System.currentTimeMillis() - t0 < Math.min(timeoutMs, 200) / 2 + 20 && timeoutMs > 100) {
            closed = true;
            return -1;
        }
        return 0;
    }

    @Override
    public int maxChunk() {
        return 16384;
    }

    @Override
    public void close() {
        closed = true;
        try {
            conn.releaseInterface(intf);
        } catch (Exception ignored) {
        }
        conn.close();
    }
}
