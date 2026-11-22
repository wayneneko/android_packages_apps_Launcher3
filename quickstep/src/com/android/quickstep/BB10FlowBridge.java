/*
 * SPDX-FileCopyrightText: 2026 Zinwa P26 Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep;

import static android.view.Display.DEFAULT_DISPLAY;

import android.app.ActivityOptions;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceControlViewHost;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.lang.ref.WeakReference;

/**
 * Self-contained QuickStep bridge connecting Android 17's gesture pipeline to
 * {@code org.p26.bb10launcher} (BB10Launcher).
 *
 * Responsibilities:
 * 1. Yields Home-over-Home bottom-edge touches directly to BB10Launcher when its main
 *    {@code LauncherActivity} is foreground (zero IPC for Videos 1 & 2).
 * 2. Manages lazy lifecycle-safe binding to BB10Launcher's {@code HubPeekService} for in-app
 *    left-edge drawer Hub Peek ({@link SurfaceControlViewHost.SurfacePackage}) and live unread
 *    counts (Video 3).
 * 3. Observes {@code Settings.Secure.bb10_flow_peek_hub_enabled} and stays 100% dormant whenever
 *    Launcher3 or another launcher is the user's default Home app.
 */
public final class BB10FlowBridge {

    private static final String TAG = "BB10FlowBridge";

    public static final String BB10_LAUNCHER_PACKAGE = "org.p26.bb10launcher";
    public static final String BB10_LAUNCHER_ACTIVITY = "org.p26.bb10launcher.LauncherActivity";
    public static final String ACTION_HUB_PEEK = "org.p26.bb10launcher.action.HUB_PEEK";

    public static final String EXTRA_TARGET_PAGE = "org.p26.bb10launcher.extra.TARGET_PAGE";
    public static final String EXTRA_SKIP_GESTURE_CONTRACT =
            "org.p26.bb10launcher.extra.SKIP_GESTURE_CONTRACT";
    public static final String TARGET_HUB = "hub";
    public static final String TARGET_ACTIVE_FRAMES = "active_frames";
    public static final String SECURE_SETTING_PEEK_HUB_ENABLED = "bb10_flow_peek_hub_enabled";

    // Protocol constants mirrored with org.p26.bb10launcher.hub.HubPeekService
    private static final int MSG_REGISTER_CLIENT = 1;
    private static final int MSG_REQUEST_HUB_SURFACE = 2;
    private static final int MSG_RELEASE_HUB_SURFACE = 3;

    private static final int MSG_HUB_SURFACE_PACKAGE = 101;
    private static final int MSG_UNREAD_COUNTS = 102;
    private static final int MSG_FULL_HUB_VISIBLE = 103;

    private static final String KEY_HOST_TOKEN = "host_token";
    private static final String KEY_DISPLAY_ID = "display_id";
    private static final String KEY_TOP_INSET = "top_inset";
    private static final String KEY_BOTTOM_INSET = "bottom_inset";
    private static final String KEY_SURFACE_PACKAGE = "surface_package";
    private static final String KEY_UNREAD_CALLS = "unread_calls";
    private static final String KEY_UNREAD_TOTAL = "unread_total";

    private static final long REBIND_BACKOFF_MS = 1500L;

    public interface StageListener {
        void onHubSurfacePackage(@NonNull SurfaceControlViewHost.SurfacePackage pkg, int generation);
        void onUnreadCountsChanged(int notifications, int sms, int calls);
    }

    private static volatile BB10FlowBridge sInstance;

    public static BB10FlowBridge get(@NonNull Context context) {
        BB10FlowBridge inst = sInstance;
        if (inst == null) {
            synchronized (BB10FlowBridge.class) {
                inst = sInstance;
                if (inst == null) {
                    inst = new BB10FlowBridge(context.getApplicationContext());
                    sInstance = inst;
                }
            }
        }
        return inst;
    }

    private final Context mAppContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Messenger mClientMessenger;

    private boolean mHasObserverState;
    private boolean mIsBB10DefaultHome;
    private boolean mPeekEnabled;
    private boolean mBound;
    @Nullable private Messenger mServiceMessenger;

    private int mUnreadNotifications;
    private int mUnreadSms;
    private int mUnreadCalls;

    @Nullable private SurfaceControlViewHost.SurfacePackage mCachedSurfacePackage;
    private int mCachedSurfaceW;
    private int mCachedSurfaceH;
    private int mCachedSurfaceGeneration;

    // Pending surface request replayed once cold bind connects
    @Nullable private IBinder mPendingHostToken;
    private int mPendingDisplayId = DEFAULT_DISPLAY;
    private int mPendingWidth;
    private int mPendingHeight;
    private int mPendingTopInset;
    private int mPendingBottomInset;

    @NonNull private WeakReference<StageListener> mStageListener = new WeakReference<>(null);
    @Nullable private Runnable mFullHubVisibleCallback;

    private final ContentObserver mSettingObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            refreshSettingState();
        }
    };

    private final Runnable mRebindRunnable = () -> {
        if (mIsBB10DefaultHome && mPeekEnabled && !mBound) {
            bindServiceLocked();
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceMessenger = new Messenger(service);
            sendToService(Message.obtain(null, MSG_REGISTER_CLIENT));
            if (mPendingHostToken != null && mPendingWidth > 0 && mPendingHeight > 0) {
                sendSurfaceRequestToService(
                        mPendingHostToken,
                        mPendingDisplayId,
                        mPendingWidth,
                        mPendingHeight,
                        mPendingTopInset,
                        mPendingBottomInset);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceMessenger = null;
            releaseCachedSurfacePackage();
            if (mIsBB10DefaultHome && mPeekEnabled) {
                scheduleRebind();
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            unbindServiceLocked();
            if (mIsBB10DefaultHome && mPeekEnabled) {
                scheduleRebind();
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            unbindServiceLocked();
        }
    };

    private BB10FlowBridge(Context appContext) {
        mAppContext = appContext;
        mClientMessenger = new Messenger(new Handler(Looper.getMainLooper(), this::handleServiceMessage));
        mPeekEnabled = readPeekEnabledSetting();
        try {
            mAppContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(SECURE_SETTING_PEEK_HUB_ENABLED),
                    false,
                    mSettingObserver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register secure setting observer", e);
        }
        // Defer fallback observer seeding to next main-loop turn so we NEVER re-enter Dagger's
        // DoubleCheck provider when BB10FlowBridge is first created from OverviewComponentObserver.<init>().
        mMainHandler.post(() -> {
            if (mHasObserverState) return;
            try {
                OverviewComponentObserver observer = OverviewComponentObserver.INSTANCE.get(mAppContext);
                if (observer != null) {
                    onOverviewTargetChanged(observer);
                }
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Returns true iff {@code BB10Launcher} is currently the default Home activity on
     * {@link android.view.Display#DEFAULT_DISPLAY}.
     */
    public static boolean isBB10DefaultHome(@Nullable OverviewComponentObserver observer) {
        if (observer == null || observer.isHomeAndOverviewSame()) {
            return false;
        }
        Intent homeIntent = observer.getHomeIntent(DEFAULT_DISPLAY);
        if (homeIntent == null) {
            return false;
        }
        ComponentName component = homeIntent.getComponent();
        if (component != null) {
            return BB10_LAUNCHER_PACKAGE.equals(component.getPackageName());
        }
        return BB10_LAUNCHER_PACKAGE.equals(homeIntent.getPackage());
    }

    /**
     * Returns true when {@code BB10Launcher}'s main {@code LauncherActivity} is already the
     * foreground Home activity on {@code DEFAULT_DISPLAY} with no recents animation running.
     */
    public static boolean shouldYieldHomeGestureToBB10(
            @Nullable OverviewComponentObserver observer,
            @Nullable GestureState gestureState,
            @Nullable GestureState previousGestureState) {
        if (gestureState == null || gestureState.getDisplayId() != DEFAULT_DISPLAY) {
            return false;
        }
        if (previousGestureState != null && previousGestureState.isRecentsAnimationRunning()) {
            return false;
        }
        if (!isBB10DefaultHome(observer)) {
            return false;
        }
        TopTaskTracker.CachedTaskInfo runningTask = gestureState.getRunningTask();
        if (runningTask == null || !runningTask.isHomeTask()) {
            return false;
        }
        TaskInfo baseTask = runningTask.getLegacyBaseTask();
        if (baseTask == null) {
            return false;
        }
        ComponentName top = baseTask.topActivity != null
                ? baseTask.topActivity
                : baseTask.baseActivity;
        return top != null
                && BB10_LAUNCHER_PACKAGE.equals(top.getPackageName())
                && BB10_LAUNCHER_ACTIVITY.equals(top.getClassName());
    }

    /**
     * Static convenience for {@link com.android.quickstep.fallback.RecentsState} to suppress
     * RecentsActivity blur & grey scrim whenever BB10 in-app Peek is active.
     */
    public static boolean isBB10InAppPeekActive(@Nullable Context context) {
        if (context == null) return false;
        BB10FlowBridge bridge = sInstance;
        return bridge != null && bridge.isInAppPeekActive();
    }

    /**
     * Launches BB10Launcher directly onto Active Frames (used by OverviewCommandHelper for
     * 3-button Overview / Alt+Tab / Meta+Tab when BB10Launcher is default Home).
     */
    public static boolean startBB10ActiveFrames(@NonNull Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.setComponent(new ComponentName(BB10_LAUNCHER_PACKAGE, BB10_LAUNCHER_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(EXTRA_TARGET_PAGE, TARGET_ACTIVE_FRAMES);
            intent.putExtra(EXTRA_SKIP_GESTURE_CONTRACT, true);
            ActivityOptions opts = ActivityOptions.makeBasic();
            context.startActivity(intent, opts.toBundle());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to start BB10 Active Frames", e);
            return false;
        }
    }

    /**
     * Convenience wrapper for {@link OverviewCommandHelper} when routing 3-button Overview or
     * Alt+Tab commands on {@code DEFAULT_DISPLAY} directly to BB10Launcher Active Frames.
     */
    public static boolean tryStartBB10ActiveFrames(
            @Nullable OverviewComponentObserver observer, int displayId) {
        if (displayId != DEFAULT_DISPLAY || !isBB10DefaultHome(observer)) {
            return false;
        }
        BB10FlowBridge bridge = sInstance;
        if (bridge == null) {
            return false;
        }
        return startBB10ActiveFrames(bridge.mAppContext);
    }

    public boolean isBB10DefaultHomeCached() {
        return mIsBB10DefaultHome;
    }

    public boolean isInAppPeekActive() {
        return mIsBB10DefaultHome && mPeekEnabled;
    }

    public int getUnreadNotifications() {
        return mUnreadNotifications;
    }

    public int getUnreadSms() {
        return mUnreadSms;
    }

    public int getUnreadCalls() {
        return mUnreadCalls;
    }

    public void onOverviewTargetChanged(@Nullable OverviewComponentObserver observer) {
        final boolean next = isBB10DefaultHome(observer);
        mHasObserverState = true;
        mMainHandler.post(() -> {
            mHasObserverState = true;
            if (mIsBB10DefaultHome == next) {
                if (mIsBB10DefaultHome && mPeekEnabled && !mBound) {
                    bindServiceLocked();
                }
                return;
            }
            mIsBB10DefaultHome = next;
            syncServiceBinding();
        });
    }

    public void onOverviewObserverDestroyed() {
        mMainHandler.post(() -> {
            mIsBB10DefaultHome = false;
            unbindServiceLocked();
        });
    }

    @UiThread
    public void setStageListener(@Nullable StageListener listener) {
        mStageListener = new WeakReference<>(listener);
        if (listener != null) {
            listener.onUnreadCountsChanged(mUnreadNotifications, mUnreadSms, mUnreadCalls);
            deliverCachedSurfaceCopy(listener);
        }
    }

    @UiThread
    public void clearStageListener(@NonNull StageListener listener) {
        if (mStageListener.get() == listener) {
            mStageListener = new WeakReference<>(null);
        }
    }

    @UiThread
    public void requestHubSurface(
            @Nullable IBinder hostToken,
            int displayId,
            int width,
            int height,
            int topInset,
            int bottomInset) {
        if (!isInAppPeekActive() || hostToken == null || width <= 0 || height <= 0) {
            return;
        }
        // Reuse valid cached package if geometry and token haven't changed
        if (mCachedSurfacePackage != null
                && mCachedSurfacePackage.getSurfaceControl() != null
                && mCachedSurfacePackage.getSurfaceControl().isValid()
                && hostToken == mPendingHostToken
                && width == mCachedSurfaceW
                && height == mCachedSurfaceH
                && topInset == mPendingTopInset
                && bottomInset == mPendingBottomInset) {
            StageListener listener = mStageListener.get();
            if (listener != null) {
                deliverCachedSurfaceCopy(listener);
            }
            return;
        }

        mPendingHostToken = hostToken;
        mPendingDisplayId = displayId;
        mPendingWidth = width;
        mPendingHeight = height;
        mPendingTopInset = topInset;
        mPendingBottomInset = bottomInset;

        if (!mBound) {
            bindServiceLocked();
            return;
        }
        if (mServiceMessenger != null) {
            sendSurfaceRequestToService(hostToken, displayId, width, height, topInset, bottomInset);
        }
    }

    private void deliverCachedSurfaceCopy(@NonNull StageListener listener) {
        SurfaceControlViewHost.SurfacePackage master = mCachedSurfacePackage;
        if (master == null || master.getSurfaceControl() == null
                || !master.getSurfaceControl().isValid()) {
            return;
        }
        try {
            SurfaceControlViewHost.SurfacePackage copy =
                    new SurfaceControlViewHost.SurfacePackage(master);
            listener.onHubSurfacePackage(copy, mCachedSurfaceGeneration);
        } catch (Exception e) {
            Log.w(TAG, "Failed to duplicate cached Hub SurfacePackage", e);
        }
    }

    @UiThread
    public void onHostSurfaceDestroyed() {
        mPendingHostToken = null;
        releaseCachedSurfacePackage();
        if (mServiceMessenger != null) {
            sendToService(Message.obtain(null, MSG_RELEASE_HUB_SURFACE));
        }
    }

    @UiThread
    public void awaitFullHubVisible(@Nullable Runnable callback) {
        mFullHubVisibleCallback = callback;
    }

    @UiThread
    public void clearFullHubVisibleCallback() {
        mFullHubVisibleCallback = null;
    }

    private void refreshSettingState() {
        boolean next = readPeekEnabledSetting();
        if (mPeekEnabled != next) {
            mPeekEnabled = next;
            syncServiceBinding();
        }
    }

    private boolean readPeekEnabledSetting() {
        try {
            return Settings.Secure.getInt(
                    mAppContext.getContentResolver(),
                    SECURE_SETTING_PEEK_HUB_ENABLED,
                    1) != 0;
        } catch (Exception e) {
            return true;
        }
    }

    private void syncServiceBinding() {
        mMainHandler.removeCallbacks(mRebindRunnable);
        if (mIsBB10DefaultHome && mPeekEnabled) {
            if (!mBound) {
                bindServiceLocked();
            }
        } else {
            unbindServiceLocked();
        }
    }

    private void bindServiceLocked() {
        if (mBound) return;
        try {
            Intent intent = new Intent(ACTION_HUB_PEEK);
            intent.setPackage(BB10_LAUNCHER_PACKAGE);
            mBound = mAppContext.bindService(
                    intent,
                    mConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            if (!mBound) {
                scheduleRebind();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to bind HubPeekService", e);
            mBound = false;
        }
    }

    private void unbindServiceLocked() {
        mMainHandler.removeCallbacks(mRebindRunnable);
        mServiceMessenger = null;
        mPendingHostToken = null;
        mFullHubVisibleCallback = null;
        releaseCachedSurfacePackage();
        if (mBound) {
            try {
                mAppContext.unbindService(mConnection);
            } catch (Exception ignored) {
            }
            mBound = false;
        }
    }

    private void scheduleRebind() {
        mMainHandler.removeCallbacks(mRebindRunnable);
        mMainHandler.postDelayed(mRebindRunnable, REBIND_BACKOFF_MS);
    }

    private void sendSurfaceRequestToService(
            IBinder hostToken,
            int displayId,
            int width,
            int height,
            int topInset,
            int bottomInset) {
        Message msg = Message.obtain(null, MSG_REQUEST_HUB_SURFACE, width, height);
        Bundle b = new Bundle();
        b.putBinder(KEY_HOST_TOKEN, hostToken);
        b.putInt(KEY_DISPLAY_ID, displayId);
        b.putInt(KEY_TOP_INSET, topInset);
        b.putInt(KEY_BOTTOM_INSET, bottomInset);
        msg.setData(b);
        sendToService(msg);
    }

    private void sendToService(Message msg) {
        Messenger svc = mServiceMessenger;
        if (svc == null) return;
        msg.replyTo = mClientMessenger;
        try {
            svc.send(msg);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed sending message to HubPeekService", e);
            mServiceMessenger = null;
            if (mIsBB10DefaultHome && mPeekEnabled) {
                scheduleRebind();
            }
        }
    }

    private boolean handleServiceMessage(Message msg) {
        switch (msg.what) {
            case MSG_HUB_SURFACE_PACKAGE: {
                Bundle data = msg.getData();
                if (data == null) return true;
                data.setClassLoader(SurfaceControlViewHost.SurfacePackage.class.getClassLoader());
                SurfaceControlViewHost.SurfacePackage pkg =
                        data.getParcelable(KEY_SURFACE_PACKAGE,
                                SurfaceControlViewHost.SurfacePackage.class);
                if (pkg != null) {
                    releaseCachedSurfacePackage();
                    mCachedSurfacePackage = pkg;
                    mCachedSurfaceW = msg.arg1;
                    mCachedSurfaceH = msg.arg2;
                    mCachedSurfaceGeneration++;
                    StageListener listener = mStageListener.get();
                    if (listener != null) {
                        deliverCachedSurfaceCopy(listener);
                    }
                }
                return true;
            }
            case MSG_UNREAD_COUNTS: {
                mUnreadNotifications = Math.max(0, msg.arg1);
                mUnreadSms = Math.max(0, msg.arg2);
                Bundle data = msg.getData();
                mUnreadCalls = data != null ? Math.max(0, data.getInt(KEY_UNREAD_CALLS, 0)) : 0;
                StageListener listener = mStageListener.get();
                if (listener != null) {
                    listener.onUnreadCountsChanged(mUnreadNotifications, mUnreadSms, mUnreadCalls);
                }
                return true;
            }
            case MSG_FULL_HUB_VISIBLE: {
                Runnable cb = mFullHubVisibleCallback;
                mFullHubVisibleCallback = null;
                if (cb != null) {
                    cb.run();
                }
                return true;
            }
            default:
                return false;
        }
    }

    private void releaseCachedSurfacePackage() {
        if (mCachedSurfacePackage != null) {
            try {
                mCachedSurfacePackage.release();
            } catch (Exception ignored) {
            }
            mCachedSurfacePackage = null;
            mCachedSurfaceW = 0;
            mCachedSurfaceH = 0;
        }
    }
}
