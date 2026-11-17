package net.d33.numpwd;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.security.keystore.KeyInfo;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.textfield.TextInputLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;

/**
 * Remembering a password behind the fingerprint / screen lock, on an emulator with an enrolled
 * fingerprint driven through the emulator console (see {@link EmulatorBiometrics}).
 */
@RunWith(AndroidJUnit4.class)
public class RememberPasswordTest {
    // How long a rejected fingerprint must keep the code hidden.
    private static final long NOT_REVEALED_MS = 3_000;

    private ActivityScenario<KeysActivity> scenario;

    private static Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    private static RememberedPasswords remembered() {
        return RememberedPasswords.open(context());
    }

    private static String string(int id, Object... args) {
        return context().getString(id, args);
    }

    @Before
    public void setUp() throws Exception {
        EmulatorBiometrics.assumeConsole();
        EmulatorBiometrics.ensureFingerprint();
        remembered().forgetAll();
        new KeyNames(context()).save(Collections.singletonList("test"));
        scenario = ActivityScenario.launch(KeysActivity.class);
    }

    @After
    public void tearDown() throws Exception {
        if (scenario != null)
            scenario.close();
        remembered().forgetAll();
    }

    /** Types the password with "Remember" ticked and confirms with the enrolled finger. */
    private void rememberTestAbc() throws Exception {
        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(KeysActivityTest.tap());
        onView(withId(R.id.remember)).inRoot(isDialog()).check(matches(isNotChecked())).perform(click());
        KeysActivityTest.enterInDialog("abc");
        assertNotNull("no prompt to remember the password",
                EmulatorBiometrics.waitForText(string(R.string.remember_prompt_title, "test")));
        EmulatorBiometrics.touch(EmulatorBiometrics.ENROLLED_FINGER);
        assertEquals(KeysActivityTest.TEST_CODE, KeysActivityTest.waitForResult());
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
        assertTrue(remembered().has("test"));
    }

    /** Taps the key and waits for the system prompt that unlocks its remembered password. */
    private void tapAndWaitForUnlockPrompt() {
        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(KeysActivityTest.tap());
        assertNotNull("no unlock prompt",
                EmulatorBiometrics.waitForText(string(R.string.unlock_prompt_title, "test")));
    }

    @Test
    public void rememberedPasswordUnlocksWithFingerprint() throws Exception {
        rememberTestAbc();
        onView(withText("test")).check(matches(withContentDescription(
                string(R.string.key_remembered_description, "test"))));

        tapAndWaitForUnlockPrompt();
        EmulatorBiometrics.touch(EmulatorBiometrics.ENROLLED_FINGER);
        assertEquals(KeysActivityTest.TEST_CODE, KeysActivityTest.waitForResult());
        onView(withId(R.id.result_full)).inRoot(isDialog())
                .check(matches(withText(KeysActivityTest.TEST_DIGITS_GROUPED)));
    }

    @Test
    public void unknownFingerDoesNotRevealAndCancelAsksForPassword() throws Exception {
        rememberTestAbc();

        tapAndWaitForUnlockPrompt();
        EmulatorBiometrics.touch(EmulatorBiometrics.UNKNOWN_FINGER);
        SystemClock.sleep(NOT_REVEALED_MS);
        assertNull("the code was shown for an unknown finger", KeysActivityTest.readResult());

        // The prompt's negative button falls back to typing the password.
        EmulatorBiometrics.waitForText(string(R.string.prompt_type_password)).click();
        onView(withId(R.id.input)).inRoot(isDialog()).check(matches(isDisplayed()));
        KeysActivityTest.enterInDialog("abc");
        assertEquals(KeysActivityTest.TEST_CODE, KeysActivityTest.waitForResult());
        assertTrue("cancelling must not forget the password", remembered().has("test"));
    }

    /**
     * The system prompt belongs to the activity that started it. A rotation replaces that
     * activity, so the prompt has to go with it; a finger afterwards must not reach the old one.
     */
    @Test
    public void recreationDuringTheUnlockPromptCancelsIt() throws Exception {
        rememberTestAbc();

        tapAndWaitForUnlockPrompt();
        scenario.recreate();
        EmulatorBiometrics.touch(EmulatorBiometrics.ENROLLED_FINGER);
        assertTrue("the unlock prompt outlived its activity",
                EmulatorBiometrics.waitUntilGone(string(R.string.unlock_prompt_title, "test")));
        assertNull("the code was shown without a prompt", KeysActivityTest.readResult());

        // The new activity unlocks as usual.
        tapAndWaitForUnlockPrompt();
        EmulatorBiometrics.touch(EmulatorBiometrics.ENROLLED_FINGER);
        assertEquals(KeysActivityTest.TEST_CODE, KeysActivityTest.waitForResult());
    }

    @Test
    public void recreationDuringTheRememberPromptDoesNotRemember() throws Exception {
        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(KeysActivityTest.tap());
        onView(withId(R.id.remember)).inRoot(isDialog()).perform(click());
        KeysActivityTest.enterInDialog("abc");
        String title = string(R.string.remember_prompt_title, "test");
        assertNotNull("no prompt to remember the password", EmulatorBiometrics.waitForText(title));

        scenario.recreate();
        EmulatorBiometrics.touch(EmulatorBiometrics.ENROLLED_FINGER);
        assertTrue("the remember prompt outlived its activity", EmulatorBiometrics.waitUntilGone(title));
        assertFalse(remembered().has("test"));

        // The new activity asks for the password again.
        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(KeysActivityTest.tap());
        KeysActivityTest.enterInDialog("abc");
        assertEquals(KeysActivityTest.TEST_CODE, KeysActivityTest.waitForResult());
    }

    @Test
    public void forgetPasswordFromKeyMenu() throws Exception {
        rememberTestAbc();

        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(longClick());
        onView(withText(R.string.forget_password)).inRoot(isDialog()).perform(click());
        assertFalse(remembered().has("test"));

        // Without a remembered password the key asks for it straight away.
        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(KeysActivityTest.tap());
        onView(withId(R.id.remember)).inRoot(isDialog()).check(matches(isNotChecked()));
        onView(withId(R.id.input)).inRoot(isDialog()).perform(replaceText("abc"));
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
        assertEquals(KeysActivityTest.TEST_CODE, KeysActivityTest.waitForResult());
    }

    @Test
    public void forgetAllFromMenu() throws Exception {
        rememberTestAbc();

        openActionBarOverflowOrOptionsMenu(context());
        onView(withText(R.string.forget_all)).perform(click());
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
        assertTrue(remembered().names().isEmpty());
    }

    @Test
    public void newFingerprintInvalidatesRememberedPasswords() throws Exception {
        rememberTestAbc();
        scenario.close();
        scenario = null;
        try {
            EmulatorBiometrics.enroll(2);
            scenario = ActivityScenario.launch(KeysActivity.class);

            onData(is("test")).inAdapterView(withId(R.id.keys)).perform(KeysActivityTest.tap());
            onView(withId(R.id.input_layout)).inRoot(isDialog()).check((view, e) -> assertEquals(
                    string(R.string.remembered_invalidated),
                    String.valueOf(((TextInputLayout) view).getHelperText())));
            assertTrue(remembered().names().isEmpty());
            KeysActivityTest.enterInDialog("abc");
            assertEquals(KeysActivityTest.TEST_CODE, KeysActivityTest.waitForResult());
        } finally {
            if (scenario != null)
                scenario.close();
            scenario = null;
            EmulatorBiometrics.resetToOneFinger();
        }
    }

    @Test
    public void keystoreKeyNeedsAuthenticationForEveryUse() throws Exception {
        rememberTestAbc();

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey key = (SecretKey) keyStore.getKey(KeystorePasswordKey.ALIAS, null);
        KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore")
                .getKeySpec(key, KeyInfo.class);
        assertTrue(info.isUserAuthenticationRequired());
        assertTrue(info.isInvalidatedByBiometricEnrollment());
        assertEquals(0, info.getUserAuthenticationValidityDurationSeconds());
        assertEquals(256, info.getKeySize());

        // Without the prompt the key refuses to encrypt or decrypt anything.
        Cipher encrypt = new KeystorePasswordKey().encryptCipher();
        assertThrows(Exception.class, () -> encrypt.doFinal("x".getBytes(StandardCharsets.UTF_8)));
        Cipher decrypt = remembered().cipherForReading("test");
        assertThrows(Exception.class, () -> remembered().read("test", decrypt));
        assertTrue(remembered().has("test"));
    }
}
