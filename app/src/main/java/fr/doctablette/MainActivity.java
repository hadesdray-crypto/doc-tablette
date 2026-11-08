package fr.doctablette;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import fr.doctablette.adb.UsbAdbChannel;
import fr.doctablette.logic.Bilan;
import fr.doctablette.logic.LicenseCodec;
import fr.doctablette.logic.Repairs;
import fr.doctablette.logic.StepRunner;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity {
    // ----- Thème -----
    static final int BG = 0xFF0F1B2D, CARD = 0xFF1B2A41, ACCENT = 0xFF35C2A1, TEXT = 0xFFEAF0F7, MUTED = 0xFF9FB0C5;
    static final int C_OK = 0xFF4CD28A, C_WARN = 0xFFF2A93B, C_BAD = 0xFFEF5B5B;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    /** Adresse qui reçoit les signalements du bouton « ? » : à compléter plus tard. */
    static final String REPORT_EMAIL = "";

    static final String ACTION_USB_PERM = "fr.doctablette.USB_PERMISSION";
    static final String ACTION_INSTALL = "fr.doctablette.INSTALL_RESULT";
    static final int REQ_APK = 11;

    final TabletLink link = new TabletLink();
    final ExecutorService io = Guard.pool(1, this::taskFailed);
    final ExecutorService net = Guard.pool(4, this::taskFailed);
    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    UsbManager usb;
    final ArrayList<String> stack = new ArrayList<>();

    // ----- États -----
    String connectMsg = "";
    boolean connecting;
    List<UsbDevice> usbAdb = new ArrayList<>();
    int usbOther;
    Bilan bilan;
    String bilanMsg = "";
    boolean bilanBusy;
    final StringBuilder repairLog = new StringBuilder();
    volatile boolean busy, cancel;
    static final class AppRow {
        String pkg, note;
        boolean disabled, suggested;
    }
    List<AppRow> apps;
    String appsMsg = "";
    String updMsg = "";
    int updPercent = -1;
    String hwText = "";
    Updater.Info pendingInfo;
    File pendingApk;

    // ----- Vues à mettre à jour sans tout redessiner -----
    TextView connectStatus, repairLogView, updStatusView;
    ProgressBar updBar;
    EditText wifiField;
    ExtraScreens extra;

    // =====================================================================
    // Cycle de vie
    // =====================================================================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Guard.install(getApplicationContext());
        prefs = getSharedPreferences("dt", MODE_PRIVATE);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        extra = new ExtraScreens(this);
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERM);
        f.addAction(ACTION_INSTALL);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rx, f);
        stack.add("HOME");
        if (!prefs.getBoolean("first_done", false)) {
            prefs.edit().putBoolean("first_done", true).apply();
            stack.add("FIRST");
        }
        render();
        handleIntent(getIntent());
        checkAppUpdate(false);
        final String last = Guard.takeLast(this);
        if (last != null) ui.postDelayed(() -> incidentDialog(last), 600);
    }

    /** Une tâche d'arrière-plan a échoué : on remet l'écran en ordre et on garde la trace. */
    void taskFailed(String where, Throwable t) {
        Guard.save(getApplicationContext(), where, t);
        ui.post(() -> {
            busy = false;
            connecting = false;
            toast("Une erreur est survenue. L'appli continue de fonctionner.");
            try {
                render();
            } catch (Throwable ignored) {
            }
        });
    }

    void incidentDialog(final String text) {
        try {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Un incident a eu lieu")
                    .setMessage("L'appli a rencontré une erreur la dernière fois. Vous pouvez copier le détail pour le signaler avec le bouton « ? ».")
                    .setPositiveButton("Copier le détail", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("Incident", text));
                        toast("Détail copié");
                    })
                    .setNegativeButton("Fermer", null).show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingApk != null && canInstall()) {
            File f = pendingApk;
            pendingApk = null;
            net.execute(() -> doInstall(f));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        if (isFinishing()) {
            link.disconnect();
            io.shutdownNow();
            net.shutdownNow();
        }
    }

    @Override
    public void onBackPressed() {
        if (stack.size() <= 1) {
            ask("Quitter ?", "Voulez-vous quitter Doc Tablette ?", "Quitter", this::finish);
            return;
        }
        if (top().equals("REPAIR") && busy) {
            ask("Arrêter ?", "Une réparation est en cours. Voulez-vous l'arrêter et revenir en arrière ?", "Arrêter", () -> {
                stopRepair();
                if (stack.size() > 1) stack.remove(stack.size() - 1);
                render();
            });
            return;
        }
        stack.remove(stack.size() - 1);
        render();
    }

    // =====================================================================
    // Navigation
    // =====================================================================
    String top() {
        return stack.get(stack.size() - 1);
    }

    void go(String s) {
        if (!top().equals(s)) stack.add(s);
        if (s.equals("CONNECT")) scanUsb(true);
        render();
    }

    static String titleOf(String s) {
        switch (s) {
            case "CONNECT": return "Connecter la tablette";
            case "BILAN": return "Bilan de la tablette";
            case "REPAIR": return "Réparer";
            case "APPS": return "Applis de la tablette";
            case "UPDATES": return "Mises à jour";
            case "TESTS": return "Tests de la tablette";
            case "HELP": return "Aide : brancher la tablette";
            case "LICENSE": return "Licence";
            case "LEGAL": return "Informations légales";
            case "APPUPDATE": return "Mettre à jour Doc Tablette";
            case "FIRST": return "Comment ça marche";
            case "SITUATION": return "Que faire maintenant ?";
            case "BACKUP": return "Sauvegarder photos et vidéos";
            case "GUIDE": return "Guide de dépannage";
            case "GUIDELIST": return "Guide";
            case "CARD": return "Fiche";
            case "HARDWARE": return "Câbles et matériel";
            case "BRANDS": return "Marques";
            case "FASTBOOT": return "Mode bootloader (fastboot)";
            case "PHOTO": return "Trouver le modèle par photo";
            case "ODIN": return "Mode Téléchargement Samsung";
            case "CHIP": return "Puces : Archos et tablettes chinoises";
            case "RAPPORT": return "Rapport pour le client";
            default: return "Doc Tablette";
        }
    }

    // =====================================================================
    // Aides d'affichage
    // =====================================================================
    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Brouillons de saisie : ce que l'on tape survit à un rafraîchissement d'écran (câble qui bouge, rotation). */
    final java.util.HashMap<String, String> drafts = new java.util.HashMap<>();

    void keepDraft(final String id, final EditText e) {
        String d = drafts.get(id);
        if (d != null && e.getText().length() == 0) {
            e.setText(d);
            e.setSelection(d.length());
        }
        e.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int n) {
            }

            public void onTextChanged(CharSequence c, int a, int b, int n) {
                drafts.put(id, c.toString());
            }

            public void afterTextChanged(android.text.Editable ed) {
            }
        });
    }

    TextView tv(String t, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
        p.topMargin = dp(8);
        v.setLayoutParams(p);
        return v;
    }

    Button btn(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(17);
        b.setTextColor(primary ? 0xFF04201A : TEXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(56));
        b.setMinimumHeight(dp(56));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : CARD);
        g.setCornerRadius(dp(14));
        if (!primary) g.setStroke(dp(1), 0xFF2E4466);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), g, null));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    LinearLayout card(int stroke) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        if (stroke != 0) g.setStroke(dp(2), stroke);
        c.setBackground(g);
        c.setPadding(dp(14), dp(10), dp(14), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
        p.topMargin = dp(10);
        c.setLayoutParams(p);
        return c;
    }

    void info(String title, String msg) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    void ask(String title, String msg, String yes, Runnable onYes) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title).setMessage(msg)
                .setPositiveButton(yes, (d, w) -> onYes.run())
                .setNegativeButton("Annuler", null).show();
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // =====================================================================
    // Dessin de l'écran courant
    // =====================================================================
    void render() {
        try {
            renderUnsafe();
        } catch (Throwable t) {
            Guard.save(getApplicationContext(), "Écran " + (stack.isEmpty() ? "?" : top()), t);
            renderFallback();
        }
    }

    /** Écran de secours : toujours un bouton Retour, jamais d'écran vide. */
    void renderFallback() {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(BG);
            root.setPadding(dp(16), dp(24), dp(16), dp(16));
            root.addView(tv("Cet écran n'a pas pu s'afficher.", 18, TEXT, true));
            root.addView(tv("Rien n'est perdu et rien n'a été modifié sur la tablette. Touchez « Retour ».", 16, MUTED, false));
            root.addView(btn("Retour", true, v -> {
                if (stack.size() > 1) stack.remove(stack.size() - 1);
                else {
                    stack.clear();
                    stack.add("HOME");
                }
                render();
            }));
            root.addView(btn("Revenir à l'accueil", false, v -> {
                stack.clear();
                stack.add("HOME");
                render();
            }));
            setContentView(root);
        } catch (Throwable ignored) {
        }
    }

    void renderUnsafe() {
        connectStatus = null;
        repairLogView = null;
        updStatusView = null;
        updBar = null;
        wifiField = null;
        String s = top();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        // En-tête : Retour | titre | ?
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(8), dp(4), dp(8), dp(4));
        Button back = btn(s.equals("HOME") ? "Quitter" : "Retour", false, v -> onBackPressed());
        back.setLayoutParams(new LinearLayout.LayoutParams(WRAP, WRAP));
        back.setTextSize(15);
        h.addView(back);
        TextView title = tv(titleOf(s), 18, TEXT, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tp.leftMargin = dp(6);
        tp.rightMargin = dp(6);
        title.setLayoutParams(tp);
        h.addView(title);
        Button q = btn("?", false, v -> reportDialog());
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(48), dp(48));
        q.setLayoutParams(qp);
        q.setMinHeight(0);
        q.setMinimumHeight(0);
        q.setMinWidth(0);
        q.setMinimumWidth(0);
        q.setPadding(0, 0, 0, 0);
        q.setTextSize(18);
        h.addView(q);
        root.addView(h, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(4), dp(16), dp(24));
        sv.addView(c, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        switch (s) {
            case "CONNECT": connectScreen(c); break;
            case "BILAN": bilanScreen(c); break;
            case "REPAIR": repairScreen(c); break;
            case "APPS": appsScreen(c); break;
            case "UPDATES": updatesScreen(c); break;
            case "TESTS": testsScreen(c); break;
            case "HELP": c.addView(tv(Texts.HELP, 16, TEXT, false)); break;
            case "LICENSE": licenseScreen(c); break;
            case "LEGAL": legalScreen(c); break;
            case "APPUPDATE": appUpdateScreen(c); break;
            case "FIRST": firstScreen(c); break;
            case "SITUATION": extra.situationScreen(c); break;
            case "BACKUP": extra.backupScreen(c); break;
            case "GUIDE": extra.guideScreen(c); break;
            case "GUIDELIST": extra.guideListScreen(c); break;
            case "CARD": extra.cardScreen(c); break;
            case "HARDWARE": extra.hardwareScreen(c); break;
            case "BRANDS": extra.brandsScreen(c); break;
            case "FASTBOOT": extra.fastbootScreen(c); break;
            case "PHOTO": extra.photoScreen(c); break;
            case "ODIN": extra.odinScreen(c); break;
            case "CHIP": extra.chipScreen(c); break;
            case "RAPPORT": rapportScreen(c); break;
            default: homeScreen(c);
        }
        setContentView(root);
    }

    boolean licensed() {
        return LicenseCodec.verify(BuildConfig.LICENSE_SECRET, prefs.getString("licence", ""));
    }

    /** Affiche le message « licence nécessaire » et renvoie faux si pas de licence. */
    boolean needLicense(LinearLayout c) {
        if (licensed()) return true;
        LinearLayout k = card(C_WARN);
        k.addView(tv("Cette fonction demande une licence.", 17, TEXT, true));
        k.addView(tv("Le bilan et les tests restent gratuits. Le code d'activation est envoyé après le paiement.", 15, MUTED, false));
        c.addView(k);
        c.addView(btn("Activer ma licence", true, v -> go("LICENSE")));
        return false;
    }

    boolean needTablet(LinearLayout c) {
        if (link.isConnected()) return true;
        LinearLayout k = card(C_WARN);
        k.addView(tv("Aucune tablette connectée.", 17, TEXT, true));
        k.addView(tv("Branchez la tablette avec le câble, puis connectez-la.", 15, MUTED, false));
        c.addView(k);
        c.addView(btn("Connecter la tablette", true, v -> go("CONNECT")));
        return false;
    }

    // =====================================================================
    // Accueil
    // =====================================================================
    void homeScreen(LinearLayout c) {
        if (prefs.getBoolean("simple", false)) {
            extra.simpleHome(c);
            return;
        }
        c.addView(tv("Réparez et mettez à jour une tablette Android, par le câble.", 16, MUTED, false));
        LinearLayout k = card(link.isConnected() ? C_OK : 0);
        k.addView(tv(link.isConnected() ? "Tablette connectée" : "Aucune tablette connectée", 17, link.isConnected() ? C_OK : TEXT, true));
        if (link.isConnected()) k.addView(tv(link.label, 15, MUTED, false));
        c.addView(k);
        if (pendingInfo != null) {
            c.addView(btn("Nouvelle version disponible : " + pendingInfo.name, true, v -> offerUpdate(pendingInfo)));
        }
        if (link.isConnected()) {
            c.addView(btn("Bilan de la tablette", true, v -> {
                go("BILAN");
                runBilan();
            }));
        } else {
            c.addView(btn("Connecter la tablette", true, v -> go("CONNECT")));
        }
        c.addView(btn("Que faire maintenant ? (détection)", false, v -> go("SITUATION")));
        c.addView(btn("Sauvegarder photos et vidéos", false, v -> go("BACKUP")));
        c.addView(btn("Guide de dépannage et recherche", false, v -> go("GUIDE")));
        c.addView(btn("Photographier l'étiquette (trouver le modèle)", false, v -> go("PHOTO")));
        c.addView(btn("Mode simple (très facile)", false, v -> {
            prefs.edit().putBoolean("simple", true).apply();
            render();
        }));
        c.addView(btn("Tout réparer", false, v -> go("REPAIR")));
        c.addView(btn("Applis de la tablette", false, v -> go("APPS")));
        c.addView(btn("Mises à jour", false, v -> go("UPDATES")));
        c.addView(btn("Tests : écran, tactile, son", false, v -> go("TESTS")));
        c.addView(btn("Aide : brancher la tablette", false, v -> go("HELP")));
        c.addView(btn(licensed() ? "Licence : active" : "Licence : activer", false, v -> go("LICENSE")));
        c.addView(btn("Mettre à jour Doc Tablette", false, v -> go("APPUPDATE")));
        c.addView(btn("Comment ça marche", false, v -> go("FIRST")));
        c.addView(btn("Informations légales", false, v -> go("LEGAL")));
        if (link.isConnected()) {
            c.addView(btn("Déconnecter la tablette", false, v -> {
                link.disconnect();
                connectMsg = "";
                render();
            }));
        }
        c.addView(tv("Version " + BuildConfig.VERSION_NAME, 13, MUTED, false));
    }

    void firstScreen(LinearLayout c) {
        c.addView(tv(Texts.FIRST, 17, TEXT, false));
        c.addView(btn("Compris", true, v -> onBackPressed()));
    }

    // =====================================================================
    // Connexion
    // =====================================================================
    void handleIntent(Intent i) {
        if (i == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) return;
        UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (d == null) return;
        if (UsbAdbChannel.hasAdbInterface(d)) {
            if (!top().equals("CONNECT")) stack.add("CONNECT");
            scanUsb(true);
            render();
        } else {
            // fastboot, transfert de fichiers, mode bas niveau… : on explique quoi faire
            if (!top().equals("SITUATION")) stack.add("SITUATION");
            scanUsb(false);
            render();
        }
    }

    void scanUsb(boolean auto) {
        usbAdb = new ArrayList<>();
        usbOther = 0;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            connectMsg = "Ce téléphone ne peut pas piloter une tablette par câble. Essayez la connexion Wi-Fi plus bas.";
            return;
        }
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (UsbAdbChannel.hasAdbInterface(d)) usbAdb.add(d);
            else usbOther++;
        }
        if (link.isConnected()) {
            connectMsg = "Connecté : " + link.label;
        } else if (usbAdb.isEmpty()) {
            connectMsg = usbOther > 0
                    ? "Un appareil est branché, mais le débogage USB n'est pas activé sur la tablette. Touchez « Aide : brancher la tablette »."
                    : "Aucune tablette détectée par le câble. Branchez-la puis touchez « Chercher à nouveau ».";
        } else if (usbAdb.size() == 1 && auto && !connecting) {
            connectUsb(usbAdb.get(0));
        } else if (!connecting) {
            connectMsg = "Plusieurs appareils : choisissez la tablette.";
        }
    }

    void connectUsb(UsbDevice d) {
        if (!usb.hasPermission(d)) {
            connectMsg = "Acceptez la demande d'accès USB sur le téléphone.";
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERM).setPackage(getPackageName()),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            usb.requestPermission(d, pi);
            return;
        }
        connecting = true;
        connectMsg = "Connexion…";
        io.execute(() -> link.connectUsb(this, usb, d, connectCallback));
    }

    final TabletLink.Callback connectCallback = new TabletLink.Callback() {
        @Override
        public void state(String m) {
            ui.post(() -> setConnectMsg(m));
        }

        @Override
        public void connected(String l) {
            ui.post(() -> {
                connecting = false;
                extra.authRefused = false;
                connectMsg = "Connecté : " + l;
                render();
            });
        }

        @Override
        public void failed(String m) {
            ui.post(() -> {
                connecting = false;
                connectMsg = m;
                extra.authRefused = m.contains("Autorisation refusée");
                render();
            });
        }
    };

    void setConnectMsg(String m) {
        connectMsg = m;
        if (connectStatus != null) connectStatus.setText(m);
    }

    void connectScreen(LinearLayout c) {
        c.addView(tv("Branchez le câble entre le téléphone et la tablette.", 16, MUTED, false));
        LinearLayout k = card(link.isConnected() ? C_OK : 0);
        connectStatus = tv(connectMsg.isEmpty() ? "Recherche…" : connectMsg, 16, TEXT, true);
        k.addView(connectStatus);
        c.addView(k);
        if (link.isConnected()) {
            c.addView(btn("Faire le bilan", true, v -> {
                go("BILAN");
                runBilan();
            }));
            c.addView(btn("Déconnecter", false, v -> {
                link.disconnect();
                connectMsg = "Déconnecté.";
                render();
            }));
            return;
        }
        if (!connecting) {
            int n = 1;
            for (UsbDevice d : usbAdb) {
                final UsbDevice dd = d;
                c.addView(btn("Se connecter à la tablette " + (usbAdb.size() > 1 ? n : ""), true, v -> {
                    connectUsb(dd);
                    render();
                }));
                n++;
            }
            c.addView(btn("Chercher à nouveau", false, v -> {
                scanUsb(true);
                render();
            }));
        }
        c.addView(btn("Aide : brancher la tablette", false, v -> go("HELP")));
        c.addView(tv("Connexion Wi-Fi (secours)", 16, TEXT, true));
        c.addView(tv("Seulement si la tablette écoute déjà sur le port 5555. Le câble reste le plus simple.", 14, MUTED, false));
        wifiField = new EditText(this);
        wifiField.setHint("Adresse de la tablette, ex. 192.168.1.20");
        wifiField.setHintTextColor(MUTED);
        wifiField.setTextColor(TEXT);
        wifiField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        wifiField.setSingleLine(true);
        keepDraft("wifi", wifiField);
        c.addView(wifiField, new LinearLayout.LayoutParams(MATCH, WRAP));
        c.addView(btn("Connexion Wi-Fi", false, v -> {
            String host = wifiField.getText().toString().trim();
            if (host.isEmpty()) {
                toast("Écrivez l'adresse de la tablette");
                return;
            }
            connecting = true;
            connectMsg = "Connexion…";
            io.execute(() -> link.connectTcp(this, host, 5555, connectCallback));
            render();
        }));
    }

    // =====================================================================
    // Bilan
    // =====================================================================
    void runBilan() {
        if (!link.isConnected() || bilanBusy) return;
        bilanBusy = true;
        bilanMsg = "Lecture de la tablette…";
        render();
        io.execute(() -> {
            try {
                String out = link.sh(Bilan.SCRIPT, 40000);
                Bilan b = Bilan.parse(out);
                ui.post(() -> {
                    bilanBusy = false;
                    if (b.looksValid()) {
                        bilan = b;
                        bilanMsg = "";
                    } else {
                        bilanMsg = "La tablette n'a pas donné de réponse lisible. Réessayez.";
                    }
                    if (top().equals("BILAN")) render();
                });
            } catch (IOException e) {
                ui.post(() -> {
                    bilanBusy = false;
                    lost(e);
                    bilanMsg = TabletLink.explain(e);
                    if (top().equals("BILAN")) render();
                });
            }
        });
    }

    void lost(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("coupée") || m.contains("débranchée") || m.contains("Pas de connexion")) {
            link.disconnect();
            connectMsg = "Tablette débranchée.";
        }
    }

    void bilanScreen(LinearLayout c) {
        if (!needTablet(c)) return;
        if (bilanBusy) {
            c.addView(tv(bilanMsg, 16, MUTED, false));
            return;
        }
        if (!bilanMsg.isEmpty()) c.addView(tv(bilanMsg, 16, C_WARN, false));
        if (bilan != null) {
            c.addView(tv(bilan.title(), 20, TEXT, true));
            boolean allOk = true;
            for (Bilan.Item it : bilan.items()) {
                int col = it.level == Bilan.BAD ? C_BAD : it.level == Bilan.WARN ? C_WARN : C_OK;
                if (it.level != Bilan.OK) allOk = false;
                LinearLayout k = card(col);
                String etat = it.level == Bilan.BAD ? "À traiter" : it.level == Bilan.WARN ? "À améliorer" : "Bon";
                k.addView(tv(it.label + " : " + etat, 16, col, true));
                k.addView(tv(it.value, 16, TEXT, false));
                if (!it.advice.isEmpty()) k.addView(tv(it.advice, 14, MUTED, false));
                c.addView(k);
            }
            if (!allOk) c.addView(btn("Tout réparer", true, v -> go("REPAIR")));
            else c.addView(tv("Tout est en bon état.", 16, C_OK, true));
        }
        if (bilan != null) c.addView(btn("Rapport pour le client", false, v -> go("RAPPORT")));
        c.addView(btn(bilan == null ? "Lancer le bilan" : "Refaire le bilan", bilan == null, v -> runBilan()));
    }

    // =====================================================================
    // Rapport client : texte sans aucune donnée personnelle (ni numéro de série, ni identifiant)
    // =====================================================================
    String rapportText() {
        StringBuilder sb = new StringBuilder("RAPPORT D'ÉTAT DE LA TABLETTE\n");
        if (bilan == null) return sb.append("\nAucun bilan n'a été fait.").toString();
        sb.append(bilan.title()).append("\n\nÉtat constaté :\n");
        for (Bilan.Item it : bilan.items()) {
            String etat = it.level == Bilan.BAD ? "à traiter" : it.level == Bilan.WARN ? "à améliorer" : "bon";
            sb.append("- ").append(it.label).append(" : ").append(it.value).append(" (").append(etat).append(")\n");
        }
        String log = repairLog.toString().trim();
        sb.append("\nInterventions faites :\n").append(log.isEmpty() ? "- aucune pour l'instant\n" : log + "\n");
        sb.append("\nÉtabli avec Doc Tablette. Ce rapport ne contient aucune donnée personnelle (ni numéro de série, ni contenu de la tablette).");
        return sb.toString();
    }

    void rapportScreen(LinearLayout c) {
        c.addView(tv("Ce texte résume l'état de la tablette. Il ne contient ni numéro de série, ni identifiant, ni contenu personnel. Relisez-le avant de le montrer.", 14, MUTED, false));
        LinearLayout k = card(0);
        k.addView(tv(rapportText(), 15, TEXT, false));
        c.addView(k);
        c.addView(btn("Copier le rapport", true, v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Rapport", rapportText()));
            toast("Rapport copié");
        }));
        c.addView(btn("Partager le rapport", false, v -> {
            try {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, rapportText());
                startActivity(Intent.createChooser(i, "Partager le rapport"));
            } catch (Throwable t) {
                toast("Aucune appli pour partager");
            }
        }));
    }

    // =====================================================================
    // Réparer
    // =====================================================================
    void logLine(String s) {
        repairLog.append(s).append('\n');
        ui.post(() -> {
            if (repairLogView != null) repairLogView.setText(repairLog.toString());
        });
    }

    void repairScreen(LinearLayout c) {
        if (!needLicense(c)) return;
        if (!needTablet(c)) return;
        c.addView(btn("Tout réparer", true, v -> {
            if (busy) return;
            StringBuilder sb = new StringBuilder("Doc Tablette va faire, dans l'ordre :\n");
            for (Repairs.Step s : Repairs.all()) sb.append("\n- ").append(s.title);
            sb.append("\n\nRien de personnel n'est supprimé. Gardez le câble branché et le téléphone allumé.");
            ask("Tout réparer ?", sb.toString(), "Lancer", () -> runSteps(Repairs.all()));
        }));
        if (busy) {
            c.addView(btn("Arrêter", false, v -> stopRepair()));
        }
        c.addView(tv("Une réparation à la fois", 16, TEXT, true));
        Repairs.Step[] singles = {Repairs.CACHES, Repairs.BACKGROUND, Repairs.ANIMS, Repairs.OPTIMIZE};
        for (Repairs.Step s : singles) {
            final Repairs.Step st = s;
            c.addView(btn(s.title, false, v -> {
                if (!busy) ask(st.title, st.explain, "Lancer", () -> runSteps(Collections.singletonList(st)));
            }));
            c.addView(tv(s.explain, 13, MUTED, false));
        }
        c.addView(btn("Annuler mes réglages (animations, applis)", false, v -> {
            if (!busy) ask("Annuler ?", "Remet les animations d'origine, réactive les applis désactivées et remet les applis livrées avec la tablette qui ont été retirées avec Doc Tablette.", "Annuler les réglages", this::undoAll);
        }));
        c.addView(btn("Redémarrer la tablette", false, v -> {
            if (!busy) ask("Redémarrer ?", "La tablette va redémarrer et la connexion sera coupée.", "Redémarrer", () -> runSteps(Collections.singletonList(Repairs.REBOOT)));
        }));
        LinearLayout k = card(0);
        repairLogView = tv(repairLog.length() == 0 ? "Le déroulement s'affichera ici." : repairLog.toString(), 14, MUTED, false);
        k.addView(repairLogView);
        c.addView(k);
    }

    StepRunner.Shell shell() {
        return (cmd, t) -> link.sh(cmd, t);
    }

    /** Arrêt réel : la commande en cours sur la tablette est fermée, pas seulement la suivante. */
    void stopRepair() {
        if (!busy || cancel) return;
        cancel = true;
        logLine("Arrêt demandé…");
        link.abortCurrent();
    }

    void runSteps(List<Repairs.Step> steps) {
        if (busy || !licensed()) return;
        busy = true;
        cancel = false;
        repairLog.setLength(0);
        render();
        io.execute(() -> {
            try {
                for (Repairs.Step s : steps) {
                    if (cancel) {
                        logLine("Arrêté.");
                        break;
                    }
                    logLine("- " + s.title + "…");
                    try {
                        if (s.id.equals("anims")) saveAnimOriginals();
                    } catch (IOException ignored) {
                    }
                    StepRunner.Result r = StepRunner.run(shell(), s);
                    logLine(r.ok ? "  terminé" : "  impossible : " + r.detail);
                    if (!link.isConnected() && !s.id.equals("reboot")) {
                        logLine("Tablette débranchée : arrêt.");
                        break;
                    }
                }
                if (!cancel) logLine("Terminé. Refaites un bilan pour voir le résultat.");
            } finally {
                busy = false;
                ui.post(() -> {
                    if (top().equals("REPAIR")) render();
                });
            }
        });
    }

    void saveAnimOriginals() throws IOException {
        String k = "anim_" + link.key;
        if (prefs.contains(k)) return;
        String o = link.sh("settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale", 10000)
                .trim().replace("\r", "").replace("\n", "|");
        prefs.edit().putString(k, o).apply();
    }

    void undoAll() {
        if (busy || !licensed()) return;
        busy = true;
        cancel = false;
        repairLog.setLength(0);
        render();
        io.execute(() -> {
            try {
                String k = "anim_" + link.key;
                String saved = prefs.getString(k, "1.0|1.0|1.0");
                String[] v = (saved + "|1.0|1.0|1.0").split("\\|");
                for (int i = 0; i < 3; i++) if (!v[i].matches("[0-9.]+")) v[i] = "1.0";
                logLine("- Animations d'origine…");
                try {
                    link.sh("settings put global window_animation_scale " + v[0] + "; settings put global transition_animation_scale " + v[1]
                            + "; settings put global animator_duration_scale " + v[2], 15000);
                    prefs.edit().remove(k).apply();
                    logLine("  terminé");
                } catch (IOException e) {
                    logLine("  impossible : " + e.getMessage());
                }
                Set<String> dis = new HashSet<>(prefs.getStringSet("disabled_" + link.key, new HashSet<>()));
                for (String p : dis) {
                    if (!p.matches("[A-Za-z0-9._]+")) continue;
                    logLine("- Réactiver " + p + "…");
                    try {
                        String out = link.sh("pm enable " + p, 15000);
                        logLine(Repairs.failed(out) ? "  impossible" : "  terminé");
                        if (!Repairs.failed(out)) {
                            Set<String> left = new HashSet<>(prefs.getStringSet("disabled_" + link.key, new HashSet<>()));
                            left.remove(p);
                            prefs.edit().putStringSet("disabled_" + link.key, left).apply();
                        }
                    } catch (IOException e) {
                        logLine("  impossible : " + e.getMessage());
                    }
                }
                Set<String> rem = new HashSet<>(prefs.getStringSet("removed_" + link.key, new HashSet<>()));
                for (String p : rem) {
                    if (!p.matches("[A-Za-z0-9._]+")) continue;
                    logLine("- Remettre " + p + "…");
                    try {
                        String out = link.sh("cmd package install-existing " + p, 30000);
                        boolean ok = !Repairs.failed(out) && !out.toLowerCase().contains("doesn't exist") && !out.toLowerCase().contains("failure");
                        logLine(ok ? "  terminé" : "  impossible : " + out.trim());
                        if (ok) {
                            Set<String> left = new HashSet<>(prefs.getStringSet("removed_" + link.key, new HashSet<>()));
                            left.remove(p);
                            prefs.edit().putStringSet("removed_" + link.key, left).apply();
                        }
                    } catch (IOException e) {
                        logLine("  impossible : " + e.getMessage());
                    }
                }
                logLine("Terminé.");
            } finally {
                busy = false;
                ui.post(() -> {
                    if (top().equals("REPAIR")) render();
                });
            }
        });
    }

    // =====================================================================
    // Applis de la tablette
    // =====================================================================
    void loadApps() {
        if (!licensed() || !link.isConnected()) return;
        appsMsg = "Lecture de la liste…";
        render();
        io.execute(() -> {
            try {
                String third = link.sh("pm list packages -3", 30000);
                String dis = link.sh("pm list packages -d", 30000);
                String all = link.sh("pm list packages", 30000);
                Set<String> userSet = pkgs(third), disSet = pkgs(dis), allSet = pkgs(all);
                List<AppRow> rows = new ArrayList<>();
                for (String[] sug : Repairs.SUGGESTED) {
                    if (allSet.contains(sug[0])) {
                        AppRow r = new AppRow();
                        r.pkg = sug[0];
                        r.note = sug[1];
                        r.suggested = true;
                        r.disabled = disSet.contains(sug[0]);
                        rows.add(r);
                    }
                }
                List<String> names = new ArrayList<>(userSet);
                Collections.sort(names);
                for (String p : names) {
                    AppRow r = new AppRow();
                    r.pkg = p;
                    r.note = "";
                    r.disabled = disSet.contains(p);
                    rows.add(r);
                }
                ui.post(() -> {
                    apps = rows;
                    appsMsg = rows.isEmpty() ? "Aucune appli trouvée." : "";
                    if (top().equals("APPS")) render();
                });
            } catch (IOException e) {
                ui.post(() -> {
                    lost(e);
                    appsMsg = TabletLink.explain(e);
                    if (top().equals("APPS")) render();
                });
            }
        });
    }

    static Set<String> pkgs(String out) {
        Set<String> s = new HashSet<>();
        for (String l : out.split("\n")) {
            l = l.trim();
            if (l.startsWith("package:")) {
                String p = l.substring(8).trim();
                if (p.matches("[A-Za-z0-9._]+")) s.add(p);
            }
        }
        return s;
    }

    void appsScreen(LinearLayout c) {
        if (!needLicense(c)) return;
        if (!needTablet(c)) return;
        c.addView(tv("Touchez une appli pour l'arrêter, la désactiver ou la désinstaller. Tout est demandé avant d'agir.", 15, MUTED, false));
        c.addView(btn(apps == null ? "Charger la liste des applis" : "Actualiser la liste", apps == null, v -> loadApps()));
        if (!appsMsg.isEmpty()) c.addView(tv(appsMsg, 15, C_WARN, false));
        if (apps != null) {
            for (AppRow r : apps) {
                final AppRow row = r;
                LinearLayout k = card(r.suggested ? C_WARN : 0);
                k.addView(tv(r.suggested ? r.note : r.pkg, 16, TEXT, true));
                String sub = (r.suggested ? r.pkg + " · suggestion · " : "") + (r.disabled ? "désactivée" : "active");
                k.addView(tv(sub, 13, r.disabled ? C_WARN : MUTED, false));
                k.setOnClickListener(v -> appActions(row));
                c.addView(k);
            }
        }
    }

    void appActions(AppRow r) {
        final String[] items = r.disabled
                ? new String[]{"Réactiver", "Désinstaller", "Annuler"}
                : new String[]{"Arrêter maintenant", "Désactiver (annulable)", "Désinstaller", "Annuler"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(r.suggested ? r.note : r.pkg)
                .setItems(items, (d, which) -> {
                    String it = items[which];
                    if (it.equals("Annuler")) return;
                    if (it.startsWith("Désinstaller")) {
                        ask("Désinstaller ?", r.pkg + "\n\nL'appli et ses données seront retirées de la tablette.\n\nSi c'est une appli installée par vous : réinstallable depuis le Play Store.\nSi c'est une appli livrée avec la tablette : elle sera seulement retirée pour l'utilisateur, et « Annuler mes réglages » pourra la remettre.", "Désinstaller", () -> appCommand(r, "uninstall"));
                    } else if (it.startsWith("Désactiver")) {
                        ask("Désactiver ?", "L'appli disparaît et ne tourne plus. Vous pourrez la réactiver ici.", "Désactiver", () -> appCommand(r, "disable"));
                    } else if (it.startsWith("Réactiver")) {
                        appCommand(r, "enable");
                    } else {
                        appCommand(r, "stop");
                    }
                }).show();
    }

    void appCommand(AppRow r, String what) {
        if (!licensed() || !link.isConnected() || !r.pkg.matches("[A-Za-z0-9._]+")) return;
        appsMsg = "En cours…";
        render();
        io.execute(() -> {
            String res;
            try {
                String out;
                switch (what) {
                    case "stop":
                        out = link.sh("am force-stop " + r.pkg, 15000);
                        break;
                    case "disable":
                        out = link.sh("pm disable-user --user 0 " + r.pkg, 15000);
                        if (!out.toLowerCase().contains("new state")) out = link.sh("pm disable " + r.pkg, 15000);
                        if (!Repairs.failed(out)) {
                            Set<String> s = new HashSet<>(prefs.getStringSet("disabled_" + link.key, new HashSet<>()));
                            s.add(r.pkg);
                            prefs.edit().putStringSet("disabled_" + link.key, s).apply();
                        }
                        break;
                    case "enable":
                        out = link.sh("pm enable " + r.pkg, 15000);
                        if (!Repairs.failed(out)) {
                            Set<String> s = new HashSet<>(prefs.getStringSet("disabled_" + link.key, new HashSet<>()));
                            s.remove(r.pkg);
                            prefs.edit().putStringSet("disabled_" + link.key, s).apply();
                        }
                        break;
                    default:
                        out = link.sh("pm uninstall " + r.pkg, 60000);
                        if (!out.contains("Success")) {
                            out = link.sh("pm uninstall --user 0 " + r.pkg, 60000);
                            if (out.contains("Success")) {
                                // appli système retirée pour l'utilisateur seulement : on note le paquet pour pouvoir la remettre
                                Set<String> s = new HashSet<>(prefs.getStringSet("removed_" + link.key, new HashSet<>()));
                                s.add(r.pkg);
                                prefs.edit().putStringSet("removed_" + link.key, s).apply();
                            }
                        }
                }
                boolean ok = !Repairs.failed(out) && !out.contains("Failure");
                res = ok ? "Fait : " + r.pkg : "La tablette a refusé : " + out.trim();
            } catch (IOException e) {
                res = TabletLink.explain(e);
                final IOException ee = e;
                ui.post(() -> lost(ee));
            }
            final String msg = res;
            ui.post(() -> {
                appsMsg = msg;
                if (top().equals("APPS")) render();
            });
            if (!what.equals("stop")) ui.post(this::loadApps);
        });
    }

    // =====================================================================
    // Mises à jour (tablette)
    // =====================================================================
    void setUpd(String msg, int percent) {
        ui.post(() -> {
            updMsg = msg;
            updPercent = percent;
            if (updStatusView != null) updStatusView.setText(msg);
            if (updBar != null) {
                updBar.setVisibility(percent >= 0 ? View.VISIBLE : View.GONE);
                updBar.setProgress(Math.max(0, percent));
            }
        });
    }

    void updatesScreen(LinearLayout c) {
        if (!needLicense(c)) return;
        if (!needTablet(c)) return;
        c.addView(btn("Mise à jour du système Android", true, v -> ask("Système Android",
                Repairs.SYSTEM_UPDATE.explain + "\n\nGardez la tablette branchée sur secteur pendant la mise à jour.", "Ouvrir sur la tablette",
                () -> runSteps(Collections.singletonList(Repairs.SYSTEM_UPDATE)))));
        c.addView(tv("Ouvre l'écran de mise à jour sur la tablette : c'est elle qui télécharge et installe.", 13, MUTED, false));
        c.addView(btn("Mises à jour des applis (Play Store)", false, v -> ask("Play Store", Repairs.PLAY_STORE.explain, "Ouvrir sur la tablette",
                () -> runSteps(Collections.singletonList(Repairs.PLAY_STORE)))));
        c.addView(tv("Ouvre le Play Store sur la tablette : touchez la photo de profil, puis « Gérer les applis et l'appareil », puis « Tout mettre à jour ».", 13, MUTED, false));
        c.addView(btn("Installer une appli depuis le téléphone", false, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_APK);
        }));
        c.addView(tv("Choisissez un fichier .apk sur ce téléphone : il est envoyé par le câble et installé (ou mis à jour) sur la tablette.", 13, MUTED, false));
        updStatusView = tv(updMsg, 15, TEXT, false);
        c.addView(updStatusView);
        updBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updBar.setMax(100);
        updBar.setVisibility(updPercent >= 0 ? View.VISIBLE : View.GONE);
        updBar.setProgress(Math.max(0, updPercent));
        c.addView(updBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        if (!busy && repairLog.length() > 0) c.addView(tv(repairLog.toString(), 13, MUTED, false));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == ExtraScreens.REQ_PHOTO || req == ExtraScreens.REQ_PICK) {
            extra.onPhoto(req, res == RESULT_OK, data);
            return;
        }
        if (req == ExtraScreens.REQ_IMG && res == RESULT_OK && data != null) {
            extra.onImage(data.getData());
            return;
        }
        if (req == ExtraScreens.REQ_TREE && res == RESULT_OK && data != null) {
            extra.onTree(data.getData());
            return;
        }
        if (req == REQ_APK && res == RESULT_OK && data != null && data.getData() != null) installApk(data.getData());
    }

    static String installError(String out) {
        if (out.contains("VERSION_DOWNGRADE")) return "La tablette a déjà une version plus récente de cette appli.";
        if (out.contains("UPDATE_INCOMPATIBLE") || out.contains("SIGNATURE")) return "Cette appli a une signature différente de celle installée. Désinstallez d'abord l'ancienne.";
        if (out.contains("INSUFFICIENT_STORAGE")) return "Plus assez de place sur la tablette.";
        if (out.contains("OLDER_SDK")) return "Cette appli est trop récente pour la version d'Android de la tablette.";
        if (out.contains("NO_MATCHING_ABIS")) return "Cette appli n'est pas compatible avec le processeur de la tablette.";
        if (out.contains("INVALID_APK") || out.contains("PARSE")) return "Le fichier n'est pas une appli valide.";
        return "La tablette a refusé : " + out.trim();
    }

    void installApk(Uri uri) {
        if (!licensed()) {
            info("Licence", "Cette fonction demande une licence.");
            return;
        }
        if (!link.isConnected()) {
            info("Tablette", "Connectez d'abord la tablette.");
            return;
        }
        setUpd("Préparation…", 0);
        io.execute(() -> {
            try {
                long size = -1;
                try (Cursor cu = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                    if (cu != null && cu.moveToFirst()) size = cu.getLong(0);
                }
                if (size <= 0) {
                    try (AssetFileDescriptor f = getContentResolver().openAssetFileDescriptor(uri, "r")) {
                        if (f != null) size = f.getLength();
                    }
                }
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null || size <= 0 || in.read() != 'P' || in.read() != 'K') {
                        setUpd("", -1);
                        ui.post(() -> info("Fichier refusé", "Ce fichier n'est pas une appli (.apk)."));
                        return;
                    }
                }
                final long fsize = size;
                final fr.doctablette.adb.AdbConnection cx = link.cx;
                if (cx == null) throw new IOException("Pas de connexion");
                cx.setProgress(sent -> setUpd("Envoi vers la tablette… " + (100 * sent / fsize) + " %", (int) (100 * sent / fsize)));
                String out;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    out = cx.execWithInput("cmd package install -r -S " + size, in, 900000);
                }
                if (!out.contains("Success") && !out.contains("Failure")) {
                    setUpd("Nouvel essai (méthode 2)…", 0);
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        cx.execWithInput("head -c " + size + " > /data/local/tmp/dt-install.apk", in, 900000);
                    }
                    setUpd("Installation sur la tablette…", -1);
                    out = link.sh("pm install -r /data/local/tmp/dt-install.apk; rm -f /data/local/tmp/dt-install.apk", 300000);
                }
                cx.setProgress(null);
                final String res = out;
                setUpd("", -1);
                ui.post(() -> {
                    if (res.contains("Success")) info("Installé", "L'appli a été installée sur la tablette.");
                    else info("Installation impossible", installError(res));
                });
            } catch (Exception ex) {
                final IOException e = ex instanceof IOException ? (IOException) ex : new IOException(String.valueOf(ex.getMessage()));
                setUpd("", -1);
                ui.post(() -> {
                    lost(e);
                    info("Installation interrompue", TabletLink.explain(e));
                });
            }
        });
    }

    // =====================================================================
    // Tests
    // =====================================================================
    void testsScreen(LinearLayout c) {
        if (!needTablet(c)) return;
        c.addView(tv("Ouvre sur la tablette une page avec trois tests : pixels morts de l'écran, tactile, et son.", 15, MUTED, false));
        c.addView(btn("Lancer les tests sur la tablette", true, v -> openTestPage()));
        c.addView(btn("Voir le matériel détecté", false, v -> hardware()));
        if (!hwText.isEmpty()) {
            LinearLayout k = card(0);
            k.addView(tv(hwText, 15, TEXT, false));
            c.addView(k);
        }
    }

    void openTestPage() {
        if (!link.isConnected()) return;
        toast("Ouverture sur la tablette…");
        io.execute(() -> {
            String done = "";
            try {
                String b64 = Base64.encodeToString(TestPage.HTML.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                String out = link.sh("am start -a android.intent.action.VIEW -d 'data:text/html;charset=utf-8;base64," + b64 + "'", 20000);
                if (!Repairs.failed(out) && !out.contains("Error")) {
                    done = "ok";
                } else {
                    byte[] page = TestPage.HTML.getBytes(StandardCharsets.UTF_8);
                    link.execIn("head -c " + page.length + " > /sdcard/Download/doc-tablette-test.html",
                            new java.io.ByteArrayInputStream(page), 30000);
                    out = link.sh("am start -a android.intent.action.VIEW -d file:///sdcard/Download/doc-tablette-test.html -t text/html", 20000);
                    if (!Repairs.failed(out) && !out.contains("Error")) done = "ok";
                }
            } catch (IOException e) {
                final IOException ee = e;
                ui.post(() -> {
                    lost(ee);
                    info("Tests", TabletLink.explain(ee));
                });
                return;
            }
            final boolean ok = done.equals("ok");
            if (!ok) ui.post(() -> info("Tests", "La tablette n'a pas pu ouvrir la page de test (pas de navigateur ?). Installez ou activez Chrome sur la tablette."));
        });
    }

    void hardware() {
        io.execute(() -> {
            try {
                String f = link.sh("pm list features", 20000);
                String[][] map = {
                        {"android.hardware.camera.front", "Caméra avant"}, {"android.hardware.camera.any", "Caméra"},
                        {"android.hardware.wifi", "Wi-Fi"}, {"android.hardware.bluetooth", "Bluetooth"},
                        {"android.hardware.location.gps", "GPS"}, {"android.hardware.nfc", "NFC"},
                        {"android.hardware.sensor.accelerometer", "Capteur de mouvement"},
                        {"android.hardware.sensor.gyroscope", "Gyroscope"}, {"android.hardware.sensor.light", "Capteur de lumière"},
                        {"android.hardware.fingerprint", "Lecteur d'empreinte"}, {"android.hardware.telephony", "Téléphonie (SIM)"},
                        {"android.hardware.microphone", "Micro"}, {"android.hardware.audio.output", "Sortie son"}};
                StringBuilder sb = new StringBuilder();
                for (String[] m : map) sb.append(m[1]).append(" : ").append(f.contains("feature:" + m[0]) ? "présent" : "non signalé").append('\n');
                final String t = sb.toString().trim();
                ui.post(() -> {
                    hwText = t;
                    if (top().equals("TESTS")) render();
                });
            } catch (IOException e) {
                ui.post(() -> {
                    lost(e);
                    hwText = TabletLink.explain(e);
                    if (top().equals("TESTS")) render();
                });
            }
        });
    }

    // =====================================================================
    // Licence et pages légales
    // =====================================================================
    void licenseScreen(LinearLayout c) {
        boolean ok = licensed();
        LinearLayout k = card(ok ? C_OK : 0);
        k.addView(tv(ok ? "Licence active. Merci !" : "Pas encore de licence", 18, ok ? C_OK : TEXT, true));
        k.addView(tv(ok ? "Toutes les fonctions sont débloquées." : "Le bilan et les tests sont gratuits. Réparer, gérer les applis et installer demandent un code d'activation, envoyé après le paiement.", 15, MUTED, false));
        c.addView(k);
        if (ok) return;
        final EditText code = new EditText(this);
        code.setHint("DT-XXXX-XXXX-XXXX-XXXX");
        code.setHintTextColor(MUTED);
        code.setTextColor(TEXT);
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keepDraft("licence", code);
        c.addView(code, new LinearLayout.LayoutParams(MATCH, WRAP));
        c.addView(btn("Activer", true, v -> {
            String s = code.getText().toString();
            if (LicenseCodec.verify(BuildConfig.LICENSE_SECRET, s)) {
                prefs.edit().putString("licence", s.trim()).apply();
                drafts.remove("licence");
                toast("Licence activée");
                render();
            } else {
                info("Code non valide", "Ce code n'est pas reconnu. Vérifiez qu'il est recopié exactement, avec ses tirets.");
            }
        }));
    }

    void legalScreen(LinearLayout c) {
        c.addView(tv(Texts.LEGAL_PLACEHOLDER_NOTE, 14, C_WARN, false));
        c.addView(btn("Mentions légales", false, v -> info("Mentions légales", Texts.MENTIONS)));
        c.addView(btn("Conditions d'utilisation", false, v -> info("Conditions d'utilisation", Texts.CGU)));
        c.addView(btn("Politique de confidentialité", false, v -> info("Confidentialité", Texts.CONFIDENTIALITE)));
    }

    // =====================================================================
    // Mise à jour de Doc Tablette elle-même
    // =====================================================================
    boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    void checkAppUpdate(boolean manual) {
        net.execute(() -> {
            try {
                Updater.Info i = Updater.check(BuildConfig.UPDATE_URL);
                if (i.code > BuildConfig.VERSION_CODE) {
                    ui.post(() -> {
                        pendingInfo = i;
                        if (top().equals("HOME")) render();
                        offerUpdate(i);
                    });
                } else if (manual) {
                    ui.post(() -> info("À jour", "Vous avez la dernière version (" + BuildConfig.VERSION_NAME + ")."));
                }
            } catch (Exception e) {
                if (manual) ui.post(() -> info("Mise à jour", "Impossible de vérifier pour le moment (pas de connexion ?). Réessayez plus tard."));
            }
        });
    }

    void offerUpdate(Updater.Info i) {
        StringBuilder sb = new StringBuilder("Version " + i.name + "\n\nCe qui change :\n");
        if (i.notes.isEmpty()) sb.append("- Améliorations et corrections\n");
        for (String n : i.notes) sb.append("- ").append(n).append('\n');
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Mise à jour disponible").setMessage(sb.toString())
                .setPositiveButton("Mettre à jour", (d, w) -> startUpdate(i))
                .setNegativeButton("Plus tard", null).show();
    }

    void startUpdate(Updater.Info i) {
        if (!top().equals("APPUPDATE")) stack.add("APPUPDATE");
        updMsg = "Téléchargement…";
        updPercent = 0;
        render();
        net.execute(() -> {
            try {
                File apk = Updater.download(this, i, p -> setUpd("Téléchargement… " + p + " %", p));
                setUpd("Téléchargé. Installation…", 100);
                ui.post(() -> {
                    if (!canInstall()) {
                        pendingApk = apk;
                        info("Autorisation nécessaire", "Android demande d'autoriser Doc Tablette à installer sa mise à jour. Touchez « Autoriser » puis revenez ici : l'installation reprend toute seule.");
                        Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                        startActivity(s);
                    } else {
                        net.execute(() -> doInstall(apk));
                    }
                });
            } catch (Exception e) {
                setUpd("Le téléchargement a échoué. Vérifiez la connexion puis touchez « Rechercher une mise à jour ».", -1);
            }
        });
    }

    void doInstall(File apk) {
        try {
            Updater.backupCurrent(this);
            setUpd("Installation en cours… Confirmez si Android le demande.", -1);
            Updater.install(this, apk, ACTION_INSTALL);
        } catch (Exception e) {
            setUpd("Installation impossible : " + e.getMessage(), -1);
        }
    }

    void appUpdateScreen(LinearLayout c) {
        c.addView(tv("Version installée : " + BuildConfig.VERSION_NAME, 16, TEXT, true));
        c.addView(btn("Rechercher une mise à jour", true, v -> checkAppUpdate(true)));
        updStatusView = tv(updMsg, 15, TEXT, false);
        c.addView(updStatusView);
        updBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updBar.setMax(100);
        updBar.setVisibility(updPercent >= 0 ? View.VISIBLE : View.GONE);
        updBar.setProgress(Math.max(0, updPercent));
        c.addView(updBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        c.addView(tv("La mise à jour se fait directement dans l'appli : la liste des changements s'affiche, vous validez, et la nouvelle version remplace l'ancienne. Vos réglages sont gardés.", 14, MUTED, false));
    }

    // =====================================================================
    // Réception des événements système
    // =====================================================================
    final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            String a = i.getAction();
            if (ACTION_USB_PERM.equals(a)) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (UsbAdbChannel.hasAdbInterface(d)) connectUsb(d);
                } else {
                    connectMsg = "Accès USB refusé. Rebranchez le câble et touchez « OK » à la demande.";
                }
                if (top().equals("CONNECT") || top().equals("FASTBOOT") || top().equals("ODIN") || top().equals("CHIP")) render();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (link.usbDevice != null && d != null && link.usbDevice.getDeviceName().equals(d.getDeviceName())) {
                    link.disconnect();
                    connectMsg = "Tablette débranchée.";
                    busy = false;
                }
                scanUsb(false);
                render();
            } else if (ACTION_INSTALL.equals(a)) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(confirm);
                    }
                } else if (st == PackageInstaller.STATUS_SUCCESS) {
                    setUpd("Mise à jour installée.", -1);
                } else {
                    String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    setUpd("Mise à jour non installée" + (m == null ? "" : " : " + m) + ". Touchez « Rechercher une mise à jour » pour réessayer.", -1);
                }
            }
        }
    };

    // =====================================================================
    // Bouton « ? » : signaler un problème
    // =====================================================================
    void reportDialog() {
        final String where = titleOf(top());
        final String ctxt = "Écran : " + where + "\nVersion : " + BuildConfig.VERSION_NAME
                + "\nTéléphone : " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")"
                + "\nTablette : " + (link.isConnected() ? link.label : "non connectée");
        final EditText e = new EditText(this);
        e.setHint("Décrivez ce qui ne va pas");
        e.setMinLines(3);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        String note = "Rien n'est envoyé automatiquement. Écran concerné : " + where + ".";
        if (REPORT_EMAIL.isEmpty()) note += "\n\nAdresse de réception des signalements : à compléter.";
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Signaler un problème").setMessage(note).setView(e)
                .setPositiveButton("Copier", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Signalement", e.getText() + "\n\n" + ctxt));
                    toast("Signalement copié");
                })
                .setNegativeButton("Fermer", null);
        if (!REPORT_EMAIL.isEmpty()) {
            b.setNeutralButton("Envoyer par e-mail", (d, w) -> {
                Intent m = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + REPORT_EMAIL));
                m.putExtra(Intent.EXTRA_SUBJECT, "Doc Tablette : problème (" + where + ")");
                m.putExtra(Intent.EXTRA_TEXT, e.getText() + "\n\n" + ctxt);
                try {
                    startActivity(m);
                } catch (Exception ex) {
                    toast("Aucune appli e-mail");
                }
            });
        }
        b.show();
    }
}
