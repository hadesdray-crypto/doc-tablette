import fr.doctablette.adb.*;
import java.io.*;
import java.util.*;

/** Teste les sondes MediaTek et Rockchip contre de fausses puces (lecture seule). */
public class FakeChipTest {
    static int fails = 0;
    static void ok(boolean c, String m) { System.out.println((c ? "OK    " : "ECHEC ") + m); if (!c) fails++; }

    static class Mtk implements AdbChannel {
        final ArrayDeque<byte[]> out = new ArrayDeque<>();
        final boolean silent;
        Mtk(boolean silent) { this.silent = silent; }
        public void write(byte[] b, int off, int len) {
            if (silent) return;
            int c = b[off] & 0xff;
            if (c == 0xA0) out.add(new byte[]{0x5F});
            else if (c == 0x0A) out.add(new byte[]{(byte) 0xF5});
            else if (c == 0x50) out.add(new byte[]{(byte) 0xAF});
            else if (c == 0x05) out.add(new byte[]{(byte) 0xFA});
            else if (c == 0xFD) out.add(new byte[]{(byte) 0xFD, 0x07, 0x66, 0, 0});
        }
        public int read(byte[] b, int off, int len, int t) {
            byte[] m = out.poll(); if (m == null) return 0;
            System.arraycopy(m, 0, b, off, m.length); return m.length;
        }
        public int maxChunk() { return 64; }
        public void close() {}
    }

    static class Rk implements AdbChannel {
        final ArrayDeque<byte[]> out = new ArrayDeque<>();
        boolean rebooted; int lastOp = -1;
        public void write(byte[] b, int off, int len) {
            if (len != 31 || b[off] != 'U') return;
            lastOp = b[off + 15] & 0xff;
            if (lastOp == 0xFF) rebooted = true;
            byte[] s = new byte[13]; s[0]='U'; s[1]='S'; s[2]='B'; s[3]='S';
            out.add(s);
        }
        public int read(byte[] b, int off, int len, int t) {
            byte[] m = out.poll(); if (m == null) return 0;
            System.arraycopy(m, 0, b, off, m.length); return m.length;
        }
        public int maxChunk() { return 64; }
        public void close() {}
    }

    public static void main(String[] a) throws Exception {
        ok(new ChipProbe(new Mtk(false)).mediatekHwCode() == 0x0766, "MediaTek : code matériel lu (0x0766)");
        try { new ChipProbe(new Mtk(true)).mediatekHwCode(); ok(false, "MediaTek muet : doit échouer"); }
        catch (IOException e) { ok(true, "MediaTek muet : erreur propre (" + e.getMessage() + ")"); }
        Rk r = new Rk();
        ok(new ChipProbe(r).rockchipReady() && r.lastOp == 0x00, "Rockchip : prête");
        new ChipProbe(r).rockchipReboot();
        ok(r.rebooted, "Rockchip : redémarrage demandé");
        try { new ChipProbe(new Mtk(true)).rockchipReady(); ok(false, "Rockchip muet : doit échouer"); }
        catch (IOException e) { ok(true, "Rockchip muet : erreur propre"); }
        System.out.println(fails == 0 ? "TOUS LES TESTS PASSENT" : fails + " ECHEC(S)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
