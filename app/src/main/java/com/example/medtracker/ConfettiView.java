package com.example.medtracker;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * 庆祝彩带层：全部打卡完成时播放的纸屑/彩带动画。
 */
public class ConfettiView extends View {

    private static final long DURATION = 3200;
    private static final int PARTICLE_COUNT = 90;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private Particle[] particles;
    private ValueAnimator animator;

    public ConfettiView(Context context) {
        super(context);
    }

    public ConfettiView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            buildParticles(w, h);
        }
    }

    /** 从当前主题取彩带配色，随主题切换变化。 */
    private int[] themeColors() {
        return new int[]{
                ThemeUtil.colorPrimary(getContext()),
                ThemeUtil.colorPrimaryContainer(getContext()),
                ThemeUtil.colorSecondary(getContext()),
                ThemeUtil.colorSecondaryContainer(getContext()),
                0xFFFFD9A0, 0xFFFFFFFF
        };
    }

    private void buildParticles(int w, int h) {
        int[] colors = themeColors();
        particles = new Particle[PARTICLE_COUNT];
        for (int i = 0; i < particles.length; i++) {
            Particle p = new Particle();
            p.x = random.nextFloat() * w;
            float delay = random.nextFloat() * 1.2f; // 秒，错峰出场
            p.y = -20 - delay * (h * 0.6f);
            p.size = 7 + random.nextFloat() * 9;
            p.speed = 240 + random.nextFloat() * 260;
            p.drift = (random.nextFloat() - 0.5f) * 140;
            p.rotation = random.nextFloat() * 360f;
            p.rotSpeed = (random.nextFloat() - 0.5f) * 420f;
            p.color = colors[random.nextInt(colors.length)];
            p.circle = random.nextBoolean();
            particles[i] = p;
        }
    }

    /** 播放一次庆祝动画。 */
    public void celebrate() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (particles == null) buildParticles(getWidth(), getHeight());

        if (animator != null) animator.cancel();
        setAlpha(1f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(120).start();

        final long[] last = {System.currentTimeMillis()};
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            long now = System.currentTimeMillis();
            float dt = (now - last[0]) / 1000f;
            last[0] = now;
            for (Particle p : particles) {
                p.y += p.speed * dt;
                p.x += (float) Math.sin((now / 900.0) + p.rotation) * 2 + p.drift * dt;
                p.rotation += p.rotSpeed * dt;
            }
            if (t > 0.78f) {
                setAlpha(Math.max(0f, 1f - (t - 0.78f) / 0.22f));
            }
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setVisibility(GONE);
                setAlpha(1f);
            }
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (particles == null) return;
        for (Particle p : particles) {
            paint.setColor(p.color);
            canvas.save();
            canvas.translate(p.x, p.y);
            canvas.rotate(p.rotation);
            if (p.circle) {
                canvas.drawCircle(0, 0, p.size / 2f, paint);
            } else {
                canvas.drawRoundRect(-p.size / 2f, -p.size / 3f, p.size / 2f, p.size / 3f, 4, 4, paint);
            }
            canvas.restore();
        }
    }

    private static class Particle {
        float x, y, size, speed, drift, rotation, rotSpeed;
        int color;
        boolean circle;
    }
}
