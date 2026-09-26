package com.example.medtracker;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/**
 * 打卡日历：按月份查看每天的打卡情况，点击日期查看当天详情。
 */
public class StatsActivity extends AppCompatActivity {

    private TextView monthTitle;
    private LinearLayout grid;
    private TextView statTotal, statMonth, statStreak;
    private TextView detailTitle;
    private LinearLayout detailContainer;

    private int shownYear, shownMonth; // shownMonth: 0 基
    private String selectedDate; // yyyy-MM-dd

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeUtil.currentThemeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);

        Calendar now = Calendar.getInstance();
        shownYear = now.get(Calendar.YEAR);
        shownMonth = now.get(Calendar.MONTH);
        selectedDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());

        monthTitle = findViewById(R.id.monthTitle);
        grid = findViewById(R.id.grid);
        statTotal = findViewById(R.id.statTotal);
        statMonth = findViewById(R.id.statMonth);
        statStreak = findViewById(R.id.statStreak);
        detailTitle = findViewById(R.id.detailTitle);
        detailContainer = findViewById(R.id.detailContainer);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPrev).setOnClickListener(v -> { shiftMonth(-1); render(); });
        findViewById(R.id.btnNext).setOnClickListener(v -> { shiftMonth(1); render(); });

        render();
    }

    private void shiftMonth(int delta) {
        shownMonth += delta;
        if (shownMonth < 0) {
            shownMonth = 11;
            shownYear--;
        } else if (shownMonth > 11) {
            shownMonth = 0;
            shownYear++;
        }
    }

    private void render() {
        monthTitle.setText(String.format(Locale.CHINA, "%d年%d月", shownYear, shownMonth + 1));
        renderStats();
        renderGrid();
        renderDetail();
    }

    private void renderStats() {
        int total = 0, monthDone = 0;
        String prefix = String.format(Locale.US, "%04d-%02d", shownYear, shownMonth + 1);
        for (Map.Entry<String, String> e : MainActivity.getAllDayRecords(this).entrySet()) {
            if (e.getValue().contains("1")) {
                total++;
                if (e.getKey().startsWith(prefix)) monthDone++;
            }
        }
        statTotal.setText(getString(R.string.stat_total, total));
        statMonth.setText(getString(R.string.stat_month, monthDone));
        statStreak.setText(getString(R.string.stat_streak,
                MainActivity.getStreak(this)));
    }

    private void renderGrid() {
        grid.removeAllViews();

        // 星期表头（周一开始），每行 7 等分
        String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
        LinearLayout headerRow = newRow();
        for (String wd : weekdays) {
            TextView tv = new TextView(this);
            tv.setText(wd);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(13);
            tv.setTextColor(ThemeUtil.colorOnSurfaceVariant(this));
            headerRow.addView(tv, cellWeight());
        }
        grid.addView(headerRow);

        Calendar cal = Calendar.getInstance();
        cal.set(shownYear, shownMonth, 1);
        int leading = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7; // 周一 = 0
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());

        LinearLayout row = newRow();
        int filled = 0;
        for (int i = 0; i < leading; i++) {
            row.addView(new View(this), cellWeight());
            filled++;
        }
        for (int d = 1; d <= days; d++) {
            String date = String.format(Locale.US, "%04d-%02d-%02d", shownYear, shownMonth + 1, d);
            boolean future = date.compareTo(today) > 0;
            boolean isToday = date.equals(today);
            boolean isSelected = date.equals(selectedDate);
            int count = MainActivity.getDayTakenCount(this, date);
            row.addView(dayCell(d, count, future, isSelected, isToday, date), cellWeight());
            filled++;
            if (filled % 7 == 0) {
                grid.addView(row);
                row = newRow();
            }
        }
        // 最后一行若不满 7 天，补足空列，保证日期列与星期表头严格对齐
        if (filled % 7 != 0) {
            while (filled % 7 != 0) {
                row.addView(new View(this), cellWeight());
                filled++;
            }
            grid.addView(row);
        }
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams cellWeight() {
        return new LinearLayout.LayoutParams(0, dp(46), 1f);
    }

    private View dayCell(int day, int count, boolean future, boolean isSelected, boolean isToday, String date) {
        FrameLayout cell = new FrameLayout(this);

        // 内层：选中的日期显示主题色描边圈
        FrameLayout inner = new FrameLayout(this);
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER);
        if (isSelected) {
            inner.setBackgroundResource(R.drawable.bg_day_today);
        } else if (isToday) {
            // 今天（未被选中时）用底部小圆点标识
            View dot = new View(this);
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(5), dp(5),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dotLp.bottomMargin = dp(3);
            dot.setBackgroundResource(R.drawable.bg_day_full);
            inner.addView(dot, dotLp);
        }
        cell.addView(inner, innerLp);

        // 满格判定：当天记录写了几位（那天的打卡次数），全为 1 才算完成
        String rec = MainActivity.getDayRecord(this, date);
        boolean full = !future && rec != null && rec.length() > 0 && count == rec.length();

        int size = dp(34);
        ImageView bg = new ImageView(this);
        if (full) {
            bg.setImageResource(R.drawable.bg_day_full);
        } else if (count > 0) {
            bg.setImageResource(R.drawable.bg_day_partial);
        } else {
            bg.setVisibility(View.INVISIBLE);
        }
        inner.addView(bg, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

        TextView tv = new TextView(this);
        tv.setText(String.valueOf(day));
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(14);
        if (future) {
            tv.setTextColor(ThemeUtil.colorOutlineVariant(this));
        } else if (full) {
            tv.setTextColor(Color.WHITE);
        } else if (count > 0) {
            tv.setTextColor(ThemeUtil.colorPrimary(this));
        } else {
            tv.setTextColor(ThemeUtil.colorOnSurfaceVariant(this));
        }
        inner.addView(tv, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

        if (!future) {
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setOnClickListener(v -> {
                if (date.equals(selectedDate)) return;
                selectedDate = date;
                renderGrid();   // 让选中圆圈移动到新日期
                renderDetail();
            });
        }
        return cell;
    }

    private void renderDetail() {
        String[] p = selectedDate.split("-");
        detailTitle.setText(String.format(Locale.CHINA, "%d年%d月%d日",
                Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
        String rec = MainActivity.getDayRecord(this, selectedDate);

        detailContainer.removeAllViews();
        if (rec == null || rec.length() == 0) {
            addDetailRow("—", false);
            return;
        }
        for (int i = 0; i < rec.length(); i++) {
            addDetailRow(MainActivity.slotName(this, i, rec.length()), rec.charAt(i) == '1');
        }
    }

    private void addDetailRow(String name, boolean done) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView nameTv = new TextView(this);
        nameTv.setText(name);
        nameTv.setTextSize(14);
        nameTv.setTextColor(ThemeUtil.colorOnSurface(this));
        row.addView(nameTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView stateTv = new TextView(this);
        stateTv.setText(done ? R.string.slot_done : R.string.slot_undone);
        stateTv.setTextSize(14);
        stateTv.setTextColor(done ? ThemeUtil.colorPrimary(this) : ThemeUtil.colorOnSurfaceVariant(this));
        row.addView(stateTv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        detailContainer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
