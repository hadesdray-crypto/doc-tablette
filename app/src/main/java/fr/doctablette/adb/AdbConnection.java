package fr.doctablette.adb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Dialogue ADB : connexion, autorisation, puis une commande à la fois. */
public final class AdbConnection {
    static final int A_SYNC = 0x434e5953;
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    static final int VERSION = 0x01000000;
    static final int MAX_DATA = 16384;

    /** Écoute l'avancement (par exemple « touchez Autoriser sur la tablette »). */
    public interface Listener {
        void onWaitingForUserApproval();
    }

    private final AdbChannel ch;
    private final AdbCrypto crypto;
    private final String keyName;
    private int peerMaxData = 4096;
    private int nextLocal = 1;
    private String banner = "";
    private volatile boolean closed;

    public AdbConnection(AdbChannel ch, AdbCrypto crypto, String keyName) {
        this.ch = ch;
        this.crypto = crypto;
        this.keyName = keyName;
    }

    public String banner() {
        return banner;
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        closed = true;
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    // ---------- messages ----------
    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data = new byte[0];
    }

    private void send(int cmd, int arg0, int arg1, byte[] data, int len) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        writeAll(h.array(), 24);
        if (len > 0) writeAll(data, len);
    }

    private void writeAll(byte[] b, int len) throws IOException {
        int off = 0;
        int chunk = Math.max(64, ch.maxChunk());
        while (off < len) {
            int n = Math.min(chunk, len - off);
            ch.write(b, off, n);
            off += n;
        }
    }

    private boolean readFully(byte[] b, int len, long deadline) throws IOException {
        int off = 0;
        while (off < len) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                if (off > 0) throw new IOException("Message ADB tronqué");
                return false;
            }
            int r = ch.read(b, off, len - off, (int) Math.min(left, 1000));
            if (r < 0) {
                closed = true;
                throw new IOException("Connexion coupée");
            }
            off += r;
        }
        return true;
    }

    /** Renvoie null si rien n'arrive avant le délai. */
    private Msg recv(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] h = new byte[24];
        if (!readFully(h, 24, deadline)) return null;
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = bb.getInt();
        m.arg0 = bb.getInt();
        m.arg1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt(); // somme de contrôle
        int magic = bb.getInt();
        if (magic != (m.cmd ^ 0xffffffff) || len < 0 || len > 1024 * 1024) {
            throw new IOException("Message ADB invalide");
        }
        if (len > 0) {
            byte[] d = new byte[len];
            // l'en-tête est arrivé : le contenu doit suivre rapidement
            if (!readFully(d, len, System.currentTimeMillis() + 10000)) throw new IOException("Message ADB tronqué");
            m.data = d;
        }
        return m;
    }

    // ---------- connexion ----------
    /** Se connecte. Peut attendre jusqu'à waitUserMs que l'utilisateur touche « Autoriser » sur la tablette. */
    public void connect(long waitUserMs, Listener l) throws Exception {
        byte[] id = "host::features=cmd,shell_v2".getBytes(StandardCharsets.US_ASCII);
        byte[] idz = new byte[id.length + 1];
        System.arraycopy(id, 0, idz, 0, id.length);
        send(A_CNXN, VERSION, MAX_DATA, idz, idz.length);

        boolean sentSignature = false, sentKey = false;
        long end = System.currentTimeMillis() + 8000;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new IOException("La tablette ne répond pas");
            Msg m = recv(Math.min(left, 2000));
            if (m == null) continue;
            if (m.cmd == A_CNXN) {
                peerMaxData = Math.max(1024, Math.min(m.arg1, MAX_DATA));
                banner = new String(m.data, StandardCharsets.US_ASCII).trim();
                return;
            }
            if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!sentSignature) {
                    byte[] sig = crypto.sign(m.data);
                    send(A_AUTH, AUTH_SIGNATURE, 0, sig, sig.length);
                    sentSignature = true;
                } else if (!sentKey) {
                    byte[] key = crypto.publicKeyForAuth(keyName);
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key, key.length);
                    sentKey = true;
                    if (l != null) l.onWaitingForUserApproval();
                    end = System.currentTimeMillis() + waitUserMs;
                } else {
                    throw new IOException("Autorisation refusée sur la tablette");
                }
            }
        }
    }

    // ---------- commandes ----------
    /** Résultat d'une commande : texte renvoyé par la tablette. */
    public String shell(String command, long timeoutMs) throws IOException {
        return text(run("shell:" + command, null, 0, timeoutMs));
    }

    public String exec(String command, long timeoutMs) throws IOException {
        return text(run("exec:" + command, null, 0, timeoutMs));
    }

    /** Envoie un flux (fichier) à une commande « exec: ». */
    public String execWithInput(String command, InputStream in, long timeoutMs) throws IOException {
        return text(run("exec:" + command, in, 0, timeoutMs));
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** Interface pour suivre l'envoi d'un gros fichier. */
    public interface Progress {
        void sent(long bytes);
    }

    /** Tant que des données arrivent, le délai se prolonge de ce temps (utile pour les gros fichiers). */
    private long idleExtend = 0;

    public void setIdleExtend(long ms) {
        this.idleExtend = ms;
    }

    private Progress progress;
    private volatile boolean abortRequested;

    /** Demande l'arrêt de la commande en cours : son flux est fermé proprement au prochain tour de boucle. */
    public void abortCurrent() {
        abortRequested = true;
    }

    public void setProgress(Progress p) {
        this.progress = p;
    }

    public byte[] run(String service, InputStream stdin, int unused, long timeoutMs) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        stream(service, stdin, bo, timeoutMs);
        return bo.toByteArray();
    }

    /** Comme run, mais la sortie va directement dans un flux (photos, vidéos : pas de limite de taille en mémoire). */
    public synchronized void stream(String service, InputStream stdin, java.io.OutputStream out, long timeoutMs) throws IOException {
        if (closed) throw new IOException("Pas de connexion");
        abortRequested = false;
        final int local = nextLocal++;
        byte[] svc = service.getBytes(StandardCharsets.UTF_8);
        byte[] svcz = new byte[svc.length + 1];
        System.arraycopy(svc, 0, svcz, 0, svc.length);
        send(A_OPEN, local, 0, svcz, svcz.length);

        long deadline = System.currentTimeMillis() + timeoutMs;
        int remote = 0;
        boolean opened = false, writeReady = false, stdinDone = stdin == null;
        long sent = 0;
        byte[] buf = new byte[peerMaxData];
        while (true) {
            if (abortRequested) {
                abortRequested = false;
                try {
                    send(A_CLSE, local, remote, new byte[0], 0);
                } catch (IOException ignored) {
                }
                throw new IOException("Arrêté à votre demande");
            }
            if (System.currentTimeMillis() > deadline) {
                try {
                    send(A_CLSE, local, remote, new byte[0], 0);
                } catch (IOException ignored) {
                }
                throw new IOException("La commande a pris trop de temps");
            }
            if (opened && writeReady && !stdinDone) {
                int n = stdin.read(buf, 0, buf.length);
                if (n < 0) {
                    stdinDone = true;
                } else if (n > 0) {
                    send(A_WRTE, local, remote, buf, n);
                    writeReady = false;
                    sent += n;
                    if (progress != null) progress.sent(sent);
                }
                continue;
            }
            Msg m = recv(Math.min(1000, Math.max(1, deadline - System.currentTimeMillis())));
            if (m == null) continue;
            if (m.cmd == A_OKAY && m.arg1 == local) {
                remote = m.arg0;
                opened = true;
                writeReady = true;
            } else if (m.cmd == A_WRTE && m.arg1 == local) {
                out.write(m.data, 0, m.data.length);
                deadline = Math.max(deadline, System.currentTimeMillis() + idleExtend);
                send(A_OKAY, local, m.arg0, new byte[0], 0);
            } else if (m.cmd == A_CLSE && m.arg1 == local) {
                if (!opened) throw new IOException("Commande refusée par la tablette");
                send(A_CLSE, local, m.arg0, new byte[0], 0);
                return;
            } else if (m.cmd == A_CLSE && m.arg1 == 0 && !opened) {
                throw new IOException("Commande refusée par la tablette");
            }
        }
    }
}
