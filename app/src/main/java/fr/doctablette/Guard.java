package fr.doctablette;

import android.content.Context;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Garde-fous : une erreur de programmation ne doit jamais fermer l'appli ni bloquer l'écran. */
final class Guard {
    private Guard() {
    }

    static File crashFile(Context c) {
        return new File(c.getFilesDir(), "dernier-incident.txt");
    }

    static String trace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 3000 ? s.substring(0, 3000) : s;
    }

    static void save(Context c, String where, Throwable t) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(crashFile(c), false);
            w.write(where + "\n" + trace(t));
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** Enregistre l'incident avant un éventuel arrêt, puis laisse Android faire son travail normal. */
    static void install(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            save(app, "Arrêt dans " + th.getName(), ex);
            if (prev != null) prev.uncaughtException(th, ex);
        });
    }

    /** Lit puis efface le dernier incident (null s'il n'y en a pas). */
    static String takeLast(Context c) {
        try {
            File f = crashFile(c);
            if (!f.exists()) return null;
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            f.delete();
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    interface Reporter {
        void failed(String where, Throwable t);
    }

    /** Pool de fils où toute erreur est attrapée : une tâche en échec ne fait jamais tomber l'appli. */
    static ExecutorService pool(final int max, final Reporter rep) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(max, max, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void execute(final Runnable r) {
                super.execute(() -> {
                    try {
                        r.run();
                    } catch (Throwable t) {
                        try {
                            rep.failed("tâche", t);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        };
        p.allowCoreThreadTimeOut(true);
        return p;
    }
}
