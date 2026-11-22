/*
 * SPDX-FileCopyrightText: 2026 Zinwa P26 Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.IBinder;
import android.text.format.DateFormat;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.Insettable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-bleed transparent stage mounted inside {@link RecentsActivity}'s drag layer (SurfaceFlinger
 * {@code middleLayers}: strictly BELOW the swiped foreground App Leash and BELOW the Android
 * System Status Bar, strictly ABOVE the live system wallpaper).
 *
 * Implements Video 3 (`20261122_112818.mp4`) left-edge drawer Hub Peek over crisp live wallpaper:
 * - Z = 0 ({@link SurfaceView}): Stationary BlackBerry Hub {@code SurfacePackage} supplied by
 *   {@code BB10Launcher}'s {@code HubPeekService}, hardware-clipped strictly to
 *   {@code [0 .. curtainX]} (invisible when {@code curtainX <= 1px} so 100% untouched wallpaper
 *   shows during vertical Peek).
 * - Z = 1 ({@link PeekChromeView}): Hardware-accelerated Canvas drawing zero-IPC visuals at 60fps:
 *   1. Left/top dark vignette gradient over {@code [curtainX .. W]} for high-contrast white icons.
 *   2. Subtle Hub brightening veil over {@code [0 .. curtainX]} (fading out as drawer opens).
 *   3. Crisp 22dp leftward vertical drop shadow along the drawer boundary {@code x = curtainX}.
 *   4. Monochrome white unread summary column ({@code [Bell] N}, {@code [SMS] N}, {@code [Call] N})
 *      locked rigidly at {@code x = curtainX + 0.08 * W}, vertically centered (~50% H). Because
 *      this sits in {@code middleLayers}, dragging down causes the expanding App Leash to naturally
 *      occlude the icons just like real BB10 OS 10.3.
 *   5. Stationary Row 2 Date line directly beneath the top-left status bar.
 */
final class BB10PeekStageView extends FrameLayout implements Insettable, BB10FlowBridge.StageListener {

    private static final Rect EMPTY_CLIP = new Rect(0, 0, 0, 0);

    private final BB10FlowBridge mBridge;
    private final SurfaceView mHubSurfaceView;
    private final PeekChromeView mChromeView;
    private final Rect mHubClipRect = new Rect();

    private int mTopInsetPx;
    private int mBottomInsetPx;
    private boolean mSurfaceCreated;
    private boolean mHasSurfacePackage;
    private int mAttachedPackageGeneration = -1;
    private int mSurfaceW;
    private int mSurfaceH;

    private float mVerticalProgress;
    private float mCurtainX;
    private float mAppLeft;
    private float mBellAlpha;
    private float mDateAlpha;
    private float mChromeAlpha = 1f;
    private boolean mStageActive;

    BB10PeekStageView(@NonNull Context context) {
        super(context);
        mBridge = BB10FlowBridge.get(context);
        setClickable(false);
        setFocusable(false);
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(Color.TRANSPARENT);

        mHubSurfaceView = new SurfaceView(context);
        mHubSurfaceView.setZOrderOnTop(false);
        mHubSurfaceView.setEnableSurfaceClipping(true);
        mHubSurfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        mHubSurfaceView.setClipBounds(EMPTY_CLIP);
        mHubSurfaceView.setVisibility(INVISIBLE);
        mHubSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                mSurfaceCreated = true;
                requestSurfaceIfReady();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int w, int h) {
                mSurfaceW = w;
                mSurfaceH = h;
                requestSurfaceIfReady();
                applyHubClip();
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                mSurfaceCreated = false;
                mHasSurfacePackage = false;
                mAttachedPackageGeneration = -1;
                mHubSurfaceView.clearChildSurfacePackage();
                mBridge.onHostSurfaceDestroyed();
            }
        });
        addView(mHubSurfaceView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mChromeView = new PeekChromeView(context);
        addView(mChromeView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBridge.setStageListener(this);
        requestSurfaceIfReady();
    }

    @Override
    protected void onDetachedFromWindow() {
        mBridge.clearStageListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void setInsets(Rect insets) {
        if (insets == null) return;
        boolean changed = mTopInsetPx != insets.top || mBottomInsetPx != insets.bottom;
        mTopInsetPx = insets.top;
        mBottomInsetPx = insets.bottom;
        mChromeView.setTopInset(mTopInsetPx);
        if (changed && mSurfaceCreated && mSurfaceW > 0 && mSurfaceH > 0) {
            requestSurfaceIfReady();
        }
    }

    @UiThread
    void activateStage() {
        mStageActive = true;
        mVerticalProgress = 0f;
        mCurtainX = 0f;
        mAppLeft = 0f;
        mBellAlpha = 0f;
        mDateAlpha = 0f;
        mChromeAlpha = 1f;
        setVisibility(VISIBLE);
        setAlpha(1f);
        mChromeView.setAlpha(1f);
        mBridge.setStageListener(this);
        mChromeView.setUnreadCounts(
                mBridge.getUnreadNotifications(),
                mBridge.getUnreadSms(),
                mBridge.getUnreadCalls());
        // Keep Hub SurfaceView attached/VISIBLE briefly if needed to trigger surface creation,
        // clipped to EMPTY_CLIP so zero pixels show until curtainX > 1px
        mHubSurfaceView.setClipBounds(EMPTY_CLIP);
        mHubSurfaceView.setVisibility(VISIBLE);
        requestSurfaceIfReady();
        mChromeView.update(0f, 0f, 0f, 0f, 0f);
    }

    @UiThread
    void updateProgress(
            float verticalProgress,
            float curtainX,
            float appLeft,
            float bellAlpha,
            float dateAlpha) {
        if (!mStageActive) return;
        mVerticalProgress = Math.max(0f, Math.min(1f, verticalProgress));
        mCurtainX = Math.max(0f, curtainX);
        mAppLeft = Math.max(0f, appLeft);
        mBellAlpha = Math.max(0f, Math.min(1f, bellAlpha));
        mDateAlpha = Math.max(0f, Math.min(1f, dateAlpha));
        applyHubClip();
        mChromeView.update(mVerticalProgress, mCurtainX, mAppLeft, mBellAlpha, mDateAlpha);
    }

    @UiThread
    void setChromeOverlayAlpha(float alpha) {
        mChromeAlpha = Math.max(0f, Math.min(1f, alpha));
        mChromeView.setAlpha(mChromeAlpha);
    }

    @UiThread
    void deactivateStage() {
        mStageActive = false;
        mVerticalProgress = 0f;
        mCurtainX = 0f;
        mAppLeft = 0f;
        mBellAlpha = 0f;
        mDateAlpha = 0f;
        mChromeAlpha = 1f;
        mHubSurfaceView.setClipBounds(EMPTY_CLIP);
        mHubSurfaceView.setVisibility(INVISIBLE);
        mChromeView.setAlpha(1f);
        mChromeView.update(0f, 0f, 0f, 0f, 0f);
        setVisibility(GONE);
    }

    private void applyHubClip() {
        int h = getHeight() > 0 ? getHeight() : mSurfaceH;
        int clipR = Math.round(mCurtainX);
        if (!mStageActive || clipR <= 1 || h <= 0) {
            mHubSurfaceView.setClipBounds(EMPTY_CLIP);
        } else {
            int maxW = getWidth() > 0 ? getWidth() : mSurfaceW;
            mHubClipRect.set(0, 0, Math.min(clipR, maxW), h);
            mHubSurfaceView.setClipBounds(mHubClipRect);
            if (!mHasSurfacePackage) {
                requestSurfaceIfReady();
            }
        }
    }

    private void requestSurfaceIfReady() {
        if (!mSurfaceCreated || !mBridge.isInAppPeekActive()) return;
        int w = mSurfaceW > 0 ? mSurfaceW : getWidth();
        int h = mSurfaceH > 0 ? mSurfaceH : getHeight();
        IBinder token = mHubSurfaceView.getHostToken();
        if (token == null || w <= 0 || h <= 0) return;
        int displayId = getDisplay() != null ? getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
        int statusBarH = mTopInsetPx > 0 ? mTopInsetPx : resolveFallbackStatusBarHeight();
        mBridge.requestHubSurface(token, displayId, w, h, statusBarH, mBottomInsetPx);
    }

    private int resolveFallbackStatusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resId > 0 ? getResources().getDimensionPixelSize(resId) : 0;
    }

    @Override
    public void onHubSurfacePackage(
            @NonNull SurfaceControlViewHost.SurfacePackage pkg, int generation) {
        if (!mSurfaceCreated) {
            pkg.release();
            return;
        }
        if (mHasSurfacePackage && mAttachedPackageGeneration == generation) {
            // Identical surface package already attached to SurfaceView — drop redundant copy
            pkg.release();
            return;
        }
        try {
            mHubSurfaceView.setChildSurfacePackage(pkg);
            mHasSurfacePackage = true;
            mAttachedPackageGeneration = generation;
            applyHubClip();
        } catch (Exception ignored) {
            pkg.release();
        }
    }

    @Override
    public void onUnreadCountsChanged(int notifications, int sms, int calls) {
        mChromeView.setUnreadCounts(notifications, sms, calls);
    }

    /**
     * Hardware-accelerated transparent overlay rendering Video 3 chrome over live wallpaper.
     */
    private static final class PeekChromeView extends View {

        private static final int ICON_BELL = 1;
        private static final int ICON_SMS = 2;
        private static final int ICON_CALL = 3;

        private final float mDensity;
        private int mTopInsetPx;

        private float mVerticalProgress;
        private float mCurtainX;
        private float mAppLeft;
        private float mBellAlpha;
        private float mDateAlpha;

        private int mUnreadNotifications;
        private int mUnreadSms;
        private int mUnreadCalls;

        private final Paint mVignetteLeftPaint = new Paint();
        private final Paint mTopSoftGradPaint = new Paint();
        private final Paint mTopSolidBarPaint = new Paint();
        private final Paint mHubVeilPaint = new Paint();
        private final Paint mSeamShadowPaint = new Paint();
        private final Paint mIconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mIconStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCountPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF mTmpRect = new RectF();
        private final Path mTmpPath = new Path();

        private float mCachedVignetteLeftX = Float.NaN;
        private float mCachedVignetteW = Float.NaN;
        private float mCachedSoftTopH = Float.NaN;
        private float mCachedShadowX = Float.NaN;
        private final float mShadowWidthPx;

        private String mCachedDateText = "";
        private long mCachedDateMs;

        PeekChromeView(Context context) {
            super(context);
            mDensity = context.getResources().getDisplayMetrics().density;
            mShadowWidthPx = 24f * mDensity;
            setClickable(false);
            setFocusable(false);

            mTopSolidBarPaint.setColor(0xFF1E2024);
            mTopSolidBarPaint.setStyle(Paint.Style.FILL);

            mHubVeilPaint.setColor(Color.BLACK);
            mHubVeilPaint.setStyle(Paint.Style.FILL);

            mIconFillPaint.setColor(Color.WHITE);
            mIconFillPaint.setStyle(Paint.Style.FILL);
            mIconFillPaint.setShadowLayer(2.2f * mDensity, 0f, 1f * mDensity, 0xCC000000);

            mIconStrokePaint.setColor(Color.WHITE);
            mIconStrokePaint.setStyle(Paint.Style.STROKE);
            mIconStrokePaint.setStrokeWidth(1.65f * mDensity);
            mIconStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            mIconStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            mIconStrokePaint.setShadowLayer(2.2f * mDensity, 0f, 1f * mDensity, 0xCC000000);

            mCountPaint.setColor(Color.WHITE);
            mCountPaint.setTextSize(15.5f * mDensity);
            mCountPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mCountPaint.setTextAlign(Paint.Align.CENTER);

            mDatePaint.setColor(Color.WHITE);
            mDatePaint.setTextSize(12.5f * mDensity);
            mDatePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mDatePaint.setTextAlign(Paint.Align.LEFT);
        }

        void setTopInset(int topInsetPx) {
            if (mTopInsetPx != topInsetPx) {
                mTopInsetPx = topInsetPx;
                invalidate();
            }
        }

        void setUnreadCounts(int notifications, int sms, int calls) {
            if (mUnreadNotifications != notifications
                    || mUnreadSms != sms
                    || mUnreadCalls != calls) {
                mUnreadNotifications = notifications;
                mUnreadSms = sms;
                mUnreadCalls = calls;
                invalidate();
            }
        }

        void update(
                float verticalProgress,
                float curtainX,
                float appLeft,
                float bellAlpha,
                float dateAlpha) {
            mVerticalProgress = verticalProgress;
            mCurtainX = curtainX;
            mAppLeft = appLeft;
            mBellAlpha = bellAlpha;
            mDateAlpha = dateAlpha;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final float w = getWidth();
            final float h = getHeight();
            if (w <= 0f || h <= 0f) return;

            final float revealFrac = Math.max(0f, Math.min(1f, mCurtainX / w));
            final float statusBarH = mTopInsetPx > 0 ? mTopInsetPx : 26f * mDensity;
            // Exact pixel alignment with HubPeekContentView.PEEK_DATE_ROW_DP (26dp)
            final float dateRowH = 26f * mDensity;
            final float topBarH = statusBarH + dateRowH;

            // 1. Subtle Hub brightening veil over [0 .. curtainX] (0.24 -> 0.0 as drawer opens)
            if (mCurtainX > 1f) {
                float veilFrac = Math.max(0f, 1f - (revealFrac / 0.45f)) * 0.24f;
                int veilAlpha = Math.round(veilFrac * 255f);
                if (veilAlpha > 0) {
                    mHubVeilPaint.setAlpha(veilAlpha);
                    canvas.drawRect(0f, 0f, mCurtainX, h, mHubVeilPaint);
                }
            }

            // 2. Narrow, subtle, seamless LEFT / Hub-edge horizontal gradient:
            //    - Compact width (~16% W max) with soft 4-stop falloff so there is ZERO obvious
            //      boundary against the bright wallpaper or centered App card.
            //    - Right and Bottom wallpaper remain bright, clean, un-darkened live wallpaper.
            //    - Top shadow stays FIXED-height and NEVER grows downward with scale.
            float leftGradAlpha = mCurtainX > 1f
                    ? 1f
                    : Math.max(0f, Math.min(1f, mVerticalProgress / 0.60f));
            if (leftGradAlpha > 0.01f && mCurtainX < w) {
                float gapW = Math.max(0f, mAppLeft - mCurtainX);
                float vigW = Math.min(w * 0.16f, Math.max(w * 0.04f, gapW * 0.72f));
                if (mCachedVignetteLeftX != mCurtainX || Math.abs(mCachedVignetteW - vigW) > 0.5f) {
                    mCachedVignetteLeftX = mCurtainX;
                    mCachedVignetteW = vigW;
                    mVignetteLeftPaint.setShader(new LinearGradient(
                            mCurtainX, 0f, mCurtainX + vigW, 0f,
                            new int[] {0x72000000, 0x34000000, 0x0E000000, 0x00000000},
                            new float[] {0.00f, 0.38f, 0.72f, 1.00f},
                            Shader.TileMode.CLAMP));
                }
                mVignetteLeftPaint.setAlpha(Math.round(leftGradAlpha * 255f));
                canvas.drawRect(mCurtainX, 0f, Math.min(w, mCurtainX + vigW), h, mVignetteLeftPaint);
            }

            // 4. Crisp leftward drop shadow along the drawer boundary x = curtainX onto the Hub
            if (mCurtainX > 1f && mCurtainX < w) {
                if (mCachedShadowX != mCurtainX) {
                    mCachedShadowX = mCurtainX;
                    mSeamShadowPaint.setShader(new LinearGradient(
                            mCurtainX - mShadowWidthPx, 0f, mCurtainX, 0f,
                            0x00000000, 0x8A000000, Shader.TileMode.CLAMP));
                }
                canvas.drawRect(Math.max(0f, mCurtainX - mShadowWidthPx), 0f, mCurtainX, h,
                        mSeamShadowPaint);
            }

            // 5. Monochrome white unread notification column — PURE PHYSICAL SLIDE at 100% solid
            //    white (NO alpha fade-in/fade-out):
            //    - Vertical lift (`curtainX <= 0.5`): tethered at `appLeft - 11.75% W`, sliding
            //      cleanly out from behind the left edge (`-11.75% -> +11.75% W`) and back off the
            //      left edge on cancel/retreat.
            //    - Hub Peek (`curtainX > 0.5`): rides at gap midpoint `(curtainX + appLeft) / 2`.
            if (mBellAlpha > 0.01f) {
                float colX;
                if (mCurtainX > 0.5f) {
                    colX = mAppLeft > mCurtainX
                            ? (mCurtainX + mAppLeft) * 0.5f
                            : (mCurtainX + w * 0.09f);
                } else {
                    colX = mAppLeft - w * 0.1175f;
                }
                if (colX > -28f * mDensity && colX < w + 28f * mDensity) {
                    canvas.save();
                    canvas.clipRect(mCurtainX, 0f, w, h);
                    drawUnreadColumn(canvas, colX, h * 0.505f, 1f);
                    canvas.restore();
                }
            }

            // 6. Two-phase top header bar across [0 .. W]:
            //    - Early lift (before Date): light soft top gradient behind single-row status bar.
            //    - Held Peek / Hub Peek (Date active): solid opaque dark charcoal bar (#1E2024)
            //      with a crisp hard bottom edge (zero gradient bleed over the white Hub).
            float softTopAlpha = mVerticalProgress * (1f - mDateAlpha);
            if (softTopAlpha > 0.01f) {
                float softTopH = statusBarH + 14f * mDensity;
                if (mCachedSoftTopH != softTopH) {
                    mCachedSoftTopH = softTopH;
                    mTopSoftGradPaint.setShader(new LinearGradient(
                            0f, 0f, 0f, softTopH,
                            0x58000000, 0x00000000, Shader.TileMode.CLAMP));
                }
                mTopSoftGradPaint.setAlpha(Math.round(softTopAlpha * 255f));
                canvas.drawRect(0f, 0f, w, softTopH, mTopSoftGradPaint);
            }

            if (mDateAlpha > 0.01f) {
                int barAlpha = Math.round(mDateAlpha * 255f);
                mTopSolidBarPaint.setAlpha(barAlpha);
                canvas.drawRect(0f, 0f, w, topBarH, mTopSolidBarPaint);

                mDatePaint.setAlpha(barAlpha);
                Paint.FontMetrics fm = mDatePaint.getFontMetrics();
                float rowCenterY = statusBarH + dateRowH * 0.48f;
                float baseline = rowCenterY - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(getDateText(), 14f * mDensity, baseline, mDatePaint);
            }
        }

        private void drawUnreadColumn(Canvas canvas, float cx, float centerY, float alpha) {
            int shadowA = Math.max(0, Math.min(0x90, Math.round(alpha * alpha * 0x90)));
            int shadowColor = Color.argb(shadowA, 0, 0, 0);
            if (shadowA > 8) {
                mIconFillPaint.setShadowLayer(2.2f * mDensity, 0f, 1f * mDensity, shadowColor);
                mIconStrokePaint.setShadowLayer(2.2f * mDensity, 0f, 1f * mDensity, shadowColor);
                mCountPaint.setShadowLayer(2.4f * mDensity, 0f, 1f * mDensity, shadowColor);
            } else {
                mIconFillPaint.clearShadowLayer();
                mIconStrokePaint.clearShadowLayer();
                mCountPaint.clearShadowLayer();
            }

            int[] types = new int[3];
            int[] counts = new int[3];
            int n = 0;
            if (mUnreadNotifications > 0) {
                types[n] = ICON_BELL;
                counts[n++] = mUnreadNotifications;
            }
            if (mUnreadSms > 0) {
                types[n] = ICON_SMS;
                counts[n++] = mUnreadSms;
            }
            if (mUnreadCalls > 0) {
                types[n] = ICON_CALL;
                counts[n++] = mUnreadCalls;
            }

            if (n == 0) {
                // Clean subtle bell cue when unread count is 0
                int dimAlpha = Math.round(alpha * 135f);
                drawBell(canvas, cx, centerY - 7f * mDensity, dimAlpha);
                mCountPaint.setAlpha(dimAlpha);
                canvas.drawText("0", cx, centerY + 20f * mDensity, mCountPaint);
                return;
            }

            float itemH = 56f * mDensity;
            float y = centerY - (n * itemH) / 2f + itemH * 0.42f;
            int aInt = Math.round(alpha * 255f);

            for (int i = 0; i < n; i++) {
                if (types[i] == ICON_BELL) {
                    drawBell(canvas, cx, y - 7f * mDensity, aInt);
                } else if (types[i] == ICON_SMS) {
                    drawSms(canvas, cx, y - 7f * mDensity, aInt);
                } else {
                    drawCall(canvas, cx, y - 7f * mDensity, aInt);
                }
                mCountPaint.setAlpha(aInt);
                String label = counts[i] > 99 ? "99+" : String.valueOf(counts[i]);
                canvas.drawText(label, cx, y + 20f * mDensity, mCountPaint);
                y += itemH;
            }
        }

        private void drawBell(Canvas canvas, float cx, float cy, int alphaInt) {
            mIconFillPaint.setAlpha(alphaInt);
            float s = 10.2f * mDensity;
            mTmpPath.reset();
            mTmpPath.moveTo(cx, cy - s);
            mTmpPath.cubicTo(cx + s * 0.65f, cy - s, cx + s * 0.72f, cy - s * 0.2f,
                    cx + s * 0.78f, cy + s * 0.42f);
            mTmpPath.lineTo(cx + s * 0.98f, cy + s * 0.68f);
            mTmpPath.lineTo(cx - s * 0.98f, cy + s * 0.68f);
            mTmpPath.lineTo(cx - s * 0.78f, cy + s * 0.42f);
            mTmpPath.cubicTo(cx - s * 0.72f, cy - s * 0.2f, cx - s * 0.65f, cy - s,
                    cx, cy - s);
            mTmpPath.close();
            canvas.drawPath(mTmpPath, mIconFillPaint);
            canvas.drawCircle(cx, cy + s * 0.88f, 2.2f * mDensity, mIconFillPaint);
        }

        private void drawSms(Canvas canvas, float cx, float cy, int alphaInt) {
            mIconFillPaint.setAlpha(alphaInt);
            float halfW = 9.2f * mDensity;
            float halfH = 6.8f * mDensity;
            mTmpRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
            canvas.drawRoundRect(mTmpRect, 2.4f * mDensity, 2.4f * mDensity, mIconFillPaint);
            mTmpPath.reset();
            mTmpPath.moveTo(cx - halfW * 0.35f, cy + halfH - 0.5f * mDensity);
            mTmpPath.lineTo(cx - halfW * 0.65f, cy + halfH + 4.2f * mDensity);
            mTmpPath.lineTo(cx + halfW * 0.12f, cy + halfH - 0.5f * mDensity);
            mTmpPath.close();
            canvas.drawPath(mTmpPath, mIconFillPaint);
        }

        private void drawCall(Canvas canvas, float cx, float cy, int alphaInt) {
            mIconStrokePaint.setAlpha(alphaInt);
            float s = 8.2f * mDensity;
            mTmpPath.reset();
            mTmpPath.moveTo(cx - s * 0.75f, cy - s * 0.65f);
            mTmpPath.quadTo(cx - s * 0.95f, cy + s * 0.25f, cx - s * 0.15f, cy + s * 0.75f);
            mTmpPath.quadTo(cx + s * 0.45f, cy + s * 0.95f, cx + s * 0.80f, cy + s * 0.45f);
            canvas.drawPath(mTmpPath, mIconStrokePaint);
        }

        private String getDateText() {
            long now = System.currentTimeMillis();
            if (mCachedDateText.isEmpty() || now - mCachedDateMs > 60_000L) {
                Locale locale = Locale.getDefault();
                String pattern = DateFormat.getBestDateTimePattern(locale, "MMMMEEEEd");
                mCachedDateText = new SimpleDateFormat(pattern, locale).format(new Date(now));
                mCachedDateMs = now;
            }
            return mCachedDateText;
        }
    }
}
