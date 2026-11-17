package net.d33.numpwd;

import android.content.Context;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricManager.Authenticators;

/** Which authentications unlock remembered passwords; must match {@link KeystorePasswordKey}. */
final class Biometrics {
    /**
     * Fingerprint (or another strong biometric) only. Allowing the screen lock as well would
     * stop a new enrolment from invalidating the key, so anyone who learns the PIN could add a
     * finger, or use the PIN directly, and read every remembered password.
     */
    static final int AUTHENTICATORS = Authenticators.BIOMETRIC_STRONG;

    private Biometrics() {
    }

    static boolean available(Context context) {
        return BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }
}
