package ru.local.bitrixtasks;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-sensitive local preferences for the form defaults. */
final class AppSettings {
    enum DeadlineMode { NONE, TOMORROW, DAYS }
    private final SharedPreferences prefs;
    AppSettings(Context context) { prefs=context.getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    DeadlineMode mode() {
        try { return DeadlineMode.valueOf(prefs.getString("deadline_mode", "NONE")); }
        catch (Exception ignored) { return DeadlineMode.NONE; }
    }
    int days() { return Math.max(1, Math.min(365, prefs.getInt("deadline_days", 1))); }
    int hour() { return Math.max(0, Math.min(23, prefs.getInt("deadline_hour", 15))); }
    int minute() { return Math.max(0, Math.min(59, prefs.getInt("deadline_minute", 0))); }
    void save(DeadlineMode mode, int days, int hour, int minute) {
        prefs.edit().putString("deadline_mode", mode.name()).putInt("deadline_days", days)
                .putInt("deadline_hour", hour).putInt("deadline_minute", minute).apply();
    }
}
