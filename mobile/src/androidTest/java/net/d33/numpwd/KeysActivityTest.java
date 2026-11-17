package net.d33.numpwd;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.pressBack;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.NoMatchingRootException;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.textfield.TextInputLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

import javax.crypto.KeyGenerator;

/**
 * UI smoke test: add a key, enter a password, read and copy the code and the full digits;
 * rename and delete a key.
 */
@RunWith(AndroidJUnit4.class)
public class KeysActivityTest {
    // openssl passwd -6 -salt test abc | tr -dc '[0-9]' | head -c6
    static final String TEST_CODE = "659550";
    // The whole tr output, 65955012972, shown below the code in groups of four.
    private static final String TEST_DIGITS = "65955012972";
    static final String TEST_DIGITS_GROUPED = "6595 5012 972";
    // Generous because a loaded emulator can take several seconds per dialog transition;
    // without cancellation the second derivation would still wait for many minutes.
    private static final long RESULT_TIMEOUT_MS = 30_000;
    private static final char PASSWORD_DOT = '\u2022';
    private static final long CLIPBOARD_CLEAR_SLACK_MS = 3_000;
    private static final long CLIPBOARD_SET_TIMEOUT_MS = 5_000;

    private ActivityScenario<KeysActivity> scenario;

    @Before
    public void clearKeys() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        new KeyNames(context).save(Collections.emptyList());
        RememberedPasswords.open(context).forgetAll();
        new AppSettings(context).setAllowPasswordManagers(false);
    }

    @After
    public void close() {
        if (scenario != null)
            scenario.close();
    }

    /** A tap on a busy emulator can register as a long press; dismiss the key menu and retry. */
    static ViewAction tap() {
        return click(pressBack());
    }

    static void enterInDialog(String text) {
        onView(withId(R.id.input)).inRoot(isDialog()).perform(replaceText(text));
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
    }

    @Test
    public void addKeyThenDerivesOpensslDigits() {
        scenario = ActivityScenario.launch(KeysActivity.class);
        onView(withId(R.id.empty)).check(matches(isDisplayed()));

        onView(withId(R.id.fab_add)).perform(click());
        enterInDialog("test");
        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(tap());
        enterInDialog("abc");

        assertEquals(TEST_CODE, waitForResult());
        onView(withId(R.id.result_full)).inRoot(isDialog()).check(matches(withText(TEST_DIGITS_GROUPED)));

        onView(withId(android.R.id.button1)).inRoot(isDialog()).check(matches(isEnabled())).perform(click());
        assertEquals(TEST_CODE, clipboardText());
        onView(withId(android.R.id.button3)).inRoot(isDialog()).perform(click());
        assertEquals(TEST_DIGITS, clipboardText());

        assertEquals(Collections.singletonList("test"),
                new KeyNames(ApplicationProvider.getApplicationContext()).load());
    }

    /** On Android < 12 "back" destroys the activity; a rotation does on every version. */
    @Test
    public void clipboardIsClearedAfterTheActivityIsGone() {
        new KeyNames(ApplicationProvider.getApplicationContext()).save(Collections.singletonList("test"));
        scenario = ActivityScenario.launch(KeysActivity.class);
        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(tap());
        enterInDialog("abc");
        assertEquals(TEST_CODE, waitForResult());
        onView(withId(android.R.id.button1)).inRoot(isDialog()).check(matches(isEnabled())).perform(click());
        assertEquals(TEST_CODE, waitForClipboard(TEST_CODE));

        scenario.recreate();
        SystemClock.sleep(KeysActivity.CLIPBOARD_CLEAR_MS + CLIPBOARD_CLEAR_SLACK_MS);
        String left = clipboardText();
        assertTrue("the code stayed on the clipboard: " + left, left == null || left.isEmpty());
    }

    @Test
    public void closingSlowDerivationDoesNotBlockTheNextOne() {
        // openssl needs minutes for the maximum round count; the app must not.
        String slowKey = "rounds=999999999$x";
        new KeyNames(ApplicationProvider.getApplicationContext()).save(Arrays.asList(slowKey, "test"));
        scenario = ActivityScenario.launch(KeysActivity.class);

        onData(is(slowKey)).inAdapterView(withId(R.id.keys)).perform(tap());
        enterInDialog("abc");
        assertEquals(computing(), readResultWhenShown());
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());

        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(tap());
        enterInDialog("abc");
        assertEquals(TEST_CODE, waitForResult());
    }

    @Test
    public void passwordFieldNeverShowsTypedCharacters() throws Exception {
        // The system default; with it, a plain password field shows the last typed character.
        shell("settings put system show_password 1");
        new KeyNames(ApplicationProvider.getApplicationContext()).save(Collections.singletonList("test"));
        scenario = ActivityScenario.launch(KeysActivity.class);

        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(tap());
        // A real key press, like typing on a keyboard; setText would not trigger the reveal.
        onView(withId(R.id.input)).inRoot(isDialog()).check(matches(isDisplayed()));
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_Q);
        onView(withId(R.id.input)).inRoot(isDialog()).check((view, e) -> {
            EditText input = (EditText) view;
            assertEquals("q", String.valueOf(input.getText()));
            assertEquals(String.valueOf(PASSWORD_DOT), String.valueOf(input.getLayout().getText()));
            int ime = input.getImeOptions();
            assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                    ime & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            int type = input.getInputType();
            assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    type & InputType.TYPE_MASK_VARIATION);
            assertEquals(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                    type & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        });
        onView(withId(R.id.input_layout)).inRoot(isDialog()).check((view, e) ->
                assertEquals(TextInputLayout.END_ICON_NONE, ((TextInputLayout) view).getEndIconMode()));
        // A dialog left open leaks its window when the activity closes and upsets the next test.
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
    }

    @Test
    public void passwordManagersAreOptIn() {
        assumeTrue("autofill exists since API 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O);
        Context context = ApplicationProvider.getApplicationContext();
        new KeyNames(context).save(Collections.singletonList("test"));
        scenario = ActivityScenario.launch(KeysActivity.class);

        assertPasswordAutofill(false);
        toggleAllowPasswordManagers();
        assertTrue(new AppSettings(context).allowPasswordManagers());
        assertPasswordAutofill(true);
        toggleAllowPasswordManagers();
        assertFalse(new AppSettings(context).allowPasswordManagers());
        assertPasswordAutofill(false);
    }

    private static void toggleAllowPasswordManagers() {
        openActionBarOverflowOrOptionsMenu(ApplicationProvider.getApplicationContext());
        onView(withText(R.string.allow_password_managers)).perform(click());
    }

    /** Opens the password dialog of key "test", checks the autofill flags and closes it. */
    private static void assertPasswordAutofill(boolean allowed) {
        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(tap());
        onView(withId(R.id.input_container)).inRoot(isDialog()).check((view, e) -> assertEquals(
                allowed ? View.IMPORTANT_FOR_AUTOFILL_AUTO : View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                view.getImportantForAutofill()));
        onView(withId(R.id.input)).inRoot(isDialog()).check((view, e) -> {
            // isImportantForAutofill() takes the ancestors' exclusion into account.
            assertEquals(allowed, view.isImportantForAutofill());
            if (allowed)
                assertTrue(Arrays.asList(view.getAutofillHints()).contains(View.AUTOFILL_HINT_PASSWORD));
        });
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
    }

    @Test
    public void renameAndDeleteKey() {
        new KeyNames(ApplicationProvider.getApplicationContext()).save(Arrays.asList("a", "b"));
        scenario = ActivityScenario.launch(KeysActivity.class);

        onData(is("a")).inAdapterView(withId(R.id.keys)).perform(longClick());
        onView(withText(R.string.rename)).inRoot(isDialog()).perform(click());
        enterInDialog("b");
        onView(withText(R.string.key_name_exists)).inRoot(isDialog()).check(matches(isDisplayed()));
        enterInDialog("c");

        onData(is("b")).inAdapterView(withId(R.id.keys)).perform(longClick());
        onView(withText(R.string.delete)).inRoot(isDialog()).perform(click());
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());

        onView(withText("b")).check(doesNotExist());
        assertEquals(Collections.singletonList("c"),
                new KeyNames(ApplicationProvider.getApplicationContext()).load());
    }

    /** Stores {@code value} as the remembered password of key "test", bypassing the encryption. */
    private static void storeRememberedValue(String value) {
        ApplicationProvider.getApplicationContext()
                .getSharedPreferences(RememberedPasswords.PREFS, Context.MODE_PRIVATE)
                .edit().putString("test", value).commit();
    }

    /** Taps key "test" and checks the notice below the password field that opens. */
    private static void tapTestAndExpectNotice(int notice) {
        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(tap());
        onView(withId(R.id.input_layout)).inRoot(isDialog()).check((view, e) -> assertEquals(
                ApplicationProvider.getApplicationContext().getString(notice),
                String.valueOf(((TextInputLayout) view).getHelperText())));
    }

    @Test
    public void corruptRememberedPasswordIsForgotten() {
        Context context = ApplicationProvider.getApplicationContext();
        new KeyNames(context).save(Collections.singletonList("test"));
        storeRememberedValue("not hex");
        scenario = ActivityScenario.launch(KeysActivity.class);

        tapTestAndExpectNotice(R.string.remembered_unreadable);
        assertFalse(RememberedPasswords.open(context).has("test"));
        enterInDialog("abc");
        assertEquals(TEST_CODE, waitForResult());
    }

    /**
     * A Keystore key that cannot decrypt makes Cipher.init fail for a reason that says nothing
     * about the stored value, like a transient Keystore error; the password stays remembered.
     */
    @Test
    public void keystoreFailureKeepsTheRememberedPassword() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        new KeyNames(context).save(Collections.singletonList("test"));
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KeystorePasswordKey.ALIAS, KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        generator.generateKey();
        // A well-formed entry: a 12 byte IV and a 16 byte ciphertext (tag only).
        storeRememberedValue("000000000000000000000000:00000000000000000000000000000000");
        scenario = ActivityScenario.launch(KeysActivity.class);

        tapTestAndExpectNotice(R.string.remembered_unavailable);
        assertTrue(RememberedPasswords.open(context).has("test"));
        enterInDialog("abc");
        assertEquals(TEST_CODE, waitForResult());
    }

    /** Runs a shell command and waits for it by reading its output to the end. */
    private static void shell(String command) throws IOException {
        try (ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
             InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            byte[] buffer = new byte[256];
            while (in.read(buffer) >= 0) {
                // drain
            }
        }
    }

    static String clipboardText() {
        String[] text = {null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ClipboardManager cm = (ClipboardManager) ApplicationProvider.getApplicationContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0)
                text[0] = String.valueOf(clip.getItemAt(0).getText());
        });
        return text[0];
    }

    /** The clipboard text once it equals {@code expected}, or whatever it holds after a few seconds. */
    private static String waitForClipboard(String expected) {
        long deadline = SystemClock.uptimeMillis() + CLIPBOARD_SET_TIMEOUT_MS;
        String text = clipboardText();
        while (!expected.equals(text) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
            text = clipboardText();
        }
        return text;
    }

    private static String computing() {
        return ApplicationProvider.getApplicationContext().getString(R.string.computing);
    }

    /** Text of the result dialog, or null while that dialog is not on screen yet. */
    static String readResult() {
        String[] text = {null};
        try {
            onView(withId(R.id.result)).inRoot(isDialog())
                    .check((view, e) -> {
                        if (view != null)
                            text[0] = String.valueOf(((TextView) view).getText());
                    });
        } catch (NoMatchingRootException | NoMatchingViewException e) {
            return null;
        }
        return text[0];
    }

    /** The result dialog opens after the password dialog has gone, which can take a while. */
    static String readResultWhenShown() {
        long deadline = SystemClock.uptimeMillis() + RESULT_TIMEOUT_MS;
        String text = readResult();
        while (text == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
            text = readResult();
        }
        return text;
    }

    /** The digits are computed off the main thread, which Espresso does not wait for. */
    static String waitForResult() {
        long deadline = SystemClock.uptimeMillis() + RESULT_TIMEOUT_MS;
        String text = readResult();
        while ((text == null || computing().equals(text)) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
            text = readResult();
        }
        return text;
    }
}
