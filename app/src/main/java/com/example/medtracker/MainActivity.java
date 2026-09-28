package com.example.medtracker;

import android.animation.ObjectAnimator;
import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "med_tracker";
    private static final String KEY_DATE = "current_date";
    private static final String KEY_MORNING = "morning_taken";
    private static final String KEY_NOON = "noon_taken";
    private static final String KEY_EVENING = "evening_taken";
    private static final String KEY_STREAK = "streak";
    private static final String KEY_LAST_DONE = "last_done_date";
    private static final String KEY_HEADER = "header_path";
    private static final String KEY_SLOT_COUNT = "slot_count";
    private static final String KEY_MAIN_TITLE = "main_title";
    private static final String KEY_HINT_TEXT = "hint_text";
    private static final String ACTION_MIDNIGHT = "com.example.medtracker.MIDNIGHT";

    static final int MAX_SLOTS = 6;
    private static final String[] SLOT_KEYS = {KEY_MORNING, KEY_NOON, KEY_EVENING};
    private static final String HEADER_CROP_FILE = "header_crop.jpg";

    // 打卡卡片图标：统一为同一个"小太阳"（无论 1 次还是 6 次打卡，图标一致）
    private static final int[] SLOT_ICONS = {
            R.drawable.ic_morning, R.drawable.ic_morning, R.drawable.ic_morning,
            R.drawable.ic_morning, R.drawable.ic_morning, R.drawable.ic_morning
    };
    private static final int[] SLOT_TITLES = {
            R.string.slot_morning_title, R.string.slot_noon_title, R.string.slot_afternoon_title,
            R.string.slot_evening_title, R.string.slot_night_title, R.string.slot_late_title
    };

    @Nullable
    private static MainActivity instance;

    private LinearLayout rowContainer;
    private TextView dateText;
    private TextView streakText;
    private TextView progressText;
    private TextView progressFraction;
    private LinearProgressIndicator progressBar;
    private ImageView headerImage;
    private MaterialCardView headerCard;
    private TextView headerTitle;
    private TextView hintText;
    private ConfettiView confetti;
    private final View[] rowViews = new View[MAX_SLOTS];

    /** 每天打卡次数对应的时段预设下标（保持时间顺序，且 3 次=早/午/晚）。 */
    static int[] slotOrder(int count) {
        switch (count) {
            case 1:
                return new int[]{0};
            case 2:
                return new int[]{0, 3};
            case 3:
                return new int[]{0, 1, 3};
            case 4:
                return new int[]{0, 1, 2, 3};
            case 5:
                return new int[]{0, 1, 2, 3, 4};
            default:
                return new int[]{0, 1, 2, 3, 4, 5};
        }
    }

    /** 某次记录位置对应的时段名称（统计页/小部件按记录长度推断，读取用户自定义文字）。 */
    static String slotName(Context c, int position, int recordLength) {
        int[] order = slotOrder(recordLength);
        if (position < 0 || position >= order.length) {
            return c.getString(SLOT_TITLES[0]);
        }
        return getSlotTitle(c, order[position]);
    }

    // ---------- 自定义文字（通用打卡） ----------

    /** 顶部大标题（默认与应用名一致）。 */
    static String getMainTitle(Context c) {
        String v = prefs(c).getString(KEY_MAIN_TITLE, null);
        return v == null || v.trim().isEmpty() ? c.getString(R.string.header_greeting) : v.trim();
    }

    /** 底部提示语（默认通用文案）。 */
    static String getHintText(Context c) {
        String v = prefs(c).getString(KEY_HINT_TEXT, null);
        return v == null || v.trim().isEmpty() ? c.getString(R.string.hint_note) : v.trim();
    }

    /** 第 slotIdx 个时段（0~5）的卡片标题（读用户自定义，缺省用通用默认）。 */
    static String getSlotTitle(Context c, int slotIdx) {
        String v = prefs(c).getString("slot_title_" + slotIdx, null);
        return v == null || v.trim().isEmpty()
                ? c.getString(SLOT_TITLES[slotIdx]) : v.trim();
    }

    /** 时段前缀（与卡片时间联动显示）。 */
    private static final String[] PERIOD_PREFIX = {"上午", "中午", "下午", "晚上", "睡前", "深夜"};

    /** 卡片时间行：由提醒时间决定（带时段前缀）；未设置提醒时返回 null（页面不显示时间）。 */
    @Nullable
    static String getSlotTime(Context c, int slotIdx) {
        if (slotIdx < 0 || slotIdx >= MAX_SLOTS || !ReminderManager.isEnabled(c, slotIdx)) {
            return null;
        }
        return PERIOD_PREFIX[slotIdx] + " "
                + String.format(Locale.CHINA, "%02d:%02d",
                ReminderManager.getHour(c, slotIdx),
                ReminderManager.getMinute(c, slotIdx));
    }

    /** 保存单个卡片的标题文字（长按单卡编辑），随后刷新小部件。 */
    static void saveSlotTitle(Context c, int slotIdx, String title) {
        prefs(c).edit()
                .putString("slot_title_" + slotIdx,
                        title == null ? "" : title.trim())
                .apply();
        MedWidgetProvider.updateAll(c);
    }

    /** 保存顶部大标题（长按标题直编），随后刷新小部件。 */
    static void saveMainTitle(Context c, String title) {
        prefs(c).edit()
                .putString(KEY_MAIN_TITLE, title == null ? "" : title.trim())
                .apply();
        MedWidgetProvider.updateAll(c);
    }

    /** 保存底部标语（长按标语直编），随后刷新小部件。 */
    static void saveHintText(Context c, String hint) {
        prefs(c).edit()
                .putString(KEY_HINT_TEXT, hint == null ? "" : hint.trim())
                .apply();
        MedWidgetProvider.updateAll(c);
    }

    /** 当前正在裁剪填充的自定义槽位。 */
    private int pendingIconSlot = -1;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickHeaderLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    startCrop(uri);
                }
            });

    /** 自定义图标：相册选图（对应待填充槽位）。 */
    private final ActivityResultLauncher<PickVisualMediaRequest> pickIconPhotoLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    startCropForIcon(uri, pendingIconSlot);
                }
            });

    /** 自定义图标：1:1 裁剪结果回调。 */
    private final ActivityResultLauncher<Intent> cropIconLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && pendingIconSlot >= 0) {
                    int slot = pendingIconSlot;
                    IconManager.finalizeCustomIcon(this, slot);
                    IconManager.setCustomActive(this, slot);
                    // 立即请求系统添加到桌面（系统弹确认框）
                    IconManager.requestPinNow(this, slot);
                    Toast.makeText(this,
                            R.string.icon_custom_set, Toast.LENGTH_LONG).show();
                }
            });

    /** 当前正在设置提醒的槽位。 */
    private int pendingReminderSlot = -1;

    /** 提醒权限请求结果回调（日历 + 通知）。 */
    private final ActivityResultLauncher<String[]> reminderPermsLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                        if (pendingReminderSlot >= 0) {
                            applyReminderAfterPermission(pendingReminderSlot);
                        }
                    });

    @Nullable
    public static MainActivity getInstance() {
        return instance;
    }

    // ---------- 静态：偏好存储与日期/连续天数逻辑 ----------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String todayString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
    }

    private static String yesterdayString() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -1);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    /** 每天打卡次数（1-6，默认 3）。 */
    static int getSlotCount(Context c) {
        int n = prefs(c).getInt(KEY_SLOT_COUNT, 3);
        if (n < 1 || n > MAX_SLOTS) {
            return 3;
        }
        return n;
    }

    /** 修改每天打卡次数：同步当天记录后生效，并刷新桌面小部件。 */
    static void setSlotCount(Context c, int count) {
        if (count < 1 || count > MAX_SLOTS) {
            return;
        }
        prefs(c).edit().putInt(KEY_SLOT_COUNT, count).apply();
        persistDay(c, todayString());
        MedWidgetProvider.updateAll(c);
    }

    /** 若本地日期与今天不一致，则清空打卡状态并写入今天，实现“每天零点进入新的一天”。 */
    static void rolloverIfNeeded(Context c) {
        SharedPreferences sp = prefs(c);
        String today = todayString();
        String stored = sp.getString(KEY_DATE, "");
        if (!today.equals(stored)) {
            // 切到新的一天前，先把前一天的状态补进历史记录（防止记录丢失）
            if (stored.length() == 10) {
                persistDay(c, stored);
            }
            SharedPreferences.Editor ed = sp.edit();
            ed.putString(KEY_DATE, today);
            for (int i = 0; i < MAX_SLOTS; i++) {
                ed.putBoolean(slotKey(i), false);
            }
            ed.apply();
        }
    }

    /** 第 i 张卡片的存储键（前 3 位兼容旧版本键名）。 */
    private static String slotKey(int slot) {
        if (slot < SLOT_KEYS.length) {
            return SLOT_KEYS[slot];
        }
        return "slot_" + slot + "_taken";
    }

    static boolean isTaken(Context c, int slot) {
        return prefs(c).getBoolean(slotKey(slot), false);
    }

    static void setTaken(Context c, int slot, boolean taken) {
        prefs(c).edit().putBoolean(slotKey(slot), taken).apply();
        persistDay(c, todayString());
    }

    /** 今日所有时段是否全部完成（静态版，供桌面小部件等使用）。 */
    static boolean allDone(Context c) {
        for (int i = 0; i < getSlotCount(c); i++) {
            if (!isTaken(c, i)) return false;
        }
        return true;
    }

    /** 把当天各时段打卡状态写入历史记录，供日历统计使用。格式：N 位字符（每时段，1=已完成）。 */
    private static void persistDay(Context c, String date) {
        StringBuilder sb = new StringBuilder();
        int count = getSlotCount(c);
        for (int i = 0; i < count; i++) {
            sb.append(isTaken(c, i) ? '1' : '0');
        }
        prefs(c).edit().putString("day_" + date, sb.toString()).apply();
    }

    /** 当天完成次数（0-3）。 */
    public static int getDayTakenCount(Context c, String date) {
        String rec = prefs(c).getString("day_" + date, null);
        if (rec == null) return 0;
        int n = 0;
        for (char ch : rec.toCharArray()) {
            if (ch == '1') n++;
        }
        return n;
    }

    /** 当天记录原文，无记录返回 null。 */
    @Nullable
    public static String getDayRecord(Context c, String date) {
        return prefs(c).getString("day_" + date, null);
    }

    /** 全部历史记录：日期 -> 三位状态串。 */
    public static Map<String, String> getAllDayRecords(Context c) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            if (e.getKey().startsWith("day_") && e.getValue() instanceof String) {
                out.put(e.getKey().substring(4), (String) e.getValue());
            }
        }
        return out;
    }

    /** 连续打卡天数。 */
    public static int getStreak(Context c) {
        return prefs(c).getInt(KEY_STREAK, 0);
    }

    /** 今日三时段全部完成时调用，累计连续打卡天数。 */
    static void recordStreak(Context c) {
        SharedPreferences sp = prefs(c);
        String today = todayString();
        if (today.equals(sp.getString(KEY_LAST_DONE, ""))) return; // 今天已记录过
        int streak = sp.getInt(KEY_STREAK, 0);
        boolean yesterdayDone = yesterdayString().equals(sp.getString(KEY_LAST_DONE, ""));
        streak = yesterdayDone ? streak + 1 : 1;
        sp.edit()
                .putInt(KEY_STREAK, streak)
                .putString(KEY_LAST_DONE, today)
                .apply();
    }

    /** 在每天零点设置一次性闹钟，触发 MidnightReceiver 刷新页面。 */
    static void scheduleMidnightAlarm(Context c) {
        Calendar next = Calendar.getInstance();
        next.add(Calendar.DAY_OF_YEAR, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        Intent intent = new Intent(c, MidnightReceiver.class);
        intent.setAction(ACTION_MIDNIGHT);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        long triggerAt = next.getTimeInMillis();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                // 无精确闹钟权限时退化为宽限窗口（5 分钟），onResume 的日期校验仍保证正确性
                am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 5 * 60 * 1000, pi);
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    // ---------- 界面 ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeUtil.currentThemeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;

        // 恢复用户持久化的桌面图标选择（覆盖安装后仍保持）
        IconManager.applyPersisted(this);
        // 若用户设置了自定义生效图标，在本次启动时请求系统添加到桌面
        IconManager.applyPendingCustom(this);

        // 打卡提醒：确保通知渠道存在，并重排全部已开启的提醒
        ReminderManager.ensureChannel(this);
        ReminderManager.scheduleAll(this);

        rowContainer = findViewById(R.id.rowContainer);
        dateText = findViewById(R.id.dateText);
        streakText = findViewById(R.id.streakText);
        progressText = findViewById(R.id.progressText);
        progressFraction = findViewById(R.id.progressFraction);
        progressBar = findViewById(R.id.progressBar);
        headerImage = findViewById(R.id.headerImage);
        headerCard = findViewById(R.id.headerCard);
        headerTitle = findViewById(R.id.headerTitle);
        hintText = findViewById(R.id.hintText);
        confetti = findViewById(R.id.confetti);
        findViewById(R.id.btnStats).setOnClickListener(v ->
                startActivity(new Intent(this, StatsActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsSheet());

        // 单击头图：只播放放大回弹动效，不更换图片；
        // 长按头图：更换图片（相册选图 → 裁剪）。
        // 点击日期/进度/已完成、图片周围空白区域均不响应
        headerImage.setOnClickListener(v -> bounceHeader());
        headerImage.setOnLongClickListener(v -> {
            pickHeaderPhoto();
            return true;
        });

        // 大标题与标语：长按直接编辑该文字
        headerTitle.setOnLongClickListener(v -> {
            showEditMainTitle();
            return true;
        });
        hintText.setOnLongClickListener(v -> {
            showEditHintText();
            return true;
        });

        buildRows();
        loadHeaderImage();
        scheduleMidnightAlarm(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
    }

    // ---------- 个性化：头图 + 主题 ----------

    private void pickHeaderPhoto() {
        pickHeaderLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    /** 选图后进入裁剪界面，裁剪比例按头图区域实际宽高比计算。 */
    private void startCrop(Uri sourceUri) {
        float density = getResources().getDisplayMetrics().density;
        float screenWidthDp = getResources().getDisplayMetrics().widthPixels / density;
        double aspect = (screenWidthDp - 68.0) / 150.0; // 头图区域比例（页面内边距 40 + 卡片内边距 28）

        File dest = new File(getFilesDir(), HEADER_CROP_FILE);
        if (dest.exists()) dest.delete();

        Intent i = new Intent(this, CropActivity.class);
        i.putExtra(CropActivity.EXTRA_SOURCE, sourceUri.toString());
        i.putExtra(CropActivity.EXTRA_OUTPUT, dest.getAbsolutePath());
        i.putExtra(CropActivity.EXTRA_ASPECT, aspect);
        startActivityForResult(i, CropActivity.REQUEST_CROP);
    }

    /** 裁剪完成：记录路径并刷新头图。 */
    private void applyHeaderCrop() {
        File f = new File(getFilesDir(), HEADER_CROP_FILE);
        if (!f.exists()) return;
        prefs(this).edit().putString(KEY_HEADER, f.getAbsolutePath()).apply();
        loadHeaderImage();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == CropActivity.REQUEST_CROP) {
            applyHeaderCrop();
        }
    }

    /** 按设置显示头图：自定义照片（铺满）或默认小精灵（居中）。 */
    private void loadHeaderImage() {
        String path = prefs(this).getString(KEY_HEADER, "");
        File f = path == null ? null : new File(path);
        if (f != null && f.exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp != null) {
                headerImage.setImageBitmap(bmp);
                headerImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                return;
            }
        }
        headerImage.setImageResource(R.drawable.mascot);
        headerImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
    }

    private void resetHeaderImage() {
        String path = prefs(this).getString(KEY_HEADER, "");
        if (path != null) {
            File f = new File(path);
            if (f.exists()) f.delete();
        }
        prefs(this).edit().remove(KEY_HEADER).apply();
        loadHeaderImage();
    }

    /** 个性化弹窗：头图（相册/恢复默认）+ 主题色板。 */
    private void showSettingsSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_settings, null, false);
        dialog.setContentView(sheet);

        sheet.findViewById(R.id.btnPickPhoto).setOnClickListener(v -> {
            dialog.dismiss();
            pickHeaderPhoto();
        });
        sheet.findViewById(R.id.btnResetHeader).setOnClickListener(v -> {
            resetHeaderImage();
            dialog.dismiss();
        });

        LinearLayout themeContainer = sheet.findViewById(R.id.themeContainer);
        buildThemeSwatches(themeContainer, dialog);

        LinearLayout iconContainer = sheet.findViewById(R.id.iconContainer);
        buildIconSwatches(iconContainer, dialog);

        LinearLayout slotContainer = sheet.findViewById(R.id.slotContainer);
        buildSlotSwatches(slotContainer, dialog);

        TextView aboutAuthor = sheet.findViewById(R.id.aboutAuthor);
        aboutAuthor.setText(getString(R.string.about_author_format, BuildConfig.VERSION_NAME));

        dialog.show();
    }

    /** 长按大标题：编辑大标题文字。 */
    private void showEditMainTitle() {
        View v = LayoutInflater.from(this)
                .inflate(R.layout.dialog_slot_title, null, false);
        com.google.android.material.textfield.TextInputEditText et =
                v.findViewById(R.id.etSlotTitle);
        et.setHint(R.string.edit_text_hint);
        et.setText(getMainTitle(this));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.edit_main_title_title)
                .setView(v)
                .setPositiveButton(R.string.text_save, (d, w) -> {
                    String t = et.getText() == null ? "" : et.getText().toString().trim();
                    saveMainTitle(this, t);
                    refresh();
                })
                .setNegativeButton(R.string.text_cancel, null)
                .show();
    }

    /** 长按标语：编辑底部提示语。 */
    private void showEditHintText() {
        View v = LayoutInflater.from(this)
                .inflate(R.layout.dialog_slot_title, null, false);
        com.google.android.material.textfield.TextInputEditText et =
                v.findViewById(R.id.etSlotTitle);
        et.setHint(R.string.edit_text_hint);
        et.setText(getHintText(this));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.edit_hint_title)
                .setView(v)
                .setPositiveButton(R.string.text_save, (d, w) -> {
                    String t = et.getText() == null ? "" : et.getText().toString().trim();
                    saveHintText(this, t);
                    refresh();
                })
                .setNegativeButton(R.string.text_cancel, null)
                .show();
    }

    /** 每日喝药次数选择：1~6 个圆形数字，当前值高亮，点击即生效并重建页面。 */
    private void buildSlotSwatches(LinearLayout container, BottomSheetDialog dialog) {
        container.removeAllViews();
        int current = getSlotCount(this);

        for (int n = 1; n <= MAX_SLOTS; n++) {
            final int count = n;
            boolean selected = n == current;
            FrameLayout wrap = new FrameLayout(this);
            int size = dp(46);
            LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(0, size, 1f);
            container.addView(wrap, wrapLp);

            TextView circle = new TextView(this);
            circle.setText(String.valueOf(n));
            circle.setTextSize(16);
            circle.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            if (selected) {
                bg.setColor(ThemeUtil.colorPrimary(this));
                circle.setTextColor(0xFFFFFFFF);
            } else {
                bg.setColor(ThemeUtil.colorSurface(this));
                bg.setStroke(dp(1), ThemeUtil.colorOutlineVariant(this));
                circle.setTextColor(ThemeUtil.colorOnSurfaceVariant(this));
            }
            circle.setBackground(bg);
            int inset = dp(3);
            wrap.addView(circle, new FrameLayout.LayoutParams(size - inset * 2, size - inset * 2, Gravity.CENTER));

            wrap.setClickable(true);
            wrap.setFocusable(true);
            wrap.setOnClickListener(v -> {
                if (count != getSlotCount(this)) {
                    setSlotCount(this, count);
                }
                dialog.dismiss();
                recreate();
            });
        }
    }

    /** 桌面图标选择：6 款内置图标圆形预览，点击立即切换；当前图标高亮描边。 */
    private void buildIconSwatches(LinearLayout container, BottomSheetDialog dialog) {
        container.removeAllViews();
        int current = IconManager.currentIndex(this);
        int size = dp(52);

        for (int i = 0; i < IconManager.ALIASES.length; i++) {
            final int index = i;
            boolean selected = i == current;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(dp(74), size + dp(30));
            container.addView(item, itemLp);

            // 圆形图标 + 选中描边
            FrameLayout circle = new FrameLayout(this);
            int circleSize = size;
            LinearLayout.LayoutParams circleLp = new LinearLayout.LayoutParams(circleSize, circleSize);
            circleLp.setMargins(dp(8), dp(4), dp(8), 0);
            item.addView(circle, circleLp);

            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(ThemeUtil.colorSurface(this));
            if (selected) {
                ring.setStroke(dp(3), ThemeUtil.colorPrimary(this));
            } else {
                ring.setStroke(dp(1), ThemeUtil.colorOutlineVariant(this));
            }
            circle.setBackground(ring);

            ImageView icon = new ImageView(this);
            icon.setImageResource(IconManager.ICON_RES[i]);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            icon.setClipToOutline(true);
            int inner = dp(6);
            circle.addView(icon, new FrameLayout.LayoutParams(circleSize - inner * 2, circleSize - inner * 2, Gravity.CENTER));

            TextView name = new TextView(this);
            name.setText(IconManager.ICON_NAMES[i]);
            name.setTextSize(12);
            name.setTextColor(selected
                    ? ThemeUtil.colorPrimary(this)
                    : ThemeUtil.colorOnSurfaceVariant(this));
            name.setGravity(Gravity.CENTER);
            item.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> {
                if (index != IconManager.currentIndex(this)) {
                    IconManager.switchIcon(this, index);
                    Toast.makeText(this, R.string.icon_updated, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }
            });
        }

        // 自定义槽位：10 个（空槽显示 +，已填充显示图片）
        for (int i = 0; i < IconManager.CUSTOM_SLOTS; i++) {
            final int slot = i;
            boolean filled = IconManager.hasCustom(this, i);
            boolean selected = IconManager.isCustomActive(this, i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(dp(74), size + dp(30));
            container.addView(item, itemLp);

            FrameLayout circle = new FrameLayout(this);
            int circleSize = size;
            LinearLayout.LayoutParams circleLp = new LinearLayout.LayoutParams(circleSize, circleSize);
            circleLp.setMargins(dp(8), dp(4), dp(8), 0);
            item.addView(circle, circleLp);

            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(ThemeUtil.colorSurface(this));
            if (selected) {
                ring.setStroke(dp(3), ThemeUtil.colorPrimary(this));
            } else {
                // 未选中（空槽/已填充）：细描边
                ring.setStroke(dp(1), ThemeUtil.colorOutlineVariant(this));
            }
            circle.setBackground(ring);

            int inner = dp(6);
            if (filled) {
                ImageView icon = new ImageView(this);
                icon.setImageBitmap(BitmapFactory.decodeFile(IconManager.customPath(this, i)));
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, android.graphics.Outline outline) {
                        outline.setOval(0, 0, view.getWidth(), view.getHeight());
                    }
                });
                icon.setClipToOutline(true);
                circle.addView(icon, new FrameLayout.LayoutParams(
                        circleSize - inner * 2, circleSize - inner * 2, Gravity.CENTER));
            } else {
                TextView plus = new TextView(this);
                plus.setText("＋");
                plus.setTextSize(26);
                plus.setGravity(Gravity.CENTER);
                plus.setTextColor(ThemeUtil.colorOnSurfaceVariant(this));
                circle.addView(plus, new FrameLayout.LayoutParams(
                        circleSize - inner * 2, circleSize - inner * 2, Gravity.CENTER));
            }

            TextView name = new TextView(this);
            name.setText(filled ? "自定义" + (i + 1) : "添加");
            name.setTextSize(12);
            name.setTextColor(selected
                    ? ThemeUtil.colorPrimary(this)
                    : ThemeUtil.colorOnSurfaceVariant(this));
            name.setGravity(Gravity.CENTER);
            item.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> {
                if (!filled) {
                    // 空槽：相册选图 → 1:1 裁剪
                    dialog.dismiss();
                    pickIconForSlot(slot);
                } else if (!selected) {
                    // 已填充：设为当前生效，并立即请求添加到桌面
                    IconManager.setCustomActive(this, slot);
                    IconManager.requestPinNow(this, slot);
                    Toast.makeText(this, R.string.icon_custom_set, Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                }
            });
            item.setOnLongClickListener(v -> {
                // 长按已填充槽位：删除该自定义图标
                if (filled) {
                    IconManager.deleteCustom(this, slot);
                    Toast.makeText(this, R.string.icon_custom_deleted, Toast.LENGTH_SHORT).show();
                    buildIconSwatches(container, dialog);
                }
                return true;
            });
        }
    }

    /** 自定义图标：相册选图 → 进入 1:1 裁剪界面（与头图裁剪一致）。 */
    private void pickIconForSlot(int slot) {
        pendingIconSlot = slot;
        pickIconPhotoLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    /** 进入裁剪界面：锁定 1:1 方形裁剪框，输出到槽位文件。 */
    private void startCropForIcon(Uri sourceUri, int slot) {
        if (slot < 0) return;
        pendingIconSlot = slot;
        Intent it = new Intent(this, CropActivity.class);
        it.putExtra(CropActivity.EXTRA_SOURCE, sourceUri.toString());
        it.putExtra(CropActivity.EXTRA_OUTPUT, IconManager.customPath(this, slot));
        it.putExtra(CropActivity.EXTRA_ASPECT, 1.0);
        cropIconLauncher.launch(it);
    }

    /** 主题色板：2 行 x 5 列，每项为色圆 + 名称，点击切换主题并重建页面。 */
    private void buildThemeSwatches(LinearLayout container, BottomSheetDialog dialog) {
        container.removeAllViews();
        int current = ThemeUtil.currentThemeIndex(this);
        int perRow = 5;
        int rows = (ThemeUtil.THEME_IDS.length + perRow - 1) / perRow;

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) rowLp.topMargin = dp(14);
            container.addView(row, rowLp);

            for (int i = 0; i < perRow; i++) {
                int idx = r * perRow + i;
                if (idx >= ThemeUtil.THEME_IDS.length) break;
                row.addView(themeItem(idx, current, dialog),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
    }

    private View themeItem(int idx, int current, BottomSheetDialog dialog) {
        boolean selected = idx == current;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout circleWrap = new FrameLayout(this);
        int size = dp(46);
        FrameLayout.LayoutParams wrapLp = new FrameLayout.LayoutParams(size, size);
        item.addView(circleWrap, wrapLp);

        View circle = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(ThemeUtil.THEME_COLORS[idx]);
        if (selected) {
            bg.setStroke(dp(3), 0xFF333838);
        }
        circle.setBackground(bg);
        int inset = selected ? dp(6) : dp(4);
        circleWrap.addView(circle, new FrameLayout.LayoutParams(size - inset * 2, size - inset * 2, Gravity.CENTER));

        TextView name = new TextView(this);
        name.setText(ThemeUtil.THEME_NAMES[idx]);
        name.setTextSize(11);
        name.setGravity(Gravity.CENTER);
        name.setTextColor(selected ? ThemeUtil.colorOnSurface(this) : ThemeUtil.colorOnSurfaceVariant(this));
        if (selected) name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(4);
        item.addView(name, nameLp);

        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(v -> {
            ThemeUtil.setThemeIndex(this, idx);
            MedWidgetProvider.updateAll(this); // 小部件同步换色
            dialog.dismiss();
            recreate(); // 重新应用主题
        });
        return item;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ---------- 打卡逻辑 ----------

    private void buildRows() {
        int count = getSlotCount(this);
        int[] order = slotOrder(count);

        LayoutInflater inflater = LayoutInflater.from(this);
        rowContainer.removeAllViews();
        for (int i = 0; i < count; i++) {
            View row = inflater.inflate(R.layout.row_medication, rowContainer, false);
            final int pos = i;
            final int preset = order[i];
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(SLOT_ICONS[preset]);
            ((TextView) row.findViewById(R.id.title)).setText(getSlotTitle(this, preset));
            TextView timeView = row.findViewById(R.id.time);
            String timeText = getSlotTime(this, preset);
            if (timeText == null) {
                timeView.setVisibility(View.GONE);
            } else {
                timeView.setText(timeText);
                timeView.setVisibility(View.VISIBLE);
            }
            row.setOnClickListener(v -> toggle(pos));
            row.setOnLongClickListener(v -> {
                showSlotMenu(preset);
                return true;
            });
            rowViews[i] = row;
            rowContainer.addView(row);
        }
    }

    // ---------- 卡片长按菜单：修改文字 / 设置提醒 / 清除提醒 ----------

    /** 长按卡片弹出的操作菜单。 */
    private void showSlotMenu(int slot) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.menu_edit_text));
        items.add(getString(R.string.menu_set_reminder));
        if (ReminderManager.isEnabled(this, slot)) {
            items.add(getString(R.string.menu_clear_reminder));
        }
        String[] arr = items.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getSlotTitle(this, slot))
                .setItems(arr, (d, which) -> {
                    String item = arr[which];
                    if (getString(R.string.menu_edit_text).equals(item)) {
                        showEditSlotTitle(slot);
                    } else if (getString(R.string.menu_set_reminder).equals(item)) {
                        promptReminderTime(slot);
                    } else if (getString(R.string.menu_clear_reminder).equals(item)) {
                        ReminderManager.clearReminder(this, slot);
                        ReminderManager.cancelSlot(this, slot);
                        ReminderManager.deleteCalendarEvent(this, slot);
                        buildRows();
                        refresh();
                        Toast.makeText(this, R.string.reminder_cleared,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** 单卡文字编辑：只改当前卡片的标题。 */
    private void showEditSlotTitle(int slot) {
        View v = LayoutInflater.from(this)
                .inflate(R.layout.dialog_slot_title, null, false);
        com.google.android.material.textfield.TextInputEditText et =
                v.findViewById(R.id.etSlotTitle);
        et.setText(getSlotTitle(this, slot));
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.menu_edit_text))
                .setView(v)
                .setPositiveButton(R.string.text_save, (d, w) -> {
                    String t = et.getText() == null ? "" : et.getText().toString().trim();
                    saveSlotTitle(this, slot, t);
                    buildRows();
                    refresh();
                })
                .setNegativeButton(R.string.text_cancel, null)
                .show();
    }

    /** 选择提醒时间（时间选择器）。 */
    private void promptReminderTime(int slot) {
        int h = ReminderManager.getHour(this, slot);
        int m = ReminderManager.getMinute(this, slot);
        new TimePickerDialog(this, (tp, hour, minute) -> {
            pendingReminderSlot = slot;
            ReminderManager.setReminder(this, slot, hour, minute);
            ReminderManager.cancelSlot(this, slot);
            requestReminderPermissions(slot);
        }, h, m, true).show();
    }

    /** 请求提醒所需权限（日历读写 + Android 13+ 通知）。 */
    private void requestReminderPermissions(int slot) {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.READ_CALENDAR);
        perms.add(Manifest.permission.WRITE_CALENDAR);
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        pendingReminderSlot = slot;
        reminderPermsLauncher.launch(perms.toArray(new String[0]));
    }

    /** 权限结果就绪后：写入系统日历（成功/失败明确提示）+ 安排闹钟。 */
    private void applyReminderAfterPermission(int slot) {
        if (slot < 0) {
            return;
        }
        boolean cal = checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
        String time = String.format(Locale.CHINA, "%02d:%02d",
                ReminderManager.getHour(this, slot),
                ReminderManager.getMinute(this, slot));
        if (cal) {
            boolean ok = ReminderManager.writeCalendarEvent(this, slot,
                    ReminderManager.getHour(this, slot),
                    ReminderManager.getMinute(this, slot));
            if (ok) {
                Toast.makeText(this,
                        getString(R.string.reminder_set_done, time),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                        getString(R.string.reminder_set_cal_fail, time),
                        Toast.LENGTH_LONG).show();
                // 弹窗：显示日历诊断 + 一键打开系统日历
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.reminder_cal_diag_title)
                        .setMessage(getString(R.string.reminder_cal_diag_body,
                                ReminderManager.diagnoseCalendars(this)))
                        .setPositiveButton(R.string.reminder_open_calendar,
                                (d, w) -> openSystemCalendar())
                        .setNegativeButton(R.string.text_cancel, null)
                        .show();
            }
        } else {
            Toast.makeText(this,
                    getString(R.string.reminder_set_no_cal, time),
                    Toast.LENGTH_LONG).show();
            // 引导用户去系统设置开启日历权限
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.reminder_perm_title)
                    .setMessage(R.string.reminder_perm_guide)
                    .setPositiveButton(R.string.reminder_go_settings,
                            (d, w) -> openAppSettings())
                    .setNegativeButton(R.string.text_cancel, null)
                    .show();
        }
        ReminderManager.ensureChannel(this);
        ReminderManager.scheduleSlot(this, slot);
        // 时间行与提醒同步显示
        buildRows();
        refresh();
    }

    /** 打开本应用的系统设置页（用于手动开启权限）。 */
    private void openAppSettings() {
        try {
            Intent it = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(it);
        } catch (Exception ignored) {
        }
    }

    /** 打开系统日历 App（OPPO/通用包名兜底）。 */
    private void openSystemCalendar() {
        try {
            Intent it = getPackageManager().getLaunchIntentForPackage("com.oppo.calendar");
            if (it == null) {
                it = getPackageManager().getLaunchIntentForPackage("com.android.calendar");
            }
            if (it == null) {
                it = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("content://com.android.calendar/time"));
            }
            if (it != null) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            }
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.reminder_open_calendar_manual,
                    Toast.LENGTH_LONG).show();
        }
    }

    private int slotCount() {
        return getSlotCount(this);
    }

    private boolean allDone() {
        return allDone(this);
    }

    private void toggle(int slot) {
        boolean wasAllDone = allDone();
        boolean taken = !isTaken(this, slot);
        setTaken(this, slot, taken);
        refresh();
        updateRow(slot, taken, true); // 点亮动画只作用于被点击的卡片

        if (!wasAllDone && allDone()) {
            // 全部完成：记录连续天数 + 庆祝动画
            recordStreak(this);
            streakText.setText(getString(R.string.streak_format, prefs(this).getInt(KEY_STREAK, 0)));
            confetti.celebrate();
            bounceHeader();
        }
    }

    private void bounceHeader() {
        headerImage.animate().cancel();
        headerImage.setScaleX(1f);
        headerImage.setScaleY(1f);
        ObjectAnimator sx = ObjectAnimator.ofFloat(headerImage, "scaleX", 1f, 1.12f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(headerImage, "scaleY", 1f, 1.12f, 1f);
        sx.setDuration(600);
        sy.setDuration(600);
        sx.setInterpolator(new OvershootInterpolator(1.5f));
        sy.setInterpolator(new OvershootInterpolator(1.5f));
        sx.start();
        sy.start();
    }

    /** 刷新整页：先确保“今天”的状态，再更新日期、连续天数、进度与卡片外观。 */
    void refresh() {
        rolloverIfNeeded(this);
        // 让“今天”的历史记录始终与当前打卡状态同步（修复升级后当天记录缺失的问题）
        persistDay(this, todayString());
        if (allDone()) recordStreak(this);

        String date = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Calendar.getInstance().getTime());
        dateText.setText(date);
        streakText.setText(getString(R.string.streak_format, prefs(this).getInt(KEY_STREAK, 0)));
        headerTitle.setText(getMainTitle(this));
        hintText.setText(getHintText(this));

        int done = 0;
        int count = slotCount();
        for (int i = 0; i < count; i++) {
            if (isTaken(this, i)) done++;
        }
        if (done == count) {
            progressText.setText(R.string.progress_done);
            progressText.setTextColor(ThemeUtil.colorSecondary(this));
            progressFraction.setTextColor(ThemeUtil.colorSecondary(this));
        } else {
            progressText.setText(R.string.progress_title);
            progressText.setTextColor(ThemeUtil.colorOnSurface(this));
            progressFraction.setTextColor(ThemeUtil.colorPrimary(this));
        }
        progressFraction.setText(done + " / " + count);
        progressBar.setProgressCompat(done * 100 / count, true);

        for (int i = 0; i < count; i++) {
            updateRow(i, isTaken(this, i), false);
        }
    }

    private void updateRow(int slot, boolean taken, boolean animate) {
        View row = rowViews[slot];
        MaterialCardView card = row.findViewById(R.id.card);
        ImageView halo = row.findViewById(R.id.halo);
        ImageView badgeBg = row.findViewById(R.id.badgeBg);
        ImageView check = row.findViewById(R.id.check);
        FrameLayout badge = row.findViewById(R.id.badge);

        if (taken) {
            card.setCardBackgroundColor(ThemeUtil.colorPrimaryContainer(this));
            card.setStrokeColor(ThemeUtil.colorPrimary(this));
            badgeBg.setImageResource(R.drawable.bg_badge_done);
            check.setVisibility(View.VISIBLE);
            halo.setVisibility(View.VISIBLE);
            halo.setAlpha(1f);
            if (animate) {
                badge.setScaleX(0.3f);
                badge.setScaleY(0.3f);
                badge.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(320)
                        .setInterpolator(new OvershootInterpolator(2.5f))
                        .start();
                halo.setAlpha(0f);
                halo.animate().alpha(1f).setDuration(350).start();
            }
        } else {
            card.setCardBackgroundColor(ThemeUtil.colorSurface(this));
            card.setStrokeColor(ThemeUtil.colorOutlineVariant(this));
            badgeBg.setImageResource(R.drawable.bg_badge_idle);
            check.setVisibility(View.INVISIBLE);
            halo.setVisibility(View.INVISIBLE);
            badge.setScaleX(1f);
            badge.setScaleY(1f);
        }
    }
}
