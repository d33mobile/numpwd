package net.d33.numpwd;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.ProviderException;
import java.security.UnrecoverableKeyException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM key in the Android Keystore that can only be used right after the user
 * authenticates with a strong biometric. Enrolling a new fingerprint or removing the screen
 * lock invalidates it.
 */
final class KeystorePasswordKey implements PasswordKey {
    static final String ALIAS = "remembered_passwords";
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int TAG_BITS = 128;

    @Override
    public Cipher encryptCipher() throws GeneralSecurityException {
        SecretKey key = existingKey();
        if (key == null)
            key = generate();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key);
        } catch (KeyPermanentlyInvalidatedException e) {
            throw new KeyInvalidatedException(e);
        }
        return cipher;
    }

    @Override
    public Cipher decryptCipher(byte[] iv) throws GeneralSecurityException {
        SecretKey key = existingKey();
        // The system deletes the key in some cases (e.g. lock screen removal on older versions).
        if (key == null)
            throw new KeyInvalidatedException(new GeneralSecurityException("key is gone"));
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        } catch (KeyPermanentlyInvalidatedException e) {
            throw new KeyInvalidatedException(e);
        }
        return cipher;
    }

    @Override
    public void delete() throws GeneralSecurityException {
        keyStore().deleteEntry(ALIAS);
    }

    private static KeyStore keyStore() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(PROVIDER);
        try {
            keyStore.load(null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        return keyStore;
    }

    private static SecretKey existingKey() throws GeneralSecurityException {
        try {
            return (SecretKey) keyStore().getKey(ALIAS, null);
        } catch (UnrecoverableKeyException e) {
            // Some Android versions report an invalidated key here instead of when it is used.
            throw new KeyInvalidatedException(e);
        }
    }

    private static SecretKey generate() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(spec(true));
                return generator.generateKey();
            } catch (ProviderException e) {
                // StrongBoxUnavailableException on the many devices without a StrongBox chip; some
                // chips refuse with a plain ProviderException. The TEE-backed key below is the usual case.
            }
        }
        generator.init(spec(false));
        return generator.generateKey();
    }

    // setUserAuthenticationValidityDurationSeconds is the only per-use variant before API 30.
    @SuppressWarnings("deprecation")
    private static KeyGenParameterSpec spec(boolean strongBox) {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Timeout 0: every encryption or decryption needs its own authentication.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        } else {
            // -1 (the default): every use needs a biometric authentication bound to the cipher.
            builder.setUserAuthenticationValidityDurationSeconds(-1);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            builder.setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true);
            builder.setIsStrongBoxBacked(strongBox);
        }
        return builder.build();
    }
}
