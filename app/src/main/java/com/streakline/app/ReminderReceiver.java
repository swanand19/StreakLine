package com.streakline.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Reminders.show(context, false);
        // One-shot alarms: arm the next occurrence of every reminder time.
        Reminders.schedule(context);
    }
}
