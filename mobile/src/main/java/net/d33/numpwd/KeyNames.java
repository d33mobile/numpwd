package net.d33.numpwd;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Ordered list of key names. Only names are stored: they are the salt, not a secret. */
public final class KeyNames {
    private static final String PREFS = "key_names";
    private static final String KEY_LIST = "names";

    private final SharedPreferences prefs;

    public KeyNames(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<String> load() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_LIST, "[]"));
            for (int i = 0; i < array.length(); i++)
                out.add(array.getString(i));
        } catch (JSONException e) {
            throw new IllegalStateException("corrupt key list", e);
        }
        return out;
    }

    public void save(List<String> names) {
        prefs.edit().putString(KEY_LIST, new JSONArray(names).toString()).apply();
    }

    public static boolean isValidName(String name) {
        return !name.isEmpty() && name.indexOf('\n') < 0 && name.indexOf('\r') < 0 && name.indexOf('\0') < 0;
    }
}
