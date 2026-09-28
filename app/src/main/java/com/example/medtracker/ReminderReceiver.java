package com.example.medtracker;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * 打卡提醒接收器：到点发通知，并安排下一天的提醒（每天重复）。
 */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ReminderManager.ACTION_REMIND.equals(intent.getAction())) {
            return;
        }
        int slot = intent.getIntExtra(ReminderManager.EXTRA_SLOT, -1);
        if (slot < 0 || slot >= MainActivity.MAX_SLOTS) {
            return;
        }
        // 通知
        ReminderManager.ensureChannel(context);
        String title = context.getString(R.string.reminder_notify_title,
                MainActivity.getSlotTitle(context, slot));
        String text = context.getString(R.string.reminder_notify_text,
                MainActivity.getSlotTitle(context, slot),
                MainActivity.getSlotTime(context, slot));
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 600 + slot, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder nb = new NotificationCompat.Builder(context,
                ReminderManager.channelId())
                .setSmallIcon(R.drawable.ic_morning)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(context).notify(700 + slot, nb.build());
        } catch (SecurityException ignored) {
            // 通知权限被关闭：静默跳过
        }

        // 重排下一天
        ReminderManager.rescheduleAfterFire(context, slot);
    }
}
