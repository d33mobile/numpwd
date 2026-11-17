package net.d33.numpwd;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * SHA-512 crypt ("$6$") with the salt handling of OpenSSL's {@code apps/passwd.c},
 * which is looser than glibc or commons-codec: the salt may contain '$', is taken as
 * raw bytes, is cut to 16 bytes (possibly inside a multi-byte character), and an
 * optional "rounds=N$" prefix is parsed with C strtoul() semantics.
 */
public final class OpensslShaCrypt {
    private static final byte[] ROUNDS_PREFIX = ascii("rounds=");
    private static final int ROUNDS_DEFAULT = 5000;
    private static final long ROUNDS_MIN = 1000;
    private static final long ROUNDS_MAX = 999_999_999;
    private static final int SALT_MAX_BYTES = 16;
    private static final int DIGEST_BYTES = 64;
    private static final String ITOA64 =
            "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private OpensslShaCrypt() {
    }

    /** Thrown where {@code openssl passwd} prints "&lt;NULL&gt;" or fails. */
    public static final class InvalidInputException extends Exception {
        public InvalidInputException(String message) {
            super(message);
        }
    }

    /** Returns the full "$6$..." string that {@code openssl passwd -6 -salt SALT PASSWORD} prints. */
    public static byte[] crypt(byte[] password, byte[] salt) throws InvalidInputException {
        if (password.length == 0)
            throw new InvalidInputException("empty password");
        if (indexOf(password, (byte) 0, 0) >= 0 || indexOf(salt, (byte) 0, 0) >= 0)
            throw new InvalidInputException("NUL byte in input");

        int rounds = ROUNDS_DEFAULT;
        boolean roundsCustom = false;
        if (startsWith(salt, ROUNDS_PREFIX)) {
            int[] end = new int[1];
            long parsed = strtoulClamped(salt, ROUNDS_PREFIX.length, end);
            if (end[0] >= salt.length || salt[end[0]] != '$')
                throw new InvalidInputException("malformed rounds= prefix");
            salt = Arrays.copyOfRange(salt, end[0] + 1, salt.length);
            rounds = (int) Math.max(ROUNDS_MIN, Math.min(ROUNDS_MAX, parsed));
            roundsCustom = true;
        }
        if (salt.length > SALT_MAX_BYTES)
            salt = Arrays.copyOf(salt, SALT_MAX_BYTES);
        if (salt.length == 0)
            throw new InvalidInputException("empty salt");

        byte[] hash = shaCrypt512(password, salt, rounds);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "$6$");
        if (roundsCustom)
            writeAscii(out, "rounds=" + rounds + "$");
        out.write(salt, 0, salt.length);
        writeAscii(out, "$");
        writeAscii(out, encodeHash(hash));
        return out.toByteArray();
    }

    private static byte[] shaCrypt512(byte[] p, byte[] s, int rounds) {
        MessageDigest md = sha512();

        md.update(p);
        md.update(s);
        md.update(p);
        byte[] b = md.digest();

        md.update(p);
        md.update(s);
        int n;
        for (n = p.length; n > DIGEST_BYTES; n -= DIGEST_BYTES)
            md.update(b, 0, DIGEST_BYTES);
        md.update(b, 0, n);
        for (n = p.length; n > 0; n >>= 1) {
            if ((n & 1) != 0)
                md.update(b, 0, DIGEST_BYTES);
            else
                md.update(p);
        }
        byte[] a = md.digest();

        for (n = 0; n < p.length; n++)
            md.update(p);
        byte[] pBytes = repeatToLength(md.digest(), p.length);

        for (n = 0; n < 16 + (a[0] & 0xff); n++)
            md.update(s);
        byte[] sBytes = Arrays.copyOf(md.digest(), s.length);

        for (int i = 0; i < rounds; i++) {
            md.update((i & 1) != 0 ? pBytes : a);
            if (i % 3 != 0)
                md.update(sBytes);
            if (i % 7 != 0)
                md.update(pBytes);
            md.update((i & 1) != 0 ? a : pBytes);
            a = md.digest();
        }
        return a;
    }

    private static String encodeHash(byte[] h) {
        StringBuilder sb = new StringBuilder();
        // Bytes i, i+21, i+42 form a 24-bit group, rotated by i % 3 (Drepper's spec).
        for (int i = 0; i < 21; i++) {
            int x = h[i] & 0xff, y = h[i + 21] & 0xff, z = h[i + 42] & 0xff;
            switch (i % 3) {
                case 0: b64From24Bit(sb, x, y, z, 4); break;
                case 1: b64From24Bit(sb, y, z, x, 4); break;
                default: b64From24Bit(sb, z, x, y, 4); break;
            }
        }
        b64From24Bit(sb, 0, 0, h[63] & 0xff, 2);
        return sb.toString();
    }

    private static void b64From24Bit(StringBuilder sb, int b2, int b1, int b0, int chars) {
        int w = (b2 << 16) | (b1 << 8) | b0;
        for (int i = 0; i < chars; i++) {
            sb.append(ITOA64.charAt(w & 0x3f));
            w >>>= 6;
        }
    }

    /**
     * C strtoul(s, &end, 10) on 64-bit Linux, clamped to [0, ROUNDS_MAX + 1] so the
     * caller can clamp further. Overflow saturates to ULONG_MAX and a leading '-'
     * negates modulo 2^64, both of which exceed ROUNDS_MAX unless the value is 0.
     */
    private static long strtoulClamped(byte[] s, int start, int[] end) {
        int i = start;
        while (i < s.length && isCSpace(s[i]))
            i++;
        boolean negative = false;
        if (i < s.length && (s[i] == '+' || s[i] == '-')) {
            negative = s[i] == '-';
            i++;
        }
        int digitsStart = i;
        BigInteger value = BigInteger.ZERO;
        while (i < s.length && s[i] >= '0' && s[i] <= '9') {
            value = value.multiply(BigInteger.TEN).add(BigInteger.valueOf(s[i] - '0'));
            i++;
        }
        if (i == digitsStart) {
            end[0] = start;
            return 0;
        }
        end[0] = i;
        if (value.signum() == 0)
            return 0;
        if (negative || value.compareTo(BigInteger.valueOf(ROUNDS_MAX)) > 0)
            return ROUNDS_MAX + 1;
        return value.longValue();
    }

    private static boolean isCSpace(byte c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    private static byte[] repeatToLength(byte[] src, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++)
            out[i] = src[i % src.length];
        return out;
    }

    private static boolean startsWith(byte[] s, byte[] prefix) {
        return s.length >= prefix.length && Arrays.equals(Arrays.copyOf(s, prefix.length), prefix);
    }

    private static int indexOf(byte[] s, byte b, int from) {
        for (int i = from; i < s.length; i++)
            if (s[i] == b)
                return i;
        return -1;
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = ascii(s);
        out.write(b, 0, b.length);
    }

    private static byte[] ascii(String s) {
        try {
            return s.getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
