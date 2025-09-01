import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Hash + salt + verifikacija lozinke + generisanje tokena */
public class PasswordUtil {

    private static final SecureRandom RNG = new SecureRandom();

    /** Vrati [saltHex, hashHex] za zadatu lozinku */
    public static String[] makeHash(String password) {
        String salt = randomHex(16); // 16 bajtova = 32 hex char
        String hash = hashWithSalt(password, salt);
        return new String[] { salt, hash };
    }

    /** Provera lozinke: da li sha256(salt + password) == hashHex */
    public static boolean verify(String password, String saltHex, String hashHex) {
        String calc = hashWithSalt(password, saltHex);
        return constantTimeEquals(calc, hashHex);
    }

    /** Random token dužine ~2*n hex karaktera (n bajtova) */
    public static String randomToken(int nBytes) {
        return randomHex(nBytes);
    }

    // ----------------- helpers -----------------

    private static String hashWithSalt(String password, String saltHex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] salt = hexToBytes(saltHex);
            md.update(salt);
            byte[] h = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return toHex(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String randomHex(int nBytes) {
        byte[] b = new byte[nBytes];
        RNG.nextBytes(b);
        return toHex(b);
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte x : data)
            sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    /** Bezbedno poređenje stringova (stalno vreme) */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null)
            return false;
        if (a.length() != b.length())
            return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++)
            r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
