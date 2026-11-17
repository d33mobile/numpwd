package net.d33.numpwd;

import java.security.GeneralSecurityException;

/** The stored value of a remembered password is damaged; it has been forgotten. */
public final class CorruptEntryException extends GeneralSecurityException {
    private static final long serialVersionUID = 1L;

    public CorruptEntryException(String message, Throwable cause) {
        super(message, cause);
    }
}
