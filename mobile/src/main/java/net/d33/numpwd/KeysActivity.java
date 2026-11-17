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
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
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

import org.fedorahosted.freeotp.R;
import org.fedorahosted.freeotp.utils.SystemBarInsets;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists key names; tapping one asks for a password and shows the derived digits. */
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
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.password_title, name))
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .create();
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
        result.setText(R.string.computing);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.result_title, name))
                .setView(view)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.copy, null)
                .create();
        final String[] digits = {null};
        dialog.setOnShowListener(d -> {
            View copy = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            copy.setEnabled(false);
            copy.setOnClickListener(v -> copyToClipboard(digits[0]));
        });
        Runnable autoDismiss = dialog::dismiss;
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacks(autoDismiss);
            result.setText("");
        });
        dialog.show();
        handler.postDelayed(autoDismiss, RESULT_VISIBLE_MS);

        worker.execute(() -> {
            String text;
            boolean ok;
            try {
                text = NumericPassword.derive(name, password);
                ok = true;
            } catch (OpensslShaCrypt.InvalidInputException e) {
                text = getString(R.string.result_error, e.getMessage());
                ok = false;
            }
            final String shown = ok ? group(text) : text;
            final String value = ok ? text : null;
            handler.post(() -> {
                if (!dialog.isShowing())
                    return;
                digits[0] = value;
                result.setText(shown);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(value != null);
            });
        });
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

    private void showWithKeyboard(AlertDialog dialog, View input) {
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        input.requestFocus();
    }
}
