import fr.doctablette.adb.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.*;

/** Teste le protocole ADB contre une fausse tablette (sans Android). */
public class FakeAdbTest {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;

    /** Tuyau d'octets avec délai d'attente. */
    static final class Pipe {
        private final ArrayDeque<byte[]> q = new ArrayDeque<>();
        private byte[] cur = new byte[0]; private int pos; boolean closed;
        synchronized void put(byte[] b, int off, int len) { q.add(Arrays.copyOfRange(b, off, off + len)); notifyAll(); }
        synchronized int get(byte[] b, int off, int len, int timeoutMs) throws InterruptedException {
            long end = System.currentTimeMillis() + timeoutMs;
            while (pos >= cur.length && q.isEmpty()) {
                if (closed) return -1;
                long left = end - System.currentTimeMillis();
                if (left <= 0) return 0;
                wait(left);
            }
            if (pos >= cur.length) { cur = q.poll(); pos = 0; }
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n); pos += n; return n;
        }
        synchronized void close() { closed = true; notifyAll(); }
    }
    static final class Side implements AdbChannel {
        final Pipe in, out;
        Side(Pipe in, Pipe out) { this.in = in; this.out = out; }
        public void write(byte[] b, int off, int len) { out.put(b, off, len); }
        public int read(byte[] b, int off, int len, int t) throws IOException {
            try { return in.get(b, off, len, t); } catch (InterruptedException e) { throw new IOException(e); }
        }
        public int maxChunk() { return 16384; }
        public void close() { in.close(); out.close(); }
    }

    // ---- fausse tablette ----
    static final class Fake implements Runnable {
        final Side s; final List<RSAPublicKey> known; final boolean approve;
        Fake(Side s, List<RSAPublicKey> known, boolean approve) { this.s = s; this.known = known; this.approve = approve; }
        int[] hdr = new int[3]; byte[] data;
        boolean rd() throws IOException {
            byte[] h = new byte[24]; int o = 0;
            while (o < 24) { int r = s.read(h, o, 24 - o, 5000); if (r < 0) return false; if (r == 0) return false; o += r; }
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int cmd = b.getInt(), a0 = b.getInt(), a1 = b.getInt(), len = b.getInt(); b.getInt(); int magic = b.getInt();
            if (magic != (cmd ^ 0xffffffff)) throw new IOException("magic");
            data = new byte[len]; o = 0;
            while (o < len) { int r = s.read(data, o, len - o, 5000); if (r <= 0) return false; o += r; }
            hdr[0] = cmd; hdr[1] = a0; hdr[2] = a1; return true;
        }
        void tx(int cmd, int a0, int a1, byte[] d) {
            ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            int sum = 0; for (byte x : d) sum += x & 0xff;
            b.putInt(cmd).putInt(a0).putInt(a1).putInt(d.length).putInt(sum).putInt(cmd ^ 0xffffffff);
            s.write(b.array(), 0, 24); if (d.length > 0) s.write(d, 0, d.length);
        }
        static RSAPublicKey parse(byte[] z) throws Exception {
            String txt = new String(z, StandardCharsets.US_ASCII); txt = txt.substring(0, txt.indexOf(' '));
            byte[] raw = Base64.getDecoder().decode(txt);
            if (raw.length != 524) throw new IOException("taille clé " + raw.length);
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != 64) throw new IOException("mots");
            byte[] n = new byte[256]; for (int i = 0; i < 256; i++) n[255 - i] = raw[8 + i];
            BigInteger N = new BigInteger(1, n);
            // vérifie n0inv : n[0] * n0inv == -1 mod 2^32
            long n0inv = b.getInt(4) & 0xffffffffL; long n0 = N.mod(BigInteger.ONE.shiftLeft(32)).longValue();
            if (((n0 * n0inv) & 0xffffffffL) != 0xffffffffL) throw new IOException("n0inv faux");
            BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(N); byte[] r = new byte[256]; for (int i = 0; i < 256; i++) r[255 - i] = raw[8 + 256 + i];
            if (!new BigInteger(1, r).equals(rr)) throw new IOException("rr faux");
            BigInteger E = BigInteger.valueOf(b.getInt(8 + 512) & 0xffffffffL);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(N, E));
        }
        static final byte[] P = {0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
        boolean verify(byte[] token, byte[] sig) throws Exception {
            for (RSAPublicKey k : known) {
                Signature v = Signature.getInstance("NONEwithRSA"); v.initVerify(k);
                byte[] d = new byte[P.length + token.length]; System.arraycopy(P, 0, d, 0, P.length); System.arraycopy(token, 0, d, P.length, token.length);
                v.update(d); if (v.verify(sig)) return true;
            }
            return false;
        }
        public void run() {
            try {
                if (!rd() || hdr[0] != CNXN) return;
                byte[] token = new byte[20]; new SecureRandom().nextBytes(token);
                tx(AUTH, 1, 0, token);
                while (true) {
                    if (!rd()) return;
                    if (hdr[0] == AUTH && hdr[1] == 2) {
                        if (verify(token, data)) { tx(CNXN, 0x01000000, 4096, "device::ro.product.model=Fake;features=cmd\0".getBytes()); break; }
                        tx(AUTH, 1, 0, token);
                    } else if (hdr[0] == AUTH && hdr[1] == 3) {
                        RSAPublicKey k = parse(data);
                        if (!approve) { s.close(); return; }
                        Thread.sleep(300); known.add(k);
                        tx(CNXN, 0x01000000, 4096, "device::ro.product.model=Fake;features=cmd\0".getBytes()); break;
                    }
                }
                int rid = 100;
                while (rd()) {
                    if (hdr[0] == OPEN) {
                        int local = hdr[1]; String svc = new String(data, 0, data.length - 1, StandardCharsets.UTF_8);
                        boolean knownSvc = svc.startsWith("shell:echo ") || svc.equals("shell:big") || svc.startsWith("exec:head -c ");
                        if (knownSvc) tx(OKAY, rid, local, new byte[0]);
                        if (svc.startsWith("shell:echo ")) { tx(WRTE, rid, local, (svc.substring(11) + "\n").getBytes()); waitOk(); tx(CLSE, rid, local, new byte[0]); }
                        else if (svc.equals("shell:big")) {
                            byte[] big = new byte[50000]; for (int i = 0; i < big.length; i++) big[i] = (byte) ('a' + i % 26);
                            for (int o = 0; o < big.length; o += 4096) { tx(WRTE, rid, local, Arrays.copyOfRange(big, o, Math.min(big.length, o + 4096))); waitOk(); }
                            tx(CLSE, rid, local, new byte[0]);
                        } else if (svc.startsWith("exec:head -c ")) {
                            int n = Integer.parseInt(svc.substring(13).split(" ")[0]); int got = 0; long sum = 0;
                            while (got < n) { if (!rd()) return; if (hdr[0] != WRTE) continue; got += data.length; for (byte x : data) sum += x & 0xff; tx(OKAY, rid, local, new byte[0]); }
                            tx(WRTE, rid, local, ("got=" + got + " sum=" + sum + "\n").getBytes()); waitOk(); tx(CLSE, rid, local, new byte[0]);
                        } else tx(CLSE, 0, local, new byte[0]);
                    }
                    rid++;
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
        void waitOk() throws IOException { while (rd()) if (hdr[0] == OKAY) return; }
    }

    static int fails = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "OK    " : "ÉCHEC ") + name); if (!ok) fails++; }

    static AdbConnection connect(AdbCrypto c, List<RSAPublicKey> known, boolean approve, boolean[] asked) throws Exception {
        Pipe a = new Pipe(), b = new Pipe();
        Side host = new Side(b, a), dev = new Side(a, b);
        Thread t = new Thread(new Fake(dev, known, approve)); t.setDaemon(true); t.start();
        AdbConnection cx = new AdbConnection(host, c, "doc-tablette@telephone");
        cx.connect(5000, () -> asked[0] = true);
        return cx;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dt-test-" + System.nanoTime());
        AdbCrypto c = AdbCrypto.loadOrCreate(dir);
        AdbCrypto c2 = AdbCrypto.loadOrCreate(dir);
        List<RSAPublicKey> known = new ArrayList<>();
        boolean[] asked = {false};
        AdbConnection cx = connect(c, known, true, asked);
        check("première connexion : la tablette demande l'autorisation", asked[0]);
        check("première connexion : clé acceptée, bannière lue", cx.banner().contains("Fake"));
        check("commande courte (echo)", cx.shell("echo bonjour", 5000).equals("bonjour\n"));
        String big = cx.shell("big", 5000);
        check("grosse réponse (50000 octets) complète", big.length() == 50000 && big.charAt(26) == 'a');
        byte[] payload = new byte[300000]; new Random(1).nextBytes(payload); long sum = 0; for (byte x : payload) sum += x & 0xff;
        long[] last = {0}; cx.setProgress(v -> last[0] = v);
        String r = cx.execWithInput("head -c " + payload.length + " > /x", new ByteArrayInputStream(payload), 20000);
        check("envoi d'un fichier de 300 Ko intact", r.trim().equals("got=300000 sum=" + sum) && last[0] == 300000);
        check("commande inconnue refusée proprement", failsWith(cx));
        cx.close();
        boolean[] asked2 = {false};
        AdbConnection cx2 = connect(c2, known, true, asked2);
        check("reconnexion : clé rechargée, aucune nouvelle autorisation", !asked2[0] && cx2.banner().contains("Fake"));
        cx2.close();
        boolean refused = false;
        try { connect(AdbCrypto.generate(), new ArrayList<>(), false, new boolean[1]); } catch (IOException e) { refused = true; }
        check("autorisation refusée : erreur claire, pas de blocage", refused);
        System.out.println(fails == 0 ? "TOUT PASSE" : fails + " ÉCHEC(S)");
        System.exit(fails == 0 ? 0 : 1);
    }
    static boolean failsWith(AdbConnection cx) {
        try { cx.shell("inconnu", 3000); return false; } catch (IOException e) { return true; }
    }
}
