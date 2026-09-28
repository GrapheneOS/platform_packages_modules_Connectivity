/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import static android.Manifest.permission.NETWORK_STACK;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.net.module.util.HandlerUtils;

import java.util.List;
import java.util.function.Consumer;

/**
 * Helper class for ConnectivityService to listen for broadcast intents and
 * provide callbacks on a given {@link HandlerThread}.
 */
public class BroadcastReceiveHelper {
    private static final String TAG = BroadcastReceiveHelper.class.getSimpleName();
    private final Context mContext;
    private final Handler mHandler;
    private final Delegate mCallback;
    private final DeferredBroadcastReceiver mPackageIntentReceiver;
    private final DeferredBroadcastReceiver mUserIntentReceiver;
    private final DeferredBroadcastReceiver mExternalAppIntentReceiver;
    private final DeferredBroadcastReceiver mUidIntentReceiver;

    /**
     * Interface defining the callback methods for package and user related events.
     */
    public interface Delegate {
        /**
         * Called when a new package has been installed.
         *
         * @param packageName The name of the package that was added.
         * @param uid         The user ID of the application that was added.
         */
        void onPackageAdded(String packageName, int uid);

        /**
         * Called when a package has been uninstalled.
         *
         * @param packageName The name of the package that was removed.
         * @param uid         The user ID of the application that was removed.
         */
        void onPackageRemoved(String packageName, int uid);

        /**
         * Called when a uid has been removed (all packages using that uid have been removed).
         *
         * @param uid The uid that has been removed.
         */
        void onUidRemoved(int uid);

        /**
         * Called when an existing package has been replaced with a new version.
         *
         * @param packageName The name of the package that was replaced.
         * @param uid         The user ID of the application that was replaced.
         */
        void onPackageReplaced(String packageName, int uid);

        /**
         * Called when the availability of external applications changes.
         *
         * @param pkgList An array of package names that have become available.
         */
        void onExternalApplicationsAvailable(String[] pkgList);

        /**
         * Called when a new user has been added to the device.
         *
         * Note that {@link #callOnUserAddedForExistingUsers} will iterate through all
         * existing users on the device and triggers the {@link Delegate#onUserAdded(UserHandle)}
         * callback inline for each user.
         * This is needed to ensure the callback is invoked for users
         * that were present before this listener was started.
         *
         * @param userHandle The {@link UserHandle} of the user that was added.
         */
        void onUserAdded(UserHandle userHandle);

        /**
         * Called when a user has been removed from the device.
         *
         * @param userHandle The {@link UserHandle} of the user that was removed.
         */
        void onUserRemoved(UserHandle userHandle);

        /**
         * Called when a user has been started.
         *
         * @param userHandle The {@link UserHandle} of the user that was started.
         */
        void onUserStarted(UserHandle userHandle);

        /**
         * Called when a user has been stopped.
         *
         * @param userHandle The {@link UserHandle} of the user that was stopped.
         */
        void onUserStopped(UserHandle userHandle);
    }

    /**
     * Constructs a new {@code ConnectivityBroadcastReceiveHelper}.
     *
     * @param context The {@link Context} to register the broadcast receiver.
     * @param handler The handler where the callback methods will be executed.
     * @param callback The {@link Delegate} implementation that will receive the broadcast events.
     */
    public BroadcastReceiveHelper(@NonNull Context context,
            @NonNull final Handler handler, @NonNull Delegate callback) {
        mContext = context;
        mHandler = handler;
        mCallback = callback;
        mPackageIntentReceiver = new DeferredBroadcastReceiver(mHandler, this::handlePackageIntent);
        mUidIntentReceiver = new DeferredBroadcastReceiver(mHandler, this::handleUidIntent);
        mExternalAppIntentReceiver =
                new DeferredBroadcastReceiver(mHandler, this::handleExternalAppIntent);
        mUserIntentReceiver = new DeferredBroadcastReceiver(mHandler, this::handleUserIntent);
    }

    /**
     * Registers the broadcast receivers for package and user related intents.
     * The callbacks for these events will be executed on the {@link HandlerThread}
     * provided in the constructor.
     */
    public void registerReceivers() {
        final Context userAllContext = mContext.createContextAsUser(UserHandle.ALL, 0 /* flags */);

        // Listen to user events.
        final IntentFilter userIntentFilter = new IntentFilter();
        userIntentFilter.addAction(Intent.ACTION_USER_ADDED);
        userIntentFilter.addAction(Intent.ACTION_USER_REMOVED);
        userIntentFilter.addAction(Intent.ACTION_USER_STARTED);
        userIntentFilter.addAction(Intent.ACTION_USER_STOPPED);
        userAllContext.registerReceiver(mUserIntentReceiver, userIntentFilter,
                NETWORK_STACK, mHandler);

        // Listen to package change events.
        final IntentFilter packageIntentFilter = new IntentFilter();
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageIntentFilter.addDataScheme("package");
        userAllContext.registerReceiver(mPackageIntentReceiver, packageIntentFilter,
                NETWORK_STACK, mHandler);

        final IntentFilter uidIntentFilter = new IntentFilter();
        uidIntentFilter.addAction(Intent.ACTION_UID_REMOVED);
        userAllContext.registerReceiver(mUidIntentReceiver, uidIntentFilter,
                NETWORK_STACK, mHandler);

        // For PermissionMonitor, listen to EXTERNAL_APPLICATIONS_AVAILABLE is that an app
        // becoming available means it may need to gain a permission. But an app that becomes
        // unavailable can neither gain nor lose permissions on that account, it just can no
        // longer run. Thus, doesn't need to listen to EXTERNAL_APPLICATIONS_UNAVAILABLE.
        final IntentFilter externalIntentFilter =
                new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        userAllContext.registerReceiver(mExternalAppIntentReceiver, externalIntentFilter,
                NETWORK_STACK, mHandler);
    }

    /**
     * Iterates through all existing users on the device and trigger the
     * {@link Delegate#onUserAdded(UserHandle)} callback inline for each user.
     * This method should be called to ensure the callback is invoked for users
     * that were present before this listener was started.
     */
    @SuppressLint("MissingPermission")
    public void callOnUserAddedForExistingUsers() {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);
        final UserManager userManager = mContext.getSystemService(UserManager.class);
        final List<UserHandle> users = userManager.getUserHandles(true /* excludeDying */);
        for (final UserHandle user : users) {
            mCallback.onUserAdded(user);
        }
    }

    /**
     * Unregisters the broadcast receiver, stopping the listener from receiving further events.
     * It's important to call this method when the listener is no longer needed to avoid
     * potential resource leaks.
     */
    public void unregisterReceivers() {
        try {
            mContext.unregisterReceiver(mPackageIntentReceiver);
            mContext.unregisterReceiver(mUserIntentReceiver);
            mContext.unregisterReceiver(mExternalAppIntentReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered
        }
    }

    /**
     * A light-weighted {@link BroadcastReceiver} that dispatches the
     * received {@link Intent} handling using a {@link Handler}.
     *
     * <p>This class is for offloading potentially time-consuming or blocking
     * operations triggered by broadcast events, preventing Application Not Responding
     * (ANR) errors.</p>
     */
    private static class DeferredBroadcastReceiver extends BroadcastReceiver {
        private final Handler mHandler;
        private final Consumer<Intent> mIntentConsumer;

        DeferredBroadcastReceiver(@NonNull Handler handler,
                @NonNull Consumer<Intent> intentConsumer) {
            mHandler = handler;
            mIntentConsumer = intentConsumer;
        }

        @Override
        public void onReceive(Context context, @NonNull Intent intent) {
            HandlerUtils.ensureRunningOnHandlerThread(mHandler);
            mHandler.post(() -> mIntentConsumer.accept(intent));
        }
    }

    private void handlePackageIntent(Intent intent) {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);
        final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
        final Uri packageData = intent.getData();
        // AOSP never sends null data in the intent (see
        // BroadcastHelper#sendPostInstallBroadcasts)
        // but OEMs may have modified this code.
        if (packageData == null) {
            Log.wtf(TAG, "Intent received with null data: " + intent);
            return;
        }
        final String packageName = packageData.getSchemeSpecificPart();

        switch (intent.getAction()) {
            case Intent.ACTION_PACKAGE_ADDED: {
                mCallback.onPackageAdded(packageName, uid);
                break;
            }
            case Intent.ACTION_PACKAGE_REMOVED: {
                mCallback.onPackageRemoved(packageName, uid);
                break;
            }
            case Intent.ACTION_PACKAGE_REPLACED: {
                mCallback.onPackageReplaced(packageName, uid);
                break;
            }
            default:
                Log.wtf(TAG, "received unexpected intent: " + intent.getAction());
        }
    }

    private void handleUidIntent(Intent intent) {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);

        final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
        if (uid == -1) {
            throw new IllegalArgumentException();
        }

        switch (intent.getAction()) {
            case Intent.ACTION_UID_REMOVED:
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    mCallback.onUidRemoved(uid);
                }
            default:
                Log.wtf(TAG, "received unexpected intent: " + intent.getAction());
        }
    }

    private void handleExternalAppIntent(Intent intent) {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);
        switch (intent.getAction()) {
            case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE: {
                final String[] pkgList =
                        intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                mCallback.onExternalApplicationsAvailable(pkgList);
                break;
            }
            default:
                Log.wtf(TAG, "received unexpected intent: " + intent.getAction());
        }
    }

    private void handleUserIntent(Intent intent) {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);
        final String action = intent.getAction();
        UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);
        if (user == null) {
            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            if (userId == -1) {
                throw new IllegalArgumentException();
            }
            user = UserHandle.of(userId);
        }

        if (Intent.ACTION_USER_ADDED.equals(action)) {
            mCallback.onUserAdded(user);
        } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
            mCallback.onUserRemoved(user);
        } else if (Intent.ACTION_USER_STARTED.equals(action)) {
            mCallback.onUserStarted(user);
        } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
            mCallback.onUserStopped(user);
        }  else {
            Log.wtf(TAG, "received unexpected intent: " + action);
        }
    }
}
