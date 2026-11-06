package fr.doctablette.adb;

import java.io.IOException;

/**
 * Tests en LECTURE SEULE des modes de chargement de puces. Écrit indépendamment, à partir des seuls
 * faits publics du protocole (valeurs d'octets échangées) : aucun code tiers n'est repris.
 * Aucune méthode ici n'écrit dans la mémoire de la tablette.
 */
public final class ChipProbe {
    private final AdbChannel ch;

    public ChipProbe(AdbChannel ch) {
        this.ch = ch;
    }

    private int readExact(byte[] b, int len, int timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        int got = 0;
        while (got < len) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new IOException("La tablette ne répond pas");
            int r = ch.read(b, got, len - got, (int) Math.min(left, 500));
            if (r < 0) throw new IOException("Connexion coupée");
            got += r;
        }
        return got;
    }

    private void expect(int send, int want) throws IOException {
        ch.write(new byte[]{(byte) send}, 0, 1);
        byte[] r = new byte[1];
        readExact(r, 1, 1500);
        if ((r[0] & 0xff) != want) throw new IOException("Réponse inattendue (" + Integer.toHexString(r[0] & 0xff) + ")");
    }

    /** MediaTek : échange d'ouverture puis lecture du code matériel. Renvoie le code (ex. 0x0766). */
    public int mediatekHwCode() throws IOException {
        expect(0xA0, 0x5F);
        expect(0x0A, 0xF5);
        expect(0x50, 0xAF);
        expect(0x05, 0xFA);
        ch.write(new byte[]{(byte) 0xFD}, 0, 1);
        byte[] r = new byte[5];
        readExact(r, 5, 1500);
        if ((r[0] & 0xff) != 0xFD) throw new IOException("Réponse inattendue");
        return ((r[1] & 0xff) << 8) | (r[2] & 0xff);
    }

    private static byte[] cbw(int tag, int opcode, int sub) {
        byte[] c = new byte[31];
        c[0] = 'U'; c[1] = 'S'; c[2] = 'B'; c[3] = 'C';
        c[4] = (byte) tag; c[5] = (byte) (tag >> 8);
        c[14] = 6; // longueur de la commande
        c[15] = (byte) opcode;
        c[16] = (byte) sub;
        return c;
    }

    private boolean rockCommand(int opcode, int sub, int tag) throws IOException {
        byte[] c = cbw(tag, opcode, sub);
        ch.write(c, 0, c.length);
        byte[] s = new byte[13];
        readExact(s, 13, 3000);
        if (s[0] != 'U' || s[1] != 'S' || s[2] != 'B' || s[3] != 'S') throw new IOException("Réponse inattendue");
        return s[12] == 0;
    }

    /** Rockchip : la puce répond-elle ? (commande « prête ? », sans effet). */
    public boolean rockchipReady() throws IOException {
        return rockCommand(0x00, 0, 1);
    }

    /** Rockchip : redémarre la tablette. N'efface rien. */
    public void rockchipReboot() throws IOException {
        try {
            rockCommand(0xFF, 0, 2);
        } catch (IOException e) {
            // la tablette peut redémarrer avant de répondre : ce n'est pas une erreur
        }
    }
}
