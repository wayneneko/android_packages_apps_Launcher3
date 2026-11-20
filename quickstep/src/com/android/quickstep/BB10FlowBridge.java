/*
 * SPDX-FileCopyrightText: 2026 Zinwa P26 Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.launcher3.util.VibratorWrapper.OVERVIEW_HAPTIC;
import static com.android.quickstep.OverviewComponentObserver.startHomeIntentSafely;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.util.VibratorWrapper;

/**
 * Lightweight, self-contained bridge connecting QuickStep's full-screen gesture pipeline to
 * {@code org.p26.bb10launcher} (BB10Launcher) when BB10Launcher is the user's active Home app:
 *
 * 1. Unifies swipe-up, swipe-up-and-hold, and Overview commands to BB10Launcher's Active Frames
 *    (eliminating stock Android horizontal Recents when BB10Launcher is default Home).
 * 2. Detects the signature BB10 L-gesture (swipe up from bottom edge, then bend right) inside
 *    any foreground app and streams live peek progress over a bound Messenger to BB10Launcher's
 *    translucent {@code HubPeekService} overlay.
 *
 * Completely dormant (no-op) whenever Launcher3 or any other launcher is the default Home app.
 */
public final class BB10FlowBridge {

    private static final String TAG = "BB10FlowBridge";

    public static final String BB10_LAUNCHER_PACKAGE = "org.p26.bb10launcher";
    public static final String ACTION_HUB_PEEK_SERVICE = "org.p26.bb10launcher.action.HUB_PEEK";
    public static final String EXTRA_TARGET_PAGE = "org.p26.bb10launcher.extra.TARGET_PAGE";
    public static final String EXTRA_SKIP_GESTURE_CONTRACT =
            "org.p26.bb10launcher.extra.SKIP_GESTURE_CONTRACT";
    public static final String TARGET_HUB = "hub";
    public static final String TARGET_ACTIVE_FRAMES = "active_frames";
    public static final String SECURE_SETTING_PEEK_HUB_ENABLED = "bb10_flow_peek_hub_enabled";

    private static final int MSG_PEEK_START = 1;
    private static final int MSG_PEEK_UPDATE = 2;
    private static final int MSG_PEEK_END = 3;

    // L-gesture tuning constants (dp)
    private static final float PEEK_UP_THRESHOLD_DP = 46f;
    private static final float PEEK_MIN_STAY_UP_DP = 22f;
    private static final float PEEK_TURN_RIGHT_TRIGGER_DP = 42f;
    private static final float PEEK_FULL_SPAN_DP = 140f;
    private static final float PEEK_COMMIT_PROGRESS = 0.42f;
    private static final float PEEK_UNCOMMIT_PROGRESS = 0.36f;
    private static final float PEEK_COMMIT_FLICK_MIN_PROGRESS = 0.18f;
    private static final float PEEK_COMMIT_FLICK_VX_PX_PER_MS = 0.45f;

    private static PeekHubClient sPeekClient;
    @Nullable private static Boolean sPeekHubEnabled;
    @Nullable private static ContentObserver sSettingsObserver;
    @Nullable private static Context sObserverAppContext;

    private BB10FlowBridge() {}

    public static boolean isBB10LauncherDefaultHome(@Nullable GestureState gestureState) {
        if (gestureState == null || gestureState.getDisplayId() != DEFAULT_DISPLAY) {
            return false;
        }
        return isBB10HomeIntent(gestureState.getHomeIntent());
    }

    public static boolean isBB10LauncherDefaultHome(
            @Nullable OverviewComponentObserver observer, int displayId) {
        if (observer == null || displayId != DEFAULT_DISPLAY) {
            return false;
        }
        return isBB10HomeIntent(observer.getHomeIntent(displayId));
    }

    private static boolean isBB10HomeIntent(@Nullable Intent homeIntent) {
        if (homeIntent == null) {
            return false;
        }
        ComponentName cn = homeIntent.getComponent();
        return cn != null && BB10_LAUNCHER_PACKAGE.equals(cn.getPackageName());
    }

    /**
     * Returns whether the user has enabled BB10 Flow Peek Hub overlay in BB10Launcher Settings.
     * Cached in memory and refreshed via a single lightweight ContentObserver on Settings.Secure.
     */
    @UiThread
    public static boolean isPeekHubEnabled(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        if (sSettingsObserver == null) {
            sPeekHubEnabled = readPeekHubEnabledSetting(appCtx);
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    boolean enabled = readPeekHubEnabledSetting(appCtx);
                    sPeekHubEnabled = enabled;
                    if (!enabled) {
                        unbindPeekClientOnly(appCtx);
                    }
                }
            };
            try {
                appCtx.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(SECURE_SETTING_PEEK_HUB_ENABLED),
                        false,
                        observer);
                sSettingsObserver = observer;
                sObserverAppContext = appCtx;
            } catch (Exception e) {
                Log.w(TAG, "Failed to observe " + SECURE_SETTING_PEEK_HUB_ENABLED, e);
            }
        } else if (sPeekHubEnabled == null) {
            sPeekHubEnabled = readPeekHubEnabledSetting(appCtx);
        }
        return sPeekHubEnabled == null || sPeekHubEnabled;
    }

    private static boolean readPeekHubEnabledSetting(@NonNull Context appCtx) {
        try {
            return Settings.Secure.getInt(
                    appCtx.getContentResolver(), SECURE_SETTING_PEEK_HUB_ENABLED, 1) != 0;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Annotates a Home launch intent for BB10Launcher:
     * - Committed Peek Hub -> lands directly on BlackBerry Hub (skipping icon gesture contract).
     * - In-app swipe-up / pause or redirected RECENTS -> lands on Active Frames.
     * - Home-over-Home normal swipe-up -> left undecorated so BB10Launcher runs normal Home default.
     */
    public static void decorateHomeIntent(
            @NonNull Intent intent, @Nullable GestureState gestureState) {
        if (!isBB10LauncherDefaultHome(gestureState)) {
            return;
        }
        if (gestureState != null && gestureState.isBB10PeekCommitted()) {
            intent.putExtra(EXTRA_TARGET_PAGE, TARGET_HUB);
            intent.putExtra(EXTRA_SKIP_GESTURE_CONTRACT, true);
        } else if (gestureState == null
                || gestureState.getRunningTask() == null
                || !gestureState.getRunningTask().isHomeTask()
                || gestureState.isBB10RedirectedFromRecents()) {
            intent.putExtra(EXTRA_TARGET_PAGE, TARGET_ACTIVE_FRAMES);
        }
    }

    /**
     * Launches BB10Launcher directly onto Active Frames (used for 3-button / keyboard Overview).
     */
    public static void launchActiveFrames(
            @NonNull Context context, @NonNull Intent homeIntent, @NonNull String reason) {
        Intent intent = new Intent(homeIntent);
        intent.putExtra(EXTRA_TARGET_PAGE, TARGET_ACTIVE_FRAMES);
        intent.putExtra(EXTRA_SKIP_GESTURE_CONTRACT, true);
        ActivityOptions opts = ActivityOptions.makeCustomAnimation(context, 0, 0);
        startHomeIntentSafely(context, intent, opts.toBundle(), reason);
    }

    @Nullable
    @UiThread
    public static PeekGestureTracker createPeekTracker(
            @NonNull Context context, @NonNull GestureState gestureState) {
        if (!isPeekHubEnabled(context)) {
            return null;
        }
        return new PeekGestureTracker(context, gestureState);
    }

    @UiThread
    public static void unbindPeekClientIfUnused(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        unbindPeekClientOnly(appCtx);
        if (sSettingsObserver != null && sObserverAppContext != null) {
            try {
                sObserverAppContext.getContentResolver()
                        .unregisterContentObserver(sSettingsObserver);
            } catch (Exception ignored) {
            }
            sSettingsObserver = null;
            sObserverAppContext = null;
            sPeekHubEnabled = null;
        }
    }

    @UiThread
    private static void unbindPeekClientOnly(@NonNull Context appCtx) {
        if (sPeekClient != null) {
            sPeekClient.unbind(appCtx);
            sPeekClient = null;
        }
    }

    @UiThread
    private static PeekHubClient getOrCreateClient(@NonNull Context context) {
        if (sPeekClient == null) {
            sPeekClient = new PeekHubClient(context.getApplicationContext());
        }
        sPeekClient.ensureBound();
        return sPeekClient;
    }

    /**
     * Per-gesture L-trajectory recognizer (swipe up -> bend right) driving the live Peek Hub
     * overlay in BB10Launcher.
     */
    public static final class PeekGestureTracker {

        private final Context mContext;
        private final GestureState mGestureState;
        private final float mUpThresholdPx;
        private final float mMinStayUpPx;
        private final float mTurnRightTriggerPx;
        private final float mFullSpanPx;

        private float mDownX;
        private float mDownY;
        private float mLastX;
        private float mLastY;
        private boolean mDownRecorded;
        private boolean mArmedForPeek;
        private boolean mDisarmed;
        private float mPivotX;
        private float mPivotY;
        private boolean mPeekActive;
        private boolean mCommitted;
        private boolean mFinished;
        private float mProgress;
        private int mLastSentProgressInt = -1;
        private boolean mLastSentCommitted;

        PeekGestureTracker(@NonNull Context context, @NonNull GestureState gestureState) {
            mContext = context.getApplicationContext();
            mGestureState = gestureState;
            float d = mContext.getResources().getDisplayMetrics().density;
            mUpThresholdPx = PEEK_UP_THRESHOLD_DP * d;
            mMinStayUpPx = PEEK_MIN_STAY_UP_DP * d;
            mTurnRightTriggerPx = PEEK_TURN_RIGHT_TRIGGER_DP * d;
            mFullSpanPx = PEEK_FULL_SPAN_DP * d;
        }

        @UiThread
        public void onDown(float x, float y) {
            mDownX = x;
            mDownY = y;
            mLastX = x;
            mLastY = y;
            mPivotX = x;
            mPivotY = y;
            mDownRecorded = true;
            mArmedForPeek = false;
            mDisarmed = false;
            mPeekActive = false;
            mCommitted = false;
            mFinished = false;
            mProgress = 0f;
            mLastSentProgressInt = -1;
            mLastSentCommitted = false;
            // Warm bind in background so the Peek Hub overlay has zero latency when right-turn starts
            getOrCreateClient(mContext);
        }

        /**
         * Compensates coordinates when the active touch pointer switches on ACTION_POINTER_UP.
         */
        @UiThread
        public void onPointerShift(float dx, float dy) {
            if (!mDownRecorded || mFinished) {
                return;
            }
            mDownX += dx;
            mDownY += dy;
            mLastX += dx;
            mLastY += dy;
            mPivotX += dx;
            mPivotY += dy;
        }

        @UiThread
        public void onMove(float x, float y) {
            if (!mDownRecorded || mFinished || mDisarmed) {
                return;
            }
            float stepDx = x - mLastX;
            float stepDy = y - mLastY;
            mLastX = x;
            mLastY = y;

            float upDist = mDownY - y;
            float totalDx = x - mDownX;

            if (!mArmedForPeek) {
                // Arm only when user first rises vertically within a tight ~29° cone;
                // diagonal swipe-up-right Home gestures disarm Peek Hub for the gesture.
                if (upDist >= mUpThresholdPx) {
                    if (Math.abs(totalDx) < upDist * 0.55f) {
                        mArmedForPeek = true;
                        mPivotX = x;
                        mPivotY = y;
                    } else {
                        mDisarmed = true;
                    }
                }
                return;
            }

            if (!mPeekActive) {
                if (x < mPivotX) {
                    // Gentle leftward drift while still rising before bending right
                    mPivotX = x;
                    mPivotY = y;
                } else if (-stepDy >= 2f
                        && -stepDy >= Math.abs(stepDx) * 0.9f
                        && (x - mPivotX) < mTurnRightTriggerPx * 0.5f) {
                    // Still climbing predominantly vertically; advance the L-corner upward
                    mPivotX = x;
                    mPivotY = y;
                }
                float rightFromPivot = x - mPivotX;
                float verticalDriftFromPivot = Math.abs(y - mPivotY);
                if (upDist >= mMinStayUpPx
                        && rightFromPivot >= mTurnRightTriggerPx
                        && rightFromPivot > verticalDriftFromPivot * 1.35f) {
                    mPeekActive = true;
                    mGestureState.setBB10PeekActive(true);
                    VibratorWrapper.INSTANCE.get(mContext).vibrate(OVERVIEW_HAPTIC);
                    getOrCreateClient(mContext).sendPeekStart();
                    updateProgressLocked(rightFromPivot);
                }
            } else {
                float rightFromPivot = x - mPivotX;
                updateProgressLocked(rightFromPivot);
            }
        }

        @UiThread
        private void updateProgressLocked(float rightFromPivot) {
            float raw = (rightFromPivot - mTurnRightTriggerPx) / mFullSpanPx;
            mProgress = Math.max(0f, Math.min(1f, raw));

            boolean prevCommitted = mCommitted;
            if (!mCommitted && mProgress >= PEEK_COMMIT_PROGRESS) {
                mCommitted = true;
                VibratorWrapper.INSTANCE.get(mContext).vibrate(OVERVIEW_HAPTIC);
            } else if (mCommitted && mProgress < PEEK_UNCOMMIT_PROGRESS) {
                mCommitted = false;
            }

            int progressInt = Math.round(mProgress * 1000f);
            if (mLastSentProgressInt < 0
                    || Math.abs(progressInt - mLastSentProgressInt) >= 8
                    || mCommitted != mLastSentCommitted
                    || prevCommitted != mCommitted) {
                mLastSentProgressInt = progressInt;
                mLastSentCommitted = mCommitted;
                getOrCreateClient(mContext).sendPeekUpdate(progressInt, mCommitted);
            }
        }

        public boolean isPeekActive() {
            return mPeekActive;
        }

        /**
         * Completes the L-gesture on finger release or cancel and notifies BB10Launcher's
         * Peek Hub overlay to either retract (return to app) or dissolve into full Hub.
         */
        @UiThread
        public boolean finish(float velocityXPxPerMs, boolean isCanceled) {
            if (mFinished) {
                return mCommitted;
            }
            mFinished = true;
            if (!mPeekActive) {
                return false;
            }
            boolean shouldCommit = !isCanceled && (mCommitted
                    || (mProgress >= PEEK_COMMIT_FLICK_MIN_PROGRESS
                            && velocityXPxPerMs >= PEEK_COMMIT_FLICK_VX_PX_PER_MS));
            mCommitted = shouldCommit;
            mGestureState.setBB10PeekCommitted(shouldCommit);
            getOrCreateClient(mContext).sendPeekEnd(shouldCommit);
            return shouldCommit;
        }

        @UiThread
        public void cancelIfActive() {
            if (mPeekActive && !mFinished) {
                finish(0f, /* isCanceled= */ true);
            }
        }
    }

    /**
     * Lightweight singleton Messenger client bound to {@code org.p26.bb10launcher/.hub.HubPeekService}.
     * Coalesces pending peek state if a gesture begins during cold service binding and cleanly
     * unbinds on binder death to avoid leaking ServiceConnections.
     */
    private static final class PeekHubClient implements ServiceConnection {
        private final Context mAppContext;
        @Nullable private Messenger mRemoteMessenger;
        private boolean mBindRequested;

        // Coalesced pending state replayed once onServiceConnected fires during cold bind
        private boolean mPendingStart;
        private boolean mHasPendingUpdate;
        private int mPendingProgressInt;
        private boolean mPendingCommitted;
        private boolean mHasPendingEnd;
        private boolean mPendingEndCommitted;

        PeekHubClient(@NonNull Context appContext) {
            mAppContext = appContext;
        }

        @UiThread
        void ensureBound() {
            if (mRemoteMessenger != null || mBindRequested) {
                return;
            }
            Intent serviceIntent = new Intent(ACTION_HUB_PEEK_SERVICE)
                    .setPackage(BB10_LAUNCHER_PACKAGE);
            try {
                mBindRequested = mAppContext.bindService(
                        serviceIntent, this, Context.BIND_AUTO_CREATE);
            } catch (Exception e) {
                Log.w(TAG, "Failed to bind BB10 HubPeekService", e);
                mBindRequested = false;
            }
        }

        @UiThread
        void unbind(@NonNull Context context) {
            clearPendingState();
            safeUnbind(context);
        }

        @UiThread
        private void safeUnbind(@NonNull Context context) {
            if (mBindRequested) {
                try {
                    context.unbindService(this);
                } catch (Exception ignored) {
                }
            }
            mBindRequested = false;
            mRemoteMessenger = null;
        }

        @UiThread
        private void clearPendingState() {
            mPendingStart = false;
            mHasPendingUpdate = false;
            mPendingProgressInt = 0;
            mPendingCommitted = false;
            mHasPendingEnd = false;
            mPendingEndCommitted = false;
        }

        @UiThread
        void sendPeekStart() {
            if (!sendNow(Message.obtain(null, MSG_PEEK_START))) {
                mPendingStart = true;
                mHasPendingUpdate = false;
                mHasPendingEnd = false;
                ensureBound();
            }
        }

        @UiThread
        void sendPeekUpdate(int progressInt, boolean committed) {
            if (!sendNow(Message.obtain(null, MSG_PEEK_UPDATE, progressInt, committed ? 1 : 0))) {
                mPendingStart = true;
                mHasPendingUpdate = true;
                mPendingProgressInt = progressInt;
                mPendingCommitted = committed;
                ensureBound();
            }
        }

        @UiThread
        void sendPeekEnd(boolean committed) {
            if (mRemoteMessenger != null) {
                if (!sendNow(Message.obtain(null, MSG_PEEK_END, committed ? 1 : 0, 0))) {
                    clearPendingState();
                }
                return;
            }
            if (!mPendingStart) {
                clearPendingState();
                return;
            }
            mHasPendingEnd = true;
            mPendingEndCommitted = committed;
            ensureBound();
        }

        @UiThread
        private boolean sendNow(@NonNull Message msg) {
            Messenger remote = mRemoteMessenger;
            if (remote == null) {
                msg.recycle();
                return false;
            }
            try {
                remote.send(msg);
                return true;
            } catch (RemoteException e) {
                Log.w(TAG, "HubPeekService binder died; resetting connection", e);
                msg.recycle();
                safeUnbind(mAppContext);
                return false;
            }
        }

        @Override
        @UiThread
        public void onServiceConnected(ComponentName name, IBinder service) {
            mRemoteMessenger = new Messenger(service);
            if (!mPendingStart) {
                return;
            }
            // If gesture already started and retracted before cold bind finished, skip flashing
            if (mHasPendingEnd && !mPendingEndCommitted) {
                clearPendingState();
                return;
            }
            boolean hasUpdate = mHasPendingUpdate;
            int progressInt = mPendingProgressInt;
            boolean committed = mPendingCommitted;
            boolean hasEnd = mHasPendingEnd;
            boolean endCommitted = mPendingEndCommitted;
            clearPendingState();

            if (!sendNow(Message.obtain(null, MSG_PEEK_START))) {
                return;
            }
            if (hasUpdate) {
                if (!sendNow(Message.obtain(
                        null, MSG_PEEK_UPDATE, progressInt, committed ? 1 : 0))) {
                    return;
                }
            }
            if (hasEnd) {
                sendNow(Message.obtain(null, MSG_PEEK_END, endCommitted ? 1 : 0, 0));
            }
        }

        @Override
        @UiThread
        public void onServiceDisconnected(ComponentName name) {
            mRemoteMessenger = null;
        }

        @Override
        @UiThread
        public void onBindingDied(ComponentName name) {
            clearPendingState();
            safeUnbind(mAppContext);
        }

        @Override
        @UiThread
        public void onNullBinding(ComponentName name) {
            clearPendingState();
            safeUnbind(mAppContext);
        }
    }
}
