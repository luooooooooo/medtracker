package com.example.medtracker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.CalendarContract;
import android.database.Cursor;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 打卡提醒管理：
 * - 每个槽位独立存储"提醒开关 + 小时/分钟"，默认关闭；
 * - 到点通过 ReminderReceiver 发通知，并自动重排下一天（每天重复）；
 * - 同时把提醒写入系统日历（RRULE=FREQ=DAILY + 到点提醒），
 *   授予 WRITE_CALENDAR 权限后生效；
 * - 设备重启后由 BootReceiver 统一重排。
 */
public final class ReminderManager {

    private static final String PREFS = "med_tracker";
    public static final String ACTION_REMIND = "com.example.medtracker.REMIND";
    public static final String EXTRA_SLOT = "slot";
    private static final String CHANNEL_ID = "checkin_reminder";

    private ReminderManager() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 状态存取 ----------

    static boolean isEnabled(Context c, int slot) {
        return prefs(c).getBoolean("slot_remind_enabled_" + slot, false);
    }

    static int getHour(Context c, int slot) {
        return prefs(c).getInt("slot_remind_hour_" + slot, 8);
    }

    static int getMinute(Context c, int slot) {
        return prefs(c).getInt("slot_remind_min_" + slot, 0);
    }

    /** 设置提醒并持久化。 */
    static void setReminder(Context c, int slot, int hour, int minute) {
        prefs(c).edit()
                .putBoolean("slot_remind_enabled_" + slot, true)
                .putInt("slot_remind_hour_" + slot, hour)
                .putInt("slot_remind_min_" + slot, minute)
                .apply();
    }

    /** 清除提醒并持久化。 */
    static void clearReminder(Context c, int slot) {
        prefs(c).edit()
                .putBoolean("slot_remind_enabled_" + slot, false)
                .remove("slot_remind_hour_" + slot)
                .remove("slot_remind_min_" + slot)
                .apply();
    }

    // ---------- 闹钟调度 ----------

    /** 重排全部已开启的提醒（启动/零点/开机时调用）。 */
    static void scheduleAll(Context c) {
        for (int i = 0; i < MainActivity.MAX_SLOTS; i++) {
            if (isEnabled(c, i)) {
                scheduleSlot(c, i);
            }
        }
    }

    /** 为槽位设置下一次触发（今天已过则明天同一时间）。 */
    static void scheduleSlot(Context c, int slot) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, getHour(c, slot));
        cal.set(Calendar.MINUTE, getMinute(c, slot));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    cal.getTimeInMillis(), remindPendingIntent(c, slot));
        } catch (SecurityException ignored) {
            // 用户关闭了精确闹钟权限：退回普通 set（系统会自行调度）
            am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(),
                    remindPendingIntent(c, slot));
        }
    }

    /** 取消槽位闹钟。 */
    static void cancelSlot(Context c, int slot) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(remindPendingIntent(c, slot));
        }
    }

    /** 提醒触发后：安排下一天（每天重复）。 */
    static void rescheduleAfterFire(Context c, int slot) {
        if (isEnabled(c, slot)) {
            scheduleSlot(c, slot);
        }
    }

    private static PendingIntent remindPendingIntent(Context c, int slot) {
        Intent it = new Intent(c, ReminderReceiver.class)
                .setAction(ACTION_REMIND)
                .putExtra(EXTRA_SLOT, slot);
        return PendingIntent.getBroadcast(c, 500 + slot, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- 系统日历 ----------

    /** 该槽位在系统日历中的事件标题（按槽位独立，互不覆盖）。 */
    static String calendarEventTitle(Context c, int slot) {
        return c.getString(R.string.cal_event_title) + " · " + slot;
    }

    /** 把槽位提醒写入系统日历（每天重复，到点提醒）。返回是否成功。 */
    static boolean writeCalendarEvent(Context c, int slot, int hour, int minute) {
        ContentResolver cr = c.getContentResolver();
        try {
            long calId = findWritableCalendarId(cr);
            if (calId < 0) {
                return false;
            }
            String title = calendarEventTitle(c, slot);
            // 只删除该槽位自己的旧提醒事件，避免误删其他卡片
            cr.delete(CalendarContract.Events.CONTENT_URI,
                    CalendarContract.Events.TITLE + "=?", new String[]{title});

            // 若今天该时间已过，事件从明天开始（与闹钟逻辑一致）
            Calendar start = Calendar.getInstance();
            start.set(Calendar.HOUR_OF_DAY, hour);
            start.set(Calendar.MINUTE, minute);
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);
            if (start.getTimeInMillis() <= System.currentTimeMillis()) {
                start.add(Calendar.DAY_OF_YEAR, 1);
            }
            long startMs = start.getTimeInMillis();
            long endMs = startMs + 5 * 60 * 1000L;

            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, calId);
            v.put(CalendarContract.Events.TITLE, title);
            v.put(CalendarContract.Events.DESCRIPTION,
                    MainActivity.getSlotTitle(c, slot));
            v.put(CalendarContract.Events.DTSTART, startMs);
            v.put(CalendarContract.Events.DTEND, endMs);
            v.put(CalendarContract.Events.EVENT_TIMEZONE,
                    TimeZone.getDefault().getID());
            v.put(CalendarContract.Events.RRULE, "FREQ=DAILY");
            android.net.Uri uri = cr.insert(CalendarContract.Events.CONTENT_URI, v);
            if (uri == null) {
                return false;
            }
            long eventId = ContentUris.parseId(uri);
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, eventId);
            r.put(CalendarContract.Reminders.METHOD,
                    CalendarContract.Reminders.METHOD_ALERT);
            r.put(CalendarContract.Reminders.MINUTES, 0);
            cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 删除指定槽位写入系统日历的提醒事件。 */
    static void deleteCalendarEvent(Context c, int slot) {
        try {
            c.getContentResolver().delete(CalendarContract.Events.CONTENT_URI,
                    CalendarContract.Events.TITLE + "=?",
                    new String[]{calendarEventTitle(c, slot)});
        } catch (Exception ignored) {
        }
    }

    /** 日历诊断：返回手机上日历数据的真实情况，用于排查"找不到日历"。 */
    static String diagnoseCalendars(Context c) {
        ContentResolver cr = c.getContentResolver();
        StringBuilder sb = new StringBuilder();
        // 全部日历
        try (Cursor cur = cr.query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                        CalendarContract.Calendars.VISIBLE},
                null, null, null)) {
            int n = cur == null ? -1 : cur.getCount();
            sb.append("全部日历数=").append(n);
            if (cur != null && cur.moveToFirst()) {
                sb.append("，首个ID=").append(cur.getLong(0));
            }
        } catch (Exception e) {
            sb.append("全部查询异常:").append(e.getClass().getSimpleName());
        }
        // 可见日历
        try (Cursor cur = cr.query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID},
                CalendarContract.Calendars.VISIBLE + " = 1", null, null)) {
            sb.append("，可见数=").append(cur == null ? -1 : cur.getCount());
        } catch (Exception e) {
            sb.append("，可见查询异常:").append(e.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** 找一个可写日历；找不到返回 -1。 */
    private static long findWritableCalendarId(ContentResolver cr) {
        String[] cols = new String[]{CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL};
        // 第一遍：可见且可写
        try (Cursor cur = cr.query(CalendarContract.Calendars.CONTENT_URI,
                cols, CalendarContract.Calendars.VISIBLE + " = 1", null, null)) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    long id = cur.getLong(0);
                    int level = cur.getInt(1);
                    if (level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                        return id;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 第二遍：任意可见日历（部分 ROM 访问级别字段异常，放宽兜底）
        try (Cursor cur = cr.query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID},
                CalendarContract.Calendars.VISIBLE + " = 1", null, null)) {
            if (cur != null && cur.moveToFirst()) {
                return cur.getLong(0);
            }
        } catch (Exception ignored) {
        }
        // 第三遍：任意日历，不限可见性（OPPO/ColorOS 等 ROM 的日历
        // 常因同步未开启而 visible=0，此时仍可写入）
        try (Cursor cur = cr.query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                return cur.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** 通知渠道（首次使用时创建）。 */
    static void ensureChannel(Context c) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    CHANNEL_ID, c.getString(R.string.channel_reminder),
                    android.app.NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(c.getString(R.string.channel_reminder));
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    static String channelId() {
        return CHANNEL_ID;
    }
}
