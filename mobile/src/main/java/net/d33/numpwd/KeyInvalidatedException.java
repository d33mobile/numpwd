package net.d33.numpwd;

import java.security.GeneralSecurityException;

/**
 * The key can no longer be used because the fingerprints or the screen lock changed. What it
 * encrypted is lost for good.
 */
public final class KeyInvalidatedException extends GeneralSecurityException {
    private static final long serialVersionUID = 1L;

    public KeyInvalidatedException(Throwable cause) {
        super(cause);
    }
}
