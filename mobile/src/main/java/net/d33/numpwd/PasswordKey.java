package net.d33.numpwd;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;

/** The secret key that encrypts remembered passwords; in the app it lives in the Android Keystore. */
interface PasswordKey {
    /**
     * A cipher ready to encrypt, creating the key on first use.
     *
     * @throws KeyInvalidatedException if the system invalidated the key
     */
    Cipher encryptCipher() throws GeneralSecurityException;

    /**
     * A cipher ready to decrypt a value encrypted with {@code iv}.
     *
     * @throws KeyInvalidatedException if the system invalidated or removed the key
     */
    Cipher decryptCipher(byte[] iv) throws GeneralSecurityException;

    void delete() throws GeneralSecurityException;
}
