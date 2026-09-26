package com.example.medtracker;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/**
 * 桌面图标动态切换：通过 PackageManager 在多个 activity-alias 启动入口之间切换，
 * 使应用图标即时变化并持久化。
 */
public final class IconManager {

    private static final String PREFS = "med_tracker";
    private static final String KEY_ICON = "launcher_icon";

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

    private IconManager() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 当前生效图标下标（默认 0=吉伊）。 */
    static int currentIndex(Context c) {
        String cur = prefs(c).getString(KEY_ICON, null);
        for (int i = 0; i < ALIASES.length; i++) {
            if (ALIASES[i].equals(cur)) {
                return i;
            }
        }
        return 0;
    }

    /** 切换到指定内置图标并持久化，桌面图标即时刷新。 */
    static void switchIcon(Context c, int index) {
        PackageManager pm = c.getPackageManager();
        for (int i = 0; i < ALIASES.length; i++) {
            ComponentName cn = new ComponentName(c, c.getPackageName() + "." + ALIASES[i]);
            int state = i == index
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
        }
        prefs(c).edit().putString(KEY_ICON, ALIASES[index]).apply();
    }

    /** App 启动时应用已持久化的图标选择（幂等）。 */
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
}
