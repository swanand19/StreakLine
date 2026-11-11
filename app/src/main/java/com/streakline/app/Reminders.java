package com.streakline.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Daily reminders, all on-device.
 * Each reminder time is a one-shot exact alarm that re-arms itself after it fires.
 * The notification lists the tasks still left today, read from the app's own state file.
 */
final class Reminders {

    static final String STATE_FILE = "state.json";
    private static final String PREFS = "reminder";
    private static final String CHANNEL = "daily";
    private static final int MAX_SLOTS = 4;
    private static final int NOTIF_ID = 1;

    private Reminders() {
    }

    /** times: comma-separated "HH:mm" values, e.g. "07:30,21:00". Empty turns reminders off. */
    static void setTimes(Context c, String times) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("times", times == null ? "" : times).apply();
        cancelAll(c);
        schedule(c);
    }

    static void schedule(Context c) {
        String[] times = times(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();

        for (int i = 0; i < times.length && i < MAX_SLOTS; i++) {
            int[] hm = parseTime(times[i]);
            if (hm == null) continue;
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hm[0]);
            cal.set(Calendar.MINUTE, hm[1]);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1);

            PendingIntent pi = alarmIntent(c, i);
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
        }
    }

    private static void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < MAX_SLOTS; i++) am.cancel(alarmIntent(c, i));
        // Alarm used by version 1.0 (single reminder, request code 0).
        am.cancel(PendingIntent.getBroadcast(c, 0, new Intent(c, ReminderReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private static String[] times(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("times", "");
        if (s == null || s.trim().isEmpty()) return new String[0];
        return s.split(",");
    }

    private static int[] parseTime(String t) {
        try {
            String[] p = t.trim().split(":");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[]{h, m};
        } catch (Exception e) {
            return null;
        }
    }

    private static PendingIntent alarmIntent(Context c, int slot) {
        Intent i = new Intent(c, ReminderReceiver.class);
        return PendingIntent.getBroadcast(c, 100 + slot, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean notificationsAllowed(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    /** Called when an alarm fires. force=true is the "test notification" button. */
    @SuppressWarnings("deprecation")
    static void show(Context c, boolean force) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Summary s = summarize(c);
        String title, text;
        if (s.total == 0) {
            if (!force) return;
            title = "Streakline";
            text = "Notifications are working. Nothing is scheduled for today.";
        } else if (s.left == 0) {
            if (!force) return; // everything done: stay quiet
            title = "All " + s.total + " tasks done today";
            text = "Rings closed. Notifications are working.";
        } else {
            title = s.left + (s.left == 1 ? " task left today" : " tasks left today");
            text = s.lines.isEmpty() ? "Open Streakline to log them." : join(s.lines, "\n");
        }

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Daily reminders", NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_HIGH);
            b.setDefaults(Notification.DEFAULT_ALL);
        }

        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String firstLine = text.contains("\n") ? text.substring(0, text.indexOf('\n')) : text;
        b.setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF6250D6)
                .setContentTitle(title)
                .setContentText(firstLine)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true);

        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (SecurityException ignored) {
            // Notification permission was declined.
        }
    }

    /* ---------- reading today's tasks from the saved state ---------- */

    private static final class Summary {
        int total, left;
        final List<String> lines = new ArrayList<>();
    }

    private static Summary summarize(Context c) {
        Summary s = new Summary();
        try {
            File f = new File(c.getFilesDir(), STATE_FILE);
            if (!f.exists()) return s;
            JSONObject root;
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                root = new JSONObject(out.toString("UTF-8"));
            }
            Calendar now = Calendar.getInstance();
            String today = ymd(now);
            int weekday = now.get(Calendar.DAY_OF_WEEK) - 1; // 0 = Sunday, like JavaScript
            JSONArray chs = root.optJSONArray("challenges");
            JSONObject logs = root.optJSONObject("logs");
            if (chs == null) return s;

            for (int i = 0; i < chs.length(); i++) {
                JSONObject ch = chs.optJSONObject(i);
                if (ch == null) continue;
                String start = ch.optString("start", "");
                int days = ch.optInt("days", 0);
                if (start.length() != 10 || days < 1) continue;
                String end = addDays(start, days - 1);
                if (today.compareTo(start) < 0 || today.compareTo(end) > 0) continue;

                JSONObject dayLog = null;
                if (logs != null) {
                    JSONObject cl = logs.optJSONObject(ch.optString("id"));
                    if (cl != null) {
                        JSONObject e = cl.optJSONObject(today);
                        if (e != null) dayLog = e.optJSONObject("t");
                    }
                }
                List<String> pending = new ArrayList<>();
                JSONArray tasks = ch.optJSONArray("tasks");
                if (tasks == null) continue;
                for (int k = 0; k < tasks.length(); k++) {
                    JSONObject t = tasks.optJSONObject(k);
                    if (t == null || !scheduledOn(t, weekday)) continue;
                    s.total++;
                    String tid = t.optString("id");
                    boolean done;
                    if ("count".equals(t.optString("type"))) {
                        double target = t.optDouble("target", 1);
                        double v = dayLog == null ? 0 : dayLog.optDouble(tid, 0);
                        if (Double.isNaN(v)) v = 0;
                        done = v >= target;
                        if (!done) {
                            String unit = t.optString("unit", "");
                            pending.add(t.optString("name") + " (" + fmtNum(v) + "/" + fmtNum(target)
                                    + (unit.isEmpty() ? "" : " " + unit) + ")");
                        }
                    } else {
                        done = dayLog != null && dayLog.optBoolean(tid, false);
                        if (!done) pending.add(t.optString("name"));
                    }
                }
                if (!pending.isEmpty()) {
                    s.left += pending.size();
                    int dayNo = daysBetween(start, today) + 1;
                    s.lines.add(ch.optString("name") + ", day " + dayNo + ": " + join(pending, ", "));
                }
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    private static boolean scheduledOn(JSONObject t, int weekday) {
        JSONArray wd = t.optJSONArray("wd");
        if (wd == null || wd.length() == 0) return true;
        for (int i = 0; i < wd.length(); i++) if (wd.optInt(i, -1) == weekday) return true;
        return false;
    }

    private static String ymd(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static Calendar parse(String s) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1,
                Integer.parseInt(s.substring(8, 10)), 12, 0, 0);
        return c;
    }

    private static String addDays(String s, int n) {
        Calendar c = parse(s);
        c.add(Calendar.DAY_OF_YEAR, n);
        return ymd(c);
    }

    private static int daysBetween(String a, String b) {
        long ms = parse(b).getTimeInMillis() - parse(a).getTimeInMillis();
        return (int) Math.round(ms / 86400000.0);
    }

    private static String fmtNum(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.1f", v);
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
