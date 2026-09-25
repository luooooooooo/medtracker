package com.example.medtracker;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 自研裁剪页：锁定头图比例（2:1 左右，按屏幕宽度计算），
 * 支持拖动、双指缩放，确认后按裁剪框裁出并保存。
 */
public class CropActivity extends AppCompatActivity {

    public static final int REQUEST_CROP = 401;
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_ASPECT = "aspect"; // 裁剪框 宽/高

    private static final int MAX_EDGE = 2048;
    private static final int MAX_OUTPUT_W = 1600;
    private static final int MAX_OUTPUT_H = 800;

    private Bitmap bitmap;
    private CropView cropView;
    private String outputPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeUtil.currentThemeRes(this));
        super.onCreate(savedInstanceState);

        outputPath = getIntent().getStringExtra(EXTRA_OUTPUT);
        double aspect = getIntent().getDoubleExtra(EXTRA_ASPECT, 2.0);
        String source = getIntent().getStringExtra(EXTRA_SOURCE);
        if (source == null || outputPath == null) {
            finish();
            return;
        }

        try {
            bitmap = decodeSampled(Uri.parse(source));
        } catch (Exception e) {
            finish();
            return;
        }
        if (bitmap == null) {
            finish();
            return;
        }

        cropView = new CropView(this, bitmap, aspect);
        setContentView(buildUi());
    }

    private View buildUi() {
        int primary = ThemeUtil.colorPrimary(this);
        int surface = ThemeUtil.colorSurface(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // 顶部栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        topBar.setBackgroundColor(0xFF111111);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(15);
        cancel.setTextColor(0xFFDDDDDD);
        cancel.setPadding(dp(14), dp(12), dp(14), dp(12));
        cancel.setOnClickListener(v -> finish());
        topBar.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("裁剪头图");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextSize(15);
        done.setTextColor(primary);
        done.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        done.setPadding(dp(14), dp(12), dp(14), dp(12));
        done.setOnClickListener(v -> onDone());
        topBar.addView(done, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 裁剪编辑区
        root.addView(cropView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部提示
        TextView hint = new TextView(this);
        hint.setText("拖动和缩放照片，调整合适位置");
        hint.setTextSize(13);
        hint.setTextColor(0xFF999999);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(14), 0, dp(18));
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    /** 采样解码 + EXIF 方向纠正。 */
    private Bitmap decodeSampled(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("bad image");
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) {
            sample *= 2;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(is, null, opt);
        }
        if (bmp == null) {
            throw new IOException("decode fail");
        }
        int o = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            o = new ExifInterface(is)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
        }
        return rotateIfNeeded(bmp, o);
    }

    private static Bitmap rotateIfNeeded(Bitmap bmp, int o) {
        if (o == ExifInterface.ORIENTATION_ROTATE_90) {
            return rotate(bmp, 90f);
        }
        if (o == ExifInterface.ORIENTATION_ROTATE_180) {
            return rotate(bmp, 180f);
        }
        if (o == ExifInterface.ORIENTATION_ROTATE_270) {
            return rotate(bmp, 270f);
        }
        return bmp;
    }

    private static Bitmap rotate(Bitmap bmp, float deg) {
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) {
            bmp.recycle();
        }
        return out;
    }

    private void onDone() {
        Bitmap out = cropView.cropBitmap();
        if (out == null) {
            finish();
            return;
        }
        try (FileOutputStream fos = new FileOutputStream(new File(outputPath))) {
            out.compress(Bitmap.CompressFormat.JPEG, 90, fos);
        } catch (Exception ignored) {
        }
        out.recycle();
        setResult(RESULT_OK);
        finish();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /** 裁剪编辑视图：图片显示 + 手势 + 裁剪框绘制。 */
    private static class CropView extends View {

        private final Bitmap bitmap;
        private final RectF cropRect = new RectF();
        private final Matrix matrix = new Matrix();
        private final double aspect;
        private final Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint maskPaint = new Paint();
        private final Paint borderPaint = new Paint();
        private final Paint gridPaint = new Paint();
        private final GestureDetector gestureDetector;
        private final ScaleGestureDetector scaleDetector;
        private float initScale = 1f;
        private boolean scaleActive = false;

        CropView(android.content.Context context, Bitmap bmp, double aspect) {
            super(context);
            this.bitmap = bmp;
            this.aspect = aspect;
            maskPaint.setColor(0x99000000);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2f);
            borderPaint.setColor(Color.WHITE);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(1f);
            gridPaint.setColor(0x66FFFFFF);

            gestureDetector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                            if (scaleActive) return true; // 双指缩放时不做单指拖拽
                            matrix.postTranslate(dx, dy);
                            clampMatrix();
                            invalidate();
                            return true;
                        }

                        @Override
                        public boolean onDown(MotionEvent e) {
                            return true;
                        }
                    });

            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector d) {
                    scaleActive = true;
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector d) {
                    scaleActive = false;
                }

                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float factor = d.getScaleFactor();
                    // 计算缩放后的整体缩放值
                    float[] vals = new float[9];
                    matrix.getValues(vals);
                    float scale = vals[Matrix.MSCALE_X];
                    float next = scale * factor;
                    if (next < initScale * 0.9f || next > initScale * 6f) {
                        return true;
                    }
                    matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                    clampMatrix();
                    invalidate();
                    return true;
                }
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w <= 0 || h <= 0) return;

            // 裁剪框：按传入比例，左右留 16px
            float cropW = w - dp(32);
            float cropH = (float) (cropW / aspect);
            float maxH = h - dp(160);
            if (cropH > maxH) {
                cropH = maxH;
                cropW = (float) (cropH * aspect);
            }
            cropRect.set((w - cropW) / 2f, (h - cropH) / 2f,
                    (w + cropW) / 2f, (h + cropH) / 2f);

            // 初始缩放：图片覆盖裁剪框
            float scaleX = cropRect.width() / bitmap.getWidth();
            float scaleY = cropRect.height() / bitmap.getHeight();
            initScale = Math.max(scaleX, scaleY) * 1.02f;
            matrix.reset();
            matrix.postScale(initScale, initScale);
            // 居中
            float dx = cropRect.centerX() - bitmap.getWidth() * initScale / 2f;
            float dy = cropRect.centerY() - bitmap.getHeight() * initScale / 2f;
            matrix.postTranslate(dx, dy);
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return true;
        }

        private void clampMatrix() {
            if (cropRect.width() <= 0) return;
            RectF bmp = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
            matrix.mapRect(bmp);
            float dx = 0, dy = 0;
            if (bmp.left > cropRect.left) {
                dx = cropRect.left - bmp.left;
            } else if (bmp.right < cropRect.right) {
                dx = cropRect.right - bmp.right;
            }
            if (bmp.top > cropRect.top) {
                dy = cropRect.top - bmp.top;
            } else if (bmp.bottom < cropRect.bottom) {
                dy = cropRect.bottom - bmp.bottom;
            }
            if (dx != 0 || dy != 0) {
                matrix.postTranslate(dx, dy);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(bitmap, matrix, imgPaint);

            // 四周遮罩
            canvas.drawRect(0, 0, getWidth(), cropRect.top, maskPaint);
            canvas.drawRect(0, cropRect.bottom, getWidth(), getHeight(), maskPaint);
            canvas.drawRect(0, cropRect.top, cropRect.left, cropRect.bottom, maskPaint);
            canvas.drawRect(cropRect.right, cropRect.top, getWidth(), cropRect.bottom, maskPaint);

            // 裁剪框 + 网格
            canvas.drawRect(cropRect, borderPaint);
            float w = cropRect.width() / 3f;
            float h = cropRect.height() / 3f;
            for (int i = 1; i < 3; i++) {
                canvas.drawLine(cropRect.left + w * i, cropRect.top, cropRect.left + w * i, cropRect.bottom, gridPaint);
                canvas.drawLine(cropRect.left, cropRect.top + h * i, cropRect.right, cropRect.top + h * i, gridPaint);
            }
        }

        private int dp(int v) {
            return Math.round(getResources().getDisplayMetrics().density * v);
        }

        /** 按裁剪框裁出图片，限制输出尺寸。 */
        Bitmap cropBitmap() {
            RectF rect = new RectF();
            Matrix inv = new Matrix();
            if (!matrix.invert(inv)) return null;
            inv.mapRect(rect, cropRect);

            int left = Math.max(0, (int) rect.left);
            int top = Math.max(0, (int) rect.top);
            int right = Math.min(bitmap.getWidth(), (int) Math.ceil(rect.right));
            int bottom = Math.min(bitmap.getHeight(), (int) Math.ceil(rect.bottom));
            if (right - left < 2 || bottom - top < 2) return null;

            Bitmap cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);

            // 限制输出尺寸
            int w = cropped.getWidth();
            int h = cropped.getHeight();
            float scale = 1f;
            if (w > MAX_OUTPUT_W || h > MAX_OUTPUT_H) {
                scale = Math.min(MAX_OUTPUT_W / (float) w, MAX_OUTPUT_H / (float) h);
            }
            if (scale < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(cropped,
                        Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)), true);
                cropped.recycle();
                cropped = scaled;
            }
            return cropped;
        }
    }
}
