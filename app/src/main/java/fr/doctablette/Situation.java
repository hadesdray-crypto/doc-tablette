package fr.doctablette;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;

import java.util.Map;

/** Reconnaît ce qui est branché au câble et dit quoi faire, en français simple. */
final class Situation {
    static final int NONE = 0, ADB = 1, FASTBOOT = 2, MTP = 3, DOWNLOAD = 4, STORAGE = 5, UNKNOWN = 6, RECOVERY = 7, SIDELOAD = 8, UNAUTHORIZED = 9;

    final int kind;
    final String title;      // phrase courte : ce que le téléphone voit
    final String text;       // explication pour un enfant de 10 ans
    final String[] steps;    // la procédure
    final String erases;     // ce qui sera effacé : phrase claire
    final String query;      // mot de recherche pour le guide
    final boolean canBackupByAdb;

    Situation(int kind, String title, String text, String[] steps, String erases, String query, boolean canBackup) {
        this.kind = kind;
        this.title = title;
        this.text = text;
        this.steps = steps;
        this.erases = erases;
        this.query = query;
        this.canBackupByAdb = canBackup;
    }

    // Identifiants USB courants des modes « bas niveau » (fabricant:produit). Indicatif.
    private static String lowLevel(UsbDevice d) {
        int v = d.getVendorId(), p = d.getProductId();
        if (v == 0x0e8d) return "MediaTek (mode préchargeur)";
        if (v == 0x2207) return "Rockchip (mode chargement)";
        if (v == 0x05c6 && p == 0x9008) return "Qualcomm (mode de secours EDL)";
        if (v == 0x04e8 && p == 0x685d) return "Samsung (mode Téléchargement / Odin)";
        if (v == 0x1f3a) return "Allwinner (mode chargement)";
        if (v == 0x1782) return "Unisoc (mode chargement)";
        return null;
    }

    static Situation of(UsbDevice d, String adbBanner, boolean connectedOk, boolean authRefused) {
        boolean adb = false, fastboot = false, mtp = false, storage = false;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            int c = f.getInterfaceClass(), s = f.getInterfaceSubclass(), p = f.getInterfaceProtocol();
            if (c == 255 && s == 66 && p == 1) adb = true;
            else if (c == 255 && s == 66 && p == 3) fastboot = true;
            else if (c == 6 && s == 1) mtp = true;
            else if (c == 255 && s == 255) mtp = mtp || (f.getName() != null && f.getName().contains("MTP"));
            else if (c == 8) storage = true;
        }
        String b = adbBanner == null ? "" : adbBanner;
        if (adb && b.startsWith("sideload")) return new Situation(SIDELOAD, "La tablette est en mode « sideload »",
                "Elle attend qu'on lui envoie un fichier de mise à jour. Ce n'est pas un mode de réparation normal.",
                new String[]{"Sur la tablette, choisissez « Reboot system now » avec les touches volume puis marche."},
                "Rien tant que vous n'envoyez pas de fichier.", "recovery", false);
        if (adb && b.startsWith("recovery")) return new Situation(RECOVERY, "La tablette est en mode récupération",
                "C'est le menu de secours de la tablette. Depuis ici on peut vider le cache, ou tout effacer (attention).",
                new String[]{"Avec les touches volume, choisissez « Wipe cache partition » : cela n'efface aucune photo ni appli.",
                        "N'utilisez « Wipe data / factory reset » qu'en dernier : cela efface TOUT (photos, vidéos, applis).",
                        "Quand c'est fini, choisissez « Reboot system now »."},
                "« Wipe cache » : rien de personnel. « Factory reset » : tout est effacé.", "recovery", false);
        if (adb && authRefused) return new Situation(UNAUTHORIZED, "La tablette refuse la connexion",
                "La tablette n'a pas été autorisée à parler avec ce téléphone.",
                new String[]{"Débranchez puis rebranchez le câble.", "Sur la tablette, quand « Autoriser le débogage USB ? » apparaît, cochez « Toujours autoriser » et touchez « Autoriser ».",
                        "Si rien n'apparaît : Paramètres, Options pour les développeurs, « Révoquer les autorisations de débogage USB », puis rebranchez."},
                "Rien n'est effacé.", "débogage USB", false);
        if (adb) return new Situation(ADB, connectedOk ? "Tablette prête" : "Tablette trouvée (débogage actif)",
                "La tablette parle avec le téléphone. On peut faire le bilan, sauvegarder les photos et réparer.",
                new String[]{"Conseil : sauvegardez d'abord les photos et vidéos.", "Ensuite faites le bilan, puis « Tout réparer »."},
                "Le bilan et la sauvegarde n'effacent rien. « Tout réparer » ne touche ni aux photos, ni aux vidéos : il vide seulement les fichiers temporaires.", "lente", true);
        if (fastboot) return new Situation(FASTBOOT, "La tablette est en mode bootloader (fastboot)",
                "C'est un mode pour installer un système. Il ne montre pas les photos. Ce n'est pas un mode de réparation simple.",
                new String[]{"Pour en sortir : maintenez la touche marche jusqu'au redémarrage (10 à 20 secondes).", "Si elle revient toujours ici, cherchez le modèle dans le guide."},
                "Rien tant que vous n'installez rien.", "bootloader fastboot", false);
        String low = lowLevel(d);
        if (low != null) return new Situation(DOWNLOAD, "Mode bas niveau détecté : " + low,
                "La tablette est dans un mode de réinstallation du système. Un téléphone seul ne peut pas la réparer ici : il faut un ordinateur et le bon logiciel du fabricant.",
                new String[]{"Ne réinstallez rien sans connaître le modèle exact.", "Cherchez le modèle dans le guide : section « Remettre le système d'origine »."},
                "Une réinstallation du système efface TOUT (photos, vidéos, applis).", "firmware", false);
        if (mtp) return new Situation(MTP, "La tablette est branchée comme une clé de transfert (MTP)",
                "Les photos peuvent se copier, mais le mode réparation n'est pas activé. Le débogage USB est éteint.",
                new String[]{"Pour réparer : activez le « Débogage USB » sur la tablette (voir l'aide).", "Pour seulement récupérer des photos, utilisez le guide « Récupérer photos et vidéos »."},
                "Rien n'est effacé.", "récupérer photos", false);
        if (storage) return new Situation(STORAGE, "La tablette est branchée comme un disque",
                "Les fichiers sont lisibles par le téléphone via le gestionnaire de fichiers.",
                new String[]{"Ouvrez l'appli « Fichiers » du téléphone pour copier les photos."}, "Rien n'est effacé.", "récupérer photos", false);
        return new Situation(UNKNOWN, "Un appareil USB est branché, mais je ne le reconnais pas",
                "Ce peut être un câble de charge seule, ou une tablette dans un mode inconnu.",
                new String[]{"Essayez un autre câble (qui transmet les données).", "Sur la tablette, tirez le bandeau du haut : choisissez « Transfert de fichiers »."},
                "Rien n'est effacé.", "câble", false);
    }

    static Situation nothing(boolean hostOk) {
        if (!hostOk) return new Situation(NONE, "Ce téléphone ne peut pas piloter une tablette",
                "Il ne gère pas le mode « hôte USB ». Essayez un autre téléphone.", new String[]{}, "Rien.", "OTG", false);
        return new Situation(NONE, "Je ne vois aucune tablette",
                "Si l'écran de la tablette est noir, c'est peut-être qu'elle est éteinte ou que le câble ne transmet pas les données.",
                new String[]{"Vérifiez que l'adaptateur est bien enfoncé des deux côtés.", "Essayez le câble de la tablette (celui du chargeur) avec un adaptateur.",
                        "Laissez-la charger 30 minutes, puis appuyez 10 secondes sur la touche marche.",
                        "Toujours rien ? Ouvrez le guide « Ne s'allume pas »."},
                "Rien n'est effacé.", "ne s'allume pas", false);
    }

    static Situation best(Map<String, UsbDevice> all, String banner, boolean connected, boolean authRefused, boolean hostOk) {
        if (all == null || all.isEmpty() || !hostOk) return nothing(hostOk);
        Situation best = null;
        for (UsbDevice d : all.values()) {
            Situation s = of(d, banner, connected, authRefused);
            if (best == null || s.kind == ADB || s.kind == RECOVERY || (best.kind == UNKNOWN)) best = s;
        }
        return best;
    }
}
