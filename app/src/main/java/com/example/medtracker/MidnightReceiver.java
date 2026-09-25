package com.example.medtracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 零点闹钟接收器：切换为新的一天，并刷新正在显示中的页面。
 */
public class MidnightReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        MainActivity.rolloverIfNeeded(context);
        MainActivity.scheduleMidnightAlarm(context);
        MedWidgetProvider.updateAll(context);

        MainActivity activity = MainActivity.getInstance();
        if (activity != null) {
            activity.runOnUiThread(activity::refresh);
        }
    }
}
