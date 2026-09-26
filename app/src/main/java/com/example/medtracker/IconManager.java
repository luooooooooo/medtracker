package com.example.medtracker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 桌面图标管理：
 * - 内置 6 款：通过 PackageManager 在多个 activity-alias 启动入口之间切换，即时生效；
 * - 自定义 10 槽位：相册选图 + 1:1 裁剪后填入槽位，设为当前后由应用
 *   在下次启动时通过官方快捷方式通道把图片添加到桌面（系统确认框）。
 */
public final class IconManager {

    private static final String PREFS = "med_tracker";
    private static final String KEY_ICON = "launcher_icon";
    private static final String KEY_CUSTOM_ACTIVE = "launcher_custom_active";
    private static final String KEY_CUSTOM_PENDING = "launcher_custom_pending";

    /** 6 套内置图标的 alias 名（与 AndroidManifest activity-alias 一一对应）。 */
    static final String[] ALIASES = {
            "icon_chiikawa", "icon_hachiware", "icon_usagi",
            "icon_pill", "icon_mint", "icon_heart"
    };

    /** 设置面板预览用的图标资源。 */
    static final int[] ICON_RES = {
            R.mipmap.ic_launcher_chiikawa, R.mipmap.ic_launcher_hachiware,
            R.mipmap.ic_launcher_usagi, R.mipmap.ic_launcher_pill,
            R.mipmap.ic_launcher_mint, R.mipmap.ic_launcher_heart
    };

    /** 图标中文名。 */
    static final String[] ICON_NAMES = {
            "吉伊", "小八", "乌萨奇", "药丸精灵", "薄荷圆点", "奶油爱心"
    };

    /** 自定义图标槽位数（内置 6 款之后追加）。 */
    static final int CUSTOM_SLOTS = 10;

    private IconManager() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 内置图标切换 ----------

    /** 当前生效内置图标下标（默认 0=吉伊）。 */
    static int currentIndex(Context c) {
        String cur = prefs(c).getString(KEY_ICON, null);
        for (int i = 0; i < ALIASES.length; i++) {
            if (ALIASES[i].equals(cur)) {
                return i;
            }
        }
        return 0;
    }

    /** 切换到指定内置图标并持久化，桌面图标即时刷新；同时清除自定义生效状态。 */
    static void switchIcon(Context c, int index) {
        PackageManager pm = c.getPackageManager();
        for (int i = 0; i < ALIASES.length; i++) {
            ComponentName cn = new ComponentName(c, c.getPackageName() + "." + ALIASES[i]);
            int state = i == index
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
        }
        prefs(c).edit().putString(KEY_ICON, ALIASES[index])
                .remove(KEY_CUSTOM_ACTIVE)
                .putBoolean(KEY_CUSTOM_PENDING, false)
                .apply();
    }

    /** App 启动时应用已持久化的内置图标选择（幂等）。 */
    static void applyPersisted(Context c) {
        String cur = prefs(c).getString(KEY_ICON, null);
        if (cur == null) {
            return; // 首次安装：默认 icon_chiikawa 已启用，无需处理
        }
        for (int i = 0; i < ALIASES.length; i++) {
            if (ALIASES[i].equals(cur)) {
                switchIcon(c, i);
                return;
            }
        }
    }

    // ---------- 自定义图标槽位 ----------

    /** 第 i 个自定义槽位的输出文件路径（裁剪结果，1:1）。 */
    static String customPath(Context c, int i) {
        return new File(c.getFilesDir(), "launcher_custom_" + i + ".jpg").getAbsolutePath();
    }

    /** 槽位是否已填充。 */
    static boolean hasCustom(Context c, int i) {
        return new File(customPath(c, i)).exists();
    }

    /** 槽位 i 是否为当前生效的自定义图标。 */
    static boolean isCustomActive(Context c, int i) {
        return prefs(c).getInt(KEY_CUSTOM_ACTIVE, -1) == i;
    }

    /** 将槽位 i 设为当前生效，并标记"下次启动时请求添加到桌面"。 */
    static void setCustomActive(Context c, int i) {
        prefs(c).edit().putInt(KEY_CUSTOM_ACTIVE, i)
                .putBoolean(KEY_CUSTOM_PENDING, true)
                .remove(KEY_ICON)
                .apply();
    }

    /** 删除槽位 i 的图片；若它是当前生效项则一并清除。 */
    static void deleteCustom(Context c, int i) {
        File f = new File(customPath(c, i));
        if (f.exists()) f.delete();
        if (isCustomActive(c, i)) {
            prefs(c).edit().remove(KEY_CUSTOM_ACTIVE)
                    .putBoolean(KEY_CUSTOM_PENDING, false).apply();
        }
    }

    /**
     * 下次启动时调用：若用户设置了自定义生效图标，则请求系统把该图片
     * 以官方快捷方式通道添加到桌面（仅请求一次，被拒后不再弹窗）。
     */
    static void applyPendingCustom(Context c) {
        if (!prefs(c).getBoolean(KEY_CUSTOM_PENDING, false)) {
            return;
        }
        int active = prefs(c).getInt(KEY_CUSTOM_ACTIVE, -1);
        prefs(c).edit().putBoolean(KEY_CUSTOM_PENDING, false).apply();
        if (active < 0 || active >= CUSTOM_SLOTS) {
            return;
        }
        File f = new File(customPath(c, active));
        if (!f.exists()) {
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
            Bitmap bmp = BitmapFactory.decodeStream(c.getContentResolver().openInputStream(uri));
            if (bmp == null) {
                return;
            }
            IconCompat icon = IconCompat.createWithAdaptiveBitmap(bmp);
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(c, MainActivity.class));
            ShortcutInfoCompat si = new ShortcutInfoCompat.Builder(c, "launcher_custom_" + active)
                    .setShortLabel(c.getString(R.string.icon_shortcut_label))
                    .setIcon(icon)
                    .setIntent(launch)
                    .build();
            ShortcutManagerCompat.requestPinShortcut(c, si, null);
        } catch (Exception ignored) {
        }
    }

    /** 把裁剪结果（1:1 JPEG）转换成自适应图标位图（中央 66% 安全区）并覆盖保存。 */
    static void finalizeCustomIcon(Context c, int i) {
        File f = new File(customPath(c, i));
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null) {
            return;
        }
        try {
            int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b,
                    (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            Bitmap content = Bitmap.createScaledBitmap(sq, 128, 128, true);
            if (sq != b) sq.recycle();
            Bitmap adaptive = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            new Canvas(adaptive).drawBitmap(content, (192 - 128) / 2f, (192 - 128) / 2f, null);
            content.recycle();
            if (f.exists()) f.delete();
            try {
                try (FileOutputStream out = new FileOutputStream(f)) {
                    adaptive.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            } catch (Exception ignored) {
            }
            adaptive.recycle();
        } finally {
            b.recycle();
        }
    }
}
