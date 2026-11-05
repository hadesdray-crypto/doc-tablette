package fr.doctablette.adb;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Clé RSA du téléphone et signature du « token » ADB. */
public final class AdbCrypto {
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final PrivateKey priv;
    private final RSAPublicKey pub;

    public AdbCrypto(PrivateKey priv, RSAPublicKey pub) {
        this.priv = priv;
        this.pub = pub;
    }

    public static AdbCrypto generate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        return new AdbCrypto(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    /** Charge la clé stockée, ou en crée une (et la garde pour que « Toujours autoriser » reste valable). */
    public static AdbCrypto loadOrCreate(File dir) throws Exception {
        File f = new File(dir, "adbkey.txt");
        if (f.exists()) {
            try {
                String[] p = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim().split("\n");
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey pr = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(p[0].trim())));
                RSAPublicKey pu = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(p[1].trim())));
                return new AdbCrypto(pr, pu);
            } catch (Exception ignored) {
                // clé illisible : on en refait une
            }
        }
        AdbCrypto c = generate();
        String txt = Base64.getEncoder().encodeToString(c.priv.getEncoded()) + "\n"
                + Base64.getEncoder().encodeToString(c.pub.getEncoded()) + "\n";
        dir.mkdirs();
        Files.write(f.toPath(), txt.getBytes(StandardCharsets.US_ASCII));
        return c;
    }

    /** Signe le token de 20 octets envoyé par la tablette. */
    public byte[] sign(byte[] token) throws Exception {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(priv);
        byte[] data = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, data, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, data, SHA1_PREFIX.length, token.length);
        s.update(data);
        return s.sign();
    }

    /** Clé publique au format ADB (base64 + nom + octet nul). */
    public byte[] publicKeyForAuth(String name) {
        byte[] raw = encodeAdbKey(pub);
        String b64 = Base64.getEncoder().encodeToString(raw);
        byte[] txt = (b64 + " " + name).getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[txt.length + 1];
        System.arraycopy(txt, 0, out, 0, txt.length);
        return out;
    }

    static byte[] encodeAdbKey(RSAPublicKey k) {
        final int words = 64; // 2048 bits
        BigInteger n = k.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);
        byte[] out = new byte[4 + 4 + 256 + 256 + 4];
        putLe(out, 0, BigInteger.valueOf(words), 4);
        putLe(out, 4, n0inv, 4);
        putLe(out, 8, n, 256);
        putLe(out, 8 + 256, rr, 256);
        putLe(out, 8 + 512, k.getPublicExponent(), 4);
        return out;
    }

    private static void putLe(byte[] dst, int off, BigInteger v, int len) {
        byte[] be = v.toByteArray();
        for (int i = 0; i < len; i++) {
            int idx = be.length - 1 - i;
            dst[off + i] = idx >= 0 ? be[idx] : 0;
        }
    }

    // Pour les tests
    RSAPublicKey publicKey() {
        return pub;
    }

    static void throwIf(boolean c, String m) throws IOException {
        if (c) throw new IOException(m);
    }
}
