package net.d33.numpwd;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.GeneralSecurityException;
import java.security.ProviderException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;

/**
 * Passwords the user chose to remember, encrypted per key name with one {@link PasswordKey}.
 *
 * <p>The key only works after the user authenticates for that single operation, so the
 * ciphers returned by {@link #cipherForSaving()} and {@link #cipherForReading(String)} must go
 * through the biometric prompt before {@link #save} or {@link #read} can use them.
 */
public final class RememberedPasswords {
    // Not in the backup rules: the ciphertext is useless without this device's Keystore key.
    private static final String PREFS = "remembered_passwords";
    private static final char IV_SEPARATOR = ':';

    /** Key name to stored value. */
    interface Store {
        String get(String name);

        void put(String name, String value);

        void remove(String name);

        Set<String> names();

        void clear();
    }

    private final Store store;
    private final PasswordKey key;

    RememberedPasswords(Store store, PasswordKey key) {
        this.store = store;
        this.key = key;
    }

    public static RememberedPasswords open(Context context) {
        return new RememberedPasswords(new PrefsStore(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), new KeystorePasswordKey());
    }

    public boolean has(String name) {
        return store.get(name) != null;
    }

    public Set<String> names() {
        return store.names();
    }

    /** An invalidated key cannot decrypt anything any more, so it is replaced along with what it encrypted. */
    public Cipher cipherForSaving() throws GeneralSecurityException {
        try {
            try {
                return key.encryptCipher();
            } catch (KeyInvalidatedException e) {
                forgetAll();
                return key.encryptCipher();
            }
        } catch (ProviderException e) {
            throw checked(e);
        }
    }

    /** Encrypts and stores {@code passwordUtf8}, then overwrites the array with zeros. */
    public void save(String name, Cipher authenticated, byte[] passwordUtf8) throws GeneralSecurityException {
        try {
            byte[] ciphertext = authenticated.doFinal(passwordUtf8);
            store.put(name, Hex.encode(authenticated.getIV()) + IV_SEPARATOR + Hex.encode(ciphertext));
        } catch (ProviderException e) {
            throw checked(e);
        } finally {
            Arrays.fill(passwordUtf8, (byte) 0);
        }
    }

    /**
     * @throws KeyInvalidatedException after forgetting every remembered password, because none
     *                                 of them can be decrypted any more
     * @throws IllegalStateException   if no password is remembered for {@code name}
     */
    public Cipher cipherForReading(String name) throws GeneralSecurityException {
        try {
            return key.decryptCipher(parse(name)[0]);
        } catch (KeyInvalidatedException e) {
            forgetAll();
            throw e;
        } catch (ProviderException e) {
            throw checked(e);
        }
    }

    /** The remembered password as UTF-8; the caller overwrites it once used. */
    public byte[] read(String name, Cipher authenticated) throws GeneralSecurityException {
        try {
            return authenticated.doFinal(parse(name)[1]);
        } catch (ProviderException e) {
            throw checked(e);
        }
    }

    public void forget(String name) {
        store.remove(name);
    }

    public void rename(String oldName, String newName) {
        String value = store.get(oldName);
        store.remove(oldName);
        if (value != null)
            store.put(newName, value);
        else
            store.remove(newName);
    }

    public void forgetAll() throws GeneralSecurityException {
        store.clear();
        key.delete();
    }

    /** The Keystore reports some failures, e.g. of the secure hardware, as unchecked exceptions. */
    private static GeneralSecurityException checked(ProviderException e) {
        return new GeneralSecurityException(e);
    }

    /** The stored IV and ciphertext. */
    private byte[][] parse(String name) throws GeneralSecurityException {
        String value = store.get(name);
        if (value == null)
            throw new IllegalStateException("no remembered password for " + name);
        int separator = value.indexOf(IV_SEPARATOR);
        if (separator < 0)
            throw new GeneralSecurityException("corrupt remembered password");
        try {
            return new byte[][]{Hex.decode(value.substring(0, separator)), Hex.decode(value.substring(separator + 1))};
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("corrupt remembered password", e);
        }
    }

    private static final class PrefsStore implements Store {
        private final SharedPreferences prefs;

        PrefsStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public String get(String name) {
            return prefs.getString(name, null);
        }

        @Override
        public void put(String name, String value) {
            prefs.edit().putString(name, value).apply();
        }

        @Override
        public void remove(String name) {
            prefs.edit().remove(name).apply();
        }

        @Override
        public Set<String> names() {
            return new HashSet<>(prefs.getAll().keySet());
        }

        @Override
        public void clear() {
            prefs.edit().clear().apply();
        }
    }
}
