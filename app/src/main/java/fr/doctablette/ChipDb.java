package fr.doctablette;

/** Table des puces (tablettes chinoises, Archos, etc.) : reconnaissance par identifiants USB, conseils honnêtes. */
final class ChipDb {
    private ChipDb() {
    }

    static final class Chip {
        final String family, mode, explain;
        final String[] steps;
        final boolean probe; // un test de lecture seule existe dans l'appli

        Chip(String family, String mode, String explain, String[] steps, boolean probe) {
            this.family = family;
            this.mode = mode;
            this.explain = explain;
            this.steps = steps;
            this.probe = probe;
        }
    }

    /** Renvoie null si l'identifiant n'est pas un mode de chargement connu. */
    static Chip of(int vid, int pid) {
        switch (vid) {
            case 0x0e8d:
                return new Chip("MediaTek", pid == 0x0003 ? "BootROM (mode d'urgence)" : pid == 0x2000 || pid == 0x2001 ? "Préchargeur" : "Mode MediaTek",
                        "Puce MediaTek, très courante dans les tablettes d'entrée de gamme (Archos, Alcatel, Lenovo, Wiko, tablettes sans marque).",
                        new String[]{"Le test de lecture seule identifie la puce sans rien écrire.",
                                "Pour réinstaller le système : il faut le fichier d'origine de CE modèle (firmware au format « scatter »).",
                                "Ne flashez jamais un fichier d'un autre modèle."}, true);
            case 0x2207:
                return new Chip("Rockchip", pid >= 0x3000 || pid == 0x2910 ? "Mode Loader (Rockusb)" : "Mode Maskrom (urgence)",
                        "Puce Rockchip, fréquente dans les tablettes chinoises et certains Archos (famille RK3066, RK3188, RK3288, RK3399…).",
                        new String[]{"Le test de lecture seule vérifie que la puce répond. « Redémarrer » est aussi proposé.",
                                "Pour réinstaller le système : fichier d'origine de CE modèle (update.img ou images séparées).",
                                "En Maskrom, la puce attend un petit programme de démarrage propre à son modèle."}, true);
            case 0x05c6:
                if (pid == 0x9008)
                    return new Chip("Qualcomm", "EDL (mode de secours 9008)",
                            "Mode de secours Qualcomm. Il exige un fichier « programmer » signé par le fabricant du modèle.",
                            new String[]{"Sans le programmer officiel de CE modèle, rien n'est possible.", "Ne pas insister : le mode est sans danger tant qu'on n'écrit rien."}, false);
                return null;
            case 0x1f3a:
                return new Chip("Allwinner", "Mode FEL (chargement)",
                        "Puce Allwinner (A20, A33, A64, H3…) : tablettes économiques et certains Archos.",
                        new String[]{"Réinstallation par image PhoenixSuite/LiveSuit propre au modèle.", "Pas de test de lecture dans cette version : identification seulement."}, false);
            case 0x1782:
                return new Chip("Unisoc / Spreadtrum", "Mode téléchargement",
                        "Puce Unisoc (SC7731, SC9863, T610…) : tablettes et téléphones d'entrée de gamme.",
                        new String[]{"Réinstallation par fichier .pac propre au modèle.", "Pas de test de lecture dans cette version : identification seulement."}, false);
            case 0x1286:
                return new Chip("Marvell", "Mode chargement", "Puce ancienne (certains PDA et tablettes anciennes).",
                        new String[]{"Identification seulement."}, false);
            case 0x0955:
                return new Chip("NVIDIA Tegra", "Mode de récupération (APX)", "Anciennes tablettes Tegra (Tegra 2/3/4).",
                        new String[]{"Identification seulement."}, false);
            case 0x2836:
                return new Chip("Amlogic", "Mode chargement", "Puce Amlogic : boîtiers TV et quelques tablettes.",
                        new String[]{"Identification seulement."}, false);
            default:
                return null;
        }
    }

    /** Noms probables de puces MediaTek d'après le code matériel (à vérifier). */
    static String mtkName(int hw) {
        switch (hw) {
            case 0x6572: return "MT6572";
            case 0x6582: return "MT6582";
            case 0x6592: return "MT6592";
            case 0x0321: return "MT6735";
            case 0x0326: return "MT6580";
            case 0x0335: return "MT6737";
            case 0x0766: return "MT6765";
            case 0x0707: return "MT6768";
            case 0x0788: return "MT6771";
            default: return null;
        }
    }
}
