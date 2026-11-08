import fr.doctablette.logic.*;
import java.io.*;

public class LogicTest {
    static int fails = 0;
    static void check(String n, boolean ok) { System.out.println((ok ? "OK    " : "ECHEC ") + n); if (!ok) fails++; }
    public static void main(String[] a) throws Exception {
        String out = "M=samsung\nN=SM-T510\nV=11\nS=30\nA=arm64-v8a\nANIM=1.0\n===BAT\nCurrent Battery Service state:\n  AC powered: false\n  status: 2\n  health: 2\n  present: true\n  level: 87\n  scale: 100\n  temperature: 285\n===MEM\nMemTotal:        2889120 kB\nMemAvailable:     310000 kB\n===DF\n/dev/block/dm-6      51234560  41000000   10000000  81% /data\n===PKG\n47\n";
        Bilan b = Bilan.parse(out);
        check("lecture du modele", b.looksValid() && b.title().equals("samsung SM-T510") && b.sdk == 30);
        check("batterie 87 % bonne sante, 28,5 C", b.batteryLevel == 87 && b.batteryHealth == 2 && b.tempTenths == 285);
        check("memoire et stockage lus", b.ramTotalKb == 2889120 && b.ramAvailKb == 310000 && b.storTotalKb == 51234560 && b.storFreeKb == 10000000);
        int warn = 0, bad = 0; for (Bilan.Item i : b.items()) { if (i.level == Bilan.WARN) warn++; if (i.level == Bilan.BAD) bad++; }
        check("stockage 19 % et memoire 10 % signales en orange, animations aussi, rien en rouge", warn == 3 && bad == 0);
        check("reponse vide = pas valide (pas de plantage)", !Bilan.parse("").looksValid() && Bilan.parse("\n\n===BAT\nlevel: x\n").items().isEmpty());
        check("echecs detectes", Repairs.failed("Exception occurred while executing") && Repairs.failed("cmd: Can't find service") && Repairs.failed("** No activities found to run, monkey aborted.") && !Repairs.failed("Success"));
        String secret = "secret-de-test";
        String code = LicenseCodec.make(secret, "ABCD2345");
        check("code valide accepte (avec/sans tirets, minuscules)", LicenseCodec.verify(secret, code) && LicenseCodec.verify(secret, code.toLowerCase().replace("-", " ")));
        check("code modifie refuse", !LicenseCodec.verify(secret, code.substring(0, code.length() - 1) + (code.endsWith("A") ? "B" : "A")));
        check("mauvais secret refuse", !LicenseCodec.verify("autre", code));
        check("vide ou n'importe quoi refuse", !LicenseCodec.verify(secret, "") && !LicenseCodec.verify(secret, null) && !LicenseCodec.verify(secret, "DT-0000-0000-0000-0000"));
        Process p = new ProcessBuilder("python3", "tools/make_licence.py", secret).redirectErrorStream(true).start();
        String py = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine(); p.waitFor();
        check("code fabrique par le script Python accepte par l'appli : " + py, LicenseCodec.verify(secret, py));
        System.out.println(fails == 0 ? "TOUT PASSE" : fails + " ECHEC(S)");
        System.exit(fails);
    }
}
