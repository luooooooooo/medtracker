package com.example.medtracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;

/** 主题管理：10 套莫兰迪配色 + 主题持久化 + 主题属性取色。 */
public final class ThemeUtil {

    public static final String PREFS = "med_tracker";
    private static final String KEY_THEME = "theme_index";

    // 与 res/values/themes.xml 中的 style 一一对应
    public static final int[] THEME_IDS = {
            R.style.Theme_Mint,
            R.style.Theme_Blue,
            R.style.Theme_Charcoal,
            R.style.Theme_Pink,
            R.style.Theme_Purple,
            R.style.Theme_Sage,
            R.style.Theme_Oat,
            R.style.Theme_Terracotta,
            R.style.Theme_Periwinkle,
            R.style.Theme_Plum
    };

    public static final String[] THEME_NAMES = {
            "薄荷", "雾蓝", "墨黑", "豆沙粉", "香芋紫",
            "鼠尾草", "燕麦", "陶土", "鸢尾蓝", "梅子"
    };

    // 主题主色（色卡展示用），与 style 中 colorPrimary 保持一致
    public static final int[] THEME_COLORS = {
            0xFF4E8A7C, 0xFF5F7E99, 0xFF4A504F, 0xFFB07171, 0xFF7E72A0,
            0xFF73887B, 0xFF9A8268, 0xFFA97B69, 0xFF6F7CA0, 0xFF8F5B6A
    };

    private ThemeUtil() {
    }

    /** 当前主题在 THEME_IDS 中的下标（默认 0 = 薄荷）。 */
    public static int currentThemeIndex(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int idx = sp.getInt(KEY_THEME, 0);
        if (idx < 0 || idx >= THEME_IDS.length) {
            return 0;
        }
        return idx;
    }

    /** 当前主题的 style 资源 id。 */
    public static int currentThemeRes(Context c) {
        return THEME_IDS[currentThemeIndex(c)];
    }

    /** 保存主题下标。 */
    public static void setThemeIndex(Context c, int idx) {
        if (idx < 0 || idx >= THEME_IDS.length) {
            idx = 0;
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_THEME, idx).apply();
    }

    /** 从当前主题解析一个颜色属性（按名称，兼容依赖库定义的属性）。 */
    private static int color(Context c, String attrName, int fallback) {
        int resId = c.getResources().getIdentifier(attrName, "attr", c.getPackageName());
        if (resId == 0) {
            return fallback;
        }
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(resId, tv, true)) {
            return tv.data;
        }
        return fallback;
    }

    public static int colorSurface(Context c) {
        return color(c, "colorSurface", 0xFFFFFFFF);
    }

    public static int colorPrimary(Context c) {
        return color(c, "colorPrimary", 0xFF4E8A7C);
    }

    public static int colorPrimaryContainer(Context c) {
        return color(c, "colorPrimaryContainer", 0xFFD8EAE3);
    }

    public static int colorSecondary(Context c) {
        return color(c, "colorSecondary", 0xFFA98E84);
    }

    public static int colorSecondaryContainer(Context c) {
        return color(c, "colorSecondaryContainer", 0xFFEFE2DA);
    }

    public static int colorOnSurface(Context c) {
        return color(c, "colorOnSurface", 0xFF333838);
    }

    public static int colorOnSurfaceVariant(Context c) {
        return color(c, "colorOnSurfaceVariant", 0xFF707B78);
    }

    public static int colorOutlineVariant(Context c) {
        return color(c, "colorOutlineVariant", 0xFFD8DCD8);
    }
}
