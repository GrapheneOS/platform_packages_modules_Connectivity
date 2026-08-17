package com.android.server.connectivity;

import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Looper;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalManagerRegistry;
import com.android.server.am.ActivityManagerLocal;

import java.io.FileDescriptor;
import java.util.Objects;

// Based on com.android.server.connectivity.LocalNetEventListener.
public class SkBindToDeviceEventListener {
    private static final String TAG = SkBindToDeviceEventListener.class.getSimpleName();

    private final ActivityManagerLocal mActivityManager;
    private final Dependencies mDeps;
    private final FileDescriptor mRingbufFd;
    private final Looper mLooper;

    public SkBindToDeviceEventListener(@NonNull Context context, @NonNull Looper looper) {
        this(new Dependencies(), context, looper);
    }

    @VisibleForTesting
    public SkBindToDeviceEventListener(@NonNull final Dependencies deps, @NonNull Context context,
            @NonNull Looper looper) {
        Objects.requireNonNull(context);
        mActivityManager = LocalManagerRegistry.getManager(ActivityManagerLocal.class);
        mDeps = deps;
        mRingbufFd = mDeps.getFileDescriptor();
        mLooper = looper;
    }

    public static class Dependencies {
        public FileDescriptor getFileDescriptor() {
            return nativeGetSkBindToDeviceEventRingbufFd();
        }

        public int[] consumeEvents() {
            return nativeConsumeAllSkBindToDeviceEvents();
        }
    }

    public void start() {
        mLooper.getQueue().addOnFileDescriptorEventListener(mRingbufFd,
                EVENT_INPUT | EVENT_ERROR, this::consumeEvents);
    }

    /**
     * @return The events to listen for next, or 0 to stop listening.
     */
    private int consumeEvents(FileDescriptor fd, int events) {
        if (!mRingbufFd.equals(fd)) {
            Log.w(TAG, "Received event for unexpected FD");
            return 0;
        }

        if ((events & EVENT_ERROR) != 0) {
            Log.e(TAG, "Error event on the ring buffer FD");
            return 0;
        }

        if ((events & EVENT_INPUT) != 0) {
            int[] uidsPids = mDeps.consumeEvents();
            if (uidsPids.length % 2 != 0) {
                Log.e(TAG, "Received malformed UID/PID list from native layer");
                return 0;
            }
            for (int i = 0; i < uidsPids.length; i += 2) {
                mActivityManager.showAppStrictLeakBlockingNotification(uidsPids[i], uidsPids[i+1]);
            }
        }

        return EVENT_INPUT | EVENT_ERROR;
    }

    public static native FileDescriptor nativeGetSkBindToDeviceEventRingbufFd();

    public static native int[] nativeConsumeAllSkBindToDeviceEvents();
}
