package fr.doctablette;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Copie les photos, vidéos et documents de la tablette vers un dossier du téléphone, sans rien effacer. */
final class Backup {
    interface Listener {
        void progress(int done, int total, String name, long bytesDone, long bytesTotal);
    }

    static final class Item {
        String path, rel;
        long size = -1;
    }

    static final class Result {
        int copied, skipped, failed;
        long bytes;
        List<String> failures = new ArrayList<>();
        boolean cancelled;
    }

    static final String[][] KINDS = {
            {"Photos et vidéos", "/sdcard/DCIM /sdcard/Pictures /sdcard/Movies"},
            {"Photos et vidéos des messageries (WhatsApp, Telegram)", "/sdcard/Android/media/com.whatsapp/WhatsApp/Media /sdcard/WhatsApp/Media /sdcard/Telegram"},
            {"Documents et téléchargements", "/sdcard/Documents /sdcard/Download"},
            {"Musique et enregistrements", "/sdcard/Music /sdcard/Recordings"},
    };

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Ajoute, pour chaque dossier de la mémoire interne, le même dossier sur une éventuelle carte microSD. */
    static String withSdCard(String dirs) {
        StringBuilder sb = new StringBuilder(dirs);
        for (String d : dirs.trim().split("\\s+")) {
            if (d.startsWith("/sdcard/")) sb.append(" /storage/[0-9A-F][0-9A-F][0-9A-F][0-9A-F]-*/").append(d.substring(8));
        }
        return sb.toString();
    }

    /** Liste les fichiers. Méthode 1 : taille connue. Méthode 2 : noms seulement. Méthode 3 (vieilles tablettes) : ls -lR. */
    static List<Item> scan(TabletLink link, String dirs) throws IOException {
        dirs = withSdCard(dirs);
        List<Item> out = new ArrayList<>();
        String a = link.sh("find " + dirs + " -type f -not -name '.*' -exec stat -c '%s|%n' {} + 2>/dev/null", 180000);
        for (String line : a.split("\n")) {
            int i = line.indexOf('|');
            if (i <= 0) continue;
            try {
                Item it = new Item();
                it.size = Long.parseLong(line.substring(0, i).trim());
                it.path = line.substring(i + 1);
                it.rel = rel(it.path);
                out.add(it);
            } catch (NumberFormatException ignored) {
            }
        }
        if (!out.isEmpty()) return out;
        String b = link.sh("find " + dirs + " -type f -not -name '.*' 2>/dev/null", 180000);
        for (String line : b.split("\n")) {
            if (!line.startsWith("/")) continue;
            Item it = new Item();
            it.path = line;
            it.rel = rel(line);
            out.add(it);
        }
        if (!out.isEmpty()) return out;
        String c = link.sh("ls -lR " + dirs + " 2>/dev/null", 180000);
        parseLsR(c, out);
        return out;
    }

    /** Lecture de « ls -lR » (toolbox ou toybox) : la taille est le champ juste avant la date. */
    static void parseLsR(String text, List<Item> out) {
        String dir = null;
        for (String raw : text.split("\n")) {
            String line = raw.replace("\r", "");
            if (line.startsWith("/") && line.endsWith(":")) {
                dir = line.substring(0, line.length() - 1);
                continue;
            }
            if (dir == null || !line.startsWith("-")) continue;
            String[] t = line.trim().split("\\s+");
            int date = -1;
            for (int i = 0; i < t.length; i++) if (t[i].matches("\\d{4}-\\d{2}-\\d{2}")) { date = i; break; }
            if (date < 2 || date + 2 >= t.length) continue;
            StringBuilder name = new StringBuilder();
            for (int i = date + 2; i < t.length; i++) name.append(i > date + 2 ? " " : "").append(t[i]);
            if (name.length() == 0 || name.charAt(0) == '.') continue;
            Item it = new Item();
            try {
                it.size = Long.parseLong(t[date - 1]);
            } catch (NumberFormatException e) {
                it.size = -1;
            }
            it.path = dir + "/" + name;
            it.rel = rel(it.path);
            out.add(it);
        }
    }

    static String rel(String p) {
        if (p.startsWith("/sdcard/")) return p.substring(8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^/storage/([^/]+)/(.*)$").matcher(p);
        if (m.matches()) return "Carte-SD_" + m.group(1) + "/" + m.group(2);
        return p.replaceFirst("^/+", "");
    }

    private final Context ctx;
    private final ContentResolver cr;
    private final Uri tree;
    private final Map<String, Uri> dirUri = new HashMap<>();
    private final Map<Uri, Map<String, Long>> listing = new HashMap<>();
    volatile boolean cancel;

    Backup(Context ctx, Uri tree) {
        this.ctx = ctx;
        this.cr = ctx.getContentResolver();
        this.tree = tree;
    }

    private Uri rootDoc() {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    private Map<String, Long> children(Uri dirDoc) {
        Map<String, Long> m = listing.get(dirDoc);
        if (m != null) return m;
        m = new HashMap<>();
        Uri ch = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dirDoc));
        try (Cursor c = cr.query(ch, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            while (c != null && c.moveToNext()) m.put(c.getString(0), c.isNull(1) ? -1L : c.getLong(1));
        } catch (Exception ignored) {
        }
        listing.put(dirDoc, m);
        return m;
    }

    private Uri dir(String relDir) throws IOException {
        if (relDir.isEmpty()) return rootDoc();
        Uri u = dirUri.get(relDir);
        if (u != null) return u;
        int i = relDir.lastIndexOf('/');
        Uri parent = dir(i < 0 ? "" : relDir.substring(0, i));
        String name = i < 0 ? relDir : relDir.substring(i + 1);
        Map<String, Long> ch = children(parent);
        Uri made = null;
        if (ch.containsKey(name)) {
            // le dossier existe déjà : retrouver son identifiant
            Uri chUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
            try (Cursor c = cr.query(chUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (name.equals(c.getString(1))) {
                        made = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                        break;
                    }
                }
            }
        }
        if (made == null) {
            try {
                made = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
            } catch (Exception e) {
                throw new IOException("Création du dossier impossible : " + name);
            }
            if (made == null) throw new IOException("Création du dossier impossible : " + name);
            ch.put(name, -1L);
        }
        dirUri.put(relDir, made);
        return made;
    }

    static String mime(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    /** Copie tous les fichiers. Ne modifie jamais la tablette. Passe ce qui existe déjà avec la même taille. */
    Result run(TabletLink link, List<Item> items, Listener l) {
        Result r = new Result();
        long total = 0;
        for (Item it : items) if (it.size > 0) total += it.size;
        long done = 0;
        int n = 0;
        for (Item it : items) {
            if (cancel) {
                r.cancelled = true;
                break;
            }
            n++;
            if (l != null) l.progress(n, items.size(), it.rel, done, total);
            try {
                int i = it.rel.lastIndexOf('/');
                Uri d = dir(i < 0 ? "" : it.rel.substring(0, i));
                String name = i < 0 ? it.rel : it.rel.substring(i + 1);
                Long have = children(d).get(name);
                if (have != null && ((it.size >= 0 && have == it.size) || (it.size < 0 && have > 0))) {
                    r.skipped++;
                    if (it.size > 0) done += it.size;
                    continue;
                }
                if (have != null) name = "copie_" + System.currentTimeMillis() % 100000 + "_" + name;
                boolean ok = false;
                IOException last = null;
                for (int attempt = 1; attempt <= 2 && !ok; attempt++) {
                    Uri f = null;
                    try {
                        f = DocumentsContract.createDocument(cr, d, mime(name), name);
                        if (f == null) throw new IOException("Création du fichier impossible");
                        final long base = done;
                        final long tot = total;
                        final int num = n;
                        CountingStream out = new CountingStream(cr.openOutputStream(f, "w"));
                        try {
                            link.cx.stream("exec:cat " + q(it.path), null, out, 600000);
                        } finally {
                            try {
                                out.close();
                            } catch (IOException ignored) {
                            }
                        }
                        if (it.size >= 0 && out.count != it.size) throw new IOException("Taille différente : " + out.count + " au lieu de " + it.size);
                        r.bytes += out.count;
                        done += Math.max(out.count, 0);
                        children(d).put(name, out.count);
                        ok = true;
                        if (l != null) l.progress(num, items.size(), it.rel, base + out.count, tot);
                    } catch (IOException e) {
                        last = e;
                        String em = e.getMessage() == null ? "" : e.getMessage();
                        if (em.contains("Arrêté à votre demande")) {
                            cancel = true;
                            attempt = 2;
                        }
                        if (em.contains("Commande refusée")) {
                            last = new IOException("Cette tablette est trop ancienne pour la copie par câble (Android 4). Utilisez le mode « Transfert de fichiers » et l'appli Fichiers du téléphone.");
                            attempt = 2;
                        }
                        if (f != null) try {
                            DocumentsContract.deleteDocument(cr, f);
                        } catch (Exception ignored) {
                        }
                        if (link.cx == null || link.cx.isClosed()) break;
                    }
                }
                if (ok) r.copied++;
                else {
                    r.failed++;
                    if (r.failures.size() < 20) r.failures.add(it.rel + " : " + (last == null ? "échec" : last.getMessage()));
                    if (link.cx == null || link.cx.isClosed()) {
                        r.failures.add("Câble débranché : arrêt.");
                        break;
                    }
                }
            } catch (Exception e) {
                r.failed++;
                if (r.failures.size() < 20) r.failures.add(it.rel + " : " + e.getMessage());
            }
        }
        return r;
    }

    private static final class CountingStream extends OutputStream {
        final OutputStream o;
        long count;

        CountingStream(OutputStream o) throws IOException {
            if (o == null) throw new IOException("Dossier de sauvegarde inaccessible");
            this.o = o;
        }

        @Override
        public void write(int b) throws IOException {
            o.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
            count += len;
        }

        @Override
        public void close() throws IOException {
            o.close();
        }
    }
}
