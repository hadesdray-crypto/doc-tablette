package fr.doctablette.adb;

import java.io.Closeable;
import java.io.IOException;

/** Canal d'octets brut vers la tablette (USB ou réseau). */
public interface AdbChannel extends Closeable {
    void write(byte[] b, int off, int len) throws IOException;

    /**
     * Lit au plus len octets. Renvoie le nombre d'octets lus, 0 si le délai est écoulé sans donnée,
     * -1 si le canal est fermé ou en erreur.
     */
    int read(byte[] b, int off, int len, int timeoutMs) throws IOException;

    /** Plus grosse taille acceptée par un seul write (USB : 16384). */
    int maxChunk();
}
