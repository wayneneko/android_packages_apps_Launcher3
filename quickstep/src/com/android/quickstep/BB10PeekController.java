/*
 * SPDX-FileCopyrightText: 2026 Zinwa P26 Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep;

import static com.android.quickstep.GestureState.GestureEndTarget.HOME;
import static com.android.quickstep.GestureState.GestureEndTarget.LAST_TASK;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.DeviceProfile;
import com.android.quickstep.GestureState.GestureEndTarget;
import com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle;
import com.android.quickstep.util.SurfaceTransaction;
import com.android.quickstep.util.TransformParams;
import com.android.launcher3.util.VibratorWrapper;

/**
 * Self-contained physics & 4-branch state machine for in-app BB10 Flow & Peek
 * (Video 3: {@code 20261122_112818.mp4}), owned per-gesture by {@link FallbackSwipeHandler}.
 *
 * Visual & gesture rules:
 * 1. Vertical Lift (`0 .. 35% H` -> `verticalProgress 0 -> 1`):
 *    - Foreground app scales uniformly `1.0 -> 0.51`, strictly horizontally centered
 *      (`left = (W - scaledW) / 2`), resting at `top = 18% H` (`y = 18%..69%`), with sharp 0dp
 *      corners, 100% opacity, subtle shadow, and clamped floor so it never flies off the top.
 *    - Fully continuous & reversible 1:1 on downward/upward drag.
 * 2. Horizontal Hub Drawer (once `verticalProgress >= 0.70` and dragging right):
 *    - `curtainX = clamp(dx * 1.35, 0, 0.92 * W)` uncovers stationary Hub in
 *      {@link BB10PeekStageView} from left to right.
 *    - App left inset relative to curtain tightens smoothly from centered (~24.5% W) to 19% W
 *      and translates rigidly rightward with the drawer boundary, clipping cleanly at right edge.
 * 3. Four-Branch Release Resolution:
 *    - Commit Hub (`reveal >= 45%` or right flick `>= 22%`): sweeps curtain off right edge,
 *      launches BB10Launcher directly onto Hub (skipping `gesture_nav_contract_v1`), and waits
 *      for `MSG_FULL_HUB_VISIBLE` (with 320ms fallback) for zero-flash handoff.
 *    - Deep-Peek Return (`maxReveal >= 25%` pulled back left `< 16%`): smoothly expands back to
 *      100% fullscreen foreground app (`LAST_TASK`).
 *    - Vertical Cancel (low release / downward pull): expands back to foreground app (`LAST_TASK`).
 *    - Commit Active Frames (straight upward flick or high Peek release): hands off to
 *      `gesture_nav_contract_v1` `RectFSpringAnim` starting from the exact current Peek rect!
 */
final class BB10PeekController {

    enum CommitAction {
        NONE,
        ACTIVE_FRAMES,
        HUB,
        RETURN_TO_APP,
    }

    private static final float VERTICAL_PLATEAU_FRACTION = 0.35f;
    private static final float MIN_APP_SCALE = 0.53f;
    private static final float TARGET_APP_TOP_FRACTION = 0.205f;
    private static final float CURTAIN_APP_INSET_FRACTION = 0.16f;
    private static final float POP_THRESHOLD_FRAC = 0.24f;
    private static final float STICKY_MAX_REVEAL_FRAC = 0.13f;
    private static final float POP_OPEN_BASE_FRAC = 0.72f;
    private static final float PULLBACK_CLOSE_FRAC = 0.16f;
    private static final long POP_OPEN_ANIM_MS = 180L;
    private static final long PULLBACK_CLOSE_ANIM_MS = 155L;
    private static final long HUB_COMMIT_ANIM_MS = 185L;
    private static final long RETURN_APP_ANIM_MS = 190L;
    private static final long HUB_HANDOFF_TIMEOUT_MS = 320L;

    private final Context mContext;
    private final BB10FlowBridge mBridge;
    private final float mDensity;
    private final float mTouchSlop;
    private final float mFlingSpeedPxPerMs;
    private final float mShadowRadiusPx;
    private final int mDisplayW;
    private final int mDisplayH;
    private final DecelerateInterpolator mDecelerate = new DecelerateInterpolator();
    private final OvershootInterpolator mPopOvershoot = new OvershootInterpolator(0.35f);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final Matrix mAppMatrix = new Matrix();
    private final Rect mAppCrop = new Rect();
    private final RectF mCurrentAppRect = new RectF();

    @Nullable private RecentsActivity mRecentsActivity;
    @Nullable private BB10PeekStageView mStageView;
    @Nullable private RemoteTargetHandle[] mLastRemoteHandles;
    @Nullable private ValueAnimator mPopAnimator;
    @Nullable private ValueAnimator mSettleAnimator;
    @Nullable private ValueAnimator mChromeFadeAnimator;
    @Nullable private Runnable mHubHandoffTimeoutRunnable;

    private boolean mStageAttached;
    private boolean mTouchInitialized;
    private float mDownX;
    private float mDownY;
    private float mLastX;
    private float mLastY;
    private float mPeakUpDist;

    private float mVerticalProgress;     // 0..1
    private float mCurtainX;             // 0..W
    private float mMaxHubRevealFrac;
    private float mDateAlpha;
    private float mBellAlpha;
    private boolean mHubDrawerEngaged;
    private boolean mHubDrawerPopped;
    private boolean mEverPoppedHub;
    private float mHorizontalAnchorX;
    private float mPopAnchorX;
    private float mPopPeakX;
    private float mPopOffsetPx;
    private float mDrawerEngageVp;
    private boolean mPeekHapticPlayed;
    private boolean mInvalidated;

    private CommitAction mCommitAction = CommitAction.NONE;

    BB10PeekController(@NonNull Context context, @NonNull DeviceProfile dp) {
        mContext = context;
        mBridge = BB10FlowBridge.get(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mFlingSpeedPxPerMs = context.getResources().getDimension(
                com.android.launcher3.R.dimen.quickstep_fling_threshold_speed);
        mShadowRadiusPx = 14f * mDensity;
        mDisplayW = Math.max(1, dp.getDeviceProperties().getWidthPx());
        mDisplayH = Math.max(1, dp.getDeviceProperties().getHeightPx());
        mCurrentAppRect.set(0f, 0f, mDisplayW, mDisplayH);
    }

    @UiThread
    void attachContainer(@Nullable RecentsActivity activity) {
        if (mInvalidated || activity == null) return;
        if (mRecentsActivity == activity && mStageAttached) return;
        mRecentsActivity = activity;
        mStageView = activity.setBB10PeekMode(true, this);
        mStageAttached = mStageView != null;
        if (mStageView != null) {
            float appLeft = computeAppLeft(mVerticalProgress, mCurtainX);
            mStageView.updateProgress(mVerticalProgress, mCurtainX, appLeft, mBellAlpha, mDateAlpha);
        }
    }

    @UiThread
    void onTouchDown(float x, float y) {
        mTouchInitialized = true;
        mDownX = x;
        mDownY = y;
        mLastX = x;
        mLastY = y;
        mPeakUpDist = 0f;
    }

    @UiThread
    void onPointerShift(float deltaX, float deltaY) {
        if (!mTouchInitialized) return;
        mDownX += deltaX;
        mDownY += deltaY;
        if (mHubDrawerEngaged) {
            mHorizontalAnchorX += deltaX;
            mPopAnchorX += deltaX;
            mPopPeakX += deltaX;
        }
        mLastX += deltaX;
        mLastY += deltaY;
    }

    private float computeStickyCurtainX(float x) {
        float dxFrac = Math.max(0f, (x - mHorizontalAnchorX) / mDisplayW);
        float p = Math.max(0f, Math.min(1f, dxFrac / POP_THRESHOLD_FRAC));
        // Rubbery sticky resistance: 0% -> ~13% W over 0..24% W drag (Video 3 `00:17`)
        return mDisplayW * STICKY_MAX_REVEAL_FRAC * (0.65f * p + 0.35f * p * p);
    }

    private float computePoppedCurtainX(float x) {
        float raw = mDisplayW * POP_OPEN_BASE_FRAC + (x - mPopAnchorX) * 0.85f;
        return Math.max(mDisplayW * 0.54f, Math.min(mDisplayW * 0.92f, raw));
    }

    private float computeCurrentDrawerTargetX(float x) {
        if (!mHubDrawerEngaged) return 0f;
        return mHubDrawerPopped ? computePoppedCurtainX(x) : computeStickyCurtainX(x);
    }

    private void cancelPopAnimator() {
        if (mPopAnimator != null) {
            mPopAnimator.cancel();
            mPopAnimator = null;
        }
        mPopOffsetPx = 0f;
    }

    private void animatePopOffset(float fromOffset, long durationMs, boolean useOvershoot) {
        if (mPopAnimator != null) {
            mPopAnimator.cancel();
        }
        mPopOffsetPx = fromOffset;
        ValueAnimator anim = ValueAnimator.ofFloat(fromOffset, 0f);
        anim.setDuration(durationMs);
        anim.setInterpolator(useOvershoot ? mPopOvershoot : mDecelerate);
        anim.addUpdateListener(a -> {
            if (mInvalidated || mCommitAction != CommitAction.NONE) return;
            mPopOffsetPx = (Float) a.getAnimatedValue();
            float target = computeCurrentDrawerTargetX(mLastX);
            mCurtainX = Math.max(0f, Math.min(mDisplayW * 0.94f, target + mPopOffsetPx));
            float rf = mCurtainX / mDisplayW;
            if (rf > mMaxHubRevealFrac) {
                mMaxHubRevealFrac = rf;
            }
            if (mLastRemoteHandles != null && mLastRemoteHandles.length > 0) {
                applyVisualStateToHandles(
                        mLastRemoteHandles, mVerticalProgress, mCurtainX, mBellAlpha, mDateAlpha);
            } else if (mStageView != null) {
                float appLeft = computeAppLeft(mVerticalProgress, mCurtainX);
                mStageView.updateProgress(mVerticalProgress, mCurtainX, appLeft, mBellAlpha, mDateAlpha);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mPopAnimator == animation) {
                    mPopAnimator = null;
                    mPopOffsetPx = 0f;
                }
            }
        });
        mPopAnimator = anim;
        anim.start();
    }

    @UiThread
    void onTouchMove(float x, float y) {
        if (mInvalidated || mCommitAction != CommitAction.NONE) return;
        if (!mTouchInitialized) {
            onTouchDown(x, y);
        }
        mLastX = x;
        mLastY = y;

        final float upDist = Math.max(0f, mDownY - y);
        if (upDist > mPeakUpDist) {
            mPeakUpDist = upDist;
        }

        final float plateauPx = mDisplayH * VERTICAL_PLATEAU_FRACTION;
        if (!mHubDrawerEngaged) {
            mVerticalProgress = Math.max(0f, Math.min(1f, upDist / plateauPx));
            if (mVerticalProgress >= 0.82f && !mPeekHapticPlayed) {
                mPeekHapticPlayed = true;
                VibratorWrapper.INSTANCE.get(mContext).vibrate(VibratorWrapper.OVERVIEW_HAPTIC);
            }

            // Engage Stage 2 sticky horizontal Hub Drawer once in Peek plateau & dragging right
            float dxFromDown = x - mDownX;
            if (mVerticalProgress >= 0.70f && dxFromDown > mTouchSlop * 1.4f) {
                mHubDrawerEngaged = true;
                mHubDrawerPopped = false;
                mHorizontalAnchorX = x;
                mDrawerEngageVp = mVerticalProgress;
                cancelPopAnimator();
                mCurtainX = 0f;
            }
        } else {
            if (!mHubDrawerPopped) {
                float dxFrac = Math.max(0f, (x - mHorizontalAnchorX) / mDisplayW);
                if (dxFrac >= POP_THRESHOLD_FRAC) {
                    // Breakaway latch crossed -> elastic POP-OPEN from sticky ~13% to ~72% W (Video 3 `00:23`)
                    float curShown = mCurtainX;
                    mHubDrawerPopped = true;
                    mEverPoppedHub = true;
                    mPopAnchorX = x;
                    mPopPeakX = x;
                    float openTarget = computePoppedCurtainX(x);
                    VibratorWrapper.INSTANCE.get(mContext).vibrate(VibratorWrapper.OVERVIEW_HAPTIC);
                    animatePopOffset(curShown - openTarget, POP_OPEN_ANIM_MS, true);
                    mCurtainX = Math.max(0f, Math.min(mDisplayW * 0.94f, openTarget + mPopOffsetPx));
                } else if (x < mHorizontalAnchorX - mTouchSlop && mCurtainX <= 4f * mDensity) {
                    // Reversed cleanly out of sticky Hub Peek back into vertical Peek
                    mHubDrawerEngaged = false;
                    cancelPopAnimator();
                    mCurtainX = 0f;
                    mVerticalProgress = Math.max(0f, Math.min(1f, upDist / plateauPx));
                } else {
                    float baseCurtain = computeStickyCurtainX(x);
                    mCurtainX = Math.max(0f, Math.min(mDisplayW * 0.94f, baseCurtain + mPopOffsetPx));
                }
            } else {
                if (x > mPopPeakX) {
                    mPopPeakX = x;
                }
                if ((mPopPeakX - x) >= mDisplayW * PULLBACK_CLOSE_FRAC) {
                    // Elastic pullback close (Video 3 `00:24`): closes Hub cleanly without returning to downX
                    float curShown = mCurtainX;
                    mHubDrawerPopped = false;
                    mHorizontalAnchorX = x;
                    animatePopOffset(curShown, PULLBACK_CLOSE_ANIM_MS, false);
                    mCurtainX = Math.max(0f, Math.min(mDisplayW * 0.94f, mPopOffsetPx));
                } else {
                    float openTarget = computePoppedCurtainX(x);
                    mCurtainX = Math.max(0f, Math.min(mDisplayW * 0.94f, openTarget + mPopOffsetPx));
                }
            }

            float revealFrac = mCurtainX / mDisplayW;
            if (revealFrac > mMaxHubRevealFrac) {
                mMaxHubRevealFrac = revealFrac;
            }
            if (mHubDrawerEngaged) {
                float ramp = Math.min(1f, revealFrac / 0.14f);
                mVerticalProgress = Math.max(
                        lerp(mDrawerEngageVp, 1f, ramp),
                        Math.min(1f, upDist / plateauPx));
            }
        }

        // Bell slides physically from behind the left edge at 100% solid white (NO alpha fade-in/out!)
        mBellAlpha = (mVerticalProgress > 0.005f || mCurtainX > 0.5f) ? 1f : 0f;

        // Date bar fades in near vertical Peek plateau and persists while reversing down (Video 3 00:08)
        float targetDate = Math.max(0f, Math.min(1f, (mVerticalProgress - 0.55f) / 0.35f));
        if (mDateAlpha > 0.5f && (mVerticalProgress > 0.15f || mCurtainX > 1f)) {
            mDateAlpha = Math.max(mDateAlpha, targetDate);
        } else {
            mDateAlpha = targetDate;
        }
    }

    /**
     * Applies Video 3's exact transform (centered 0dp square scaled down to ~53% at top=20.5% H,
     * sliding rightward rigidly with `curtainX` during Hub Peek) directly to the closing app leash.
     */
    @UiThread
    boolean applyTransform(@Nullable RemoteTargetHandle[] remoteHandles) {
        if (mInvalidated || remoteHandles == null || remoteHandles.length == 0) {
            return false;
        }
        mLastRemoteHandles = remoteHandles;
        applyVisualStateToHandles(
                remoteHandles, mVerticalProgress, mCurtainX, mBellAlpha, mDateAlpha);
        return true;
    }

    private float computeAppLeft(float verticalProgress, float curtainX) {
        final float scale = lerp(1f, MIN_APP_SCALE, verticalProgress);
        final float scaledW = mDisplayW * scale;
        final float centeredLeft = (mDisplayW - scaledW) * 0.5f;
        if (curtainX <= 0.5f) {
            return centeredLeft;
        }
        // Video 3 parallax tightening: as curtain opens 0 -> 50% W, app left inset from
        // curtain tightens smoothly from centered (23.5% W) down to 16% W (`00:18` ~22%, `00:23` ~16%).
        final float revealFrac = Math.max(0f, Math.min(1f, curtainX / mDisplayW));
        final float insetBlend = Math.min(1f, revealFrac / 0.50f);
        final float curtainInset = lerp(centeredLeft, mDisplayW * CURTAIN_APP_INSET_FRACTION, insetBlend);
        return curtainX + curtainInset;
    }

    private void applyVisualStateToHandles(
            @NonNull RemoteTargetHandle[] remoteHandles,
            float verticalProgress,
            float curtainX,
            float bellAlpha,
            float dateAlpha) {
        final float scale = lerp(1f, MIN_APP_SCALE, verticalProgress);
        final float scaledW = mDisplayW * scale;
        final float scaledH = mDisplayH * scale;

        final float targetTop = lerp(0f, mDisplayH * TARGET_APP_TOP_FRACTION, verticalProgress);
        final float targetLeft = computeAppLeft(verticalProgress, curtainX);

        mCurrentAppRect.set(targetLeft, targetTop, targetLeft + scaledW, targetTop + scaledH);

        for (RemoteTargetHandle handle : remoteHandles) {
            TransformParams params = handle.getTransformParams();
            if (params == null || params.getTargetSet() == null) continue;
            params.setTargetAlpha(1f);
            SurfaceTransaction st = params.createSurfaceParams((builder, app, tp) -> {
                int appW = app.localBounds.width() > 0 ? app.localBounds.width() : mDisplayW;
                int appH = app.localBounds.height() > 0 ? app.localBounds.height() : mDisplayH;
                mAppCrop.set(0, 0, appW, appH);
                float sx = scaledW / appW;
                float sy = scaledH / appH;
                mAppMatrix.setScale(sx, sy);
                mAppMatrix.postTranslate(targetLeft, targetTop);
                builder.setMatrix(mAppMatrix)
                        .setWindowCrop(mAppCrop)
                        .setCornerRadius(0f)
                        .setShadowRadius(verticalProgress > 0.02f ? mShadowRadiusPx : 0f)
                        .setAlpha(1f);
            });
            params.applySurfaceParams(st);
        }

        if (mStageView != null) {
            mStageView.updateProgress(verticalProgress, curtainX, targetLeft, bellAlpha, dateAlpha);
        }
    }

    @UiThread
    @NonNull
    GestureEndTarget resolveEndTarget(
            @NonNull PointF velocityPxPerMs,
            boolean isFlingY,
            boolean isCancel) {
        cancelPopAnimator();
        final float revealFrac = Math.max(0f, Math.min(1f, mCurtainX / mDisplayW));
        final float upDist = Math.max(0f, mDownY - mLastY);
        final float dxFromAnchorFrac = mHubDrawerEngaged
                ? Math.max(0f, (mLastX - mHorizontalAnchorX) / mDisplayW)
                : 0f;

        if (isCancel) {
            mCommitAction = CommitAction.RETURN_TO_APP;
            return LAST_TASK;
        }

        // Branch 1: Commit to full interactive BlackBerry Hub (popped open and not flicking left,
        // or fast rightward flick past light sticky peek)
        boolean isRightFling = velocityPxPerMs.x > mFlingSpeedPxPerMs;
        boolean isLeftFling = velocityPxPerMs.x < -mFlingSpeedPxPerMs;
        if ((mHubDrawerPopped && !isLeftFling)
                || (mHubDrawerEngaged && revealFrac >= 0.45f)
                || (mHubDrawerEngaged && dxFromAnchorFrac >= 0.12f && isRightFling)) {
            mCommitAction = CommitAction.HUB;
            return HOME;
        }

        // Branch 2: Deep Hub peek pulled back left OR light sticky Hub peek released -> return to app
        if (mEverPoppedHub || mHubDrawerEngaged) {
            mCommitAction = CommitAction.RETURN_TO_APP;
            return LAST_TASK;
        }

        // Branch 3: Vertical cancel (pulled down or released low without upward fling)
        boolean isDownFling = velocityPxPerMs.y > mFlingSpeedPxPerMs * 0.5f;
        boolean pulledDownSignificantly = (mPeakUpDist - upDist) > 36f * mDensity
                && velocityPxPerMs.y >= -0.05f;
        boolean isUpFling = isFlingY
                && -velocityPxPerMs.y > mFlingSpeedPxPerMs
                && Math.abs(velocityPxPerMs.y) >= Math.abs(velocityPxPerMs.x)
                && !pulledDownSignificantly;
        if (isDownFling || pulledDownSignificantly || (mVerticalProgress < 0.35f && !isUpFling)) {
            mCommitAction = CommitAction.RETURN_TO_APP;
            return LAST_TASK;
        }

        // Branch 4: Straight upward swipe or high vertical Peek release -> BB10 Active Frames
        mCommitAction = CommitAction.ACTIVE_FRAMES;
        return HOME;
    }

    boolean isCommittingHub() {
        return mCommitAction == CommitAction.HUB;
    }

    boolean isCommittingActiveFrames() {
        return mCommitAction == CommitAction.ACTIVE_FRAMES;
    }

    boolean isReturningToApp() {
        return mCommitAction == CommitAction.RETURN_TO_APP;
    }

    @NonNull
    RectF getCurrentAppScreenRect() {
        return new RectF(mCurrentAppRect);
    }

    /**
     * Fades out stage chrome quickly as stock `RectFSpringAnim` springs the app leash into its
     * Active Frame card slot.
     */
    @UiThread
    void onActiveFramesSpringStarted() {
        if (mStageView == null) return;
        if (mChromeFadeAnimator != null) {
            mChromeFadeAnimator.cancel();
        }
        mChromeFadeAnimator = ValueAnimator.ofFloat(1f, 0f);
        mChromeFadeAnimator.setDuration(110L);
        mChromeFadeAnimator.setInterpolator(mDecelerate);
        mChromeFadeAnimator.addUpdateListener(a -> {
            if (mStageView != null && !mInvalidated) {
                mStageView.setChromeOverlayAlpha((Float) a.getAnimatedValue());
            }
        });
        mChromeFadeAnimator.start();
    }

    /**
     * Runs smooth custom animation for {@link CommitAction#RETURN_TO_APP} (`LAST_TASK`):
     * interpolates `(verticalProgress -> 0, curtainX -> 0)` so the app window smoothly expands
     * back to 100% fullscreen over the fading unread badges, then invokes {@code onComplete}.
     */
    @UiThread
    @NonNull
    SwipeUpAnimationLogic.RunningWindowAnim startReturnToAppAnimation(
            @Nullable RemoteTargetHandle[] remoteHandles,
            @NonNull Runnable onComplete) {
        cancelSettleAnimator();
        final float startVp = mVerticalProgress;
        final float startCx = mCurtainX;
        final float startBell = mBellAlpha;
        final float startDate = mDateAlpha;

        if (remoteHandles == null || remoteHandles.length == 0
                || (startVp < 0.01f && startCx < 1f)) {
            onComplete.run();
            return NO_OP_RUNNING_ANIM;
        }

        final boolean[] cancelled = {false};
        final boolean[] finished = {false};

        mSettleAnimator = ValueAnimator.ofFloat(0f, 1f);
        mSettleAnimator.setDuration(RETURN_APP_ANIM_MS);
        mSettleAnimator.setInterpolator(mDecelerate);
        mSettleAnimator.addUpdateListener(anim -> {
            if (mInvalidated || cancelled[0]) return;
            float t = (Float) anim.getAnimatedValue();
            float curVp = lerp(startVp, 0f, t);
            float curCx = lerp(startCx, 0f, t);
            applyVisualStateToHandles(
                    remoteHandles,
                    curVp,
                    curCx,
                    (curVp > 0.005f || curCx > 0.5f) ? startBell : 0f,
                    lerp(startDate, 0f, t));
        });
        mSettleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled[0] = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mSettleAnimator = null;
                if (finished[0] || cancelled[0] || mInvalidated) return;
                finished[0] = true;
                applyVisualStateToHandles(remoteHandles, 0f, 0f, 0f, 0f);
                onComplete.run();
            }
        });
        mSettleAnimator.start();
        final ValueAnimator running = mSettleAnimator;
        return new SwipeUpAnimationLogic.RunningWindowAnim() {
            @Override
            public void end() {
                if (running != null) running.end();
            }

            @Override
            public void cancel() {
                cancelled[0] = true;
                if (running != null) running.cancel();
            }
        };
    }

    /**
     * Runs smooth custom animation for {@link CommitAction#HUB}:
     * sweeps `curtainX -> W` (sliding the app leash & unread badges off the right edge while
     * uncovering 100% of the stationary Hub `SurfacePackage`), immediately triggers
     * {@code launchHubAction}, and invokes {@code onComplete} once BOTH the curtain animation
     * finishes AND BB10Launcher signals {@code MSG_FULL_HUB_VISIBLE} (or a 320ms fallback fires).
     */
    @UiThread
    @NonNull
    SwipeUpAnimationLogic.RunningWindowAnim startCommitHubAnimation(
            @Nullable RemoteTargetHandle[] remoteHandles,
            @NonNull Runnable launchHubAction,
            @NonNull Runnable onComplete) {
        cancelSettleAnimator();
        final float startCx = mCurtainX;
        final float startVp = Math.max(mVerticalProgress, 0.85f);
        final float startBell = mBellAlpha;
        final float startDate = mDateAlpha;

        final boolean[] animDone = {false};
        final boolean[] hubVisible = {false};
        final boolean[] completed = {false};
        final boolean[] cancelled = {false};

        final Runnable tryComplete = () -> {
            if (completed[0] || cancelled[0] || mInvalidated) return;
            if (animDone[0] && hubVisible[0]) {
                completed[0] = true;
                cancelHubHandoffWait();
                onComplete.run();
            }
        };

        mBridge.awaitFullHubVisible(() -> {
            hubVisible[0] = true;
            tryComplete.run();
        });
        mHubHandoffTimeoutRunnable = () -> {
            hubVisible[0] = true;
            tryComplete.run();
        };
        mMainHandler.postDelayed(mHubHandoffTimeoutRunnable, HUB_COMMIT_ANIM_MS + HUB_HANDOFF_TIMEOUT_MS);

        // Launch BB10Launcher onto TARGET_HUB in parallel while curtain slides off screen
        launchHubAction.run();

        if (remoteHandles != null && remoteHandles.length > 0) {
            mSettleAnimator = ValueAnimator.ofFloat(0f, 1f);
            mSettleAnimator.setDuration(HUB_COMMIT_ANIM_MS);
            mSettleAnimator.setInterpolator(mDecelerate);
            mSettleAnimator.addUpdateListener(anim -> {
                if (mInvalidated || cancelled[0]) return;
                float t = (Float) anim.getAnimatedValue();
                float cx = lerp(startCx, mDisplayW, t);
                applyVisualStateToHandles(
                        remoteHandles, startVp, cx, startBell, lerp(startDate, 0f, t));
            });
            mSettleAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled[0] = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mSettleAnimator = null;
                    if (mInvalidated || cancelled[0]) return;
                    applyVisualStateToHandles(remoteHandles, startVp, mDisplayW, 0f, 0f);
                    animDone[0] = true;
                    tryComplete.run();
                }
            });
            mSettleAnimator.start();
        } else {
            animDone[0] = true;
            tryComplete.run();
        }

        return new SwipeUpAnimationLogic.RunningWindowAnim() {
            @Override
            public void end() {
                if (mSettleAnimator != null) mSettleAnimator.end();
                hubVisible[0] = true;
                animDone[0] = true;
                tryComplete.run();
            }

            @Override
            public void cancel() {
                cancelled[0] = true;
                cancelSettleAnimator();
                cancelHubHandoffWait();
            }
        };
    }

    /**
     * Ensures the newly appeared BB10Launcher Home surface in {@code belowLayers} is opaque and
     * ready underneath {@code RecentsActivity}'s stationary Hub preview before transition finish.
     */
    @UiThread
    void onHubHomeTaskAppeared(@NonNull RemoteAnimationTarget homeTarget) {
        if (homeTarget.leash == null || !homeTarget.leash.isValid()) return;
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.setAlpha(homeTarget.leash, 1f);
            t.setPosition(homeTarget.leash, 0f, 0f);
            t.setMatrix(homeTarget.leash, 1f, 0f, 0f, 1f);
            t.show(homeTarget.leash);
            t.apply();
        } catch (RuntimeException ignored) {
        }
    }

    @UiThread
    void invalidate() {
        if (mInvalidated) return;
        mInvalidated = true;
        cancelPopAnimator();
        cancelSettleAnimator();
        if (mChromeFadeAnimator != null) {
            mChromeFadeAnimator.cancel();
            mChromeFadeAnimator = null;
        }
        cancelHubHandoffWait();
        if (mRecentsActivity != null && mStageAttached) {
            mRecentsActivity.setBB10PeekMode(false, this);
        }
        mLastRemoteHandles = null;
        mRecentsActivity = null;
        mStageView = null;
        mStageAttached = false;
    }

    private static final SwipeUpAnimationLogic.RunningWindowAnim NO_OP_RUNNING_ANIM =
            new SwipeUpAnimationLogic.RunningWindowAnim() {
                @Override
                public void end() {}

                @Override
                public void cancel() {}
            };

    private void cancelSettleAnimator() {
        if (mSettleAnimator != null) {
            mSettleAnimator.cancel();
            mSettleAnimator = null;
        }
    }

    private void cancelHubHandoffWait() {
        if (mHubHandoffTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mHubHandoffTimeoutRunnable);
            mHubHandoffTimeoutRunnable = null;
        }
        mBridge.clearFullHubVisibleCallback();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
