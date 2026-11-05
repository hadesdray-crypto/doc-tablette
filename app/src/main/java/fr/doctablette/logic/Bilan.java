package fr.doctablette.logic;

import java.util.ArrayList;
import java.util.List;

/** Bilan de santé d'une tablette, lu à partir de la réponse de la commande SCRIPT. */
public final class Bilan {
    public static final int OK = 0, WARN = 1, BAD = 2;

    /** Commande unique envoyée à la tablette. */
    public static final String SCRIPT =
            "echo \"M=$(getprop ro.product.manufacturer)\"; echo \"N=$(getprop ro.product.model)\"; "
                    + "echo \"V=$(getprop ro.build.version.release)\"; echo \"S=$(getprop ro.build.version.sdk)\"; "
                    + "echo \"A=$(getprop ro.product.cpu.abi)\"; echo \"ANIM=$(settings get global window_animation_scale)\"; "
                    + "echo ===BAT; dumpsys battery; echo ===MEM; grep -E 'MemTotal|MemAvailable' /proc/meminfo; "
                    + "echo ===DF; df /data | tail -n 1; echo ===PKG; pm list packages -3 | wc -l";

    public static final class Item {
        public final String label, value, advice;
        public final int level;

        Item(String label, String value, int level, String advice) {
            this.label = label;
            this.value = value;
            this.level = level;
            this.advice = advice;
        }
    }

    public String maker = "", model = "", android = "", abi = "", animScale = "";
    public int sdk = -1, batteryLevel = -1, batteryHealth = -1, tempTenths = -1, userApps = -1;
    public long ramTotalKb = -1, ramAvailKb = -1, storTotalKb = -1, storFreeKb = -1;

    public static Bilan parse(String out) {
        Bilan b = new Bilan();
        String section = "";
        for (String raw : out.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("===")) {
                section = l;
                continue;
            }
            if (section.isEmpty()) {
                if (l.startsWith("M=")) b.maker = l.substring(2);
                else if (l.startsWith("N=")) b.model = l.substring(2);
                else if (l.startsWith("V=")) b.android = l.substring(2);
                else if (l.startsWith("S=")) b.sdk = toInt(l.substring(2), -1);
                else if (l.startsWith("A=")) b.abi = l.substring(2);
                else if (l.startsWith("ANIM=")) b.animScale = l.substring(5);
            } else if (section.equals("===BAT")) {
                if (l.startsWith("level:")) b.batteryLevel = toInt(l.substring(6).trim(), -1);
                else if (l.startsWith("health:")) b.batteryHealth = toInt(l.substring(7).trim(), -1);
                else if (l.startsWith("temperature:")) b.tempTenths = toInt(l.substring(12).trim(), -1);
            } else if (section.equals("===MEM")) {
                if (l.startsWith("MemTotal:")) b.ramTotalKb = kb(l);
                else if (l.startsWith("MemAvailable:")) b.ramAvailKb = kb(l);
            } else if (section.equals("===DF")) {
                String[] t = l.split("\\s+");
                if (t.length >= 5) { // ... total utilisé libre pourcentage point de montage
                    long total = toLong(t[t.length - 5], -1), free = toLong(t[t.length - 3], -1);
                    if (total > 0 && free >= 0) {
                        b.storTotalKb = total;
                        b.storFreeKb = free;
                    }
                }
            } else if (section.equals("===PKG")) {
                b.userApps = toInt(l, -1);
            }
        }
        return b;
    }

    private static long kb(String l) {
        String[] t = l.split("\\s+");
        return t.length >= 2 ? toLong(t[1], -1) : -1;
    }

    private static int toInt(String s, int d) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return d;
        }
    }

    private static long toLong(String s, long d) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return d;
        }
    }

    public boolean looksValid() {
        return !model.isEmpty() || sdk > 0;
    }

    public String title() {
        String m = (maker + " " + model).trim();
        return m.isEmpty() ? "Tablette" : m;
    }

    private static String gb(long kb) {
        double g = kb / 1048576.0;
        return String.format(java.util.Locale.FRANCE, "%.1f Go", g);
    }

    public List<Item> items() {
        List<Item> r = new ArrayList<>();
        if (sdk > 0) {
            int lvl = sdk < 26 ? BAD : sdk < 30 ? WARN : OK;
            String adv = sdk < 26 ? "Android très ancien : plus de sécurité ni d'applis récentes. Les réparations aident un peu, pas plus."
                    : sdk < 30 ? "Android ancien : certaines applis récentes ne s'installeront plus." : "";
            r.add(new Item("Android", android + " (niveau " + sdk + ")", lvl, adv));
        }
        if (storTotalKb > 0) {
            double pct = 100.0 * storFreeKb / storTotalKb;
            int lvl = pct < 10 ? BAD : pct < 20 ? WARN : OK;
            String adv = lvl == OK ? "" : "Stockage presque plein : c'est la première cause de lenteur. Videz les caches et retirez les applis inutiles.";
            r.add(new Item("Stockage libre", gb(storFreeKb) + " sur " + gb(storTotalKb) + " (" + Math.round(pct) + " %)", lvl, adv));
        }
        if (ramTotalKb > 0 && ramAvailKb >= 0) {
            double pct = 100.0 * ramAvailKb / ramTotalKb;
            int lvl = pct < 8 ? BAD : pct < 15 ? WARN : OK;
            String adv = lvl == OK ? "" : "Mémoire presque saturée : arrêtez les applis en arrière-plan et désactivez celles qui ne servent pas.";
            r.add(new Item("Mémoire (RAM) libre", gb(ramAvailKb) + " sur " + gb(ramTotalKb), lvl, adv));
        }
        if (batteryLevel >= 0) {
            String h;
            int lvl;
            String adv = "";
            switch (batteryHealth) {
                case 2:
                    h = "bonne";
                    lvl = OK;
                    break;
                case 3:
                    h = "surchauffe";
                    lvl = BAD;
                    adv = "La batterie a chauffé : laissez-la refroidir.";
                    break;
                case 4:
                    h = "morte";
                    lvl = BAD;
                    adv = "Batterie à remplacer (réparation matérielle).";
                    break;
                case 5:
                    h = "surtension";
                    lvl = BAD;
                    adv = "Problème de chargeur ou de batterie.";
                    break;
                case 6:
                    h = "défaillante";
                    lvl = BAD;
                    adv = "Batterie à remplacer (réparation matérielle).";
                    break;
                case 7:
                    h = "trop froide";
                    lvl = WARN;
                    break;
                default:
                    h = "non indiquée";
                    lvl = OK;
            }
            r.add(new Item("Batterie", batteryLevel + " %, santé " + h, lvl, adv));
        }
        if (tempTenths >= 0) {
            double c = tempTenths / 10.0;
            int lvl = c >= 50 ? BAD : c >= 42 ? WARN : OK;
            r.add(new Item("Température", String.format(java.util.Locale.FRANCE, "%.1f °C", c), lvl,
                    lvl == OK ? "" : "Tablette chaude : elle se ralentit toute seule. Laissez-la refroidir avant de réparer."));
        }
        if (userApps >= 0) {
            int lvl = userApps > 100 ? BAD : userApps > 60 ? WARN : OK;
            r.add(new Item("Applis installées", String.valueOf(userApps), lvl,
                    lvl == OK ? "" : "Beaucoup d'applis : repérez celles que vous n'utilisez plus dans « Applis »."));
        }
        if (!animScale.isEmpty() && !animScale.equals("null")) {
            boolean fast = false;
            try {
                fast = Float.parseFloat(animScale.replace(',', '.')) <= 0.5f;
            } catch (Exception ignored) {
            }
            r.add(new Item("Animations", "vitesse ×" + animScale, fast ? OK : WARN,
                    fast ? "" : "Accélérer les animations donne une impression de grande fluidité."));
        }
        return r;
    }
}
