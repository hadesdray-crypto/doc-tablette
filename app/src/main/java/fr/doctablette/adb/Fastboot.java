package fr.doctablette.adb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Protocole fastboot (public, documenté par Android) : le téléphone joue le rôle de l'ordinateur. */
public final class Fastboot {
    public static final class Reply {
        public final boolean ok;
        public final String text;     // texte final (après OKAY ou FAIL)
        public final List<String> info = new ArrayList<>();

        Reply(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }
    }

    public interface Progress {
        void sent(long bytes, long total);
    }

    private final AdbChannel ch;

    public Fastboot(AdbChannel ch) {
        this.ch = ch;
    }

    public void close() {
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    private void write(byte[] b, int len) throws IOException {
        int off = 0, chunk = Math.max(64, ch.maxChunk());
        while (off < len) {
            int n = Math.min(chunk, len - off);
            ch.write(b, off, n);
            off += n;
        }
    }

    /** Lit une réponse de 4 lettres + texte. Les messages INFO sont collectés. */
    private Reply readReply(long timeoutMs, List<String> info, String[] dataOut) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        byte[] b = new byte[256];
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new IOException("L'appareil ne répond plus");
            int r = ch.read(b, 0, b.length, (int) Math.min(left, 1000));
            if (r < 0) throw new IOException("Connexion coupée");
            if (r == 0) continue;
            if (r < 4) throw new IOException("Réponse fastboot invalide");
            String s = new String(b, 0, r, StandardCharsets.US_ASCII);
            String tag = s.substring(0, 4), rest = s.substring(4).trim();
            switch (tag) {
                case "INFO":
                    if (info != null) info.add(rest);
                    end = System.currentTimeMillis() + timeoutMs; // l'appareil travaille encore
                    break;
                case "TEXT":
                    end = System.currentTimeMillis() + timeoutMs;
                    break;
                case "OKAY":
                    return new Reply(true, rest);
                case "FAIL":
                    return new Reply(false, rest);
                case "DATA":
                    if (dataOut != null) dataOut[0] = rest;
                    return new Reply(true, rest);
                default:
                    throw new IOException("Réponse fastboot inconnue : " + tag);
            }
        }
    }

    /** Envoie une commande texte (getvar:…, reboot, erase:…, flash:…). */
    public Reply command(String cmd, long timeoutMs) throws IOException {
        byte[] c = cmd.getBytes(StandardCharsets.US_ASCII);
        if (c.length > 64) throw new IOException("Commande trop longue");
        write(c, c.length);
        List<String> info = new ArrayList<>();
        Reply r = readReply(timeoutMs, info, null);
        Reply out = new Reply(r.ok, r.text);
        out.info.addAll(info);
        return out;
    }

    public String getvar(String name) throws IOException {
        Reply r = command("getvar:" + name, 8000);
        return r.ok ? r.text : "";
    }

    /** Envoie les données (étape « download ») puis attend OKAY. */
    public void download(InputStream in, long len, Progress p) throws IOException {
        String hex = String.format("%08x", len);
        byte[] c = ("download:" + hex).getBytes(StandardCharsets.US_ASCII);
        write(c, c.length);
        String[] d = new String[1];
        Reply r = readReply(15000, null, d);
        if (!r.ok) throw new IOException("Refusé par l'appareil : " + r.text);
        if (d[0] == null || Long.parseLong(d[0].trim(), 16) != len) throw new IOException("Taille refusée par l'appareil");
        byte[] buf = new byte[16384];
        long sent = 0;
        while (sent < len) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, len - sent));
            if (n < 0) throw new IOException("Fichier trop court");
            write(buf, n);
            sent += n;
            if (p != null) p.sent(sent, len);
        }
        Reply done = readReply(60000, null, null);
        if (!done.ok) throw new IOException("Envoi refusé : " + done.text);
    }

    /** Installe une image dans une partition. L'image doit tenir dans max-download-size. */
    public Reply flash(String partition, InputStream in, long len, Progress p) throws IOException {
        if (!partition.matches("[A-Za-z0-9_\\-]{1,30}")) throw new IOException("Nom de partition invalide");
        long max = maxDownload();
        if (max > 0 && len > max) throw new IOException("Image trop grosse pour un seul envoi (" + len + " > " + max + " octets)");
        download(in, len, p);
        return command("flash:" + partition, 600000);
    }

    public long maxDownload() {
        try {
            String v = getvar("max-download-size").trim();
            if (v.startsWith("0x") || v.startsWith("0X")) return Long.parseLong(v.substring(2), 16);
            return v.isEmpty() ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }
}
