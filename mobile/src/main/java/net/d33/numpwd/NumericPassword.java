package net.d33.numpwd;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reproduces {@code openssl passwd -6 -salt KEYNAME | tr -dc '[0-9]'} with the password
 * typed at openssl's interactive prompt.
 */
public final class NumericPassword {
    // openssl passwd truncates longer passwords (with a warning) to this many bytes.
    static final int PASSWORD_MAX_BYTES = 256;

    // The interactive prompt buffer holds PASSWORD_MAX_BYTES + 2; longer input makes
    // openssl exit with an error instead of truncating.
    static final int PROMPT_MAX_BYTES = PASSWORD_MAX_BYTES + 2;

    private NumericPassword() {
    }

    public static String derive(String keyName, String password) throws OpensslShaCrypt.InvalidInputException {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        if (pw.length > PROMPT_MAX_BYTES)
            throw new OpensslShaCrypt.InvalidInputException(
                    "password longer than " + PROMPT_MAX_BYTES + " bytes");
        if (pw.length > PASSWORD_MAX_BYTES)
            pw = Arrays.copyOf(pw, PASSWORD_MAX_BYTES);
        return trDigits(OpensslShaCrypt.crypt(pw, keyName.getBytes(StandardCharsets.UTF_8)));
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
