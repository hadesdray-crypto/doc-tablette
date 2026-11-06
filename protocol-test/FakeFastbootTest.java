import fr.doctablette.adb.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Teste le protocole fastboot contre un faux appareil (sans matériel). */
public class FakeFastbootTest {
    static int fails = 0;
    static void ok(boolean c, String m) { System.out.println((c ? "OK    " : "ECHEC ") + m); if (!c) fails++; }

    /** Faux appareil fastboot : répond aux commandes dans l'ordre. */
    static final class Dev implements AdbChannel {
        final ArrayDeque<byte[]> out = new ArrayDeque<>();
        byte[] flashed; String flashedName; long expect = -1; ByteArrayOutputStream rx = new ByteArrayOutputStream();
        public void write(byte[] b, int off, int len) {
            if (expect >= 0) { rx.write(b, off, len); if (rx.size() >= expect) { expect = -1; r("OKAY"); } return; }
            String c = new String(b, off, len, StandardCharsets.US_ASCII);
            if (c.startsWith("getvar:max-download-size")) r("OKAY0x00100000");
            else if (c.startsWith("getvar:product")) r("OKAYtablette-test");
            else if (c.startsWith("download:")) { expect = Long.parseLong(c.substring(9), 16); rx = new ByteArrayOutputStream(); r("DATA" + c.substring(9)); }
            else if (c.startsWith("flash:")) { flashed = rx.toByteArray(); flashedName = c.substring(6); r("INFOecriture…"); r("OKAY"); }
            else if (c.equals("reboot")) r("OKAY");
            else r("FAILunknown command");
        }
        void r(String s) { out.add(s.getBytes(StandardCharsets.US_ASCII)); }
        public int read(byte[] b, int off, int len, int t) {
            byte[] m = out.poll(); if (m == null) return 0;
            System.arraycopy(m, 0, b, off, m.length); return m.length;
        }
        public int maxChunk() { return 16384; }
        public void close() {}
    }

    public static void main(String[] a) throws Exception {
        Dev d = new Dev(); Fastboot f = new Fastboot(d);
        ok("tablette-test".equals(f.getvar("product")), "getvar product");
        ok(f.maxDownload() == 0x100000, "max-download-size lu");
        ok(!f.command("oem nimportequoi", 3000).ok, "commande inconnue : échec propre");
        byte[] img = new byte[100000]; new Random(1).nextBytes(img);
        long[] last = {0};
        Fastboot.Reply r = f.flash("boot", new ByteArrayInputStream(img), img.length, (s, t) -> last[0] = s);
        ok(r.ok && r.info.size() == 1, "flash réussi avec message INFO");
        ok(Arrays.equals(d.flashed, img) && "boot".equals(d.flashedName), "image reçue intacte (100 Ko)");
        ok(last[0] == img.length, "progression complète");
        boolean refused = false;
        try { f.flash("../x", new ByteArrayInputStream(img), 10, null); } catch (IOException e) { refused = true; }
        ok(refused, "nom de partition dangereux refusé");
        refused = false;
        try { f.flash("boot", new ByteArrayInputStream(new byte[2000000]), 2000000, null); } catch (IOException e) { refused = true; }
        ok(refused, "image trop grosse refusée avant envoi");
        ok(f.command("reboot", 3000).ok, "reboot");
        System.out.println(fails == 0 ? "TOUT PASSE" : fails + " ECHEC(S)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
