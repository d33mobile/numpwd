package net.d33.numpwd;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.ProviderException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;

/**
 * Passwords the user chose to remember, encrypted per key name with one {@link PasswordKey}.
 *
 * <p>An entry is "02:" + hex(IV) + ":" + hex(ciphertext). The plaintext is the password's
 * length in two bytes, the password and zeros up to a fixed size, so the stored value does not
 * reveal the password's length. The version byte and the key name are GCM associated data, so
 * an entry only decrypts under the name it was saved for. Version 1.5 and older stored
 * hex(IV) + ":" + hex(ciphertext) of the bare password without associated data; such entries
 * are still read.
 *
 * <p>The key only works after the user authenticates for that single operation, so the
 * ciphers returned by {@link #cipherForSaving()} and {@link #cipherForReading(String)} must go
 * through the biometric prompt before {@link #save} or {@link #read} can use them.
 */
public final class RememberedPasswords {
    // Not in the backup rules: the ciphertext is useless without this device's Keystore key.
    static final String PREFS = "remembered_passwords";
    private static final char IV_SEPARATOR = ':';
    // The Keystore generates 96 bit GCM IVs.
    private static final int IV_BYTES = 12;
    private static final int LEGACY_VERSION = 1;
    private static final byte VERSION = 2;
    private static final String VERSION_PREFIX = "02" + IV_SEPARATOR;
    private static final int LENGTH_BYTES = 2;
    // Every password the app can derive a code from fits.
    static final int PADDED_BYTES = LENGTH_BYTES + NumericPassword.PROMPT_MAX_BYTES;

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
        byte[] padded = new byte[PADDED_BYTES];
        try {
            if (passwordUtf8.length > NumericPassword.PROMPT_MAX_BYTES)
                throw new GeneralSecurityException(
                        "password longer than " + NumericPassword.PROMPT_MAX_BYTES + " bytes");
            padded[0] = (byte) (passwordUtf8.length >> 8);
            padded[1] = (byte) passwordUtf8.length;
            System.arraycopy(passwordUtf8, 0, padded, LENGTH_BYTES, passwordUtf8.length);
            authenticated.updateAAD(associatedData(name));
            byte[] ciphertext = authenticated.doFinal(padded);
            store.put(name, VERSION_PREFIX + Hex.encode(authenticated.getIV()) + IV_SEPARATOR
                    + Hex.encode(ciphertext));
        } catch (ProviderException e) {
            throw checked(e);
        } finally {
            Arrays.fill(passwordUtf8, (byte) 0);
            Arrays.fill(padded, (byte) 0);
        }
    }

    /**
     * Any other {@link GeneralSecurityException} leaves the entry in place: it may work next time.
     *
     * @throws KeyInvalidatedException after forgetting every remembered password, because none
     *                                 of them can be decrypted any more
     * @throws CorruptEntryException   after forgetting the damaged entry for {@code name}
     * @throws IllegalStateException   if no password is remembered for {@code name}
     */
    public Cipher cipherForReading(String name) throws GeneralSecurityException {
        try {
            return key.decryptCipher(parse(name).iv);
        } catch (KeyInvalidatedException e) {
            forgetAll();
            throw e;
        } catch (ProviderException e) {
            throw checked(e);
        }
    }

    /**
     * The remembered password as UTF-8; the caller overwrites it once used.
     *
     * @throws CorruptEntryException after forgetting the entry, if it fails authentication
     */
    public byte[] read(String name, Cipher authenticated) throws GeneralSecurityException {
        Entry entry = parse(name);
        byte[] plaintext;
        try {
            if (entry.version == VERSION)
                authenticated.updateAAD(associatedData(name));
            plaintext = authenticated.doFinal(entry.ciphertext);
        } catch (AEADBadTagException e) {
            // The user authenticated, so the key works: the stored bytes are wrong or belong
            // to another key name.
            throw corrupt(name, e);
        } catch (ProviderException e) {
            throw checked(e);
        }
        if (entry.version == LEGACY_VERSION)
            return plaintext;
        try {
            int length = plaintext.length == PADDED_BYTES
                    ? ((plaintext[0] & 0xff) << 8) | (plaintext[1] & 0xff) : -1;
            if (length < 0 || length > NumericPassword.PROMPT_MAX_BYTES)
                throw corrupt(name, null);
            return Arrays.copyOfRange(plaintext, LENGTH_BYTES, LENGTH_BYTES + length);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public void forget(String name) {
        store.remove(name);
    }

    /**
     * The password is bound to the name it was saved for, and moving it would take another
     * fingerprint, so the renamed key asks for it again.
     */
    public void rename(String oldName, String newName) {
        store.remove(oldName);
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

    private CorruptEntryException corrupt(String name, Throwable cause) {
        forget(name);
        return new CorruptEntryException("corrupt remembered password", cause);
    }

    private static byte[] associatedData(String name) {
        byte[] nameUtf8 = name.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[1 + nameUtf8.length];
        data[0] = VERSION;
        System.arraycopy(nameUtf8, 0, data, 1, nameUtf8.length);
        return data;
    }

    private static final class Entry {
        final int version;
        final byte[] iv;
        final byte[] ciphertext;

        Entry(int version, byte[] iv, byte[] ciphertext) {
            this.version = version;
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }

    private Entry parse(String name) throws CorruptEntryException {
        String value = store.get(name);
        if (value == null)
            throw new IllegalStateException("no remembered password for " + name);
        int version = LEGACY_VERSION;
        if (value.startsWith(VERSION_PREFIX)) {
            version = VERSION;
            value = value.substring(VERSION_PREFIX.length());
        }
        int separator = value.indexOf(IV_SEPARATOR);
        if (separator < 0)
            throw corrupt(name, null);
        byte[] iv;
        byte[] ciphertext;
        try {
            iv = Hex.decode(value.substring(0, separator));
            ciphertext = Hex.decode(value.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            throw corrupt(name, e);
        }
        if (iv.length != IV_BYTES)
            throw corrupt(name, null);
        return new Entry(version, iv, ciphertext);
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
