package net.d33.numpwd;

import android.content.Context;
import android.content.SharedPreferences;

/** User choices from the overflow menu. */
public final class AppSettings {
    private static final String PREFS = "settings";
    private static final String ALLOW_PASSWORD_MANAGERS = "allow_password_managers";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Off by default: a password manager would otherwise offer to store the master password. */
    public boolean allowPasswordManagers() {
        return prefs.getBoolean(ALLOW_PASSWORD_MANAGERS, false);
    }

    public void setAllowPasswordManagers(boolean allow) {
        prefs.edit().putBoolean(ALLOW_PASSWORD_MANAGERS, allow).apply();
    }
}
