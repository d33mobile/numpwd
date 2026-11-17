package net.d33.numpwd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.biometric.BiometricManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Drives the emulator's fingerprint sensor and lock screen from an instrumented test.
 *
 * <p>Fingers are simulated through the emulator console ({@code finger touch N}), which the
 * guest reaches at 10.0.2.2, the host's loopback. The console needs the auth token from the
 * host's ~/.emulator_console_auth_token, passed as the instrumentation argument
 * {@code emulatorConsoleToken}; without it the biometric tests are skipped.
 */
final class EmulatorBiometrics {
    static final String PIN = "1111";
    static final int ENROLLED_FINGER = 1;
    static final int UNKNOWN_FINGER = 9;

    private static final String HOST_LOOPBACK = "10.0.2.2";
    private static final String DEFAULT_CONSOLE_PORT = "5554";
    private static final String SETTINGS = "com.android.settings";
    private static final long UI_TIMEOUT_MS = 15_000;
    private static final long ENROLL_TIMEOUT_MS = 120_000;
    private static final long ENROLL_STEP_MS = 1_000;
    private static final Pattern ENROLL_BUTTONS = Pattern.compile("(?i)more|i agree|agree|next|start");
    private static final Pattern ENROLL_DONE = Pattern.compile("(?i)fingerprint added");

    private EmulatorBiometrics() {
    }

    static UiDevice device() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    private static String token() {
        Bundle args = InstrumentationRegistry.getArguments();
        return args.getString("emulatorConsoleToken");
    }

    /** Skips the calling test unless it runs on an emulator whose console it can reach. */
    static void assumeConsole() {
        assumeTrue("needs the emulatorConsoleToken instrumentation argument", token() != null);
        // executeShellCommandRw, used to talk to the console, exists since API 31.
        assumeTrue("needs API 31+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
    }

    static boolean canRemember() {
        return BiometricManager.from(ApplicationProvider.getApplicationContext())
                .canAuthenticate(Biometrics.AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /** Sets the PIN and enrols {@link #ENROLLED_FINGER} unless a fingerprint is already set up. */
    static void ensureFingerprint() throws IOException {
        if (canRemember())
            return;
        shell("locksettings set-pin " + PIN);
        enroll(ENROLLED_FINGER);
        assertEquals("fingerprint enrolment failed", true, canRemember());
    }

    /**
     * Removes the lock screen, which also removes every enrolled fingerprint, then sets up the
     * PIN and a single finger again.
     */
    static void resetToOneFinger() throws IOException {
        shell("locksettings clear --old " + PIN);
        shell("locksettings set-pin " + PIN);
        enroll(ENROLLED_FINGER);
    }

    /** Enrols a finger through the Settings app, as a user would. */
    static void enroll(int finger) throws IOException {
        UiDevice device = device();
        shell("am start -a android.settings.FINGERPRINT_ENROLL");
        UiObject2 pin = device.wait(Until.findObject(By.res(SETTINGS, "password_entry")), UI_TIMEOUT_MS);
        assertNotNull("Settings did not ask for the PIN", pin);
        pin.setText(PIN);
        device.pressEnter();

        long deadline = SystemClock.uptimeMillis() + ENROLL_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(ENROLL_STEP_MS);
            UiObject2 done = device.findObject(By.text(ENROLL_DONE));
            if (done != null) {
                UiObject2 doneButton = device.findObject(By.text(Pattern.compile("(?i)done")));
                if (doneButton != null)
                    doneButton.click();
                return;
            }
            UiObject2 button = device.findObject(By.text(ENROLL_BUTTONS).clickable(true));
            if (button == null)
                button = device.findObject(By.text(ENROLL_BUTTONS));
            if (button != null)
                button.click();
            else
                touch(finger);
        }
        throw new AssertionError("fingerprint enrolment did not finish");
    }

    /** Puts finger {@code finger} on the emulator's sensor. */
    static void touch(int finger) throws IOException {
        String reply = console("finger touch " + finger);
        if (!reply.contains("OK"))
            throw new AssertionError("emulator console refused: " + reply);
    }

    private static String console(String command) throws IOException {
        String port = InstrumentationRegistry.getArguments()
                .getString("emulatorConsolePort", DEFAULT_CONSOLE_PORT);
        ParcelFileDescriptor[] fds = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommandRw("nc " + HOST_LOOPBACK + " " + port);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fds[0])) {
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fds[1])) {
                out.write(("auth " + token() + "\n" + command + "\nquit\n").getBytes(StandardCharsets.US_ASCII));
            }
            return readAll(in);
        }
    }

    /**
     * Waits for a system window (the biometric prompt) showing the given text; buttons may
     * show it in capitals.
     */
    static UiObject2 waitForText(String text) {
        Pattern pattern = Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE);
        return device().wait(Until.findObject(By.text(pattern)), UI_TIMEOUT_MS);
    }

    /** Whether the system window showing the given text went away in time. */
    static boolean waitUntilGone(String text) {
        Pattern pattern = Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE);
        return device().wait(Until.gone(By.text(pattern)), UI_TIMEOUT_MS);
    }

    static boolean isShown(BySelector selector) {
        return device().hasObject(selector);
    }

    /** Runs a shell command and returns its output. */
    static String shell(String command) throws IOException {
        try (ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
             InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            return readAll(in);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int n;
        while ((n = in.read(buffer)) >= 0)
            bytes.write(buffer, 0, n);
        return bytes.toString("UTF-8");
    }
}
