package net.d33.numpwd;

/** Lower-case hex, for storing binary values in SharedPreferences (java.util.Base64 needs API 26). */
final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    static String encode(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = DIGITS[(bytes[i] >> 4) & 0xf];
            out[2 * i + 1] = DIGITS[bytes[i] & 0xf];
        }
        return new String(out);
    }

    /** @throws IllegalArgumentException if {@code s} is not an even-length hex string */
    static byte[] decode(String s) {
        if (s.length() % 2 != 0)
            throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0)
                throw new IllegalArgumentException("not a hex digit");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
