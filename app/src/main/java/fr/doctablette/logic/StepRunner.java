package fr.doctablette.logic;

import java.io.IOException;

/** Exécute une réparation avec ses méthodes de secours. */
public final class StepRunner {
    public interface Shell {
        String run(String command, long timeoutMs) throws IOException;
    }

    public static final class Result {
        public final boolean ok;
        public final String detail;

        Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    private StepRunner() {
    }

    public static Result run(Shell sh, Repairs.Step step) {
        String last = "";
        for (int i = 0; i < step.methods.length; i++) {
            try {
                String out = sh.run(step.methods[i], step.timeoutMs).trim();
                if (!Repairs.failed(out)) return new Result(true, out);
                last = out;
            } catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                // Après « reboot », la tablette coupe la connexion : c'est normal.
                if (step.id.equals("reboot") && (m.contains("coupée") || m.contains("Pas de connexion"))) {
                    return new Result(true, "");
                }
                if (m.contains("Arrêté à votre demande")) return new Result(false, "arrêté");
                if (m.contains("coupée") || m.contains("débranchée") || m.contains("Pas de connexion")) {
                    return new Result(false, "La tablette a été débranchée");
                }
                last = m;
            }
        }
        if (last.length() > 160) last = last.substring(0, 160);
        return new Result(false, last.isEmpty() ? "la tablette a refusé" : last);
    }
}
