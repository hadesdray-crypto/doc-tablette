import fr.doctablette.adb.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Teste le mode Téléchargement Samsung contre une fausse tablette (lecture et sortie seulement). */
public class FakeOdinTest {
    static int fails = 0;
    static void ok(boolean c, String m) { System.out.println((c ? "OK    " : "ECHEC ") + m); if (!c) fails++; }

    static class Dev implements AdbChannel {
        final ArrayDeque<byte[]> out = new ArrayDeque<>();
        final byte[] pit;
        boolean rebooted, ended;
        Dev(byte[] pit) { this.pit = pit; }
        static long rd(byte[] b, int o) { return (b[o] & 0xffL) | (b[o+1] & 0xffL) << 8 | (b[o+2] & 0xffL) << 16 | (b[o+3] & 0xffL) << 24; }
        static byte[] resp(int type, long v) { byte[] r = new byte[8]; for (int i = 0; i < 4; i++) { r[i] = (byte) (type >> (8*i)); r[4+i] = (byte) (v >> (8*i)); } return r; }
        public void write(byte[] b, int off, int len) {
            if (len == 4 && new String(b, off, 4, StandardCharsets.US_ASCII).equals("ODIN")) { out.add("LOKE".getBytes()); return; }
            if (len != 1024) return;
            byte[] p = Arrays.copyOfRange(b, off, off + len);
            long type = rd(p, 0), a = rd(p, 4), c = rd(p, 8);
            if (type == 0x64) out.add(resp(0x64, a == 0 ? 1 : 0));
            else if (type == 0x65) {
                if (a == 1) out.add(resp(0x65, pit.length));
                else if (a == 2) { int s = (int) c * 500; out.add(Arrays.copyOfRange(pit, s, Math.min(pit.length, s + 500))); }
                else out.add(resp(0x65, 0));
            } else if (type == 0x67) { if (a == 1) rebooted = true; else ended = true; out.add(resp(0x67, 0)); }
        }
        public int read(byte[] b, int off, int len, int t) {
            byte[] m = out.poll(); if (m == null) return 0;
            int n = Math.min(len, m.length); System.arraycopy(m, 0, b, off, n); return n;
        }
        public int maxChunk() { return 16384; }
        public void close() {}
    }

    static void le(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o+i] = (byte) (v >> (8*i)); }

    public static void main(String[] a) throws Exception {
        int n = 3;
        byte[] pit = new byte[28 + n * 132];
        le(pit, 0, 0x12349876L); le(pit, 4, n);
        String[] names = {"BOOT", "RECOVERY", "SYSTEM"};
        for (int i = 0; i < n; i++) {
            int o = 28 + i * 132; le(pit, o + 8, 10 + i); le(pit, o + 24, 100 * (i + 1));
            System.arraycopy(names[i].getBytes(), 0, pit, o + 36, names[i].length());
            String f = names[i].toLowerCase() + ".img"; System.arraycopy(f.getBytes(), 0, pit, o + 68, f.length());
        }
        Dev d = new Dev(pit); OdinDownload o = new OdinDownload(d);
        o.handshake(); ok(true, "poignée de main ODIN/LOKE");
        o.beginSession(); ok(true, "session ouverte");
        byte[] got = o.readPit();
        ok(Arrays.equals(got, pit), "table des partitions reçue intacte (" + got.length + " octets)");
        List<OdinDownload.Partition> ps = OdinDownload.parsePit(got);
        ok(ps.size() == 3 && ps.get(1).name.equals("RECOVERY") && ps.get(2).flashFile.equals("system.img") && ps.get(2).blockCount == 300, "partitions lues : " + ps.get(0).name + ", " + ps.get(1).name + ", " + ps.get(2).name);
        o.end(true); ok(d.ended && d.rebooted, "sortie du mode Téléchargement avec redémarrage");
        boolean refused = false;
        try { OdinDownload.parsePit(new byte[200]); } catch (IOException e) { refused = true; }
        ok(refused, "table invalide refusée sans plantage");
        Dev bad = new Dev(pit) { public void write(byte[] b, int off, int len) { if (len == 4) out.add("NOPE".getBytes()); } };
        refused = false;
        try { new OdinDownload(bad).handshake(); } catch (IOException e) { refused = true; }
        ok(refused, "appareil qui n'est pas Samsung : refusé");
        System.out.println(fails == 0 ? "TOUT PASSE" : fails + " ECHEC(S)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
