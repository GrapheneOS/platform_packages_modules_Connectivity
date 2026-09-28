/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.Manifest.permission.ACCESS_LOCAL_NETWORK;
import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.FORCE_USE_LOOPBACK_INTERFACE;
import static android.Manifest.permission.INTERNET;
import static android.Manifest.permission.NEARBY_WIFI_DEVICES;
import static android.Manifest.permission.NETWORK_STACK;
import static android.Manifest.permission.INTERACT_ACROSS_PROFILES;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.Manifest.permission.USE_LOOPBACK_INTERFACE;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.net.ConnectivitySettingsManager.UIDS_ALLOWED_ON_RESTRICTED_NETWORKS;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.net.connectivity.ConnectivityCompatChanges.RESTRICT_LOCAL_NETWORK;
import static android.os.Process.INVALID_UID;
import static android.os.Process.SYSTEM_UID;
import static android.permission.flags.Flags.accessLocalNetworkPermissionEnabled;

import static com.android.modules.utils.build.SdkLevel.isAtLeastB;
import static com.android.net.module.util.CollectionUtils.toIntArray;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_ACCESS_LOCAL_NETWORK;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_NONE;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_NO_INTERNET;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_UPDATE_DEVICE_STATS;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_USE_LOOPBACK_INTERFACE;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_FORCE_USE_LOOPBACK_INTERFACE;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_INTERACT_ACROSS_USERS_FULL;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_INTERACT_ACROSS_USERS_OR_PROFILES;
import static com.android.server.ConnectivityStatsLog.CONNECTIVITY_PERMISSION_CHANGE_LISTENER_LATENCY_REPORTED;
import static com.android.server.connectivity.ConnectivityFlags.USE_BROADCAST_RECEIVE_HELPER_FOR_PERMISSION_MONITOR;
import static com.android.server.connectivity.NetworkPermissions.PERMISSION_NETWORK;
import static com.android.server.connectivity.NetworkPermissions.PERMISSION_NONE;
import static com.android.server.connectivity.NetworkPermissions.PERMISSION_SYSTEM;
import static com.android.server.connectivity.NetworkPermissions.TRAFFIC_PERMISSION_INTERNET;
import static com.android.server.connectivity.NetworkPermissions.TRAFFIC_PERMISSION_UNINSTALLED;
import static com.android.server.connectivity.NetworkPermissions.TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.compat.CompatChanges;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.ext.ConnectivityUtil;
import android.net.ConnectivitySettingsManager;
import android.net.INetd;
import android.net.UidRange;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.SystemConfigManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.annotation.RequiresApi;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.SharedLog;
import com.android.server.BpfNetMaps;
import com.android.server.ConnectivityStatsLog;
import com.android.server.LocalManagerRegistry;
import com.android.server.permission.PermissionBpfMap;
import com.android.server.permission.PermissionManagerLocal;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.pm.PackageManagerLocal.GosPackageStateChangeCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A utility class to inform Netd of UID permissions.
 * Does a mass update at boot and then monitors for app install/remove.
 */
public class PermissionMonitor {
    private static final String TAG = "PermissionMonitor";
    private static final boolean DBG = true;
    private static final int VERSION_Q = Build.VERSION_CODES.Q;

    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final SystemConfigManager mSystemConfigManager;
    private final PermissionManager mPermissionManager;
    private final PermissionChangeListener mPermissionChangeListener;
    private final INetd mNetd;
    private final Dependencies mDeps;
    private final Context mContext;
    private final BpfNetMaps mBpfNetMaps;
    private final HandlerThread mThread;


    @GuardedBy("this")
    private final Set<UserHandle> mUsers = new HashSet<>();

    // Keys are uids. Values are netd network permissions.
    @GuardedBy("this")
    private final SparseIntArray mUidToNetworkPerm = new SparseIntArray();

    // NonNull keys are active non-bypassable and fully-routed VPN's interface name, Values are uid
    // ranges for apps under the VPNs which enable interface filtering.
    // If key is null, Values are uid ranges for apps under the VPNs which are connected but do not
    // enable interface filtering.
    @GuardedBy("this")
    private final Map<String, Set<UidRange>> mVpnInterfaceUidRanges = new ArrayMap<>();

    // Items are uid ranges for apps under the VPN Lockdown
    // Ranges were given through ConnectivityManager#setRequireVpnForUids, and ranges are allowed to
    // have duplicates. Also, it is allowed to give ranges that are already subject to lockdown.
    // So we need to maintain uid range with multiset.
    @GuardedBy("this")
    private final MultiSet<UidRange> mVpnLockdownUidRanges = new MultiSet<>();

    // A set of appIds for apps across all users on the device. We track appIds instead of uids
    // directly to reduce its size and also eliminate the need to update this set when user is
    // added/removed.
    @GuardedBy("this")
    private final Set<Integer> mAllApps = new HashSet<>();

    // A set of uids which are allowed to use restricted networks. The packages of these uids can't
    // hold the CONNECTIVITY_USE_RESTRICTED_NETWORKS permission because they can't be
    // signature|privileged apps. However, these apps should still be able to use restricted
    // networks under certain conditions (e.g. government app using emergency services). So grant
    // netd system permission to these uids which is listed in UIDS_ALLOWED_ON_RESTRICTED_NETWORKS.
    @GuardedBy("this")
    private final Set<Integer> mUidsAllowedOnRestrictedNetworks = new ArraySet<>();

    // Store PackageManager for each user.
    // Keys are users, Values are PackageManagers which get from each user.
    @GuardedBy("this")
    private final Map<UserHandle, PackageManager> mUsersPackageManager = new ArrayMap<>();

    // Store appIds traffic permissions for each user.
    // Keys are users, Values are SparseArrays where each entry maps an appId to the permissions
    // that appId has within that user. The permissions are a bitmask of PERMISSION_INTERNET and
    // PERMISSION_UPDATE_DEVICE_STATS, or 0 (PERMISSION_NONE) if the app has neither of those
    // permissions. They can never be PERMISSION_UNINSTALLED.
    // It is used only when permission_map_uid_migration flag is disabled
    @GuardedBy("this")
    private final Map<UserHandle, SparseIntArray> mUsersAppIdsTrafficPermissions = new ArrayMap<>();

    // Store uids traffic permissions for each user.
    // Keys are users, Values are SparseArrays where each entry maps an uid to the permissions
    // that uid has within that user. The permissions are a bitmask of PERMISSION_INTERNET and
    // PERMISSION_UPDATE_DEVICE_STATS, or 0 (PERMISSION_NONE) if the app has neither of those
    // permissions. They can never be PERMISSION_UNINSTALLED.
    // It is used only when permission_map_uid_migration flag is enabled
    @GuardedBy("this")
    private final Map<UserHandle, SparseIntArray> mUsersUidsTrafficPermissions = new ArrayMap<>();

    // For each user, stores the uids that have app strict leak blocking disabled.
    private final Map<UserHandle, Set<Integer>> mUsersUidsAppStrictLeakBlockingDisabled =
            new ArrayMap<>();

    private static final int SYSTEM_APPID = SYSTEM_UID;

    private static final int MAX_PERMISSION_UPDATE_LOGS = 40;
    private final SharedLog mPermissionUpdateLogs = new SharedLog(MAX_PERMISSION_UPDATE_LOGS, TAG);
    private final boolean mUseBroadcastReceiveHelper;
    private final boolean mIsLoopbackPermissionEnabled;

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mUseBroadcastReceiveHelper) {
                throw new IllegalStateException(
                        "This should only be called if UseBroadcastReceiveHelper is false");
            }
            final String action = intent.getAction();

            if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                final Uri packageData = intent.getData();
                final String packageName =
                        packageData != null ? packageData.getSchemeSpecificPart() : null;
                onPackageAdded(packageName, uid);
            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                final Uri packageData = intent.getData();
                final String packageName =
                        packageData != null ? packageData.getSchemeSpecificPart() : null;
                onPackageRemoved(packageName, uid);
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                final String[] pkgList =
                        intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                onExternalApplicationsAvailable(pkgList);
            } else if (Intent.ACTION_USER_ADDED.equals(action)) {
                final UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);
                // User should be filled for below intents, check the existence.
                if (user == null) {
                    Log.wtf(TAG, action + " broadcast without EXTRA_USER");
                    return;
                }
                onUserAdded(user);
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                final UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);
                // User should be filled for below intents, check the existence.
                if (user == null) {
                    Log.wtf(TAG, action + " broadcast without EXTRA_USER");
                    return;
                }
                onUserRemoved(user);
            } else {
                Log.wtf(TAG, "received unexpected intent: " + action);
            }
        }
    };

    // Use this list in PermissionManagerLocal#registerBpfMap().
    public static final List<String> PERMISSIONS = List.of(
            ACCESS_LOCAL_NETWORK,
            UPDATE_DEVICE_STATS,
            INTERNET,
            USE_LOOPBACK_INTERFACE,
            FORCE_USE_LOOPBACK_INTERFACE,
            INTERACT_ACROSS_USERS_FULL,
            INTERACT_ACROSS_PROFILES,
            INTERACT_ACROSS_USERS,
            PERMISSION_MAINLINE_NETWORK_STACK,
            // Monitor ACCESS_NETWORK_STATE to ensure that UIDs with no other networking permissions
            // are still reported.
            ACCESS_NETWORK_STATE
    );

    // The perm bitmask expected from PermissionManagerLocal when calling setUidsPermissionBits
    public static final int PERMISSION_BPF_MAP_BIT_ACCESS_LOCAL_NETWORK = 1 << 0;
    public static final int PERMISSION_BPF_MAP_BIT_UPDATE_DEVICE_STATS = 1 << 1;
    public static final int PERMISSION_BPF_MAP_BIT_INTERNET = 1 << 2;
    public static final int PERMISSION_BPF_MAP_BIT_USE_LOOPBACK_INTERFACE = 1 << 3;
    public static final int PERMISSION_BPF_MAP_BIT_FORCE_USE_LOOPBACK_INTERFACE = 1 << 4;
    public static final int PERMISSION_BPF_MAP_BIT_INTERACT_ACROSS_USERS_FULL = 1 << 5;
    public static final int PERMISSION_BPF_MAP_BIT_INTERACT_ACROSS_PROFILES = 1 << 6;
    public static final int PERMISSION_BPF_MAP_BIT_INTERACT_ACROSS_USERS = 1 << 7;
    public static final int PERMISSION_BPF_MAP_BIT_MAINLINE_NETWORK_STACK = 1 << 8;
    // This permission serves as a trigger to ensure the UID is reported.
    // It does not have a corresponding functional bit in the BPF map.
    public static final int PERMISSION_BPF_MAP_BIT_ACCESS_NETWORK_STATE = 1 << 9;

    private int convertToChunkPermissionBits(int permissionBits) {
        int chunkPermissions = PERMISSION_BIT_NONE;
        if ((permissionBits & PERMISSION_BPF_MAP_BIT_ACCESS_LOCAL_NETWORK) != 0
                || (permissionBits & PERMISSION_BPF_MAP_BIT_MAINLINE_NETWORK_STACK) != 0) {
            chunkPermissions |= PERMISSION_BIT_ACCESS_LOCAL_NETWORK;
        }
        if ((permissionBits & PERMISSION_BPF_MAP_BIT_UPDATE_DEVICE_STATS) != 0) {
            chunkPermissions |= PERMISSION_BIT_UPDATE_DEVICE_STATS;
        }
        if ((permissionBits & PERMISSION_BPF_MAP_BIT_INTERNET) == 0) {
            chunkPermissions |= PERMISSION_BIT_NO_INTERNET;
        }
        if (!mIsLoopbackPermissionEnabled) {
            return chunkPermissions;
        }

        if ((permissionBits & PERMISSION_BPF_MAP_BIT_USE_LOOPBACK_INTERFACE) != 0) {
            chunkPermissions |= PERMISSION_BIT_USE_LOOPBACK_INTERFACE;
        }
        if ((permissionBits & PERMISSION_BPF_MAP_BIT_FORCE_USE_LOOPBACK_INTERFACE) != 0) {
            chunkPermissions |= PERMISSION_BIT_FORCE_USE_LOOPBACK_INTERFACE;
        }
        if ((permissionBits & PERMISSION_BPF_MAP_BIT_INTERACT_ACROSS_USERS_FULL) != 0) {
            chunkPermissions |= PERMISSION_BIT_INTERACT_ACROSS_USERS_FULL;
        }
        // INTERACT_ACROSS_PROFILES and INTERACT_ACROSS_USERS are passed down to the ebpf map as a
        // single OR bit
        if ((permissionBits & PERMISSION_BPF_MAP_BIT_INTERACT_ACROSS_PROFILES) != 0
                || (permissionBits & PERMISSION_BPF_MAP_BIT_INTERACT_ACROSS_USERS) != 0) {
            chunkPermissions |= PERMISSION_BIT_INTERACT_ACROSS_USERS_OR_PROFILES;
        }
        return chunkPermissions;
    }

    /**
     * Dependencies of PermissionMonitor, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Get device first sdk version.
         */
        public int getDeviceFirstSdkInt() {
            return Build.VERSION.DEVICE_INITIAL_SDK_INT;
        }

        /**
         * Get uids allowed to use restricted networks via ConnectivitySettingsManager.
         */
        public Set<Integer> getUidsAllowedOnRestrictedNetworks(@NonNull Context context) {
            return ConnectivitySettingsManager.getUidsAllowedOnRestrictedNetworks(context);
        }

        /**
         * Register ContentObserver for given Uri.
         */
        public void registerContentObserver(@NonNull Context context, @NonNull Uri uri,
                boolean notifyForDescendants, @NonNull ContentObserver observer) {
            context.getContentResolver().registerContentObserver(
                    uri, notifyForDescendants, observer);
        }

        /**
         * Check whether the UID is opted-in to the RESTRICT_LOCAL_NETWORK compat flag.
         */
        public boolean isOptedInToLocalNetworkRestrictions(int uid) {
            // TODO(b/394567896): Update compat change checks for enforcement
            return isAtLeastB()
                    && CompatChanges.isChangeEnabled(RESTRICT_LOCAL_NETWORK, uid);
        }

        /**
         * @see DeviceConfigUtils#isTetheringFeatureNotChickenedOut
         */
        public boolean isFeatureNotChickenedOut(Context context, String name) {
            return DeviceConfigUtils.isTetheringFeatureNotChickenedOut(context, name);
        }

        /**
         * Logs the latency of the PermissionChangeListener#onPermissionsChanged callback.
         */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        public void logPermissionChangeListenerLatency(int durationMicros) {
            ConnectivityStatsLog.write(CONNECTIVITY_PERMISSION_CHANGE_LISTENER_LATENCY_REPORTED,
                    durationMicros);
        }

        /**
         * @see com.android.tethering.mainline.beta.Flags#lnpDeveloperOptIn()
         */
        public boolean isLnpDeveloperOptInEnabled() {
            return com.android.tethering.mainline.beta.Flags.lnpDeveloperOptIn();
        }

        /**
         * Wrapper to get the process stable flag and to allow injection for unit testing.
         *
         * @see android.permission.flags.Flags#useLoopbackInterfacePermissionEnabled()
         */
        public boolean isLoopbackPermissionEnabled() {
            return android.permission.flags.Flags.useLoopbackInterfacePermissionEnabled();
        }

        /**
         * @see com.android.server.permission.PermissionManagerLocal#registerBpfMap
         */
        public void registerBpfMap(
                Consumer<SparseIntArray> setUidsPermissionBits,
                Consumer<Integer> removeAppId,
                Consumer<Integer> removeUser,
                List<String> permissionNames) {
            final PermissionManagerLocal permissionManagerLocal =
                    LocalManagerRegistry.getManager(
                            PermissionManagerLocal.class);
            permissionManagerLocal.registerBpfMap(new PermissionBpfMap() {
                @Override
                public void setUidsPermissionBits(
                        SparseIntArray uidsPermissionBits) {
                    setUidsPermissionBits.accept(uidsPermissionBits);
                }

                @Override
                public void removeAppId(int appId) {
                    removeAppId.accept(appId);
                }

                @Override
                public void removeUser(int userId) {
                    removeUser.accept(userId);
                }
            }, permissionNames);
        }

        /**
         * @see android.permission.flags.Flags#accessLocalNetworkPermissionEnabled()
         */
        public boolean isAccessLocalNetworkPermissionEnabled() {
            return accessLocalNetworkPermissionEnabled();
        }

        public GosPackageState getGosPackageState(String packageName, int userId) {
            return GosPackageState.get(packageName, userId);
        }
    }

    private boolean shouldEnforceLocalNetRestrictions(int uid) {
        return mDeps.isOptedInToLocalNetworkRestrictions(uid)
            || mDeps.isAccessLocalNetworkPermissionEnabled();
    }

    private static class MultiSet<T> {
        private final Map<T, Integer> mMap = new ArrayMap<>();

        /**
         * Returns the number of key in the set before this addition.
         */
        public int add(T key) {
            final int oldCount = mMap.getOrDefault(key, 0);
            mMap.put(key, oldCount + 1);
            return oldCount;
        }

        /**
         * Return the number of key in the set before this removal.
         */
        public int remove(T key) {
            final int oldCount = mMap.getOrDefault(key, 0);
            if (oldCount == 0) {
                Log.wtf(TAG, "Attempt to remove non existing key = " + key.toString());
            } else if (oldCount == 1) {
                mMap.remove(key);
            } else {
                mMap.put(key, oldCount - 1);
            }
            return oldCount;
        }

        public Set<T> getSet() {
            return mMap.keySet();
        }
    }

    public PermissionMonitor(@NonNull final Context context, @NonNull final INetd netd,
            @NonNull final BpfNetMaps bpfNetMaps, @NonNull final HandlerThread thread) {
        this(context, netd, bpfNetMaps, new Dependencies(), thread);
    }

    @VisibleForTesting
    public PermissionMonitor(@NonNull final Context context, @NonNull final INetd netd,
            @NonNull final BpfNetMaps bpfNetMaps,
            @NonNull final Dependencies deps,
            @NonNull final HandlerThread thread) {
        mPackageManager = context.getPackageManager();
        mSystemConfigManager = context.getSystemService(SystemConfigManager.class);
        mPermissionManager = context.getSystemService(PermissionManager.class);
        mPermissionChangeListener = new PermissionChangeListener();
        mNetd = netd;
        mDeps = deps;
        mContext = context;
        mBpfNetMaps = bpfNetMaps;
        mThread = thread;
        mIsLoopbackPermissionEnabled = mDeps.isLoopbackPermissionEnabled();
        if (isAtLeastB() && !mBpfNetMaps.isPermissionPropagationEnabled()) {
            // Local net restrictions is supported as a developer opt-in starting in Android B.
            // This listener should finish registration by the time the system has completed
            // boot setup such that any changes to runtime permissions for local network
            // restrictions can only occur after this registration has completed.
            mPackageManager.addOnPermissionsChangeListener(mPermissionChangeListener);
        }

        if (mBpfNetMaps.isPermissionPropagationEnabled()) {
            mDeps.registerBpfMap(
                    (SparseIntArray uidsPermissionBits) -> { /* setUidsPermissionBits */
                        long startTimeNanos = SystemClock.elapsedRealtimeNanos();
                        try {
                            SparseIntArray allUidsPermissionBits = new SparseIntArray();
                            for (int i = 0; i < uidsPermissionBits.size(); i++){
                                int uid = uidsPermissionBits.keyAt(i);
                                int chunkPermissions = convertToChunkPermissionBits(
                                        uidsPermissionBits.valueAt(i));
                                allUidsPermissionBits.put(uid, chunkPermissions);
                                if (hasSdkSandbox(uid)) {
                                    allUidsPermissionBits.put(Process.toSdkSandboxUid(uid),
                                            chunkPermissions);
                                }
                            }
                            mBpfNetMaps.setChunkPermListForUids(allUidsPermissionBits);
                        } catch (ServiceSpecificException e) {
                            Log.e(TAG, "Send uid traffic permission failed." + e);
                        } finally {
                            long durationNanos =
                                    SystemClock.elapsedRealtimeNanos() - startTimeNanos;
                            int durationMicros = (int) TimeUnit.NANOSECONDS.toMicros(durationNanos);
                            if (DBG) {
                                Log.d(TAG,
                                        "setUidsPermissionBits in PermissionBpfMap took "
                                                + durationMicros + " microseconds.");
                            }
                            mDeps.logPermissionChangeListenerLatency(durationMicros);
                        }
                    },
                    (Integer appId) -> { /* removeAppId */
                        mBpfNetMaps.removePermissionsForAppId(appId);
                        if (hasSdkSandbox(appId)) {
                            int sdkSandboxAppId = Process.toSdkSandboxUid(appId);
                            mBpfNetMaps.removePermissionsForAppId(sdkSandboxAppId);
                        }
                    },
                    (Integer userId) -> { /* removeUser */
                        mBpfNetMaps.removePermissionsForUserId(userId);
                    },
                    PERMISSIONS
            );
        }
        mUseBroadcastReceiveHelper = mDeps.isFeatureNotChickenedOut(
                mContext, USE_BROADCAST_RECEIVE_HELPER_FOR_PERMISSION_MONITOR);
        if (!mUseBroadcastReceiveHelper) {
            mUserManager = context.getSystemService(UserManager.class);
        } else {
            mUserManager = null;
        }
    }

    @VisibleForTesting
    void setLocalNetworkPermissions(final int uid, @Nullable final String packageName) {
        if (!shouldEnforceLocalNetRestrictions(uid)
                || mBpfNetMaps.isPermissionPropagationEnabled()) {
            return;
        }

        final AttributionSource attributionSource =
                new AttributionSource.Builder(uid).setPackageName(packageName).build();
        final String permission = mDeps.isAccessLocalNetworkPermissionEnabled()
                ? ACCESS_LOCAL_NETWORK
                : NEARBY_WIFI_DEVICES;
        final int permissionState = mPermissionManager.checkPermissionForPreflight(
                permission, attributionSource);
        // Note this does not check PERMISSION_MAINLINE_NETWORK_STACK, because
        // isPermissionPropagationEnabled is always enabled after B, so this code path is only for
        // apps that opted in to the local network permission on B, and apps that have
        // MAINLINE_NETWORK_STACK did not opt in.
        if (permissionState == PermissionManager.PERMISSION_GRANTED) {
            mBpfNetMaps.removeUidFromLocalNetBlockMap(attributionSource.getUid());
        } else {
            mBpfNetMaps.addUidToLocalNetBlockMap(attributionSource.getUid());
        }
        if (hasSdkSandbox(uid)){
            // SDKs in the SDK RT cannot hold runtime permissions
            final int sdkSandboxUid = Process.toSdkSandboxUid(uid);
            mBpfNetMaps.addUidToLocalNetBlockMap(sdkSandboxUid);
        }
    }

    private void ensureRunningOnHandlerThread() {
        if (mThread.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on Handler thread: " + Thread.currentThread().getName());
        }
    }

    private int getPackageNetdNetworkPermission(@NonNull final PackageInfo app) {
        if (hasRestrictedNetworkPermission(app)) {
            return PERMISSION_SYSTEM;
        }
        if (hasNetworkPermission(app)) {
            return PERMISSION_NETWORK;
        }
        return PERMISSION_NONE;
    }

    static boolean isHigherNetworkPermission(final int targetPermission,
            final int currentPermission) {
        // This is relied on strict order of network permissions (SYSTEM > NETWORK > NONE), and it
        // is enforced in tests.
        return targetPermission > currentPermission;
    }

    private synchronized void updateAllApps(final List<PackageInfo> apps) {
        for (PackageInfo app : apps) {
            final int appId = app.applicationInfo != null
                    ? UserHandle.getAppId(app.applicationInfo.uid) : INVALID_UID;
            if (appId < 0) {
                continue;
            }
            mAllApps.add(appId);
        }
    }

    private static boolean hasSdkSandbox(final int uid) {
        return SdkLevel.isAtLeastT() && Process.isApplicationUid(uid);
    }

    // Return the network permission for the passed list of apps. Note that this depends on the
    // current settings of the device (See isUidAllowedOnRestrictedNetworks).
    private SparseIntArray makeUidsNetworkPerm(final List<PackageInfo> apps) {
        final SparseIntArray uidsPerm = new SparseIntArray();
        for (PackageInfo app : apps) {
            final int uid = app.applicationInfo != null ? app.applicationInfo.uid : INVALID_UID;
            if (uid < 0) {
                continue;
            }
            final int permission = getPackageNetdNetworkPermission(app);
            if (isHigherNetworkPermission(permission, uidsPerm.get(uid, PERMISSION_NONE))) {
                uidsPerm.put(uid, permission);
                if (hasSdkSandbox(uid)) {
                    int sdkSandboxUid = Process.toSdkSandboxUid(uid);
                    uidsPerm.put(sdkSandboxUid, permission);
                }
            }
            setLocalNetworkPermissions(uid, app.packageName);
        }
        return uidsPerm;
    }

    private static SparseIntArray makeAppsTrafficPerm(final List<PackageInfo> apps,
            boolean isUidMigrationEnabled) {
        final SparseIntArray trafficPerm = new SparseIntArray();
        for (PackageInfo app : apps) {
            final int id = app.applicationInfo != null
                    ? (isUidMigrationEnabled ? app.applicationInfo.uid
                            : UserHandle.getAppId(app.applicationInfo.uid))
                    : INVALID_UID;
            if (id < 0) {
                continue;
            }
            final int otherNetdPerms = getNetdPermissionMask(app.requestedPermissions,
                    app.requestedPermissionsFlags);
            final int permission = trafficPerm.get(id) | otherNetdPerms;
            trafficPerm.put(id, permission);
            // TODO(454320180): add sdkSandboxUids before calling BpfNetMaps
            if (hasSdkSandbox(id)) {
                trafficPerm.put(Process.toSdkSandboxUid(id), permission);
            }
        }
        return trafficPerm;
    }

    private synchronized void updateUidsNetworkPermission(final SparseIntArray uids) {
        for (int i = 0; i < uids.size(); i++) {
            mUidToNetworkPerm.put(uids.keyAt(i), uids.valueAt(i));
        }
        sendUidsNetworkPermission(uids, true /* add */);
    }

    /**
     * Calculates permissions for all users.
     *
     * @param usersTrafficPermissions the map which stores traffic permissions for each user
     * @param isUidMigrationEnabled whether uid migration is enabled
     *
     * @return The traffic permissions for all users.
     */
    private synchronized SparseIntArray makeTrafficPermForAllUsers(
        Map<UserHandle, SparseIntArray> usersTrafficPermissions, boolean isUidMigrationEnabled
    ) {
        final SparseIntArray trafficPerm = new SparseIntArray();
        // Check trafficPerm permissions from each user.
        for (UserHandle user : usersTrafficPermissions.keySet()) {
            final SparseIntArray userTrafficPerm = usersTrafficPermissions.get(user);
            for (int i = 0; i < userTrafficPerm.size(); i++) {
                final int id = userTrafficPerm.keyAt(i);
                final int permission = userTrafficPerm.valueAt(i);
                if (isUidMigrationEnabled) {
                    trafficPerm.put(id, permission);
                } else {
                    trafficPerm.put(id, trafficPerm.get(id) | permission);
                }
            }
        }
        return trafficPerm;
    }

    private SparseIntArray getSystemTrafficPerm(boolean isUidMigrationEnabled) {
        final SparseIntArray trafficPerm = new SparseIntArray();
        for (final int uid : mSystemConfigManager.getSystemPermissionUids(INTERNET)) {
            final int id = isUidMigrationEnabled ? uid : UserHandle.getAppId(uid);
            final int permission = trafficPerm.get(id) | TRAFFIC_PERMISSION_INTERNET;
            trafficPerm.put(id, permission);
            if (hasSdkSandbox(id)) {
                trafficPerm.put(Process.toSdkSandboxUid(id), permission);
            }
        }
        for (final int uid : mSystemConfigManager.getSystemPermissionUids(UPDATE_DEVICE_STATS)) {
            final int id = isUidMigrationEnabled ? uid : UserHandle.getAppId(uid);
            final int permission = trafficPerm.get(id) | TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS;
            trafficPerm.put(id, permission);
            if (hasSdkSandbox(id)) {
                trafficPerm.put(Process.toSdkSandboxUid(id), permission);
            }
        }
        return trafficPerm;
    }

    /**
     * Initializer of this class.
     *
     * Intended to be called only once at startup, in the systemReady phase.
     * This shouldn't/needn't be called again.
     */
    @SuppressLint("MissingPermission")
    public synchronized void initialize() {
        log("Initialize");

        final Handler handler = new Handler(mThread.getLooper());
        final Context userAllContext = mContext.createContextAsUser(UserHandle.ALL, 0 /* flags */);

        if (!mUseBroadcastReceiveHelper) {
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
            intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            intentFilter.addDataScheme("package");
            userAllContext.registerReceiver(
                    mIntentReceiver, intentFilter, NETWORK_STACK, handler);

            // Listen to EXTERNAL_APPLICATIONS_AVAILABLE is that an app becoming
            // available means it may need to gain a permission. But an app that
            // becomes unavailable can neither gain nor lose permissions on that
            // account, it just can no longer run. Thus, doesn't need to listen to
            // EXTERNAL_APPLICATIONS_UNAVAILABLE.
            final IntentFilter externalIntentFilter =
                    new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
            userAllContext.registerReceiver(
                    mIntentReceiver, externalIntentFilter, NETWORK_STACK, handler);

            // Listen for user add/remove.
            final IntentFilter userIntentFilter = new IntentFilter();
            userIntentFilter.addAction(Intent.ACTION_USER_ADDED);
            userIntentFilter.addAction(Intent.ACTION_USER_REMOVED);
            userAllContext.registerReceiver(
                    mIntentReceiver, userIntentFilter, NETWORK_STACK, handler);
        }

        // The UIDS_ALLOWED_ON_RESTRICTED_NETWORKS setting is ignored on automotive devices to
        // ensure only privileged apps can access restricted networks.
        if (!isAutomotiveDevice()) {
            // Register UIDS_ALLOWED_ON_RESTRICTED_NETWORKS setting observer
            mDeps.registerContentObserver(
                userAllContext,
                Settings.Global.getUriFor(UIDS_ALLOWED_ON_RESTRICTED_NETWORKS),
                false /* notifyForDescendants */,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        onSettingChanged();
                    }
                });

            // Read UIDS_ALLOWED_ON_RESTRICTED_NETWORKS setting and update
            // mUidsAllowedOnRestrictedNetworks.
            updateUidsAllowedOnRestrictedNetworks(
                    mDeps.getUidsAllowedOnRestrictedNetworks(mContext));
        }

        // Read system traffic permissions when a user removed and put them to USER_ALL because they
        // are not specific to any particular user.
        if (mBpfNetMaps.isUidMigrationEnabled()) {
            if (!mBpfNetMaps.isPermissionPropagationEnabled()) {
                mUsersUidsTrafficPermissions.put(UserHandle.ALL,
                        getSystemTrafficPerm(true /* isUidMigrationEnabled */));
            }
        } else {
            mUsersAppIdsTrafficPermissions.put(UserHandle.ALL,
                    getSystemTrafficPerm(false /* isUidMigrationEnabled */));
        }

        if (!mUseBroadcastReceiveHelper) {
            final List<UserHandle> users = mUserManager.getUserHandles(true /* excludeDying */);
            // Update netd permissions for all users.
            for (UserHandle user : users) {
                onUserAdded(user);
            }
        }

        log("UidToNetworkPerm: " + mUidToNetworkPerm.size());
    }

    /**
     * Indicates whether the BroadcastReceiveHelper should be used by PermissionMonitor.
     *
     * This flag value is initialized in the constructor, ensuring consistency across sub-modules.
     */
    public boolean useBroadcastReceiveHelper() {
        return mUseBroadcastReceiveHelper;
    }

    private boolean isAutomotiveDevice() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    @VisibleForTesting
    synchronized void updateUidsAllowedOnRestrictedNetworks(final Set<Integer> uids) {
        mUidsAllowedOnRestrictedNetworks.clear();
        mUidsAllowedOnRestrictedNetworks.addAll(uids);
    }

    @VisibleForTesting
    static boolean isVendorApp(@NonNull ApplicationInfo appInfo) {
        return appInfo.isVendor() || appInfo.isOem() || appInfo.isProduct();
    }

    @VisibleForTesting
    boolean isCarryoverPackage(final ApplicationInfo appInfo) {
        if (appInfo == null) return false;
        return (appInfo.targetSdkVersion < VERSION_Q && isVendorApp(appInfo))
                // Backward compatibility for b/114245686, on devices that launched before Q daemons
                // and apps running as the system UID are exempted from this check.
                || (UserHandle.getAppId(appInfo.uid) == SYSTEM_APPID
                        && mDeps.getDeviceFirstSdkInt() < VERSION_Q);
    }

    @VisibleForTesting
    synchronized boolean isUidAllowedOnRestrictedNetworks(final ApplicationInfo appInfo) {
        if (appInfo == null) return false;
        // Check whether package's uid is in allowed on restricted networks uid list. If so, this
        // uid can have netd system permission.
        return isUidAllowedOnRestrictedNetworks(appInfo.uid);
    }

    /**
     * Returns whether the given uid is in allowed on restricted networks list.
     */
    public synchronized boolean isUidAllowedOnRestrictedNetworks(final int uid) {
        return mUidsAllowedOnRestrictedNetworks.contains(uid);
    }

    @VisibleForTesting
    boolean hasPermission(@NonNull final PackageInfo app, @NonNull final String permission) {
        if (app.requestedPermissions == null || app.requestedPermissionsFlags == null) {
            return false;
        }
        final int index = CollectionUtils.indexOf(app.requestedPermissions, permission);
        if (index < 0 || index >= app.requestedPermissionsFlags.length) return false;
        return (app.requestedPermissionsFlags[index] & REQUESTED_PERMISSION_GRANTED) != 0;
    }

    @VisibleForTesting
    boolean hasNetworkPermission(@NonNull final PackageInfo app) {
        return hasPermission(app, CHANGE_NETWORK_STATE);
    }

    @VisibleForTesting
    boolean hasRestrictedNetworkPermission(@NonNull final PackageInfo app) {
        // TODO : remove carryover package check in the future(b/31479477). All apps should just
        //  request the appropriate permission for their use case since android Q.
        return isCarryoverPackage(app.applicationInfo)
                || isUidAllowedOnRestrictedNetworks(app.applicationInfo)
                || hasPermission(app, PERMISSION_MAINLINE_NETWORK_STACK)
                || hasPermission(app, NETWORK_STACK)
                || hasPermission(app, CONNECTIVITY_USE_RESTRICTED_NETWORKS);
    }

    /** Returns whether the given uid has using background network permission. */
    public synchronized boolean hasUseBackgroundNetworksPermission(final int uid) {
        // Apps with any of the CHANGE_NETWORK_STATE, NETWORK_STACK, CONNECTIVITY_INTERNAL or
        // CONNECTIVITY_USE_RESTRICTED_NETWORKS permission has the permission to use background
        // networks. mUidToNetworkPerm contains the result of checks for hasNetworkPermission and
        // hasRestrictedNetworkPermission, as well as the list of UIDs allowed on restricted
        // networks. If uid is in the mUidToNetworkPerm list that means uid has one of permissions
        // at least.
        return mUidToNetworkPerm.get(uid, PERMISSION_NONE) != PERMISSION_NONE;
    }

    /**
     * Returns whether the given uid has permission to use restricted networks.
     */
    public synchronized boolean hasRestrictedNetworksPermission(int uid) {
        return PERMISSION_SYSTEM == mUidToNetworkPerm.get(uid, PERMISSION_NONE);
    }

    private void sendUidsNetworkPermission(SparseIntArray uids, boolean add) {
        ensureRunningOnHandlerThread();
        List<Integer> network = new ArrayList<>();
        List<Integer> system = new ArrayList<>();
        for (int i = 0; i < uids.size(); i++) {
            final int permission = uids.valueAt(i);
            if (PERMISSION_NONE == permission) {
                continue; // Normally NONE is not stored in this map, but just in case
            }
            List<Integer> list = (PERMISSION_SYSTEM == permission) ? system : network;
            list.add(uids.keyAt(i));
        }
        try {
            if (add) {
                mNetd.networkSetPermissionForUser(PERMISSION_NETWORK, toIntArray(network));
                mNetd.networkSetPermissionForUser(PERMISSION_SYSTEM, toIntArray(system));
            } else {
                mNetd.networkClearPermissionForUser(toIntArray(network));
                mNetd.networkClearPermissionForUser(toIntArray(system));
            }
        } catch (RemoteException e) {
            loge("Exception when updating permissions: " + e);
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void onUserAdded(@NonNull UserHandle user) {
        if (mUseBroadcastReceiveHelper) {
            throw new IllegalStateException(
                    "This should only be called if UseBroadcastReceiveHelper is false");
        }
        final List<PackageInfo> apps =  mPackageManager.getInstalledPackagesAsUser(
                GET_PERMISSIONS, user.getIdentifier());
        onUserAddedWithInstalledPackageList(user, apps);
    }

    /**
     * Called when a user is added. See {link #ACTION_USER_ADDED}.
     *
     * @param user The userHandle of the added user. See {@link #EXTRA_USER_HANDLE}.
     * @param apps The list of packages which is installed on the user.
     */
    public synchronized void onUserAddedWithInstalledPackageList(@NonNull UserHandle user,
            @NonNull List<PackageInfo> apps) {
        ensureRunningOnHandlerThread();
        mUsers.add(user);

        // Save all apps in mAllApps
        updateAllApps(apps);

        // Uids network permissions
        final SparseIntArray uids = makeUidsNetworkPerm(apps);
        updateUidsNetworkPermission(uids);

        if (mBpfNetMaps.isUidMigrationEnabled()) {
            if (mBpfNetMaps.isPermissionPropagationEnabled()) {
                // Log user added
                mPermissionUpdateLogs.log("New user(" + user.getIdentifier()
                        + ") added: nPerm uids=" + uids);
            } else {
                // Add new user uids permissions.
                final SparseIntArray addedUserUids = makeAppsTrafficPerm(apps,
                        true /* isUidMigrationEnabled */);
                mUsersUidsTrafficPermissions.put(user, addedUserUids);
                // Generate uids from all users and send result to netd.
                final SparseIntArray permUids = makeTrafficPermForAllUsers(
                    mUsersUidsTrafficPermissions, true /* isUidMigrationEnabled */);
                sendUidsTrafficPermission(permUids);

                // Log user added
                mPermissionUpdateLogs.log("New user(" + user.getIdentifier()
                        + ") added: networkPerm uids=" + uids + ", trafficPerm uids=" + permUids);
            }
        } else {
            // Add new user appIds permissions.
            final SparseIntArray addedUserAppIds = makeAppsTrafficPerm(apps,
                    false /* isUidMigrationEnabled */);
            mUsersAppIdsTrafficPermissions.put(user, addedUserAppIds);
            // Generate appIds from all users and send result to netd.
            final SparseIntArray appIds = makeTrafficPermForAllUsers(
                mUsersAppIdsTrafficPermissions, false /* isUidMigrationEnabled */);
            sendAppIdsTrafficPermission(appIds);

            // Log user added
            mPermissionUpdateLogs.log("New user(" + user.getIdentifier() + ") added: nPerm uids="
                    + uids + ", tPerm appIds=" + addedUserAppIds);
        }
    }

    public void registerGosPackageStateChangeCallback() {
        GosPackageStateChangeCallback callback = new GosPackageStateChangeCallback(
                new Handler(mThread.getLooper())) {
            @Override
            public void onGosPackageStateChanged(int uid, @NonNull GosPackageState state,
                    int userId) {
                PermissionMonitor.this.onGosPackageStateChanged(uid, state, userId);
            }
        };
        PackageManagerLocal pml = LocalManagerRegistry.getManager(PackageManagerLocal.class);
        if (pml != null) {
            pml.addGosPackageStateChangeCallback(callback);
        }
    }

    public void onGosPackageStateChanged(int uid, @NonNull GosPackageState ps,
            int userId) {
        ensureRunningOnHandlerThread();
        updateAppStrictLeakBlockingBpf(UserHandle.of(userId), uid, ps);
    }

    public void updateAppStrictLeakBlockingBpf(UserHandle user, int uid,
            @Nullable GosPackageState ps) {
        // Core uids have strict leak blocking unconditionally disabled in eBPF, so ignore them.
        if (UserHandle.isCore(uid)) {
            return;
        }

        boolean wasDisabled = wasAppStrictLeakBlockingDisabled(user, uid);
        boolean isDisabled = isAppStrictLeakBlockingDisabled(user.getIdentifier(), uid, ps);

        if (wasDisabled == isDisabled) {
            return;
        }

        if (isDisabled) {
            disableAppStrictLeakBlocking(user, uid);
        } else {
            enableAppStrictLeakBlocking(user, uid);
        }
    }

    private boolean wasAppStrictLeakBlockingDisabled(UserHandle user, int uid) {
        Set<Integer> disabledUids = mUsersUidsAppStrictLeakBlockingDisabled.get(user);
        if (disabledUids == null) {
            return false;
        }
        return disabledUids.contains(uid);
    }

    private boolean isAppStrictLeakBlockingDisabled(int userId, int uid,
            @Nullable GosPackageState ps) {
        String[] packageNames = mPackageManager.getPackagesForUid(uid);
        if (packageNames == null) {
            // All packages for this uid have been uninstalled, so there's no need to disable strict
            // leak blocking.
            return false;
        }

        if (ps == null) {
            // GosPackageState is same for all packages that share a uid, so just use 0th.
            ps = mDeps.getGosPackageState(packageNames[0], userId);
        }

        ApplicationInfo ai = null;
        // Iterate all packageNames in search of an applicationInfo. For AswSocketBindToDevice it
        // doesn't matter which package's applicationInfo we use, only that the packages all share a
        // uid.
        for (String packageName : packageNames) {
            try {
                ai = mPackageManager.getPackageInfoAsUser(packageName, 0, userId).applicationInfo;
            } catch (NameNotFoundException ignored) {}

            if (ai != null) {
                // Technically a package name could have been reinstalled with a different uid, so
                // ensure the uids match.
                if (ai.uid == uid) {
                    break;
                }
                ai = null;
            }
        }

        if (ai == null) {
            // No applicationInfo implies no executable code, so there's no need to disable strict
            // leak blocking.
            return false;
        }

        return !ConnectivityUtil.isAppStrictLeakBlockingEnabled(mContext, userId, ai, ps);
    }

    private void disableAppStrictLeakBlocking(UserHandle user, int uid) {
        Set<Integer> disabledUids = mUsersUidsAppStrictLeakBlockingDisabled.get(user);
        if (disabledUids == null) {
            disabledUids = new ArraySet<>();
            mUsersUidsAppStrictLeakBlockingDisabled.put(user, disabledUids);
        }
        for (int associatedUid : getAssociatedUids(uid)) {
            disabledUids.add(associatedUid);
            mBpfNetMaps.updateAppStrictLeakBlockingDisabledRule(associatedUid, true);
        }
    }

    private void enableAppStrictLeakBlocking(UserHandle user, int uid) {
        Set<Integer> disabledUids = mUsersUidsAppStrictLeakBlockingDisabled.get(user);
        for (int associatedUid : getAssociatedUids(uid)) {
            disabledUids.remove(associatedUid);
            mBpfNetMaps.updateAppStrictLeakBlockingDisabledRule(associatedUid, false);
        }
    }

    private List<Integer> getAssociatedUids(int uid) {
        ArrayList<Integer> associatedUids = new ArrayList<>(List.of(uid));
        if (hasSdkSandbox(uid)) {
            associatedUids.add(Process.toSdkSandboxUid(uid));
        }
        return associatedUids;
    }

    public void onUserStarted(@NonNull UserHandle user) {
        ensureRunningOnHandlerThread();
        final List<PackageInfo> packages = mContext.getPackageManager()
                .getInstalledPackagesAsUser(0, user.getIdentifier());

        for (PackageInfo pi : packages) {
            if (pi.applicationInfo != null) {
                updateAppStrictLeakBlockingBpf(user, pi.applicationInfo.uid, null);
            }
        }
    }

    public void onUserStopped(@NonNull UserHandle user) {
        ensureRunningOnHandlerThread();
        Set<Integer> disabledUids = mUsersUidsAppStrictLeakBlockingDisabled.get(user);
        for (Integer disabledUid : disabledUids) {
            // Remove the rule so that the BPF UidOwnerValue can potentially be marked for removal.
            mBpfNetMaps.updateAppStrictLeakBlockingDisabledRule(disabledUid, false);
        }

        mUsersUidsAppStrictLeakBlockingDisabled.remove(user);
    }

    public void onUidRemoved(int uid) {
        ensureRunningOnHandlerThread();
        UserHandle user = UserHandle.getUserHandleForUid(uid);
        if (wasAppStrictLeakBlockingDisabled(user, uid)) {
            // The uid is guaranteed not to be reused until next boot, but enabling it allows the
            // BPF UidOwnerValue to potentially be marked for removal.
            enableAppStrictLeakBlocking(user, uid);
        }
    }

    /**
     * Called when an user is removed. See {link #ACTION_USER_REMOVED}.
     *
     * @param user The userHandle of the removed user. See {@link #EXTRA_USER_HANDLE}.
     */
    public synchronized void onUserRemoved(@NonNull UserHandle user) {
        ensureRunningOnHandlerThread();
        mUsers.remove(user);

        // Remove uids network permissions that belongs to the user.
        final SparseIntArray removedUids = new SparseIntArray();
        final SparseIntArray allUids = mUidToNetworkPerm.clone();
        for (int i = 0; i < allUids.size(); i++) {
            final int uid = allUids.keyAt(i);
            if (user.equals(UserHandle.getUserHandleForUid(uid))) {
                mUidToNetworkPerm.delete(uid);
                if (shouldEnforceLocalNetRestrictions(uid)
                        && !mBpfNetMaps.isPermissionPropagationEnabled()) {
                    mBpfNetMaps.removeUidFromLocalNetBlockMap(uid);
                    if (hasSdkSandbox(uid)) mBpfNetMaps.removeUidFromLocalNetBlockMap(
                            Process.toSdkSandboxUid(uid));
                }
                removedUids.put(uid, allUids.valueAt(i));
            }
        }
        sendUidsNetworkPermission(removedUids, false /* add */);

        if (mBpfNetMaps.isUidMigrationEnabled()) {
            if (mBpfNetMaps.isPermissionPropagationEnabled()) {
                // Log user removed
                mPermissionUpdateLogs.log("User(" + user.getIdentifier() + ") removed: nPerm uids="
                        + removedUids);
            } else {
                // Remove traffic permission that belongs to the user
                final SparseIntArray removedTrafficPerm = mUsersUidsTrafficPermissions.remove(user);
                // Generate uids from the remaining users.
                final SparseIntArray trafficPermForAllUsers = makeTrafficPermForAllUsers(
                    mUsersUidsTrafficPermissions, true /* isUidMigrationEnabled */);

                if (removedTrafficPerm == null) {
                    Log.wtf(TAG, "onUserRemoved: Receive unknown user=" + user);
                    return;
                }

                // Clear permission on those ids belong to this user only, set the permission to
                // PERMISSION_UNINSTALLED.
                for (int i = 0; i < removedTrafficPerm.size(); i++) {
                    final int uid = removedTrafficPerm.keyAt(i);
                    trafficPermForAllUsers.put(uid, TRAFFIC_PERMISSION_UNINSTALLED);
                }
                sendUidsTrafficPermission(trafficPermForAllUsers);

                // Log user removed
                mPermissionUpdateLogs.log("User(" + user.getIdentifier() + ") removed: nPerm uids="
                    + removedUids + ", tPerm uids=" + removedTrafficPerm);
            }
        } else {
            // Remove appIds traffic permission that belongs to the user
            final SparseIntArray removedUserAppIds = mUsersAppIdsTrafficPermissions.remove(user);
            // Generate appIds from the remaining users.
            final SparseIntArray appIds = makeTrafficPermForAllUsers(
                mUsersAppIdsTrafficPermissions, false /* isUidMigrationEnabled */);

            if (removedUserAppIds == null) {
                Log.wtf(TAG, "onUserRemoved: Receive unknown user=" + user);
                return;
            }

            // Clear permission on those appIds belong to this user only, set the permission to
            // PERMISSION_UNINSTALLED.
            for (int i = 0; i < removedUserAppIds.size(); i++) {
                final int appId = removedUserAppIds.keyAt(i);
                // Need to clear permission if the removed appId is not found in the array.
                if (appIds.indexOfKey(appId) < 0) {
                    appIds.put(appId, TRAFFIC_PERMISSION_UNINSTALLED);
                }
            }
            sendAppIdsTrafficPermission(appIds);

            // Log user removed
            mPermissionUpdateLogs.log("User(" + user.getIdentifier() + ") removed: nPerm uids="
                    + removedUids + ", tPerm appIds=" + removedUserAppIds);
        }
    }

    /**
     * Compare the current network permission and the given package's permission to find out highest
     * permission for the uid.
     *
     * @param uid The target uid
     * @param currentPermission Current uid network permission
     * @param name The package has same uid that need compare its permission to update uid network
     *             permission.
     */
    @VisibleForTesting
    protected int highestPermissionForUid(int uid, int currentPermission, String name) {
        // If multiple packages share a UID (cf: android:sharedUserId) and ask for different
        // permissions, don't downgrade (i.e., if it's already SYSTEM, leave it as is).
        if (currentPermission == PERMISSION_SYSTEM) {
            return currentPermission;
        }
        final PackageInfo app = getPackageInfoAsUser(name, UserHandle.getUserHandleForUid(uid));
        if (app == null) return currentPermission;

        final int permission = getPackageNetdNetworkPermission(app);
        if (isHigherNetworkPermission(permission, currentPermission)) {
            return permission;
        }
        return currentPermission;
    }

    private int getTrafficPermissionForUid(final int uid) {
        int permission = PERMISSION_NONE;
        // Check all the packages for this UID. The UID has the permission if any of the
        // packages in it has the permission.
        final String[] packages = mPackageManager.getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            for (String name : packages) {
                final PackageInfo app = getPackageInfoAsUser(name,
                        UserHandle.getUserHandleForUid(uid));
                if (app != null && app.requestedPermissions != null) {
                    permission |= getNetdPermissionMask(app.requestedPermissions,
                            app.requestedPermissionsFlags);
                }
            }
        } else {
            // The last package of this uid is removed from device. Clean the package up.
            permission = TRAFFIC_PERMISSION_UNINSTALLED;
        }
        return permission;
    }

    private synchronized void updateVpnUid(int uid, boolean add) {
        // Apps that can use restricted networks can always bypass VPNs.
        if (hasRestrictedNetworksPermission(uid)) {
            return;
        }
        for (Map.Entry<String, Set<UidRange>> vpn : mVpnInterfaceUidRanges.entrySet()) {
            if (UidRange.containsUid(vpn.getValue(), uid)) {
                final Set<Integer> changedUids = new HashSet<>();
                changedUids.add(uid);
                updateVpnUidsInterfaceRules(vpn.getKey(), changedUids, add);
            }
        }
    }

    private synchronized void updateLockdownUid(int uid, boolean add) {
        // Apps that can use restricted networks can always bypass VPNs.
        if (hasRestrictedNetworksPermission(uid)) {
            return;
        }

        if (UidRange.containsUid(mVpnLockdownUidRanges.getSet(), uid)) {
            updateLockdownUidRule(uid, add);
        }
    }

    /**
     * This handles both network and traffic permission, because there is no overlap in actual
     * values, where network permission is NETWORK or SYSTEM, and traffic permission is INTERNET
     * or UPDATE_DEVICE_STATS
     */
    private String permissionToString(int permission) {
        switch (permission) {
            case PERMISSION_NONE:
                return "NONE";
            case PERMISSION_NETWORK:
                return "NETWORK";
            case PERMISSION_SYSTEM:
                return "SYSTEM";
            case TRAFFIC_PERMISSION_INTERNET:
                return "INTERNET";
            case TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS:
                return "UPDATE_DEVICE_STATS";
            case (TRAFFIC_PERMISSION_INTERNET | TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS):
                return "ALL";
            case TRAFFIC_PERMISSION_UNINSTALLED:
                return "UNINSTALLED";
            default:
                return "UNKNOWN";
        }
    }

    private synchronized void updateUidTrafficPermission(int uid) {
        final int uidTrafficPerm = getTrafficPermissionForUid(uid);
        final SparseIntArray userTrafficPerms =
                mUsersUidsTrafficPermissions.get(UserHandle.getUserHandleForUid(uid));
        if (userTrafficPerms == null) {
            Log.wtf(TAG, "Can't get user traffic permission from uid=" + uid);
            return;
        }
        // Do not put PERMISSION_UNINSTALLED into the array. If no package left on the uid
        // (PERMISSION_UNINSTALLED), remove the uid from the array. Otherwise, update the latest
        // permission to the uid.
        if (uidTrafficPerm == TRAFFIC_PERMISSION_UNINSTALLED) {
            userTrafficPerms.delete(uid);
        } else {
            userTrafficPerms.put(uid, uidTrafficPerm);
        }
    }

    private synchronized void updateAppIdTrafficPermission(int uid) {
        final int uidTrafficPerm = getTrafficPermissionForUid(uid);
        final SparseIntArray userTrafficPerms =
                mUsersAppIdsTrafficPermissions.get(UserHandle.getUserHandleForUid(uid));
        if (userTrafficPerms == null) {
            Log.wtf(TAG, "Can't get user traffic permission from uid=" + uid);
            return;
        }
        // Do not put PERMISSION_UNINSTALLED into the array. If no package left on the uid
        // (PERMISSION_UNINSTALLED), remove the appId from the array. Otherwise, update the latest
        // permission to the appId.
        final int appId = UserHandle.getAppId(uid);
        if (uidTrafficPerm == TRAFFIC_PERMISSION_UNINSTALLED) {
            userTrafficPerms.delete(appId);
        } else {
            userTrafficPerms.put(appId, uidTrafficPerm);
        }
    }

    private synchronized int getUidPackagePermissions(int uid) {
        final SparseIntArray userTrafficPerms = mUsersUidsTrafficPermissions.get(
            UserHandle.getUserHandleForUid(uid));
        if (userTrafficPerms != null && userTrafficPerms.indexOfKey(uid) >= 0) {
            return userTrafficPerms.valueAt(userTrafficPerms.indexOfKey(uid));
        }
        return TRAFFIC_PERMISSION_UNINSTALLED;
    }

    private synchronized int getAppIdTrafficPermission(int appId) {
        int permission = PERMISSION_NONE;
        boolean installed = false;
        for (UserHandle user : mUsersAppIdsTrafficPermissions.keySet()) {
            final SparseIntArray userApps = mUsersAppIdsTrafficPermissions.get(user);
            final int appIdx = userApps.indexOfKey(appId);
            if (appIdx >= 0) {
                permission |= userApps.valueAt(appIdx);
                installed = true;
            }
        }
        return installed ? permission : TRAFFIC_PERMISSION_UNINSTALLED;
    }

    /**
     * Called when a package is added.
     *
     * @param packageName The name of the new package.
     * @param uid The uid of the new package.
     */
    public synchronized void onPackageAdded(@NonNull final String packageName, final int uid) {
        ensureRunningOnHandlerThread();
        final int appId = UserHandle.getAppId(uid);
        final int trafficPermission;
        if (mBpfNetMaps.isUidMigrationEnabled()) {
            if (!mBpfNetMaps.isPermissionPropagationEnabled()) {
                updateUidTrafficPermission(uid);
                trafficPermission = getUidPackagePermissions(uid);
                sendPackagePermissionsForUid(uid, trafficPermission);

                mPermissionUpdateLogs.log("Package add: uid=" + uid
                        + ", tPerm=" + permissionToString(trafficPermission));
                }
        } else {
            // Update uid permission.
            updateAppIdTrafficPermission(uid);
            // Get the appId permission from all users then send the latest permission to netd.
            trafficPermission = getAppIdTrafficPermission(appId);
            sendPackagePermissionsForAppId(appId, trafficPermission);

            mPermissionUpdateLogs.log("Package add: uid=" + uid
                    + ", tPerm=" + permissionToString(trafficPermission));
        }

        final int currentPermission = mUidToNetworkPerm.get(uid, PERMISSION_NONE);
        final int permission = highestPermissionForUid(uid, currentPermission, packageName);
        if (permission != currentPermission) {
            mUidToNetworkPerm.put(uid, permission);

            SparseIntArray apps = new SparseIntArray();
            apps.put(uid, permission);

            if (hasSdkSandbox(uid)) {
                int sdkSandboxUid = Process.toSdkSandboxUid(uid);
                mUidToNetworkPerm.put(sdkSandboxUid, permission);
                apps.put(sdkSandboxUid, permission);
            }
            sendUidsNetworkPermission(apps, true /* add */);
        }
        setLocalNetworkPermissions(uid, packageName);

        // If the newly-installed package falls within some VPN's uid range, update Netd with it.
        // This needs to happen after the mUidToNetworkPerm update above, since
        // hasRestrictedNetworksPermission() in updateVpnUid() and updateLockdownUid() depends on
        // mUidToNetworkPerm to check if the package can bypass VPN.
        updateVpnUid(uid, true /* add */);
        updateLockdownUid(uid, true /* add */);
        mAllApps.add(appId);

        // Log package added.
        mPermissionUpdateLogs.log("Package add: uid=" + uid
                + ", nPerm=(" + permissionToString(permission) + "/"
                + permissionToString(currentPermission) + ")");
    }

    private int highestUidNetworkPermission(int uid) {
        int permission = PERMISSION_NONE;
        final String[] packages = mPackageManager.getPackagesForUid(uid);
        if (!CollectionUtils.isEmpty(packages)) {
            for (String name : packages) {
                // If multiple packages have the same UID, give the UID all permissions that
                // any package in that UID has.
                permission = highestPermissionForUid(uid, permission, name);
                if (permission == PERMISSION_SYSTEM) {
                    break;
                }
            }
        }
        return permission;
    }

    /**
     * Called when a package is removed.
     *
     * @param packageName The name of the removed package or null.
     * @param uid containing the integer uid previously assigned to the package.
     */
    public synchronized void onPackageRemoved(@NonNull final String packageName, final int uid) {
        ensureRunningOnHandlerThread();
        final int appId = UserHandle.getAppId(uid);
        final int trafficPermission;
        if (mBpfNetMaps.isUidMigrationEnabled()) {
            if (!mBpfNetMaps.isPermissionPropagationEnabled()) {
                updateUidTrafficPermission(uid);
                trafficPermission = getUidPackagePermissions(uid);
                sendPackagePermissionsForUid(uid, trafficPermission);

                mPermissionUpdateLogs.log("Package remove: uid=" + uid
                        + ", tPerm=" + permissionToString(trafficPermission));
            }
        } else {
            // Update uid permission.
            updateAppIdTrafficPermission(uid);
            // Get the appId permission from all users then send the latest permission to netd.
            trafficPermission = getAppIdTrafficPermission(appId);
            sendPackagePermissionsForAppId(appId, trafficPermission);

            mPermissionUpdateLogs.log("Package remove: uid=" + uid
                    + ", tPerm=" + permissionToString(trafficPermission));
        }

        if (isAtLeastB() && !mBpfNetMaps.isPermissionPropagationEnabled()) {
            mBpfNetMaps.removeUidFromLocalNetBlockMap(uid);
            if (hasSdkSandbox(uid)) mBpfNetMaps.removeUidFromLocalNetBlockMap(
                    Process.toSdkSandboxUid(uid));
        }

        // If the newly-removed package falls within some VPN's uid range, update Netd with it.
        // This needs to happen before the mUidToNetworkPerm update below, since
        // hasRestrictedNetworksPermission() in updateVpnUid() and updateLockdownUid() depends on
        // mUidToNetworkPerm to check if the package can bypass VPN.
        updateVpnUid(uid, false /* add */);
        updateLockdownUid(uid, false /* add */);
        // If the package has been removed from all users on the device, clear it form mAllApps.
        if (mPackageManager.getNameForUid(uid) == null) {
            mAllApps.remove(appId);
        }

        final int currentPermission = mUidToNetworkPerm.get(uid, PERMISSION_NONE);
        final int permission = highestUidNetworkPermission(uid);

        // Log package removed.
        mPermissionUpdateLogs.log("Package remove: uid=" + uid
                + ", nPerm=(" + permissionToString(permission) + "/"
                + permissionToString(currentPermission) + ")");

        if (permission != currentPermission) {
            final SparseIntArray apps = new SparseIntArray();
            int sdkSandboxUid = -1;
            if (hasSdkSandbox(uid)) {
                sdkSandboxUid = Process.toSdkSandboxUid(uid);
            }
            if (permission == PERMISSION_NONE) {
                mUidToNetworkPerm.delete(uid);
                apps.put(uid, PERMISSION_NETWORK);  // doesn't matter which permission we pick here
                if (sdkSandboxUid != -1) {
                    mUidToNetworkPerm.delete(sdkSandboxUid);
                    apps.put(sdkSandboxUid, PERMISSION_NETWORK);
                }
                sendUidsNetworkPermission(apps, false);
            } else {
                mUidToNetworkPerm.put(uid, permission);
                apps.put(uid, permission);
                if (sdkSandboxUid != -1) {
                    mUidToNetworkPerm.put(sdkSandboxUid, permission);
                    apps.put(sdkSandboxUid, permission);
                }
                sendUidsNetworkPermission(apps, true);
            }
        }
    }

    private static int getNetdPermissionMask(String[] requestedPermissions,
                                             int[] requestedPermissionsFlags) {
        int permissions = PERMISSION_NONE;
        if (requestedPermissions == null || requestedPermissionsFlags == null) return permissions;
        for (int i = 0; i < requestedPermissions.length; i++) {
            if (requestedPermissions[i].equals(INTERNET)
                    && ((requestedPermissionsFlags[i] & REQUESTED_PERMISSION_GRANTED) != 0)) {
                permissions |= TRAFFIC_PERMISSION_INTERNET;
            }
            if (requestedPermissions[i].equals(UPDATE_DEVICE_STATS)
                    && ((requestedPermissionsFlags[i] & REQUESTED_PERMISSION_GRANTED) != 0)) {
                permissions |= TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS;
            }
        }
        return permissions;
    }

    private synchronized PackageManager getPackageManagerAsUser(UserHandle user) {
        PackageManager pm = mUsersPackageManager.get(user);
        if (pm == null) {
            pm = mContext.createContextAsUser(user, 0 /* flag */).getPackageManager();
            mUsersPackageManager.put(user, pm);
        }
        return pm;
    }

    private PackageInfo getPackageInfoAsUser(String packageName, UserHandle user) {
        try {
            final PackageInfo info = getPackageManagerAsUser(user)
                    .getPackageInfo(packageName, GET_PERMISSIONS);
            return info;
        } catch (NameNotFoundException e) {
            // App not found.
            loge("NameNotFoundException " + packageName);
            return null;
        }
    }

    private static Set<UidRange> getFilteredUidRanges(Set<UidRange> ranges, int vpnAppUid,
            Set<Integer> delegateBypassUids) {
        // UIDs with the restricted network permission are not included here because they are not
        // filtered from the stored VPN interface ranges. Filtering them from the stored ranges
        // is unnecessary because:
        // - When a VPN connects, removeBypassingUids removes those UIDs from the list of UIDs to
        //   which it applies IIF_MATCH rules.
        // - If an app that can use restricted networks is installed and gets a UID in the range
        //   of a currently-connected VPN, updateVpnUid will ignore it.
        //
        // If this code did include these UIDs, the code would need to ensure that
        // onVpnUidRangesRemoved correctly removed the IIF_MATCH rule and the entry in
        // mVpnInterfaceUidRanges for a UID that did not have the permission when the VPN
        // connected and acquired the permission after the VPN connected.
        //
        // TODO: IIF_MATCH rules are not correctly updated when an app is added to or removed
        // from  mUidsAllowedOnRestrictedNetworks.
        final Set<Integer> bypassingUids = new ArraySet<>(delegateBypassUids);
        bypassingUids.add(vpnAppUid);

        final Set<UidRange> uidRanges = new ArraySet<>();
        for (UidRange range : ranges) {
            uidRanges.addAll(UidRangeUtils.removeUidsFromUidRange(range, bypassingUids));
        }
        return uidRanges;
    }

    /**
     * Called when a new set of UID ranges are added to an active VPN network
     *
     * @param iface The active VPN network's interface name. Null iface indicates that the app is
     *              allowed to receive packets on all interfaces.
     * @param rangesToAdd The new UID ranges to be added to the network
     * @param vpnAppUid The uid of the VPN app
     */
    public synchronized void onVpnUidRangesAdded(@Nullable String iface, Set<UidRange> rangesToAdd,
            int vpnAppUid, Set<Integer> delegatedBypassUids) {
        // Calculate the list of new app uids under the VPN due to the new UID ranges and update
        // Netd about them. Because mAllApps only contains appIds instead of uids, the result might
        // be an overestimation if an app is not installed on the user on which the VPN is running,
        // but that's safe: if an app is not installed, it cannot receive any packets, so dropping
        // packets to that UID is fine.
        final Set<Integer> changedUids = intersectUids(rangesToAdd, mAllApps);
        final Set<UidRange> filteredRangesToAdd = getFilteredUidRanges(
                rangesToAdd, vpnAppUid, delegatedBypassUids);
        removeBypassingUids(changedUids, vpnAppUid, delegatedBypassUids);
        updateVpnUidsInterfaceRules(iface, changedUids, true /* add */);
        if (mVpnInterfaceUidRanges.containsKey(iface)) {
            mVpnInterfaceUidRanges.get(iface).addAll(filteredRangesToAdd);
        } else {
            mVpnInterfaceUidRanges.put(iface, new HashSet<UidRange>(filteredRangesToAdd));
        }
    }

    /**
     * Called when a set of UID ranges are removed from an active VPN network
     *
     * @param iface The VPN network's interface name. Null iface indicates that the app is allowed
     *              to receive packets on all interfaces.
     * @param rangesToRemove Existing UID ranges to be removed from the VPN network
     * @param vpnAppUid The uid of the VPN app
     */
    public synchronized void onVpnUidRangesRemoved(@Nullable String iface,
            Set<UidRange> rangesToRemove, int vpnAppUid, Set<Integer> delegatedBypassUids) {
        // Calculate the list of app uids that are no longer under the VPN due to the removed UID
        // ranges and update Netd about them.
        final Set<Integer> changedUids = intersectUids(rangesToRemove, mAllApps);
        final Set<UidRange> filteredRangesToRemove = getFilteredUidRanges(
                rangesToRemove, vpnAppUid, delegatedBypassUids);
        removeBypassingUids(changedUids, vpnAppUid, delegatedBypassUids);
        updateVpnUidsInterfaceRules(iface, changedUids, false /* add */);
        Set<UidRange> existingRanges = mVpnInterfaceUidRanges.getOrDefault(iface, null);
        if (existingRanges == null) {
            loge("Attempt to remove unknown vpn uid Range iface = " + iface);
            return;
        }
        existingRanges.removeAll(filteredRangesToRemove);
        if (existingRanges.size() == 0) {
            mVpnInterfaceUidRanges.remove(iface);
        }
    }

    /**
     * Called when UID ranges under VPN Lockdown are updated
     *
     * @param add {@code true} if the uids are to be added to the Lockdown, {@code false} if they
     *        are to be removed from the Lockdown.
     * @param ranges The updated UID ranges under VPN Lockdown. This function does not treat the VPN
     *               app's UID in any special way. The caller is responsible for excluding the VPN
     *               app UID from the passed-in ranges.
     *               Ranges can have duplications, overlaps, and/or contain the range that is
     *               already subject to lockdown.
     */
    public synchronized void updateVpnLockdownUidRanges(boolean add, UidRange[] ranges) {
        final Set<UidRange> affectedUidRanges = new HashSet<>();

        for (final UidRange range : ranges) {
            if (add) {
                // Rule will be added if mVpnLockdownUidRanges does not have this uid range entry
                // currently.
                if (mVpnLockdownUidRanges.add(range) == 0) {
                    affectedUidRanges.add(range);
                }
            } else {
                // Rule will be removed if the number of the range in the set is 1 before the
                // removal.
                if (mVpnLockdownUidRanges.remove(range) == 1) {
                    affectedUidRanges.add(range);
                }
            }
        }

        // mAllApps only contains appIds instead of uids. So the generated uid list might contain
        // apps that are installed only on some users but not others. But that's safe: if an app is
        // not installed, it cannot receive any packets, so dropping packets to that UID is fine.
        final Set<Integer> affectedUids = intersectUids(affectedUidRanges, mAllApps);

        // We skip adding rule to privileged apps and allow them to bypass incoming packet
        // filtering. The behaviour is consistent with how lockdown works for outgoing packets, but
        // the implementation is different: while ConnectivityService#setRequireVpnForUids does not
        // exclude privileged apps from the prohibit routing rules used to implement outgoing packet
        // filtering, privileged apps can still bypass outgoing packet filtering because the
        // prohibit rules observe the protected from VPN bit.
        // If removing a UID, we ensure it is not present anywhere in the set first.
        for (final int uid: affectedUids) {
            if (!hasRestrictedNetworksPermission(uid)
                    && (add || !UidRange.containsUid(mVpnLockdownUidRanges.getSet(), uid))) {
                updateLockdownUidRule(uid, add);
            }
        }
    }

    /**
     * Compute the intersection of a set of UidRanges and appIds. Returns a set of uids
     * that satisfies:
     *   1. falls into one of the UidRange
     *   2. matches one of the appIds
     */
    private Set<Integer> intersectUids(Set<UidRange> ranges, Set<Integer> appIds) {
        Set<Integer> result = new HashSet<>();
        for (UidRange range : ranges) {
            for (int userId = range.getStartUser(); userId <= range.getEndUser(); userId++) {
                for (int appId : appIds) {
                    final UserHandle handle = UserHandle.of(userId);
                    if (handle == null) continue;

                    final int uid = handle.getUid(appId);
                    if (range.contains(uid)) {
                        result.add(uid);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Remove all apps which can elect to bypass the VPN from the list of uids
     *
     * An app can elect to bypass the VPN if it holds SYSTEM permission, or if it's the active VPN
     * app itself.
     *
     * @param uids The list of uids to operate on
     * @param vpnAppUid The uid of the VPN app
     */
    private void removeBypassingUids(Set<Integer> uids, int vpnAppUid,
            Set<Integer> delegateBypassUids) {
        uids.remove(vpnAppUid);
        uids.removeAll(delegateBypassUids);
        uids.removeIf(this::hasRestrictedNetworksPermission);
    }

    /**
     * Update netd about the list of uids that are under an active VPN connection which they cannot
     * bypass.
     *
     * This is to instruct netd to set up appropriate filtering rules for these uids, such that they
     * can only receive ingress packets from the VPN's tunnel interface (and loopback).
     * Null iface set up a wildcard rule that allow app to receive packets on all interfaces.
     *
     * @param iface the interface name of the active VPN connection
     * @param add {@code true} if the uids are to be added to the interface, {@code false} if they
     *        are to be removed from the interface.
     */
    private void updateVpnUidsInterfaceRules(String iface, Set<Integer> uids, boolean add) {
        if (uids.size() == 0) {
            return;
        }
        try {
            if (add) {
                mBpfNetMaps.addUidInterfaceRules(iface, toIntArray(uids));
            } else {
                mBpfNetMaps.removeUidInterfaceRules(toIntArray(uids));
            }
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Exception when updating permissions: ", e);
        }
    }

    private void updateLockdownUidRule(int uid, boolean add) {
        try {
            mBpfNetMaps.updateUidLockdownRule(uid, add);
        } catch (ServiceSpecificException e) {
            loge("Failed to " + (add ? "add" : "remove") + " Lockdown rule: " + e);
        }
    }

    /**
     * Send the updated permission information to bpf map. Called upon package
     * install/uninstall.
     *
     * @param uid the uid of the package installed
     * @param permissions the permissions the app requested and netd cares about.
     */
    @VisibleForTesting
    void sendPackagePermissionsForUid(int uid, int permissions) {
        ensureRunningOnHandlerThread();
        final SparseIntArray permissionsUids = new SparseIntArray();
        permissionsUids.put(uid, permissions);
        if (hasSdkSandbox(uid)) {
            int sdkSandboxUid = Process.toSdkSandboxUid(uid);
            permissionsUids.put(sdkSandboxUid, permissions);
        }
        sendUidsTrafficPermission(permissionsUids);
    }

    /**
     * Send the updated permission information to netd. Called upon package install/uninstall.
     *
     * @param appId the appId of the package installed
     * @param permissions the permissions the app requested and netd cares about.
     */
    @VisibleForTesting
    void sendPackagePermissionsForAppId(int appId, int permissions) {
        SparseIntArray netdPermissionsAppIds = new SparseIntArray();
        netdPermissionsAppIds.put(appId, permissions);
        if (hasSdkSandbox(appId)) {
            int sdkSandboxAppId = Process.toSdkSandboxUid(appId);
            netdPermissionsAppIds.put(sdkSandboxAppId, permissions);
        }
        sendAppIdsTrafficPermission(netdPermissionsAppIds);
    }

    /**
     * Grant or revoke the INTERNET and/or UPDATE_DEVICE_STATS permission of the uids in
     * array.
     *
     * @param allUserTrafficPermissions integer pairs of uids and the permission granted
     *        to it. If the permission is 0, revoke all permissions of that uid.
     */
    @VisibleForTesting
    void sendUidsTrafficPermission(SparseIntArray allUserTrafficPermissions) {
        ensureRunningOnHandlerThread();
        try {
            mBpfNetMaps.setPermListForUids(allUserTrafficPermissions);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Send uid traffic permission failed." + e);
        }
    }

    /**
     * Grant or revoke the INTERNET and/or UPDATE_DEVICE_STATS permission of the appIds in array.
     *
     * @param netdPermissionsAppIds integer pairs of appIds and the permission granted to it. If the
     * permission is 0, revoke all permissions of that appId.
     */
    @VisibleForTesting
    void sendAppIdsTrafficPermission(SparseIntArray netdPermissionsAppIds) {
        ensureRunningOnHandlerThread();
        final ArrayList<Integer> allPermissionAppIds = new ArrayList<>();
        final ArrayList<Integer> internetPermissionAppIds = new ArrayList<>();
        final ArrayList<Integer> updateStatsPermissionAppIds = new ArrayList<>();
        final ArrayList<Integer> noPermissionAppIds = new ArrayList<>();
        final ArrayList<Integer> uninstalledAppIds = new ArrayList<>();
        for (int i = 0; i < netdPermissionsAppIds.size(); i++) {
            int permissions = netdPermissionsAppIds.valueAt(i);
            switch(permissions) {
                case (TRAFFIC_PERMISSION_INTERNET | TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS):
                    allPermissionAppIds.add(netdPermissionsAppIds.keyAt(i));
                    break;
                case TRAFFIC_PERMISSION_INTERNET:
                    internetPermissionAppIds.add(netdPermissionsAppIds.keyAt(i));
                    break;
                case TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS:
                    updateStatsPermissionAppIds.add(netdPermissionsAppIds.keyAt(i));
                    break;
                case PERMISSION_NONE:
                    noPermissionAppIds.add(netdPermissionsAppIds.keyAt(i));
                    break;
                case TRAFFIC_PERMISSION_UNINSTALLED:
                    uninstalledAppIds.add(netdPermissionsAppIds.keyAt(i));
                    break;
                default:
                    Log.e(TAG, "unknown permission type: " + permissions + "for uid: "
                            + netdPermissionsAppIds.keyAt(i));
            }
        }
        try {
            // TODO: add a lock inside netd to protect IPC trafficSetNetPermForUids()
            if (allPermissionAppIds.size() != 0) {
                mBpfNetMaps.setNetPermForUids(
                        TRAFFIC_PERMISSION_INTERNET | TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS,
                        toIntArray(allPermissionAppIds));
            }
            if (internetPermissionAppIds.size() != 0) {
                mBpfNetMaps.setNetPermForUids(TRAFFIC_PERMISSION_INTERNET,
                        toIntArray(internetPermissionAppIds));
            }
            if (updateStatsPermissionAppIds.size() != 0) {
                mBpfNetMaps.setNetPermForUids(TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS,
                        toIntArray(updateStatsPermissionAppIds));
            }
            if (noPermissionAppIds.size() != 0) {
                mBpfNetMaps.setNetPermForUids(PERMISSION_NONE,
                        toIntArray(noPermissionAppIds));
            }
            if (uninstalledAppIds.size() != 0) {
                mBpfNetMaps.setNetPermForUids(TRAFFIC_PERMISSION_UNINSTALLED,
                        toIntArray(uninstalledAppIds));
            }
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Pass appId list of special permission failed." + e);
        }
    }

    private synchronized void onSettingChanged() {
        // Step1. Update uids allowed to use restricted networks and compute the set of uids to
        // update.
        final Set<Integer> uidsToUpdate = new ArraySet<>(mUidsAllowedOnRestrictedNetworks);
        updateUidsAllowedOnRestrictedNetworks(mDeps.getUidsAllowedOnRestrictedNetworks(mContext));
        uidsToUpdate.addAll(mUidsAllowedOnRestrictedNetworks);

        final SparseIntArray updatedUids = new SparseIntArray();
        final SparseIntArray removedUids = new SparseIntArray();

        // Step2. For each uid to update, find out its new permission.
        for (Integer uid : uidsToUpdate) {
            final int permission = highestUidNetworkPermission(uid);

            if (PERMISSION_NONE == permission) {
                // Doesn't matter which permission is set here.
                removedUids.put(uid, PERMISSION_NETWORK);
                mUidToNetworkPerm.delete(uid);
                if (hasSdkSandbox(uid)) {
                    int sdkSandboxUid = Process.toSdkSandboxUid(uid);
                    removedUids.put(sdkSandboxUid, PERMISSION_NETWORK);
                    mUidToNetworkPerm.delete(sdkSandboxUid);
                }
            } else {
                updatedUids.put(uid, permission);
                mUidToNetworkPerm.put(uid, permission);
                if (hasSdkSandbox(uid)) {
                    int sdkSandboxUid = Process.toSdkSandboxUid(uid);
                    updatedUids.put(sdkSandboxUid, permission);
                    mUidToNetworkPerm.put(sdkSandboxUid, permission);
                }
            }
        }

        // Step3. Update or revoke permission for uids with netd.
        sendUidsNetworkPermission(updatedUids, true /* add */);
        sendUidsNetworkPermission(removedUids, false /* add */);
        mPermissionUpdateLogs.log("Setting change: update=" + updatedUids
                + ", remove=" + removedUids);
    }

    /**
     * Called when external applications are available.
     *
     * @param pkgList The package names of the external applications.
     */
    public synchronized void onExternalApplicationsAvailable(String[] pkgList) {
        ensureRunningOnHandlerThread();
        if (CollectionUtils.isEmpty(pkgList)) {
            Log.e(TAG, "No available external application.");
            return;
        }

        for (String app : pkgList) {
            for (UserHandle user : mUsers) {
                final PackageInfo info = getPackageInfoAsUser(app, user);
                if (info == null || info.applicationInfo == null) continue;

                final int uid = info.applicationInfo.uid;
                onPackageAdded(app, uid); // Use onPackageAdded to add package one by one.
            }
        }
    }

    /** Dump info to dumpsys */
    public void dump(IndentingPrintWriter pw) {
        pw.println("Interface filtering rules:");
        pw.increaseIndent();
        for (Map.Entry<String, Set<UidRange>> vpn : mVpnInterfaceUidRanges.entrySet()) {
            pw.println("Interface: " + vpn.getKey());
            pw.println("UIDs: " + vpn.getValue().toString());
            pw.println();
        }
        pw.decreaseIndent();

        pw.println();
        pw.println("Lockdown filtering rules:");
        pw.increaseIndent();
        synchronized (this) {
            for (final UidRange range : mVpnLockdownUidRanges.getSet()) {
                pw.println("UIDs: " + range);
            }
        }
        pw.decreaseIndent();

        pw.println();
        pw.println("Update logs:");
        pw.increaseIndent();
        mPermissionUpdateLogs.reverseDump(pw);
        pw.decreaseIndent();
    }

    private static void log(String s) {
        if (DBG) {
            Log.d(TAG, s);
        }
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }

    private static void loge(String s, Throwable e) {
        Log.e(TAG, s, e);
    }

    private class PermissionChangeListener implements PackageManager.OnPermissionsChangedListener {
        @Override
        public void onPermissionsChanged(int uid) {
            long startTimeNanos = SystemClock.elapsedRealtimeNanos();
            try {
                setLocalNetworkPermissions(uid, null);
            } finally {
                long durationNanos = SystemClock.elapsedRealtimeNanos() - startTimeNanos;
                int durationMicros = (int) TimeUnit.NANOSECONDS.toMicros(durationNanos);
                if (DBG) {
                    Log.d(TAG,
                            "setLocalNetworkPermissions in onPermissionsChanged took "
                                    + durationMicros + " microseconds.");
                }
                //The ConnectivityStatsLog#write method is only available on Android T
                //and higher. The surrounding logic in logPermissionChangeListenerLatency
                //ensures this code path is only executed on compatible platform versions, this
                //explicit SDK version check is necessary to suppress the NewApi lint warning.
                if (mDeps.isLnpDeveloperOptInEnabled() && isAtLeastB()) {
                    mDeps.logPermissionChangeListenerLatency(durationMicros);
                }
            }
        }
    }
}
