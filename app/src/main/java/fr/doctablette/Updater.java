package fr.doctablette;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Mise à jour de l'appli sur place : vérification, téléchargement avec reprise, installation. */
final class Updater {
    static final class Info {
        int code;
        String name, url, sha256;
        List<String> notes = new ArrayList<>();
    }

    interface Progress {
        void percent(int p);
    }

    private Updater() {
    }

    static Info check(String url) throws Exception {
        HttpURLConnection c = open(url, 0);
        try {
            if (c.getResponseCode() != 200) throw new IOException("Réponse " + c.getResponseCode());
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            }
            JSONObject o = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            Info i = new Info();
            i.code = o.getInt("versionCode");
            i.name = o.optString("versionName", String.valueOf(i.code));
            i.url = o.getString("apkUrl");
            i.sha256 = o.optString("sha256", "");
            JSONArray n = o.optJSONArray("notes");
            if (n != null) for (int k = 0; k < n.length(); k++) i.notes.add(n.getString(k));
            return i;
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, long from) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
        return c;
    }

    /** Télécharge avec reprise (jusqu'à 4 essais) et vérifie l'empreinte. */
    static File download(Context ctx, Info info, Progress pr) throws Exception {
        File dir = new File(ctx.getCacheDir(), "maj");
        dir.mkdirs();
        File part = new File(dir, "doc-tablette-" + info.code + ".part");
        File done = new File(dir, "doc-tablette-" + info.code + ".apk");
        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                long have = part.exists() ? part.length() : 0;
                HttpURLConnection c = open(info.url, have);
                int code = c.getResponseCode();
                if (code == 200) have = 0; // le serveur ne reprend pas : on repart de zéro
                else if (code != 206) throw new IOException("Réponse " + code);
                long total = have + c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, have > 0)) {
                    byte[] b = new byte[16384];
                    int n;
                    long got = have;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        got += n;
                        if (total > 0 && pr != null) pr.percent((int) (100 * got / total));
                    }
                }
                c.disconnect();
                if (!info.sha256.isEmpty() && !sha256(part).equalsIgnoreCase(info.sha256)) {
                    part.delete();
                    throw new IOException("Fichier incomplet ou abîmé, nouveau téléchargement");
                }
                if (done.exists()) done.delete();
                if (!part.renameTo(done)) throw new IOException("Fichier impossible à enregistrer");
                if (pr != null) pr.percent(100);
                return done;
            } catch (Exception e) {
                last = e;
                Thread.sleep(1500L * attempt);
            }
        }
        throw last;
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Garde une copie de la version actuelle avant de la remplacer. */
    static void backupCurrent(Context ctx) {
        try {
            File src = new File(ctx.getApplicationInfo().sourceDir);
            File dir = new File(ctx.getFilesDir(), "sauvegarde");
            dir.mkdirs();
            File dst = new File(dir, "doc-tablette-precedente.apk");
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
        } catch (Exception ignored) {
        }
    }

    /** Installation sur place par le système (une seule confirmation). */
    static void install(Context ctx, File apk, String action) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("doc-tablette", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(action).setPackage(ctx.getPackageName());
            PendingIntent pe = PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pe.getIntentSender());
        }
    }
}
