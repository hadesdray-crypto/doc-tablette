package fr.doctablette.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Les réparations proposées. Chaque étape a plusieurs méthodes : si la première échoue, on passe à la suivante. */
public final class Repairs {
    public static final class Step {
        public final String id, title, explain;
        public final String[] methods;
        public final long timeoutMs;
        public final boolean inAll;

        Step(String id, String title, String explain, long timeoutMs, boolean inAll, String... methods) {
            this.id = id;
            this.title = title;
            this.explain = explain;
            this.timeoutMs = timeoutMs;
            this.inAll = inAll;
            this.methods = methods;
        }
    }

    public static final Step CACHES = new Step("caches", "Vider les caches",
            "Supprime les fichiers temporaires des applis (rien de personnel n'est effacé).", 120000, true,
            "pm trim-caches 512G", "cmd package trim-caches 512G");
    public static final Step BACKGROUND = new Step("background", "Arrêter les applis en arrière-plan",
            "Libère la mémoire en fermant ce qui tourne sans servir.", 30000, true,
            "am kill-all", "cmd activity kill-all");
    public static final Step ANIMS = new Step("anims", "Accélérer les animations",
            "Les fenêtres s'ouvrent plus vite (réglage annulable).", 30000, true,
            "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5");
    public static final Step ANIMS_RESET = new Step("anims_reset", "Remettre les animations d'origine",
            "Retour à la vitesse normale.", 30000, false,
            "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0");
    public static final Step OPTIMIZE = new Step("optimize", "Optimiser les applis",
            "Prépare les applis pour qu'elles démarrent plus vite. Peut durer quelques minutes.", 900000, true,
            "cmd package compile -m speed-profile -a", "cmd package bg-dexopt-job");
    public static final Step REBOOT = new Step("reboot", "Redémarrer la tablette",
            "Un redémarrage propre termine le travail. La connexion sera coupée.", 15000, false,
            "reboot");
    public static final Step SYSTEM_UPDATE = new Step("sysupdate", "Mise à jour du système Android",
            "Ouvre l'écran de mise à jour de la tablette : c'est elle qui télécharge et installe.", 15000, false,
            "am start -a android.settings.SYSTEM_UPDATE_SETTINGS", "am start -a android.settings.DEVICE_INFO_SETTINGS");
    public static final Step PLAY_STORE = new Step("playstore", "Mises à jour des applis (Play Store)",
            "Ouvre le Play Store sur la tablette. Touchez la photo de profil puis « Gérer les applis et l'appareil » puis « Tout mettre à jour ».", 15000, false,
            "monkey -p com.android.vending -c android.intent.category.LAUNCHER 1", "am start -a android.intent.action.VIEW -d market://details?id=com.android.vending");

    /** Dans l'ordre : ce que fait « Tout réparer ». */
    public static List<Step> all() {
        return new ArrayList<>(Arrays.asList(CACHES, BACKGROUND, ANIMS, OPTIMIZE));
    }

    /** Vrai si la réponse de la tablette montre un échec. */
    public static boolean failed(String out) {
        String o = out.toLowerCase(java.util.Locale.ROOT);
        return o.contains("exception") || o.contains("error:") || o.contains("unknown command")
                || o.contains("not found") || o.contains("permission denial") || o.contains("inaccessible")
                || o.contains("no activity found") || o.contains("unable to resolve");
    }

    /** Applis souvent préinstallées et sans danger à désactiver (suggestions seulement). */
    public static final String[][] SUGGESTED = {
            {"com.facebook.appmanager", "Facebook – gestionnaire préinstallé"},
            {"com.facebook.services", "Facebook – services préinstallés"},
            {"com.facebook.system", "Facebook – installateur préinstallé"},
            {"com.netflix.partner.activation", "Netflix – activation préinstallée"},
            {"com.samsung.android.app.tips", "Samsung – astuces"},
            {"com.samsung.android.game.gamehome", "Samsung – Game Launcher"},
            {"com.microsoft.appmanager", "Microsoft – Lien avec Windows"},
    };
}
