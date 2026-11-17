package net.d33.numpwd;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reproduces {@code openssl passwd -6 -salt KEYNAME | tr -dc '[0-9]'} with the password
 * typed at openssl's interactive prompt, and its first six characters
 * ({@code ... | head -c6}).
 */
public final class NumericPassword {
    // openssl passwd truncates longer passwords (with a warning) to this many bytes.
    static final int PASSWORD_MAX_BYTES = 256;

    // The interactive prompt buffer holds PASSWORD_MAX_BYTES + 2; longer input makes
    // openssl exit with an error instead of truncating.
    static final int PROMPT_MAX_BYTES = PASSWORD_MAX_BYTES + 2;

    static final int CODE_LENGTH = 6;

    private NumericPassword() {
    }

    public static String derive(String keyName, String password)
            throws OpensslShaCrypt.InvalidInputException, InterruptedException {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        if (pw.length > PROMPT_MAX_BYTES)
            throw new OpensslShaCrypt.InvalidInputException(
                    "password longer than " + PROMPT_MAX_BYTES + " bytes");
        if (pw.length > PASSWORD_MAX_BYTES)
            pw = Arrays.copyOf(pw, PASSWORD_MAX_BYTES);
        return trDigits(OpensslShaCrypt.crypt(pw, keyName.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * {@code head -c6} over the output of {@link #derive}: the first six characters, '[' and ']'
     * included, or the whole output when it is shorter (about 0.15% of passwords for a key name
     * without digits, e.g. key "test" with password "pw2002" gives "62960").
     */
    public static String sixDigits(String digits) {
        return digits.substring(0, Math.min(CODE_LENGTH, digits.length()));
    }

    /**
     * {@code tr -dc '[0-9]'}: the brackets are literal set members for tr, so '[' and ']'
     * coming from the salt survive along with the digits.
     */
    static String trDigits(byte[] s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s) {
            if ((b >= '0' && b <= '9') || b == '[' || b == ']')
                sb.append((char) b);
        }
        return sb.toString();
    }
}
