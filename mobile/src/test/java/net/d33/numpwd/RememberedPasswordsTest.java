package net.d33.numpwd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Storage of remembered passwords, with a software AES key standing in for the Android
 * Keystore key and a map standing in for SharedPreferences. In the app the ciphers handed to
 * save() and read() are the ones the biometric prompt authenticated.
 */
public class RememberedPasswordsTest {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    /** Like the Keystore key: created on first use, can be invalidated by the system. */
    private static final class FakeKey implements PasswordKey {
        SecretKey key;
        boolean invalidated;
        boolean providerFails;
        int deletions;

        private SecretKey key() throws GeneralSecurityException {
            // The Keystore reports some failures (e.g. of the secure hardware) unchecked.
            if (providerFails)
                throw new ProviderException("keystore operation failed");
            if (invalidated)
                throw new KeyInvalidatedException(new GeneralSecurityException("enrolment changed"));
            if (key == null) {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                key = generator.generateKey();
            }
            return key;
        }

        @Override
        public Cipher encryptCipher() throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            return cipher;
        }

        @Override
        public Cipher decryptCipher(byte[] iv) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return cipher;
        }

        @Override
        public void delete() {
            key = null;
            invalidated = false;
            deletions++;
        }
    }

    private static final class MapStore implements RememberedPasswords.Store {
        final Map<String, String> map = new HashMap<>();

        @Override
        public String get(String name) {
            return map.get(name);
        }

        @Override
        public void put(String name, String value) {
            map.put(name, value);
        }

        @Override
        public void remove(String name) {
            map.remove(name);
        }

        @Override
        public Set<String> names() {
            return new HashSet<>(map.keySet());
        }

        @Override
        public void clear() {
            map.clear();
        }
    }

    private final FakeKey key = new FakeKey();
    private final MapStore store = new MapStore();
    private final RememberedPasswords remembered = new RememberedPasswords(store, key);

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void remember(String name, String password) throws GeneralSecurityException {
        remembered.save(name, remembered.cipherForSaving(), utf8(password));
    }

    private byte[] recall(String name) throws GeneralSecurityException {
        return remembered.read(name, remembered.cipherForReading(name));
    }

    @Test
    public void roundTripReturnsTheSavedPassword() throws Exception {
        remember("test", "abc");
        remember("zażółć", "pässwörd with spaces");

        assertArrayEquals(utf8("abc"), recall("test"));
        assertArrayEquals(utf8("pässwörd with spaces"), recall("zażółć"));
        assertEquals(new HashSet<>(Arrays.asList("test", "zażółć")), remembered.names());
        assertTrue(remembered.has("test"));
        assertFalse(remembered.has("other"));
    }

    @Test
    public void storedValueHoldsNoPlaintext() throws Exception {
        String password = "plaintext-password";
        remember("test", password);

        String stored = store.map.get("test");
        assertNotNull(stored);
        assertFalse(stored.contains(password));
        assertFalse(stored.contains(Hex.encode(utf8(password))));
    }

    @Test
    public void saveWipesThePlaintextArray() throws Exception {
        byte[] password = utf8("abc");
        remembered.save("test", remembered.cipherForSaving(), password);
        assertArrayEquals(new byte[3], password);
    }

    @Test
    public void eachSaveUsesAFreshIv() throws Exception {
        remember("a", "same");
        remember("b", "same");
        assertFalse(store.map.get("a").equals(store.map.get("b")));
    }

    @Test
    public void tamperedCiphertextIsRejected() throws Exception {
        remember("test", "abc");
        String stored = store.map.get("test");
        char last = stored.charAt(stored.length() - 1);
        store.map.put("test", stored.substring(0, stored.length() - 1) + (last == '0' ? '1' : '0'));

        assertThrows(AEADBadTagException.class, () -> recall("test"));
    }

    @Test
    public void forgetRemovesOnlyThatKey() throws Exception {
        remember("a", "1");
        remember("b", "2");
        remembered.forget("a");

        assertEquals(Collections.singleton("b"), remembered.names());
        assertArrayEquals(utf8("2"), recall("b"));
        assertThrows(IllegalStateException.class, () -> remembered.cipherForReading("a"));
    }

    @Test
    public void renameMovesThePassword() throws Exception {
        remember("old", "abc");
        remembered.rename("old", "new");

        assertEquals(Collections.singleton("new"), remembered.names());
        assertArrayEquals(utf8("abc"), recall("new"));
    }

    @Test
    public void renameOfAKeyWithoutPasswordDropsAStaleEntry() throws Exception {
        remember("new", "stale");
        remembered.rename("old", "new");
        assertTrue(remembered.names().isEmpty());
    }

    @Test
    public void forgetAllWipesEntriesAndTheKey() throws Exception {
        remember("a", "1");
        remember("b", "2");
        remembered.forgetAll();

        assertTrue(remembered.names().isEmpty());
        assertTrue(store.map.isEmpty());
        assertEquals(1, key.deletions);
    }

    @Test
    public void invalidatedKeyWipesEverythingWhenReading() throws Exception {
        remember("a", "1");
        remember("b", "2");
        key.invalidated = true;

        assertThrows(KeyInvalidatedException.class, () -> remembered.cipherForReading("a"));
        assertTrue(remembered.names().isEmpty());
        assertTrue(store.map.isEmpty());
        assertEquals(1, key.deletions);

        // A new key is created for the next password the user chooses to remember.
        remember("a", "new");
        assertArrayEquals(utf8("new"), recall("a"));
    }

    @Test
    public void invalidatedKeyIsReplacedWhenSaving() throws Exception {
        remember("a", "1");
        key.invalidated = true;

        remember("b", "2");
        assertEquals(Collections.singleton("b"), remembered.names());
        assertArrayEquals(utf8("2"), recall("b"));
    }

    @Test
    public void corruptEntryIsReportedNotDecrypted() {
        store.map.put("test", "not hex");
        assertThrows(GeneralSecurityException.class, () -> remembered.cipherForReading("test"));
    }

    @Test
    public void uncheckedKeystoreFailureIsReportedAsChecked() throws Exception {
        remember("a", "1");
        key.providerFails = true;

        GeneralSecurityException reading = assertThrows(GeneralSecurityException.class,
                () -> remembered.cipherForReading("a"));
        assertFalse(reading instanceof KeyInvalidatedException);
        assertThrows(GeneralSecurityException.class, remembered::cipherForSaving);
        // Nothing says the key is lost, so the entry stays.
        assertTrue(remembered.has("a"));
        assertEquals(0, key.deletions);
    }

    /** Stands in for a Keystore cipher whose hardware operation fails at the last step. */
    private static final class FailingCipherSpi extends CipherSpi {
        @Override
        protected void engineSetMode(String mode) {
        }

        @Override
        protected void engineSetPadding(String padding) {
        }

        @Override
        protected int engineGetBlockSize() {
            return 0;
        }

        @Override
        protected int engineGetOutputSize(int inputLen) {
            return inputLen;
        }

        @Override
        protected byte[] engineGetIV() {
            return new byte[12];
        }

        @Override
        protected AlgorithmParameters engineGetParameters() {
            return null;
        }

        @Override
        protected void engineInit(int opmode, Key k, SecureRandom random) {
        }

        @Override
        protected void engineInit(int opmode, Key k,
                                  AlgorithmParameterSpec params,
                                  SecureRandom random) {
        }

        @Override
        protected void engineInit(int opmode, Key k, AlgorithmParameters params,
                                  SecureRandom random) {
        }

        @Override
        protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
            return new byte[0];
        }

        @Override
        protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) {
            return 0;
        }

        @Override
        protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen) {
            throw new ProviderException("keystore operation failed");
        }

        @Override
        protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) {
            throw new ProviderException("keystore operation failed");
        }
    }

    private static final class FailingCipher extends Cipher {
        FailingCipher() throws GeneralSecurityException {
            // A Cipher needs a provider the runtime trusts; the real one's will do.
            super(new FailingCipherSpi(), Cipher.getInstance(TRANSFORMATION).getProvider(), "failing");
            init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[32], "AES"));
        }
    }

    @Test
    public void uncheckedFailureWhileEncryptingOrDecryptingIsReportedAsChecked() throws Exception {
        remember("a", "1");
        byte[] password = utf8("abc");

        Cipher failing = new FailingCipher();
        assertThrows(GeneralSecurityException.class, () -> remembered.save("b", failing, password));
        assertArrayEquals("the plaintext must be wiped after a failed save too", new byte[3], password);
        assertFalse(remembered.has("b"));
        assertThrows(GeneralSecurityException.class, () -> remembered.read("a", failing));
    }
}
