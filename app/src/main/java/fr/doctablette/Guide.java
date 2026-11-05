package fr.doctablette;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Le guide intégré : fiches lues dans assets/guide.json, hors connexion, avec recherche tolérante. */
final class Guide {
    static final class Card {
        String id, cat, title, summary, warn, android;
        boolean unconventional, verified, dataLoss;
        String risk;
        List<String> brands = new ArrayList<>(), steps = new ArrayList<>(), sources = new ArrayList<>(), needs = new ArrayList<>();
        String hay; // texte de recherche normalisé
    }

    private static List<Card> cache;

    static synchronized List<Card> all(Context ctx) {
        if (cache != null) return cache;
        List<Card> out = new ArrayList<>();
        try (InputStream in = ctx.getAssets().open("guide.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            JSONArray a = new JSONArray(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                try {
                    JSONObject o = a.getJSONObject(i);
                    Card c = new Card();
                    c.id = o.optString("id");
                    c.cat = o.optString("c", "Divers");
                    c.title = o.optString("t");
                    c.summary = o.optString("r");
                    c.warn = o.optString("w");
                    c.android = o.optString("a", "tous");
                    c.unconventional = o.optInt("n") == 1;
                    c.verified = o.optInt("v") == 1;
                    c.dataLoss = o.optInt("p") == 1;
                    c.risk = o.optString("k", "faible");
                    fill(c.brands, o.optJSONArray("m"));
                    fill(c.steps, o.optJSONArray("e"));
                    fill(c.sources, o.optJSONArray("s"));
                    fill(c.needs, o.optJSONArray("nec"));
                    c.hay = norm(c.title + " " + c.summary + " " + c.cat + " " + String.join(" ", c.brands) + " " + String.join(" ", c.steps));
                    if (!c.steps.isEmpty()) out.add(c);
                } catch (Exception ignored) {
                    // une fiche abîmée ne bloque pas les autres
                }
            }
        } catch (Exception ignored) {
        }
        cache = out;
        return out;
    }

    private static void fill(List<String> l, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
    }

    static String norm(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    // Mots que les gens disent  ->  mots des fiches
    private static final String[][] SYN = {
            {"rame", "lente lent lag"}, {"lag", "lente lent"}, {"lent", "lente"}, {"plante", "bloque gele"}, {"gele", "bloque"},
            {"noir", "ecran allume"}, {"casse", "ecran"}, {"fissure", "ecran"}, {"tactile", "ecran"},
            {"charge", "batterie chargeur"}, {"chargeur", "charge batterie"}, {"batterie", "charge"},
            {"demarre", "allume"}, {"allume", "demarre"}, {"boucle", "bootloop demarrage logo"}, {"logo", "demarrage boucle"},
            {"photos", "videos donnees"}, {"photo", "photos videos"}, {"video", "videos photos"}, {"recuperer", "sauvegarde donnees"},
            {"mot de passe", "code verrouillage"}, {"code", "verrouillage"}, {"verrouillee", "verrouillage frp compte"},
            {"reset", "reinitialisation usine"}, {"reinitialiser", "reinitialisation usine"}, {"formater", "reinitialisation usine"},
            {"cable", "usb otg"}, {"otg", "usb cable"}, {"adaptateur", "otg cable"}
    };

    static List<Card> search(Context ctx, String query, boolean unconvOnly) {
        List<Card> all = all(ctx);
        String q = norm(query);
        if (q.isEmpty()) return filter(all, unconvOnly);
        List<String> terms = new ArrayList<>();
        for (String t : q.split(" ")) {
            if (t.length() < 2) continue;
            terms.add(t);
            for (String[] s : SYN) if (q.contains(s[0])) for (String x : s[1].split(" ")) if (!terms.contains(x)) terms.add(x);
        }
        List<Object[]> scored = new ArrayList<>();
        for (Card c : all) {
            if (unconvOnly && !c.unconventional) continue;
            int score = 0;
            String title = norm(c.title);
            for (int i = 0; i < terms.size(); i++) {
                String t = terms.get(i);
                boolean orig = q.contains(t);
                int w = orig ? 3 : 1;
                if (title.contains(t)) score += 5 * w;
                if (c.hay.contains(t)) score += w;
                if (t.length() > 4 && fuzzy(c.hay, t)) score += 1; // fautes de frappe
            }
            if (score > 0) scored.add(new Object[]{score, c});
        }
        Collections.sort(scored, (a, b) -> (Integer) b[0] - (Integer) a[0]);
        List<Card> out = new ArrayList<>();
        for (Object[] o : scored) out.add((Card) o[1]);
        return out;
    }

    private static boolean fuzzy(String hay, String t) {
        // accepte une lettre en moins à la fin ou une lettre changée dans un mot proche
        String root = t.substring(0, t.length() - 1);
        return hay.contains(root);
    }

    private static List<Card> filter(List<Card> all, boolean unconvOnly) {
        if (!unconvOnly) return all;
        List<Card> o = new ArrayList<>();
        for (Card c : all) if (c.unconventional) o.add(c);
        return o;
    }

    static Map<String, List<Card>> byCategory(Context ctx, boolean unconvOnly) {
        Map<String, List<Card>> m = new LinkedHashMap<>();
        for (Card c : all(ctx)) {
            if (unconvOnly && !c.unconventional) continue;
            if (!unconvOnly && c.unconventional && false) continue;
            List<Card> l = m.get(c.cat);
            if (l == null) m.put(c.cat, l = new ArrayList<>());
            l.add(c);
        }
        return m;
    }
}
