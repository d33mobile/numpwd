package net.d33.numpwd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.Build;
import android.security.keystore.KeyInfo;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;

/**
 * Properties of the Keystore key on any API level with an enrolled fingerprint. Unlike
 * {@link RememberPasswordTest} it needs no emulator console, so it also runs where the console
 * cannot be reached (API < 31) once a finger was enrolled by hand.
 */
@RunWith(AndroidJUnit4.class)
public class KeystoreKeyTest {
    // KeyInfo reports per-operation authentication as 0 since API 30 and as -1 before.
    private static final int PER_OPERATION_BEFORE_R = -1;

    @Before
    public void setUp() throws Exception {
        assumeTrue("needs an enrolled fingerprint", EmulatorBiometrics.canRemember());
        RememberedPasswords.open(ApplicationProvider.getApplicationContext()).forgetAll();
    }

    @After
    public void tearDown() throws Exception {
        RememberedPasswords.open(ApplicationProvider.getApplicationContext()).forgetAll();
    }

    @Test
    public void keyNeedsAFingerprintForEveryUse() throws Exception {
        Cipher encrypt = new KeystorePasswordKey().encryptCipher();

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey key = (SecretKey) keyStore.getKey(KeystorePasswordKey.ALIAS, null);
        KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore")
                .getKeySpec(key, KeyInfo.class);
        assertTrue(info.isUserAuthenticationRequired());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            assertTrue(info.isInvalidatedByBiometricEnrollment());
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? 0 : PER_OPERATION_BEFORE_R,
                info.getUserAuthenticationValidityDurationSeconds());
        assertEquals(256, info.getKeySize());

        // Without the prompt the key refuses to encrypt anything.
        assertThrows(Exception.class, () -> encrypt.doFinal("x".getBytes(StandardCharsets.UTF_8)));
    }
}
