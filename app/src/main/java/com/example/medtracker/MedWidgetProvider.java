package com.example.medtracker;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * 桌面小部件：显示当天日期、进度与各时段打卡卡片，点击卡片直接打卡。
 * 状态与主页共用 SharedPreferences，打卡/改次数/零点后同步刷新。
 */
public class MedWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_TOGGLE = "com.example.medtracker.WIDGET_TOGGLE";
    public static final String EXTRA_SLOT = "widget_slot";

    private static final int ON_SURFACE = 0xFF333838;
    private static final int ON_SURFACE_VARIANT = 0xFF707B78;
    private static final int WHITE = 0xFFFFFFFF;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            int slot = intent.getIntExtra(EXTRA_SLOT, -1);
            if (slot >= 0) {
                MainActivity.rolloverIfNeeded(context);
                boolean taken = MainActivity.isTaken(context, slot);
                MainActivity.setTaken(context, slot, !taken);
                if (MainActivity.allDone(context)) {
                    MainActivity.recordStreak(context);
                }
            }
            updateAll(context);
            // 主页若在前台则同步刷新
            MainActivity act = MainActivity.getInstance();
            if (act != null) {
                act.runOnUiThread(act::refresh);
            }
            return;
        }
        super.onReceive(context, intent);
    }

    /** 重建所有小部件实例。 */
    public static void updateAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, MedWidgetProvider.class));
        for (int id : ids) {
            mgr.updateAppWidget(id, buildViews(context));
        }
    }

    /** 按当前日期/次数/打卡状态构建小部件视图。 */
    private static RemoteViews buildViews(Context context) {
        MainActivity.rolloverIfNeeded(context);

        RemoteViews root = new RemoteViews(context.getPackageName(), R.layout.widget_medication);
        int primary = ThemeUtil.THEME_COLORS[ThemeUtil.currentThemeIndex(context)];

        String date = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
                .format(Calendar.getInstance().getTime());
        root.setTextViewText(R.id.wDate, date);

        int count = MainActivity.getSlotCount(context);
        int done = 0;
        for (int i = 0; i < count; i++) {
            if (MainActivity.isTaken(context, i)) done++;
        }
        root.setTextViewText(R.id.wProgress, done + " / " + count);
        root.setTextColor(R.id.wProgress, done == count ? primary : ON_SURFACE_VARIANT);

        for (int i = 0; i < count; i++) {
            root.addView(R.id.wContainer, buildRow(context, i, count, primary));
        }
        return root;
    }

    private static RemoteViews buildRow(Context context, int slot, int count, int primary) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_slot_row);
        boolean taken = MainActivity.isTaken(context, slot);

        row.setTextViewText(R.id.wTitle, MainActivity.slotName(context, slot, count));
        if (taken) {
            row.setImageViewResource(R.id.wIcon, R.drawable.ic_check);
            row.setInt(R.id.wIcon, "setColorFilter", primary);
            row.setTextColor(R.id.wTitle, primary);
            row.setTextViewText(R.id.wState, "已打卡");
            row.setTextColor(R.id.wState, primary);
        } else {
            row.setImageViewResource(R.id.wIcon, R.drawable.ic_circle_outline);
            row.setInt(R.id.wIcon, "setColorFilter", primary);
            row.setTextColor(R.id.wTitle, ON_SURFACE);
            row.setTextViewText(R.id.wState, "");
        }

        Intent toggle = new Intent(context, MedWidgetProvider.class)
                .setAction(ACTION_TOGGLE)
                .putExtra(EXTRA_SLOT, slot);
        PendingIntent pi = PendingIntent.getBroadcast(context, 1000 + slot, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        row.setOnClickPendingIntent(R.id.wRow, pi);
        return row;
    }
}
