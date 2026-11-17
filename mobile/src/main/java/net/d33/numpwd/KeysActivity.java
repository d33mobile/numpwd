package net.d33.numpwd;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.Toolbar;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.Cipher;

/**
 * Lists key names; tapping one asks for a password (or unlocks a remembered one with the
 * fingerprint) and shows the six-character code with the full digit string
 * below it.
 */
public class KeysActivity extends AppCompatActivity {
    private static final int RESULT_VISIBLE_MS = 60_000;
    static final int CLIPBOARD_CLEAR_MS = 30_000;
    private static final int DIGIT_GROUP = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    // Clears the clipboard; unlike the activity's handler it is not emptied when the activity goes.
    private static final Handler CLIPBOARD_CLEARER = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private KeyNames store;
    private RememberedPasswords remembered;
    private AppSettings settings;
    private List<String> names;
    private ArrayAdapter<String> adapter;
    // The system prompt on screen or about to appear, and the typed password waiting for its outcome.
    @Nullable
    private BiometricPrompt prompt;
    @Nullable
    private byte[] passwordAwaitingPrompt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keys);
        SystemBarInsets.apply(this);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        store = new KeyNames(this);
        remembered = RememberedPasswords.open(this);
        settings = new AppSettings(this);
        names = store.load();
        adapter = new KeyAdapter();
        ListView list = findViewById(R.id.keys);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener((parent, view, position, id) -> openKey(names.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showKeyActions(position);
            return true;
        });
        findViewById(R.id.fab_add).setOnClickListener(v -> askName(-1));
    }

    @Override
    protected void onDestroy() {
        // The system prompt would outlive a rotation and report to this destroyed activity. The
        // callbacks clear the field once the prompt is gone.
        if (prompt != null)
            prompt.cancelAuthentication();
        wipePasswordAwaitingPrompt();
        worker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.keys, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // Autofill exists since API 26; before that there is nothing to allow.
        menu.findItem(R.id.allow_password_managers)
                .setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                .setChecked(settings.allowPasswordManagers());
        menu.findItem(R.id.forget_all).setVisible(!remembered.names().isEmpty());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.allow_password_managers) {
            settings.setAllowPasswordManagers(!settings.allowPasswordManagers());
            return true;
        }
        if (item.getItemId() == R.id.forget_all) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.forget_all)
                    .setMessage(R.string.forget_all_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.forget, (d, w) -> forgetAll())
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void forgetAll() {
        try {
            remembered.forgetAll();
        } catch (GeneralSecurityException e) {
            // The entries are gone already; only deleting the unusable Keystore key failed.
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
        }
        adapter.notifyDataSetChanged();
    }

    private void persist() {
        store.save(names);
        adapter.notifyDataSetChanged();
    }

    private void showKeyActions(int position) {
        String name = names.get(position);
        List<CharSequence> items = new ArrayList<>(Arrays.asList(getString(R.string.rename), getString(R.string.delete)));
        if (remembered.has(name))
            items.add(getString(R.string.forget_password));
        new MaterialAlertDialogBuilder(this)
                .setTitle(name)
                .setItems(items.toArray(new CharSequence[0]),
                        (d, which) -> {
                            if (which == 0)
                                askName(position);
                            else if (which == 1)
                                confirmDelete(position);
                            else {
                                remembered.forget(name);
                                adapter.notifyDataSetChanged();
                            }
                        })
                .show();
    }

    private void confirmDelete(int position) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.key_delete)
                .setMessage(getString(R.string.key_delete_confirm, names.get(position)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    remembered.forget(names.remove(position));
                    persist();
                })
                .show();
    }

    /** position < 0 adds a new key, otherwise renames the key at position. */
    private void askName(int position) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        TextInputLayout layout = view.findViewById(R.id.input_layout);
        TextInputEditText input = view.findViewById(R.id.input);
        layout.setHint(getString(R.string.key_name_hint));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (position >= 0)
            input.setText(names.get(position));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(position < 0 ? R.string.key_add : R.string.key_rename)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .create();
        Runnable submit = () -> {
            String name = String.valueOf(input.getText());
            int existing = names.indexOf(name);
            if (!KeyNames.isValidName(name)) {
                layout.setError(getString(R.string.key_name_invalid));
            } else if (existing >= 0 && existing != position) {
                layout.setError(getString(R.string.key_name_exists));
            } else {
                if (position < 0) {
                    names.add(name);
                } else {
                    String oldName = names.set(position, name);
                    // The password belongs to the name it was typed for; a new name is a new salt.
                    if (!oldName.equals(name))
                        remembered.rename(oldName, name);
                }
                persist();
                dialog.dismiss();
            }
        };
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> submit.run()));
        input.setOnEditorActionListener((v, actionId, event) -> {
            submit.run();
            return true;
        });
        showWithKeyboard(dialog, input);
    }

    /** Unlocks the remembered password if there is one, otherwise asks for it. */
    private void openKey(String name) {
        // A second tap before the system prompt shows up would take over the callback of the
        // prompt, which still authenticates the first key's cipher.
        if (prompt != null)
            return;
        if (remembered.has(name))
            unlockRemembered(name);
        else
            askPassword(name, null);
    }

    private void unlockRemembered(String name) {
        Cipher cipher;
        try {
            cipher = remembered.cipherForReading(name);
        } catch (GeneralSecurityException e) {
            notReadable(name, e);
            return;
        }
        // E.g. the sensor is temporarily unavailable; the password stays remembered.
        if (!Biometrics.available(this)) {
            askPassword(name, null);
            return;
        }
        authenticate(cipher, getString(R.string.unlock_prompt_title, name), R.string.prompt_type_password,
                new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                prompt = null;
                if (isGone())
                    return;
                byte[] password;
                try {
                    password = remembered.read(name, authenticatedCipher(result));
                } catch (GeneralSecurityException e) {
                    notReadable(name, e);
                    return;
                }
                showResult(name, password);
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                prompt = null;
                // Cancelled, "Type the password", lockout, ...: typing the password always works.
                if (!isGone())
                    askPassword(name, null);
            }
        });
    }

    /**
     * Falls back to typing the password. Only an invalidated key or a damaged entry loses the
     * remembered password; any other failure may not happen next time.
     */
    private void notReadable(String name, GeneralSecurityException e) {
        int notice;
        if (e instanceof KeyInvalidatedException)
            notice = R.string.remembered_invalidated;
        else if (e instanceof CorruptEntryException)
            notice = R.string.remembered_unreadable;
        else
            notice = R.string.remembered_unavailable;
        adapter.notifyDataSetChanged();
        askPassword(name, getString(notice));
    }

    /** Asks for the password of key {@code name}; {@code notice} is shown below the field. */
    private void askPassword(String name, @Nullable String notice) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        TextInputLayout layout = view.findViewById(R.id.input_layout);
        TextInputEditText input = view.findViewById(R.id.input);
        CheckBox remember = view.findViewById(R.id.remember);
        layout.setHint(getString(R.string.password_hint));
        if (notice != null)
            layout.setHelperText(notice);
        if (Biometrics.available(this))
            remember.setVisibility(View.VISIBLE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // After setInputType, which installs the platform transformation that briefly shows the
        // last typed character. There is no show/hide toggle: the password is never displayed.
        input.setTransformationMethod(new HiddenPasswordTransformation());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The keyboard must not learn the password (the flag exists since API 26).
            input.setImeOptions(input.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            if (settings.allowPasswordManagers()) {
                input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
                input.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
            } else {
                // Unless the user opted in, a password manager must not offer to save the master password.
                view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            }
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.password_title, name))
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .create();
        secure(dialog);
        Runnable submit = () -> {
            byte[] password = utf8(input.getText());
            if (input.getText() != null)
                input.getText().clear();
            dialog.dismiss();
            if (remember.isChecked())
                rememberThenShow(name, password);
            else
                showResult(name, password);
        };
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> submit.run()));
        input.setOnEditorActionListener((v, actionId, event) -> {
            submit.run();
            return true;
        });
        showWithKeyboard(dialog, input);
    }

    /** Stores the password behind the biometric prompt; the code is shown either way. */
    private void rememberThenShow(String name, byte[] password) {
        Cipher cipher;
        try {
            cipher = remembered.cipherForSaving();
        } catch (GeneralSecurityException e) {
            notRemembered(e.toString());
            showResult(name, password);
            return;
        }
        passwordAwaitingPrompt = password;
        authenticate(cipher, getString(R.string.remember_prompt_title, name), R.string.prompt_do_not_remember,
                new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                prompt = null;
                if (isGone()) {
                    Arrays.fill(password, (byte) 0);
                    return;
                }
                passwordAwaitingPrompt = null;
                try {
                    remembered.save(name, authenticatedCipher(result), password.clone());
                } catch (GeneralSecurityException e) {
                    notRemembered(e.toString());
                }
                adapter.notifyDataSetChanged();
                showResult(name, password);
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                prompt = null;
                if (isGone()) {
                    Arrays.fill(password, (byte) 0);
                    return;
                }
                passwordAwaitingPrompt = null;
                notRemembered(errString.toString());
                showResult(name, password);
            }
        });
    }

    /** Whether a prompt result arrives too late for this activity to show anything. */
    private boolean isGone() {
        return isFinishing() || isDestroyed();
    }

    private void wipePasswordAwaitingPrompt() {
        if (passwordAwaitingPrompt != null)
            Arrays.fill(passwordAwaitingPrompt, (byte) 0);
        passwordAwaitingPrompt = null;
    }

    private void notRemembered(String reason) {
        Toast.makeText(this, getString(R.string.not_remembered, reason), Toast.LENGTH_LONG).show();
    }

    /**
     * Shows the system prompt; the cipher can only be used inside onAuthenticationSucceeded,
     * because the Keystore key demands an authentication for every operation.
     */
    private void authenticate(Cipher cipher, String title, int negativeButton,
                              BiometricPrompt.AuthenticationCallback callback) {
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(Biometrics.AUTHENTICATORS)
                .setNegativeButtonText(getString(negativeButton))
                .build();
        prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback);
        prompt.authenticate(info, new BiometricPrompt.CryptoObject(cipher));
    }

    private static Cipher authenticatedCipher(BiometricPrompt.AuthenticationResult result)
            throws GeneralSecurityException {
        BiometricPrompt.CryptoObject crypto = result.getCryptoObject();
        if (crypto == null || crypto.getCipher() == null)
            throw new GeneralSecurityException("the prompt returned no cipher");
        return crypto.getCipher();
    }

    /** UTF-8 bytes of the typed text without an intermediate String, so the copy can be wiped. */
    private static byte[] utf8(@Nullable Editable text) {
        if (text == null)
            return new byte[0];
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(text));
        byte[] out = new byte[encoded.remaining()];
        encoded.get(out);
        if (encoded.hasArray())
            Arrays.fill(encoded.array(), (byte) 0);
        return out;
    }

    /** Shows the code for {@code password} and overwrites the array once it is used. */
    private void showResult(String name, byte[] password) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_result, null);
        TextView result = view.findViewById(R.id.result);
        TextView resultFull = view.findViewById(R.id.result_full);
        result.setText(R.string.computing);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.result_title, name))
                .setView(view)
                .setNeutralButton(R.string.copy_all, null)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.copy, null)
                .create();
        secure(dialog);
        // Set once the derivation succeeds: the full tr output, as copied by "Copy all".
        final String[] digits = {null};
        dialog.setOnShowListener(d -> {
            View copy = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            View copyAll = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            copy.setEnabled(false);
            copyAll.setEnabled(false);
            copy.setOnClickListener(v -> copyToClipboard(NumericPassword.sixDigits(digits[0])));
            copyAll.setOnClickListener(v -> copyToClipboard(digits[0]));
        });
        Future<?> task = worker.submit(() -> {
            String text;
            String error = null;
            try {
                text = NumericPassword.derive(name, password);
            } catch (OpensslShaCrypt.InvalidInputException e) {
                text = null;
                error = getString(R.string.result_error, e.getMessage());
            } catch (InterruptedException e) {
                // The dialog was closed; nobody is waiting for this result.
                return;
            } finally {
                Arrays.fill(password, (byte) 0);
            }
            final String value = text;
            final String shown = value != null ? NumericPassword.sixDigits(value) : error;
            final String shownFull = value != null ? group(value) : "";
            handler.post(() -> {
                if (!dialog.isShowing())
                    return;
                digits[0] = value;
                // "Computing…" and error messages stay small; only the code is large.
                if (value != null)
                    result.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                            getResources().getDimension(R.dimen.result_code_text));
                result.setText(shown);
                resultFull.setText(shownFull);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(value != null);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(value != null);
            });
        });
        Runnable autoDismiss = dialog::dismiss;
        dialog.setOnDismissListener(d -> {
            // A large "rounds=" key name can compute for minutes and would hold up the single
            // worker thread, so every later derivation would wait behind it.
            task.cancel(true);
            handler.removeCallbacks(autoDismiss);
            result.setText("");
            resultFull.setText("");
        });
        dialog.show();
        handler.postDelayed(autoDismiss, RESULT_VISIBLE_MS);
    }

    private static String group(String digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % DIGIT_GROUP == 0)
                sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    private void copyToClipboard(String value) {
        ClipboardManager cm = (ClipboardManager) getApplicationContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(getString(R.string.app_name), value);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PersistableBundle extras = new PersistableBundle();
            // Literal value of ClipDescription.EXTRA_IS_SENSITIVE, which only exists as a constant on API 33+.
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        Toast.makeText(this, getString(R.string.copied, CLIPBOARD_CLEAR_MS / 1000), Toast.LENGTH_SHORT).show();
        // The clipboard holds one value, so only the latest copy's timer counts; an earlier one
        // for the same code would clear this copy too early.
        CLIPBOARD_CLEARER.removeCallbacksAndMessages(null);
        CLIPBOARD_CLEARER.postDelayed(() -> clearClipboardIfOurs(cm, value), CLIPBOARD_CLEAR_MS);
    }

    private static void clearClipboardIfOurs(ClipboardManager cm, String value) {
        ClipData current = cm.getPrimaryClip();
        if (current == null || current.getItemCount() == 0
                || !value.contentEquals(String.valueOf(current.getItemAt(0).getText())))
            return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            cm.clearPrimaryClip();
        else
            cm.setPrimaryClip(ClipData.newPlainText("", ""));
    }

    /**
     * Keeps the password and the digits out of screenshots and screen recordings. Only these
     * dialogs are secure, so the key list can still be captured.
     */
    private static void secure(AlertDialog dialog) {
        if (dialog.getWindow() != null)
            dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void showWithKeyboard(AlertDialog dialog, View input) {
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        input.requestFocus();
    }

    /** Key rows; a lock at the end marks keys with a remembered password. */
    private final class KeyAdapter extends ArrayAdapter<String> {
        KeyAdapter() {
            super(KeysActivity.this, R.layout.key_item, R.id.key_name, names);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            TextView row = (TextView) super.getView(position, convertView, parent);
            String name = names.get(position);
            boolean hasPassword = remembered.has(name);
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    AppCompatResources.getDrawable(KeysActivity.this, R.drawable.ic_key), null,
                    hasPassword ? AppCompatResources.getDrawable(KeysActivity.this, R.drawable.ic_lock) : null,
                    null);
            row.setContentDescription(hasPassword ? getString(R.string.key_remembered_description, name) : null);
            return row;
        }
    }
}
