package com.example.medtracker;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * 桌面小部件：固定 2行x3列 时段卡片，按当天次数显示前 N 个槽位。
 * 状态与主页共用 SharedPreferences，点击卡片直接打卡。
 * 注意：禁止动态 addView（RemoteViews 刷新时会重复追加导致卡片叠加），
 * 一律用固定槽位 + setVisibility，每次刷新完整重绘全部槽位。
 */
public class MedWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_TOGGLE = "com.example.medtracker.WIDGET_TOGGLE";
    public static final String EXTRA_SLOT = "widget_slot";

    private static final int ON_SURFACE = 0xFF333838;
    private static final int MAX_SLOTS = 6;

    // 固定 6 个槽位对应的视图 id（与 widget_medication.xml 一一对应）
    private static final int[] SLOT_IDS = {
            R.id.wSlot0, R.id.wSlot1, R.id.wSlot2, R.id.wSlot3, R.id.wSlot4, R.id.wSlot5
    };
    private static final int[] ICON_IDS = {
            R.id.wIcon0, R.id.wIcon1, R.id.wIcon2, R.id.wIcon3, R.id.wIcon4, R.id.wIcon5
    };
    private static final int[] NAME_IDS = {
            R.id.wName0, R.id.wName1, R.id.wName2, R.id.wName3, R.id.wName4, R.id.wName5
    };

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

    /** 按当前日期/次数/打卡状态构建小部件视图（固定槽位，完整重绘）。 */
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
        root.setTextColor(R.id.wProgress, done == count ? primary : 0xFF707B78);

        // 完整重绘全部 6 个槽位：前 count 个显示并绑定状态，其余隐藏
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (i >= count) {
                root.setViewVisibility(SLOT_IDS[i], View.GONE);
                continue;
            }
            root.setViewVisibility(SLOT_IDS[i], View.VISIBLE);
            boolean taken = MainActivity.isTaken(context, i);

            root.setTextViewText(NAME_IDS[i], MainActivity.slotName(context, i, count));
            if (taken) {
                root.setImageViewResource(ICON_IDS[i], R.drawable.ic_check);
                root.setInt(ICON_IDS[i], "setColorFilter", primary);
                root.setTextColor(NAME_IDS[i], primary);
            } else {
                root.setImageViewResource(ICON_IDS[i], R.drawable.ic_circle_outline);
                root.setInt(ICON_IDS[i], "setColorFilter", primary);
                root.setTextColor(NAME_IDS[i], ON_SURFACE);
            }

            Intent toggle = new Intent(context, MedWidgetProvider.class)
                    .setAction(ACTION_TOGGLE)
                    .putExtra(EXTRA_SLOT, i);
            PendingIntent pi = PendingIntent.getBroadcast(context, 1000 + i, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            root.setOnClickPendingIntent(SLOT_IDS[i], pi);
        }
        return root;
    }
}
