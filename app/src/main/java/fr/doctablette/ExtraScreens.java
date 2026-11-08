package fr.doctablette;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Écrans ajoutés : situation au branchement, sauvegarde, guide, matériel, mode enfant. */
final class ExtraScreens {
    static final int REQ_TREE = 12;
    private final MainActivity a;

    // état
    Situation situation;
    String guideCat = "";
    boolean guideUnconv;
    String guideQuery = "";
    Guide.Card card;
    boolean authRefused;

    Uri tree;
    final boolean[] kindOn = {true, false, false, false};
    List<Backup.Item> items;
    String backupMsg = "";
    boolean backupBusy;
    Backup backup;
    TextView backupLine;
    ProgressBar backupBar;

    ExtraScreens(MainActivity a) {
        this.a = a;
        String t = a.prefs.getString("backup_tree", "");
        if (!t.isEmpty()) tree = Uri.parse(t);
    }

    // ------------------------------------------------------------------
    // Situation détectée au branchement
    // ------------------------------------------------------------------
    void refreshSituation() {
        String banner = a.link.cx != null ? a.link.cx.banner() : "";
        situation = Situation.best(a.usb.getDeviceList(), banner, a.link.isConnected(), authRefused,
                a.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST));
    }

    void situationScreen(LinearLayout c) {
        refreshSituation();
        Situation s = situation;
        LinearLayout k = a.card(s.kind == Situation.ADB ? MainActivity.C_OK : MainActivity.C_WARN);
        k.addView(a.tv(s.title, 19, MainActivity.TEXT, true));
        k.addView(a.tv(s.text, 16, MainActivity.TEXT, false));
        c.addView(k);
        if (s.steps.length > 0) {
            c.addView(a.tv("Quoi faire", 17, MainActivity.ACCENT, true));
            int n = 1;
            for (String st : s.steps) c.addView(a.tv(n++ + ". " + st, 16, MainActivity.TEXT, false));
        }
        LinearLayout e = a.card(MainActivity.C_WARN);
        e.addView(a.tv("Ce qui sera effacé", 16, MainActivity.C_WARN, true));
        e.addView(a.tv(s.erases, 15, MainActivity.TEXT, false));
        c.addView(e);
        if (s.kind == Situation.ADB) {
            if (a.link.isConnected()) {
                c.addView(a.btn("Sauvegarder mes photos et vidéos", true, v -> a.go("BACKUP")));
                c.addView(a.btn("Faire le bilan", false, v -> {
                    a.go("BILAN");
                    a.runBilan();
                }));
                c.addView(a.btn("Tout réparer", false, v -> a.go("REPAIR")));
            } else {
                c.addView(a.btn("Se connecter à la tablette", true, v -> a.go("CONNECT")));
            }
        }
        if (s.kind == Situation.ODIN) c.addView(a.btn("Ouvrir le mode Téléchargement Samsung", true, v -> a.go("ODIN")));
        if (chipDevice() != null) c.addView(a.btn("Ouvrir l'écran des puces (Archos, tablettes chinoises)", true, v -> a.go("CHIP")));
        if (s.kind == Situation.FASTBOOT) c.addView(a.btn("Ouvrir les outils fastboot", true, v -> a.go("FASTBOOT")));
        if (s.kind == Situation.UNAUTHORIZED) c.addView(a.btn("Réessayer la connexion", true, v -> a.go("CONNECT")));
        c.addView(a.btn("Voir le guide pour ce cas", false, v -> openGuide(s.query)));
        c.addView(a.btn("Chercher à nouveau", false, v -> {
            a.scanUsb(true);
            a.render();
        }));
    }

    // ------------------------------------------------------------------
    // Sauvegarde photos / vidéos / documents
    // ------------------------------------------------------------------
    void backupScreen(LinearLayout c) {
        if (!a.needTablet(c)) return;
        c.addView(a.tv("La sauvegarde COPIE les fichiers de la tablette vers votre téléphone. Elle n'efface rien sur la tablette.", 16, MainActivity.TEXT, false));
        LinearLayout k = a.card(tree != null ? MainActivity.C_OK : 0);
        k.addView(a.tv(tree != null ? "Dossier de sauvegarde choisi." : "1. Choisissez où mettre la sauvegarde.", 16, MainActivity.TEXT, true));
        c.addView(k);
        if (!backupBusy) c.addView(a.btn(tree != null ? "Changer le dossier" : "Choisir le dossier", tree == null, v -> pickTree()));
        c.addView(a.tv("2. Que copier ?", 16, MainActivity.TEXT, true));
        for (int i = 0; i < Backup.KINDS.length; i++) {
            final int idx = i;
            Button b = a.btn((kindOn[i] ? "[x] " : "[  ] ") + Backup.KINDS[i][0], false, v -> {
                if (backupBusy) return;
                kindOn[idx] = !kindOn[idx];
                items = null;
                a.render();
            });
            c.addView(b);
        }
        if (items != null) {
            long tot = 0;
            for (Backup.Item it : items) if (it.size > 0) tot += it.size;
            LinearLayout r = a.card(0);
            r.addView(a.tv(items.size() + " fichiers trouvés" + (tot > 0 ? ", environ " + mo(tot) : "") + ".", 16, MainActivity.TEXT, true));
            r.addView(a.tv("Vérifiez qu'il reste assez de place sur le téléphone. Les fichiers déjà copiés sont passés.", 14, MainActivity.MUTED, false));
            c.addView(r);
        }
        if (!backupBusy) {
            c.addView(a.btn("3. Analyser la tablette", false, v -> analyze()));
            if (items != null && !items.isEmpty() && tree != null) c.addView(a.btn("4. Copier maintenant", true, v -> startCopy()));
        } else {
            c.addView(a.btn("Arrêter la copie", false, v -> {
                if (backup != null) backup.cancel = true;
            }));
        }
        LinearLayout st = a.card(0);
        backupLine = a.tv(backupMsg.isEmpty() ? "Le déroulement s'affichera ici." : backupMsg, 14, MainActivity.MUTED, false);
        st.addView(backupLine);
        backupBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        backupBar.setMax(1000);
        st.addView(backupBar);
        c.addView(st);
    }

    static String mo(long b) {
        if (b > 1073741824L) return String.format(java.util.Locale.FRANCE, "%.1f Go", b / 1073741824.0);
        return (b / 1048576) + " Mo";
    }

    void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            a.startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            a.info("Impossible", "Ce téléphone n'a pas de sélecteur de dossier. Utilisez le guide « Récupérer photos et vidéos ».");
        }
    }

    void onTree(Uri u) {
        if (u == null) return;
        try {
            a.getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        tree = u;
        a.prefs.edit().putString("backup_tree", u.toString()).apply();
        a.render();
    }

    void setBackupMsg(String m) {
        backupMsg = m;
        a.ui.post(() -> {
            if (backupLine != null) backupLine.setText(m);
        });
    }

    void analyze() {
        StringBuilder dirs = new StringBuilder();
        for (int i = 0; i < Backup.KINDS.length; i++) if (kindOn[i]) dirs.append(Backup.KINDS[i][1]).append(' ');
        if (dirs.length() == 0) {
            a.toast("Cochez au moins une catégorie");
            return;
        }
        backupBusy = true;
        setBackupMsg("Analyse de la tablette… (peut durer une minute)");
        a.render();
        final String d = dirs.toString();
        a.io.execute(() -> {
            try {
                List<Backup.Item> l = Backup.scan(a.link, d);
                a.ui.post(() -> {
                    items = l;
                    backupBusy = false;
                    backupMsg = l.isEmpty() ? "Aucun fichier trouvé dans ces dossiers." : "Analyse terminée.";
                    a.render();
                });
            } catch (Exception e) {
                a.ui.post(() -> {
                    backupBusy = false;
                    backupMsg = "Analyse impossible : " + TabletLink.explain(e);
                    a.render();
                });
            }
        });
    }

    void startCopy() {
        if (tree == null || items == null || backupBusy) return;
        backupBusy = true;
        backup = new Backup(a, tree);
        final List<Backup.Item> list = items;
        setBackupMsg("Copie en cours…");
        a.render();
        a.io.execute(() -> {
            try {
                if (a.link.cx != null) a.link.cx.setIdleExtend(60000);
            } catch (Exception ignored) {
            }
            Backup.Result r = backup.run(a.link, list, (n, total, name, done, bytes) -> a.ui.post(() -> {
                if (backupLine != null) backupLine.setText("Copie " + n + " / " + total + "\n" + name);
                if (backupBar != null && bytes > 0) backupBar.setProgress((int) (1000L * done / bytes));
            }));
            try {
                if (a.link.cx != null) a.link.cx.setIdleExtend(0);
            } catch (Exception ignored) {
            }
            StringBuilder sb = new StringBuilder();
            sb.append(r.cancelled ? "Copie arrêtée.\n" : "Copie terminée.\n");
            sb.append(r.copied).append(" copiés (").append(mo(r.bytes)).append("), ").append(r.skipped).append(" déjà présents, ").append(r.failed).append(" en échec.");
            for (String f : r.failures) sb.append("\n- ").append(f);
            final String msg = sb.toString();
            a.ui.post(() -> {
                backupBusy = false;
                backupMsg = msg;
                a.render();
            });
        });
    }

    // ------------------------------------------------------------------
    // Photo de l'étiquette ou du code-barres
    // ------------------------------------------------------------------
    static final int REQ_PHOTO = 14, REQ_PICK = 15;
    Uri photoUri;
    String scanMsg = "";
    boolean scanBusy;
    final List<String> scanModels = new ArrayList<>(), scanBarcodes = new ArrayList<>();
    String scanBrand = "", scanText = "";

    // Début de référence -> marque et famille (seulement des préfixes sûrs)
    static final String[][] PREFIX = {
            {"SM-T", "Samsung Galaxy Tab"}, {"SM-P", "Samsung Galaxy Tab (S Pen)"}, {"SM-X", "Samsung Galaxy Tab"},
            {"SM-A", "Samsung Galaxy A"}, {"SM-S", "Samsung Galaxy S"}, {"SM-G", "Samsung Galaxy"}, {"SM-F", "Samsung Galaxy Z"},
            {"SM-N", "Samsung Galaxy Note"}, {"SM-M", "Samsung Galaxy M"}, {"GT-", "Samsung (ancien modèle)"},
            {"TB-", "Lenovo Tab"}, {"YT-", "Lenovo Yoga Tab"}, {"KF", "Amazon Fire"},
            {"AC", "Archos (probable)"}, {"A101", "Archos"}, {"A70", "Archos"}, {"A80", "Archos"}
    };

    void photoScreen(LinearLayout c) {
        c.addView(a.tv("Photographiez l'étiquette au dos de la tablette ou du téléphone (ou sa boîte). L'appli lit le texte et le code-barres, reconnaît la marque et le modèle, puis ouvre les fiches qui correspondent. Tout reste sur ce téléphone.", 15, MainActivity.TEXT, false));
        c.addView(a.tv("À savoir : un code-barres contient une référence de vente ou un numéro de série, pas le modèle. Le modèle se lit surtout sur le texte de l'étiquette.", 13, MainActivity.MUTED, false));
        if (!scanBusy) {
            c.addView(a.btn("Photographier l'étiquette", true, v -> takePhoto()));
            c.addView(a.btn("Choisir une photo déjà prise", false, v -> {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                try {
                    a.startActivityForResult(i, REQ_PICK);
                } catch (Exception e) {
                    a.toast("Aucune galerie");
                }
            }));
        }
        if (!scanMsg.isEmpty()) {
            LinearLayout k = a.card(0);
            k.addView(a.tv(scanMsg, 15, MainActivity.TEXT, false));
            c.addView(k);
        }
        if (!scanBrand.isEmpty()) c.addView(a.tv("Marque / famille probable : " + scanBrand, 17, MainActivity.C_OK, true));
        for (final String m : scanModels) {
            c.addView(a.btn("Chercher « " + m + " » dans le guide", true, v -> openGuide(m)));
        }
        if (!scanBrand.isEmpty()) {
            final String q = scanBrand.replace(" (probable)", "").replace(" (ancien modèle)", "");
            c.addView(a.btn("Guide pour " + q, false, v -> openGuide(q)));
        }
        for (String b : scanBarcodes) c.addView(a.tv("Code-barres lu : " + b, 14, MainActivity.MUTED, false));
        if (!scanText.isEmpty()) c.addView(a.tv("Texte lu :\n" + scanText, 13, MainActivity.MUTED, false));
        c.addView(a.tv("Ou écrivez le modèle à la main", 16, MainActivity.TEXT, true));
        final EditText e = new EditText(a);
        e.setHint("Ex. SM-T510, TB-X606F, Archos…");
        e.setHintTextColor(MainActivity.MUTED);
        e.setTextColor(MainActivity.TEXT);
        e.setSingleLine(true);
        a.keepDraft("modele", e);
        c.addView(e, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        c.addView(a.btn("Chercher", false, v -> {
            String t = e.getText().toString().trim();
            if (!t.isEmpty()) openGuide(t);
        }));
    }

    void takePhoto() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "doc-tablette-etiquette-" + System.currentTimeMillis() + ".jpg");
                cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Doc Tablette");
                cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
                photoUri = a.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri);
                a.startActivityForResult(i, REQ_PHOTO);
            } else {
                scanMsg = "Sur ce téléphone, prenez la photo avec l'appli Appareil photo, puis touchez « Choisir une photo déjà prise ».";
                a.render();
            }
        } catch (Exception e) {
            scanMsg = "Impossible d'ouvrir l'appareil photo. Touchez « Choisir une photo déjà prise » ou écrivez le modèle.";
            a.render();
        }
    }

    void onPhoto(int req, boolean ok, Intent data) {
        Uri u = req == REQ_PHOTO ? photoUri : (data == null ? null : data.getData());
        if (req == REQ_PHOTO && photoUri != null) {
            try {
                if (ok) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                    a.getContentResolver().update(photoUri, cv, null, null);
                } else {
                    a.getContentResolver().delete(photoUri, null, null); // photo annulée : pas d'image vide dans la galerie
                }
            } catch (Exception ignored) {
            }
        }
        if (!ok || u == null) {
            scanMsg = "Aucune photo.";
            a.render();
            return;
        }
        scanBusy = true;
        scanMsg = "Lecture de la photo…";
        scanModels.clear();
        scanBarcodes.clear();
        scanBrand = "";
        scanText = "";
        a.render();
        try {
            final com.google.mlkit.vision.common.InputImage img = com.google.mlkit.vision.common.InputImage.fromFilePath(a, u);
            final int[] pending = {2};
            final StringBuilder all = new StringBuilder();
            com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(img)
                    .addOnSuccessListener(t -> {
                        all.append(t.getText());
                        done(pending, all);
                    })
                    .addOnFailureListener(e -> done(pending, all));
            com.google.mlkit.vision.barcode.BarcodeScanning.getClient().process(img)
                    .addOnSuccessListener(list -> {
                        for (com.google.mlkit.vision.barcode.common.Barcode b : list) {
                            String v = b.getRawValue();
                            if (v != null && !scanBarcodes.contains(v)) scanBarcodes.add(v);
                        }
                        done(pending, all);
                    })
                    .addOnFailureListener(e -> done(pending, all));
        } catch (Exception e) {
            scanBusy = false;
            scanMsg = "Photo illisible. Réessayez avec plus de lumière, ou écrivez le modèle.";
            a.render();
        }
    }

    private void done(int[] pending, StringBuilder all) {
        if (--pending[0] > 0) return;
        scanBusy = false;
        String txt = all.toString();
        scanText = txt.length() > 400 ? txt.substring(0, 400) : txt;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b([A-Z]{1,4}[- ]?[A-Z0-9]{0,2}[0-9][A-Z0-9\\-]{2,10})\\b").matcher(txt.toUpperCase(java.util.Locale.ROOT));
        while (m.find() && scanModels.size() < 5) {
            String cand = m.group(1).replace(' ', '-');
            if (cand.length() < 5 || scanModels.contains(cand)) continue;
            for (String[] p : PREFIX) {
                if (cand.startsWith(p[0])) {
                    scanModels.add(cand);
                    if (scanBrand.isEmpty()) scanBrand = p[1];
                    break;
                }
            }
        }
        // marques écrites en toutes lettres
        String low = txt.toLowerCase(java.util.Locale.ROOT);
        if (scanBrand.isEmpty()) for (String br : new String[]{"archos", "samsung", "lenovo", "huawei", "xiaomi", "alcatel", "wiko", "asus", "acer", "amazon", "medion", "trekstor", "odys", "polaroid", "blackview", "teclast", "chuwi", "honor", "oppo", "realme", "nokia", "motorola"})
            if (low.contains(br)) {
                scanBrand = br.substring(0, 1).toUpperCase() + br.substring(1);
                break;
            }
        if (txt.trim().isEmpty() && scanBarcodes.isEmpty()) scanMsg = "Rien de lisible sur la photo. Rapprochez-vous, avec de la lumière, sans reflet.";
        else if (scanModels.isEmpty() && scanBrand.isEmpty()) scanMsg = "Texte lu, mais aucun modèle reconnu. Vérifiez ci-dessous ou écrivez le modèle.";
        else scanMsg = "Lecture terminée. Vérifiez que le modèle est exact avant d'agir.";
        a.ui.post(a::render);
    }

    // ------------------------------------------------------------------
    // Fastboot (tablette en mode bootloader)
    // ------------------------------------------------------------------
    fr.doctablette.adb.Fastboot fb;
    String fbInfo = "";
    boolean fbBusy;
    TextView fbLine;
    String fbPartition = "boot";
    Uri fbImage;

    android.hardware.usb.UsbDevice fastbootDevice() {
        for (android.hardware.usb.UsbDevice d : a.usb.getDeviceList().values())
            if (fr.doctablette.adb.UsbAdbChannel.hasFastbootInterface(d)) return d;
        return null;
    }

    void fbSay(String m) {
        fbInfo = m;
        a.ui.post(() -> {
            if (fbLine != null) fbLine.setText(m);
        });
    }

    void fastbootScreen(LinearLayout c) {
        android.hardware.usb.UsbDevice d = fastbootDevice();
        c.addView(a.tv("La tablette est en mode bootloader (fastboot). Ce mode sert à réinstaller des parties du système. Une mauvaise image peut rendre la tablette inutilisable : n'installez que l'image officielle exacte de CE modèle.", 15, MainActivity.TEXT, false));
        if (d == null) {
            fb = null;
            c.addView(a.tv("Aucune tablette en mode fastboot n'est branchée.", 16, MainActivity.C_WARN, true));
            return;
        }
        fbLine = a.tv(fbInfo.isEmpty() ? "Touchez « Lire les informations »." : fbInfo, 14, MainActivity.MUTED, false);
        LinearLayout k = a.card(0);
        k.addView(fbLine);
        c.addView(k);
        if (fbBusy) return;
        if (!a.usb.hasPermission(d)) {
            final android.hardware.usb.UsbDevice dd = d;
            c.addView(a.btn("Autoriser l'accès USB à la tablette", true, v -> {
                android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(a, 0,
                        new Intent(MainActivity.ACTION_USB_PERM).setPackage(a.getPackageName()),
                        android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                a.usb.requestPermission(dd, pi);
            }));
            return;
        }
        c.addView(a.btn("Lire les informations de la tablette", true, v -> fbRun(() -> {
            StringBuilder sb = new StringBuilder();
            for (String n : new String[]{"product", "variant", "serialno", "secure", "unlocked", "current-slot", "max-download-size", "version-bootloader"}) {
                String val = fb.getvar(n);
                if (!val.isEmpty()) sb.append(n).append(" : ").append(val).append("\n");
            }
            fbSay(sb.length() == 0 ? "L'appareil n'a rien répondu." : sb.toString());
        })));
        c.addView(a.btn("Redémarrer normalement", false, v -> a.ask("Redémarrer ?", "La tablette redémarre sur son système.", "Redémarrer", () -> fbRun(() -> {
            fb.command("reboot", 8000);
            fbSay("Redémarrage demandé.");
        }))));
        c.addView(a.btn("Redémarrer en mode récupération", false, v -> a.ask("Redémarrer en récupération ?", "La tablette redémarre dans son menu de secours. Rien n'est effacé par ce redémarrage.", "Redémarrer", () -> fbRun(() -> {
            fr.doctablette.adb.Fastboot.Reply r = fb.command("reboot-recovery", 8000);
            fbSay(r.ok ? "Demande envoyée." : "Non pris en charge par cet appareil : " + r.text);
        }))));
        c.addView(a.btn("Vider le cache (sans toucher aux photos)", false, v -> a.ask("Vider le cache ?", "Efface seulement la partition de cache. Vos photos, vidéos et applis ne sont pas touchées.", "Vider", () -> fbRun(() -> {
            fr.doctablette.adb.Fastboot.Reply r = fb.command("erase:cache", 60000);
            fbSay(r.ok ? "Cache vidé." : "Refusé : " + r.text + " (appareil verrouillé ?)");
        }))));
        c.addView(a.tv("Installer une image (avancé)", 17, MainActivity.ACCENT, true));
        final EditText e = new EditText(a);
        e.setText(fbPartition);
        e.setHint("Partition : boot, recovery…");
        e.setHintTextColor(MainActivity.MUTED);
        e.setTextColor(MainActivity.TEXT);
        e.setSingleLine(true);
        c.addView(e, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        c.addView(a.btn(fbImage == null ? "Choisir le fichier image (.img)" : "Fichier choisi : changer", false, v -> {
            fbPartition = e.getText().toString().trim();
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                a.startActivityForResult(i, REQ_IMG);
            } catch (Exception ex) {
                a.toast("Aucun sélecteur de fichiers");
            }
        }));
        if (fbImage != null) c.addView(a.btn("Installer cette image maintenant", false, v -> {
            fbPartition = e.getText().toString().trim();
            a.ask("ATTENTION", "Vous allez écrire l'image dans la partition « " + fbPartition + " ».\n\nUne image qui ne correspond pas EXACTEMENT à ce modèle peut empêcher la tablette de démarrer. Les données peuvent être perdues (surtout avec « system » ou « userdata »).\n\nSauvegardez d'abord les photos si la tablette démarre encore.", "Installer", this::fbFlash);
        }));
    }

    static final int REQ_IMG = 13;

    void onImage(Uri u) {
        fbImage = u;
        a.render();
    }

    void fbRun(final FbTask t) {
        fbBusy = true;
        fbSay("En cours…");
        a.render();
        a.io.execute(() -> {
            fr.doctablette.adb.UsbAdbChannel ch = null;
            try {
                android.hardware.usb.UsbDevice d = fastbootDevice();
                if (d == null) throw new java.io.IOException("Tablette débranchée");
                if (!a.usb.hasPermission(d)) throw new java.io.IOException("Accès USB non accordé : rebranchez et acceptez la demande");
                ch = new fr.doctablette.adb.UsbAdbChannel(a.usb, d, 3);
                fb = new fr.doctablette.adb.Fastboot(ch);
                t.run();
            } catch (Exception ex) {
                fbSay("Échec : " + ex.getMessage());
            } finally {
                if (ch != null) try {
                    ch.close();
                } catch (Exception ignored) {
                }
                fb = null;
                a.ui.post(() -> {
                    fbBusy = false;
                    a.render();
                });
            }
        });
    }

    interface FbTask {
        void run() throws Exception;
    }

    void fbFlash() {
        final Uri u = fbImage;
        final String part = fbPartition;
        fbRun(() -> {
            long len;
            try (android.content.res.AssetFileDescriptor afd = a.getContentResolver().openAssetFileDescriptor(u, "r")) {
                len = afd == null ? -1 : afd.getLength();
            }
            if (len <= 0) throw new java.io.IOException("Taille du fichier inconnue");
            try (java.io.InputStream in = a.getContentResolver().openInputStream(u)) {
                fr.doctablette.adb.Fastboot.Reply r = fb.flash(part, in, len, (s, t) -> fbSay("Envoi " + (100 * s / t) + " %"));
                fbSay(r.ok ? "Image installée dans « " + part + " ». Redémarrez la tablette." : "Refusé par la tablette : " + r.text);
            }
        });
    }

    // ------------------------------------------------------------------
    // Mode Téléchargement Samsung (Odin) : lecture et sortie
    // ------------------------------------------------------------------
    String odinInfo = "";
    boolean odinBusy;
    TextView odinLine;

    android.hardware.usb.UsbDevice odinDevice() {
        for (android.hardware.usb.UsbDevice d : a.usb.getDeviceList().values())
            if (d.getVendorId() == 0x04e8 && fr.doctablette.adb.UsbAdbChannel.hasCdcDataInterface(d)) return d;
        return null;
    }

    void odinSay(String m) {
        odinInfo = m;
        a.ui.post(() -> {
            if (odinLine != null) odinLine.setText(m);
        });
    }

    void odinScreen(LinearLayout c) {
        android.hardware.usb.UsbDevice d = odinDevice();
        c.addView(a.tv("La tablette Samsung est en mode Téléchargement. Ici, Doc Tablette peut lire la liste des partitions et faire quitter ce mode. Aucune de ces deux actions n'écrit ni n'efface quoi que ce soit.", 15, MainActivity.TEXT, false));
        if (d == null) {
            c.addView(a.tv("Aucune tablette Samsung en mode Téléchargement n'est branchée.", 16, MainActivity.C_WARN, true));
            return;
        }
        LinearLayout k = a.card(0);
        odinLine = a.tv(odinInfo.isEmpty() ? "Choisissez une action." : odinInfo, 14, MainActivity.MUTED, false);
        k.addView(odinLine);
        c.addView(k);
        if (odinBusy) return;
        if (!a.usb.hasPermission(d)) {
            final android.hardware.usb.UsbDevice dd = d;
            c.addView(a.btn("Autoriser l'accès USB à la tablette", true, v -> {
                android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(a, 0,
                        new Intent(MainActivity.ACTION_USB_PERM).setPackage(a.getPackageName()),
                        android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                a.usb.requestPermission(dd, pi);
            }));
            return;
        }
        c.addView(a.btn("Lire la liste des partitions", true, v -> odinRun(false)));
        c.addView(a.btn("Quitter le mode Téléchargement (redémarrer)", false, v -> a.ask("Quitter ?", "La tablette quitte le mode Téléchargement et redémarre. Rien n'est effacé.", "Quitter", () -> odinRun(true))));
        c.addView(a.tv("L'installation d'un système Samsung (fichiers .tar.md5) n'est pas encore disponible dans cette version.", 13, MainActivity.MUTED, false));
    }

    void odinRun(final boolean exit) {
        odinBusy = true;
        odinSay("Connexion à la tablette…");
        a.render();
        a.io.execute(() -> {
            fr.doctablette.adb.UsbAdbChannel ch = null;
            try {
                android.hardware.usb.UsbDevice d = odinDevice();
                if (d == null) throw new java.io.IOException("Tablette débranchée");
                ch = fr.doctablette.adb.UsbAdbChannel.openCdc(a.usb, d);
                fr.doctablette.adb.OdinDownload o = new fr.doctablette.adb.OdinDownload(ch);
                o.handshake();
                o.beginSession();
                StringBuilder sb = new StringBuilder();
                if (!exit) {
                    List<fr.doctablette.adb.OdinDownload.Partition> ps = fr.doctablette.adb.OdinDownload.parsePit(o.readPit());
                    sb.append(ps.size()).append(" partitions :\n");
                    for (fr.doctablette.adb.OdinDownload.Partition p : ps) sb.append("- ").append(p.name).append(" (").append(p.flashFile).append(")\n");
                }
                o.end(exit);
                odinSay(exit ? "La tablette redémarre." : sb.toString());
            } catch (Exception ex) {
                odinSay("Échec : " + ex.getMessage());
            } finally {
                if (ch != null) try {
                    ch.close();
                } catch (Exception ignored) {
                }
                a.ui.post(() -> {
                    odinBusy = false;
                    a.render();
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // Guide
    // ------------------------------------------------------------------
    void openGuide(String q) {
        guideQuery = q == null ? "" : q;
        guideCat = "";
        guideUnconv = false;
        if (!a.top().equals("GUIDE")) a.go("GUIDE");
        else a.render();
    }

    void guideScreen(LinearLayout c) {
        c.addView(a.tv("Cherchez le modèle de la tablette ou ce qui ne va pas. Par exemple : « Archos écran noir », « batterie ne charge plus », « photos ».", 15, MainActivity.MUTED, false));
        final EditText e = new EditText(a);
        e.setHint("Modèle, marque, problème…");
        e.setHintTextColor(MainActivity.MUTED);
        e.setTextColor(MainActivity.TEXT);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setText(guideQuery);
        a.keepDraft("guide", e);
        c.addView(e, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        c.addView(a.btn("Chercher", true, v -> {
            guideQuery = e.getText().toString();
            a.render();
        }));
        if (!guideQuery.trim().isEmpty()) {
            List<Guide.Card> res = Guide.search(a, guideQuery, false);
            c.addView(a.tv(res.isEmpty() ? "Aucune fiche. Essayez d'autres mots (sans faute, ou la marque seule)." : res.size() + " fiches", 16, MainActivity.TEXT, true));
            int n = 0;
            for (Guide.Card g : res) {
                if (n++ >= 30) break;
                addCardButton(c, g);
            }
            c.addView(a.btn("Effacer la recherche", false, v -> {
                guideQuery = "";
                a.render();
            }));
            return;
        }
        c.addView(a.tv("Par sujet", 17, MainActivity.ACCENT, true));
        Map<String, List<Guide.Card>> cats = Guide.byCategory(a, false);
        for (Map.Entry<String, List<Guide.Card>> en : cats.entrySet()) {
            final String cat = en.getKey();
            c.addView(a.btn(cat + " (" + en.getValue().size() + ")", false, v -> {
                guideCat = cat;
                guideUnconv = false;
                a.go("GUIDELIST");
            }));
        }
        c.addView(a.btn("Techniques non conventionnelles", false, v -> {
            guideUnconv = true;
            guideCat = "";
            a.go("GUIDELIST");
        }));
        c.addView(a.btn("Photographier l'étiquette (trouver le modèle)", false, v -> a.go("PHOTO")));
        c.addView(a.btn("Câbles et matériel à acheter", false, v -> a.go("HARDWARE")));
        c.addView(a.btn("Marques : touches et outils", false, v -> a.go("BRANDS")));
        c.addView(a.tv("Les fiches sans mention « à vérifier » ont été recoupées par 2 sources ou plus. Les autres sont rapportées par des utilisateurs : à vérifier sur votre modèle.", 13, MainActivity.MUTED, false));
    }

    void guideListScreen(LinearLayout c) {
        c.addView(a.tv(guideUnconv ? "Techniques non conventionnelles" : guideCat, 18, MainActivity.TEXT, true));
        if (guideUnconv) c.addView(a.tv("Astuces de passionnés. Elles marchent pour certains, pas pour tous : lisez l'avertissement de chaque fiche. Aucune ne contourne un verrouillage.", 14, MainActivity.MUTED, false));
        List<Guide.Card> l = guideUnconv ? Guide.search(a, "", true) : Guide.byCategory(a, false).get(guideCat);
        if (l == null) l = new ArrayList<>();
        for (Guide.Card g : l) addCardButton(c, g);
    }

    void addCardButton(LinearLayout c, Guide.Card g) {
        String tag = g.verified ? "" : "  (à vérifier)";
        c.addView(a.btn(g.title + tag, false, v -> {
            card = g;
            a.go("CARD");
        }));
        if (!g.summary.isEmpty()) c.addView(a.tv(g.summary, 13, MainActivity.MUTED, false));
    }

    void cardScreen(LinearLayout c) {
        Guide.Card g = card;
        if (g == null) {
            c.addView(a.tv("Fiche introuvable.", 16, MainActivity.TEXT, false));
            return;
        }
        c.addView(a.tv(g.title, 19, MainActivity.TEXT, true));
        if (!g.summary.isEmpty()) c.addView(a.tv(g.summary, 16, MainActivity.TEXT, false));
        LinearLayout k = a.card(g.dataLoss ? MainActivity.C_BAD : (g.verified ? MainActivity.C_OK : MainActivity.C_WARN));
        k.addView(a.tv(g.dataLoss ? "ATTENTION : cette opération peut effacer vos données (photos, vidéos, applis). Sauvegardez d'abord." : "Aucune donnée effacée par cette fiche.", 15, MainActivity.TEXT, true));
        k.addView(a.tv((g.verified ? "Recoupée par au moins 2 sources lors de la recherche (pas testée sur votre modèle)." : "À vérifier : source unique ou rapportée par des utilisateurs.")
                + (g.unconventional ? " Technique non conventionnelle." : "") + " Risque : " + g.risk + ". Marques : " + String.join(", ", g.brands) + ". Android : " + g.android + ".", 13, MainActivity.MUTED, false));
        c.addView(k);
        if (g.dataLoss && a.link.isConnected()) c.addView(a.btn("Sauvegarder mes photos avant", true, v -> a.go("BACKUP")));
        c.addView(a.tv("Les étapes", 17, MainActivity.ACCENT, true));
        int n = 1;
        for (String s : g.steps) c.addView(a.tv(n++ + ". " + s, 16, MainActivity.TEXT, false));
        if (!g.warn.isEmpty()) {
            LinearLayout w = a.card(MainActivity.C_WARN);
            w.addView(a.tv("Attention : " + g.warn, 15, MainActivity.TEXT, false));
            c.addView(w);
        }
        if (!g.sources.isEmpty()) {
            c.addView(a.tv("Sources (touchez pour ouvrir)", 14, MainActivity.MUTED, true));
            for (String s : g.sources) c.addView(a.btn(host(s), false, v -> openUrl(s)));
        }
    }

    static String host(String u) {
        try {
            return Uri.parse(u).getHost();
        } catch (Exception e) {
            return u;
        }
    }

    void openUrl(String u) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) {
            a.toast("Impossible d'ouvrir le lien");
        }
    }

    // ------------------------------------------------------------------
    // Matériel : câbles, kits, connecteurs
    // ------------------------------------------------------------------
    static String readAsset(Activity a, String name) {
        try (InputStream in = a.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    void hardwareScreen(LinearLayout c) {
        c.addView(a.tv("Pour brancher la tablette au téléphone : le téléphone doit être « hôte USB » (c'est le cas de presque tous). On y met un adaptateur, puis le câble de données de la tablette (le câble de charge d'origine du client convient en général).", 15, MainActivity.TEXT, false));
        try {
            JSONObject h = new JSONObject(readAsset(a, "hardware.json"));
            JSONArray kits = h.optJSONArray("kits");
            if (kits != null) for (int i = 0; i < kits.length(); i++) {
                JSONObject k = kits.getJSONObject(i);
                LinearLayout cd = a.card(MainActivity.ACCENT);
                cd.addView(a.tv(k.optString("nom") + " : environ " + k.optString("prix_total_eur") + " €", 17, MainActivity.TEXT, true));
                cd.addView(a.tv("Pour : " + k.optString("pour_qui"), 14, MainActivity.MUTED, false));
                cd.addView(a.tv(k.optString("explication_simple"), 15, MainActivity.TEXT, false));
                c.addView(cd);
            }
            JSONArray pr = h.optJSONArray("produits");
            if (pr != null) {
                c.addView(a.tv("Produits (liens)", 17, MainActivity.ACCENT, true));
                for (int i = 0; i < pr.length(); i++) {
                    JSONObject p = pr.getJSONObject(i);
                    final String url = p.optString("url");
                    boolean ok = p.optBoolean("verifie");
                    String label = p.optString("nom") + "\n" + p.optString("prix_eur") + " € · " + p.optString("magasin") + (ok ? " · lien vérifié le " + p.optString("verifie_le") : " · page de recherche, non vérifiée");
                    c.addView(a.btn(label, false, v -> openUrl(url)));
                    String rq = p.optString("remarque");
                    if (!rq.isEmpty()) c.addView(a.tv(rq, 13, MainActivity.MUTED, false));
                }
            }
            JSONArray co = h.optJSONArray("tablettes_connecteurs");
            if (co != null) {
                c.addView(a.tv("Quel adaptateur selon la tablette", 17, MainActivity.ACCENT, true));
                for (int i = 0; i < co.length(); i++) {
                    JSONObject t = co.getJSONObject(i);
                    c.addView(a.tv(t.optString("marque_gamme") + " : " + t.optString("connecteur") + " -> " + t.optString("adaptateur_necessaire"), 14, MainActivity.TEXT, false));
                }
            }
        } catch (Exception e) {
            c.addView(a.tv("Liste indisponible.", 15, MainActivity.TEXT, false));
        }
        c.addView(a.tv("Les prix et la disponibilité changent. Les liens marqués « non vérifiée » mènent à une recherche, pas à un produit précis.", 13, MainActivity.MUTED, false));
    }

    void brandsScreen(LinearLayout c) {
        c.addView(a.tv("Informations par marque. « non confirmé » veut dire qu'aucune source fiable n'a été trouvée : rien n'est inventé.", 14, MainActivity.MUTED, false));
        try {
            JSONArray m = new JSONArray(readAsset(a, "marques.json"));
            for (int i = 0; i < m.length(); i++) {
                JSONObject o = m.getJSONObject(i);
                LinearLayout k = a.card(0);
                k.addView(a.tv(o.optString("marque"), 18, MainActivity.TEXT, true));
                k.addView(a.tv(o.optString("explication_simple"), 15, MainActivity.TEXT, false));
                k.addView(a.tv("Mode récupération : " + o.optString("touches_recuperation"), 14, MainActivity.MUTED, false));
                k.addView(a.tv("Bootloader : " + o.optString("touches_bootloader"), 14, MainActivity.MUTED, false));
                k.addView(a.tv("Système plus léger : " + o.optString("systeme_leger_possible"), 14, MainActivity.MUTED, false));
                final String fw = o.optString("firmware_officiel");
                if (fw.startsWith("http")) k.addView(a.btn("Site officiel du système", false, v -> openUrl(fw)));
                c.addView(k);
            }
        } catch (Exception e) {
            c.addView(a.tv("Liste indisponible.", 15, MainActivity.TEXT, false));
        }
    }

    // ------------------------------------------------------------------
    // Mode enfant : 4 gros boutons
    // ------------------------------------------------------------------
    void simpleHome(LinearLayout c) {
        refreshSituation();
        LinearLayout k = a.card(situation.kind == Situation.ADB ? MainActivity.C_OK : MainActivity.C_WARN);
        k.addView(a.tv(situation.title, 20, MainActivity.TEXT, true));
        k.addView(a.tv(situation.text, 17, MainActivity.TEXT, false));
        c.addView(k);
        c.addView(a.btn("Que faire maintenant ?", true, v -> a.go("SITUATION")));
        c.addView(a.btn("Sauver mes photos et vidéos", false, v -> a.go(a.link.isConnected() ? "BACKUP" : "CONNECT")));
        c.addView(a.btn("Rendre la tablette plus rapide", false, v -> a.go(a.link.isConnected() ? "REPAIR" : "CONNECT")));
        c.addView(a.btn("Chercher une solution", false, v -> openGuide("")));
        c.addView(a.btn("Mode complet", false, v -> {
            a.prefs.edit().putBoolean("simple", false).apply();
            a.render();
        }));
    }

    // ------------------------------------------------------------------
    // Puces (MediaTek, Rockchip, Allwinner, Unisoc…) : reconnaissance et tests en lecture seule
    // ------------------------------------------------------------------
    volatile String chipInfo = "";
    volatile boolean chipBusy;
    volatile TextView chipLine;

    android.hardware.usb.UsbDevice chipDevice() {
        for (android.hardware.usb.UsbDevice d : a.usb.getDeviceList().values())
            if (ChipDb.of(d.getVendorId(), d.getProductId()) != null && isChipMode(d)) return d;
        return null;
    }

    /** Une tablette qui démarre normalement (ADB, fastboot, MTP, disque) n'est jamais traitée comme un mode puce. */
    static boolean isChipMode(android.hardware.usb.UsbDevice d) {
        if (fr.doctablette.adb.UsbAdbChannel.hasAdbInterface(d) || fr.doctablette.adb.UsbAdbChannel.hasFastbootInterface(d)) return false;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            int cl = d.getInterface(i).getInterfaceClass();
            if (cl == 6 || cl == 8) return false;
        }
        return fr.doctablette.adb.UsbAdbChannel.hasBulkInterface(d);
    }

    void chipSay(String m) {
        chipInfo = m;
        a.ui.post(() -> {
            if (chipLine != null) chipLine.setText(m);
        });
    }

    void chipScreen(LinearLayout c) {
        android.hardware.usb.UsbDevice d = chipDevice();
        if (d == null && !chipBusy) chipInfo = "";
        if (d == null) {
            c.addView(a.tv("Aucune tablette en mode de chargement de puce n'est branchée.", 16, MainActivity.C_WARN, true));
            c.addView(a.tv("Cet écran sert aux tablettes Archos, aux tablettes chinoises et aux téléphones à puce MediaTek, Rockchip, Allwinner ou Unisoc qui ne démarrent plus.", 15, MainActivity.TEXT, false));
            return;
        }
        final ChipDb.Chip ch = ChipDb.of(d.getVendorId(), d.getProductId());
        final String devName = d.getDeviceName();
        LinearLayout k = a.card(MainActivity.C_WARN);
        k.addView(a.tv(ch.family + " : " + ch.mode, 19, MainActivity.TEXT, true));
        k.addView(a.tv(ch.explain, 15, MainActivity.TEXT, false));
        k.addView(a.tv("Identifiant USB : " + String.format("%04x:%04x", d.getVendorId(), d.getProductId()), 13, MainActivity.MUTED, false));
        c.addView(k);
        int n = 1;
        for (String st : ch.steps) c.addView(a.tv(n++ + ". " + st, 15, MainActivity.TEXT, false));
        LinearLayout r = a.card(0);
        chipLine = a.tv(chipInfo.isEmpty() ? "Les tests ci-dessous n'écrivent rien dans la tablette." : chipInfo, 14, MainActivity.MUTED, false);
        r.addView(chipLine);
        c.addView(r);
        if (chipBusy) return;
        if (!ch.probe) {
            c.addView(a.tv("Pas de test de lecture pour cette puce dans cette version. Voir le guide pour ce cas.", 14, MainActivity.MUTED, false));
            return;
        }
        if (!a.usb.hasPermission(d)) {
            final android.hardware.usb.UsbDevice dd = d;
            c.addView(a.btn("Autoriser l'accès USB à la tablette", true, v -> {
                android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(a, 0,
                        new Intent(MainActivity.ACTION_USB_PERM).setPackage(a.getPackageName()),
                        android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                a.usb.requestPermission(dd, pi);
            }));
            return;
        }
        final boolean mtk = d.getVendorId() == 0x0e8d;
        c.addView(a.btn(mtk ? "Identifier la puce (lecture seule)" : "Tester la réponse de la puce (lecture seule)", true, v -> chipRun(devName, mtk, false)));
        if (!mtk && ch.family.equals("Rockchip") && d.getProductId() >= 0x3000)
            c.addView(a.btn("Redémarrer la tablette", false, v -> a.ask("Redémarrer ?", "La tablette redémarre. Rien n'est effacé.", "Redémarrer", () -> chipRun(devName, false, true))));
    }

    void chipRun(final String devName, final boolean mtk, final boolean reboot) {
        chipBusy = true;
        chipSay("Connexion à la tablette…");
        a.render();
        a.io.execute(() -> {
            fr.doctablette.adb.UsbAdbChannel ch = null;
            try {
                android.hardware.usb.UsbDevice d = a.usb.getDeviceList().get(devName);
                if (d == null || !isChipMode(d)) throw new java.io.IOException("Tablette débranchée");
                ch = fr.doctablette.adb.UsbAdbChannel.openBulk(a.usb, d);
                fr.doctablette.adb.ChipProbe p = new fr.doctablette.adb.ChipProbe(ch);
                if (mtk) {
                    int hw = p.mediatekHwCode();
                    String nm = ChipDb.mtkName(hw);
                    chipSay("Code matériel MediaTek : 0x" + Integer.toHexString(hw) + (nm != null ? "\nPuce probable : " + nm + " (à vérifier)" : "\nPuce non listée : notez ce code pour la recherche du firmware."));
                } else if (reboot) {
                    p.rockchipReboot();
                    chipSay("Redémarrage demandé.");
                } else {
                    chipSay(p.rockchipReady() ? "La puce Rockchip répond correctement." : "La puce répond, mais signale qu'elle n'est pas prête.");
                }
            } catch (Exception ex) {
                chipSay("Échec : " + (ex.getMessage() == null ? "erreur USB" : ex.getMessage()) + "\nRien n'a été modifié. Débranchez, rebranchez, et réessayez.");
            } finally {
                if (ch != null) try {
                    ch.close();
                } catch (Exception ignored) {
                }
                a.ui.post(() -> {
                    chipBusy = false;
                    a.render();
                });
            }
        });
    }
}
