package fr.doctablette.logic;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Code d'activation hors connexion : DT-XXXX-XXXX-XXXX-XXXX.
 * 8 caractères d'identifiant + 8 caractères de contrôle (HMAC-SHA256 tronqué à 40 bits).
 * Le même calcul est dans tools/make_licence.py pour l'appli de vente.
 */
public final class LicenseCodec {
    static final String ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private LicenseCodec() {
    }

    public static String normalize(String code) {
        String s = code == null ? "" : code.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (s.startsWith("DT")) s = s.substring(2);
        return s;
    }

    static String mac(String secret, String id8) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = m.doFinal(("DT1:" + id8).getBytes(StandardCharsets.UTF_8));
        long v = 0;
        for (int i = 0; i < 5; i++) v = (v << 8) | (h[i] & 0xff);
        StringBuilder sb = new StringBuilder();
        for (int i = 7; i >= 0; i--) sb.append(ALPHA.charAt((int) ((v >> (5 * i)) & 31)));
        return sb.toString();
    }

    public static String make(String secret, String id8) throws Exception {
        String id = id8.toUpperCase(java.util.Locale.ROOT);
        String c = mac(secret, id);
        return "DT-" + id.substring(0, 4) + "-" + id.substring(4) + "-" + c.substring(0, 4) + "-" + c.substring(4);
    }

    public static boolean verify(String secret, String code) {
        try {
            String s = normalize(code);
            if (s.length() != 16) return false;
            String id = s.substring(0, 8);
            for (char ch : id.toCharArray()) if (ALPHA.indexOf(ch) < 0) return false;
            String expected = mac(secret, id);
            int diff = 0;
            String given = s.substring(8);
            for (int i = 0; i < 8; i++) diff |= expected.charAt(i) ^ given.charAt(i);
            return diff == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
