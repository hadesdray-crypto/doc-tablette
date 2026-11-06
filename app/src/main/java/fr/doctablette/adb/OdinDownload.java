package fr.doctablette.adb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Protocole du mode « Téléchargement » Samsung (« Odin »).
 * Fondé sur la description publique du protocole par le projet Heimdall (licence MIT,
 * Copyright (c) 2010-2017 Benjamin Dobell, Glass Echidna). Cette version ne sait que LIRE (liste des
 * partitions) et QUITTER le mode : aucune écriture dans la mémoire de la tablette.
 */
public final class OdinDownload {
    public static final class Partition {
        public String name, flashFile;
        public long identifier, blockCount, blockSize;
    }

    private static final int CTL = 1024;
    private final AdbChannel ch;

    public OdinDownload(AdbChannel ch) {
        this.ch = ch;
    }

    public void close() {
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    private void le(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private long rd(byte[] b, int off) {
        return (b[off] & 0xffL) | (b[off + 1] & 0xffL) << 8 | (b[off + 2] & 0xffL) << 16 | (b[off + 3] & 0xffL) << 24;
    }

    private void writeAll(byte[] b, int len) throws IOException {
        int off = 0, chunk = Math.max(64, ch.maxChunk());
        while (off < len) {
            int n = Math.min(chunk, len - off);
            ch.write(b, off, n);
            off += n;
        }
    }

    private void control(int type, long... ints) throws IOException {
        byte[] p = new byte[CTL];
        le(p, 0, type);
        for (int i = 0; i < ints.length; i++) le(p, 4 + 4 * i, ints[i]);
        writeAll(p, CTL);
        ch.writeEmpty();
    }

    private int read(byte[] b, int len, int timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new IOException("La tablette ne répond pas");
            int r = ch.read(b, 0, len, (int) Math.min(left, 1000));
            if (r < 0) throw new IOException("Connexion coupée");
            if (r > 0) return r;
        }
    }

    private long response(int expectedType) throws IOException {
        byte[] b = new byte[8];
        int r = read(b, 8, 5000);
        if (r != 8) throw new IOException("Réponse incomplète");
        if (rd(b, 0) != expectedType) throw new IOException("Réponse inattendue");
        return rd(b, 4);
    }

    /** Dialogue d'ouverture : « ODIN » puis « LOKE ». */
    public void handshake() throws IOException {
        byte[] h = "ODIN".getBytes(StandardCharsets.US_ASCII);
        ch.write(h, 0, 4);
        byte[] b = new byte[7];
        int r = read(b, 7, 3000);
        if (r != 4 || !new String(b, 0, 4, StandardCharsets.US_ASCII).equals("LOKE"))
            throw new IOException("Ce n'est pas un mode Téléchargement Samsung");
    }

    public void beginSession() throws IOException {
        control(0x64, 0);
        long deviceDefault = response(0x64);
        if (deviceDefault != 0) {
            control(0x64, 5, 1048576);
            if (response(0x64) != 0) throw new IOException("La tablette refuse la taille de transfert");
        }
    }

    public byte[] readPit() throws IOException {
        control(0x65, 1);
        long size = response(0x65);
        if (size <= 0 || size > 1024 * 1024) throw new IOException("Taille de la table des partitions invalide");
        byte[] pit = new byte[(int) size];
        int parts = (int) ((size + 499) / 500), off = 0;
        byte[] buf = new byte[500];
        for (int i = 0; i < parts; i++) {
            control(0x65, 2, i);
            int r = read(buf, 500, 5000);
            int n = (int) Math.min(r, size - off);
            System.arraycopy(buf, 0, pit, off, n);
            off += n;
        }
        control(0x65, 3);
        response(0x65);
        return pit;
    }

    public static List<Partition> parsePit(byte[] d) throws IOException {
        if (d.length < 28 || rdStatic(d, 0) != 0x12349876L) throw new IOException("Table des partitions illisible");
        int count = (int) rdStatic(d, 4);
        if (count < 0 || 28 + (long) count * 132 > d.length) throw new IOException("Table des partitions tronquée");
        List<Partition> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int o = 28 + i * 132;
            Partition p = new Partition();
            p.identifier = rdStatic(d, o + 8);
            p.blockSize = rdStatic(d, o + 20);
            p.blockCount = rdStatic(d, o + 24);
            p.name = cstr(d, o + 36);
            p.flashFile = cstr(d, o + 68);
            out.add(p);
        }
        return out;
    }

    private static long rdStatic(byte[] b, int off) {
        return (b[off] & 0xffL) | (b[off + 1] & 0xffL) << 8 | (b[off + 2] & 0xffL) << 16 | (b[off + 3] & 0xffL) << 24;
    }

    private static String cstr(byte[] d, int off) {
        int e = off;
        while (e < off + 32 && d[e] != 0) e++;
        return new String(d, off, e - off, StandardCharsets.US_ASCII);
    }

    /** Termine la session ; avec reboot=true la tablette quitte le mode Téléchargement et redémarre. */
    public void end(boolean reboot) throws IOException {
        control(0x67, 0);
        response(0x67);
        if (reboot) {
            control(0x67, 1);
            response(0x67);
        }
    }
}
