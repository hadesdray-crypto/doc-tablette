package fr.doctablette;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.io.File;
import java.io.IOException;

import fr.doctablette.adb.AdbChannel;
import fr.doctablette.adb.AdbConnection;
import fr.doctablette.adb.AdbCrypto;
import fr.doctablette.adb.TcpAdbChannel;
import fr.doctablette.adb.UsbAdbChannel;

/** La tablette actuellement branchée (une seule à la fois). */
final class TabletLink {
    interface Callback {
        void state(String message);

        void connected(String label);

        void failed(String message);
    }

    volatile AdbConnection cx;
    volatile String label = "";
    volatile String key = "";
    volatile UsbDevice usbDevice;

    boolean isConnected() {
        AdbConnection c = cx;
        return c != null && !c.isClosed();
    }

    void disconnect() {
        AdbConnection c = cx;
        cx = null;
        label = "";
        key = "";
        usbDevice = null;
        if (c != null) c.close();
    }

    String sh(String cmd, long timeoutMs) throws IOException {
        AdbConnection c = cx;
        if (c == null || c.isClosed()) throw new IOException("Tablette débranchée");
        return c.shell(cmd, timeoutMs);
    }

    /** À appeler dans un fil d'arrière-plan. Deux essais : le deuxième repart d'un canal neuf. */
    void connectUsb(Context ctx, UsbManager mgr, UsbDevice dev, Callback cb) {
        disconnect();
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            AdbChannel ch = null;
            try {
                cb.state(attempt == 1 ? "Ouverture du câble USB…" : "Nouvel essai…");
                ch = new UsbAdbChannel(mgr, dev);
                finish(ctx, ch, cb);
                usbDevice = dev;
                return;
            } catch (Exception e) {
                last = e;
                if (ch != null) try {
                    ch.close();
                } catch (IOException ignored) {
                }
                if (e.getMessage() != null && e.getMessage().contains("refusée")) break;
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
            }
        }
        cb.failed(explain(last));
    }

    void connectTcp(Context ctx, String host, int port, Callback cb) {
        disconnect();
        AdbChannel ch = null;
        try {
            cb.state("Connexion à " + host + "…");
            ch = new TcpAdbChannel(host, port);
            finish(ctx, ch, cb);
        } catch (Exception e) {
            if (ch != null) try {
                ch.close();
            } catch (IOException ignored) {
            }
            cb.failed(explain(e));
        }
    }

    private void finish(Context ctx, AdbChannel ch, Callback cb) throws Exception {
        AdbCrypto crypto = AdbCrypto.loadOrCreate(new File(ctx.getFilesDir(), "adb"));
        AdbConnection c = new AdbConnection(ch, crypto, "doc-tablette@telephone");
        c.connect(90000, () -> cb.state("Sur la tablette, touchez « Autoriser » (cochez « Toujours autoriser »)."));
        cx = c;
        String model = "", serial = "";
        try {
            model = c.shell("getprop ro.product.manufacturer; getprop ro.product.model", 8000).trim().replace('\n', ' ');
            serial = c.shell("getprop ro.serialno", 5000).trim();
        } catch (IOException ignored) {
        }
        label = model.isEmpty() ? "Tablette" : model;
        key = (model + "_" + serial).replaceAll("[^A-Za-z0-9_.-]", "");
        cb.connected(label);
    }

    static String explain(Exception e) {
        String m = e == null || e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("refusée")) return "Autorisation refusée sur la tablette. Débranchez, rebranchez, et touchez « Autoriser ».";
        if (m.contains("ne répond pas")) return "La tablette ne répond pas. Vérifiez que le débogage USB est activé (voir « Aide »).";
        if (m.contains("Accès USB")) return "Accès USB non accordé. Rebranchez le câble et acceptez la demande du téléphone.";
        if (m.contains("occupé")) return "Le port USB est utilisé par une autre appli. Fermez-la puis rebranchez.";
        if (m.contains("coupée") || m.contains("débranchée")) return "Câble débranché ou tablette éteinte.";
        if (m.contains("timed out") || m.contains("failed to connect") || m.contains("Connection refused")) return "Aucune réponse de la tablette en Wi-Fi. Utilisez plutôt le câble USB.";
        return "Connexion impossible. " + m;
    }
}
