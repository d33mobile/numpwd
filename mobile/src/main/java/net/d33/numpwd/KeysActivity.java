package net.d33.numpwd;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Lists key names; tapping one asks for a password and shows the six-character code with the
 * full digit string below it.
 */
public class KeysActivity extends AppCompatActivity {
    private static final int RESULT_VISIBLE_MS = 60_000;
    private static final int CLIPBOARD_CLEAR_MS = 30_000;
    private static final int DIGIT_GROUP = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private KeyNames store;
    private List<String> names;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keys);
        SystemBarInsets.apply(this);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        store = new KeyNames(this);
        names = store.load();
        adapter = new ArrayAdapter<>(this, R.layout.key_item, R.id.key_name, names);
        ListView list = findViewById(R.id.keys);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener((parent, view, position, id) -> askPassword(names.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showKeyActions(position);
            return true;
        });
        findViewById(R.id.fab_add).setOnClickListener(v -> askName(-1));
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void persist() {
        store.save(names);
        adapter.notifyDataSetChanged();
    }

    private void showKeyActions(int position) {
        String name = names.get(position);
        new MaterialAlertDialogBuilder(this)
                .setTitle(name)
                .setItems(new CharSequence[]{getString(R.string.rename), getString(R.string.delete)},
                        (d, which) -> {
                            if (which == 0)
                                askName(position);
                            else
                                confirmDelete(position);
                        })
                .show();
    }

    private void confirmDelete(int position) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.key_delete)
                .setMessage(getString(R.string.key_delete_confirm, names.get(position)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    names.remove(position);
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
                if (position < 0)
                    names.add(name);
                else
                    names.set(position, name);
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

    private void askPassword(String name) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        TextInputLayout layout = view.findViewById(R.id.input_layout);
        TextInputEditText input = view.findViewById(R.id.input);
        layout.setHint(getString(R.string.password_hint));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // After setInputType, which installs the platform transformation that briefly shows the
        // last typed character. There is no show/hide toggle: the password is never displayed.
        input.setTransformationMethod(new HiddenPasswordTransformation());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The keyboard must not learn the password (the flag exists since API 26).
            input.setImeOptions(input.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            // A password manager must not offer to save the master password.
            view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.password_title, name))
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .create();
        secure(dialog);
        Runnable submit = () -> {
            String password = String.valueOf(input.getText());
            input.setText("");
            dialog.dismiss();
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

    private void showResult(String name, String password) {
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
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(getString(R.string.app_name), value);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PersistableBundle extras = new PersistableBundle();
            // Literal value of ClipDescription.EXTRA_IS_SENSITIVE, which only exists as a constant on API 33+.
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        Toast.makeText(this, getString(R.string.copied, CLIPBOARD_CLEAR_MS / 1000), Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> clearClipboardIfOurs(cm, value), CLIPBOARD_CLEAR_MS);
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
}
