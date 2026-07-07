/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server;

import static android.net.BpfNetMapsConstants.CONFIGURATION_MAP_PATH;
import static android.net.BpfNetMapsConstants.COOKIE_TAG_MAP_PATH;
import static android.net.BpfNetMapsConstants.CURRENT_STATS_MAP_CONFIGURATION_KEY;
import static android.net.BpfNetMapsConstants.DATA_SAVER_DISABLED;
import static android.net.BpfNetMapsConstants.DATA_SAVER_ENABLED;
import static android.net.BpfNetMapsConstants.DATA_SAVER_ENABLED_KEY;
import static android.net.BpfNetMapsConstants.DATA_SAVER_ENABLED_MAP_PATH;
import static android.net.BpfNetMapsConstants.IIF_MATCH;
import static android.net.BpfNetMapsConstants.INGRESS_DISCARD_MAP_PATH;
import static android.net.BpfNetMapsConstants.L4S_ENABLED_MAP_PATH;
import static android.net.BpfNetMapsConstants.L4S_SOCKOPS_PROGRAM_PATH;
import static android.net.BpfNetMapsConstants.LOCAL_NET_ACCESS_MAP_PATH;
import static android.net.BpfNetMapsConstants.LOCAL_NET_BLOCKED_UID_MAP_PATH;
import static android.net.BpfNetMapsConstants.LOCAL_NET_CACHE_GENERATION_ID_MAP_PATH;
import static android.net.BpfNetMapsConstants.LOCAL_NET_NOTE_OP_ENABLED_MAP_PATH;
import static android.net.BpfNetMapsConstants.LOCAL_NET_UID_HOST_ALLOWLIST_MAP_PATH;
import static android.net.BpfNetMapsConstants.LOCKDOWN_VPN_MATCH;
import static android.net.BpfNetMapsConstants.LOOPBACK_ACCESS_METRICS_ENABLED_MAP_PATH;
import static android.net.BpfNetMapsConstants.LOOPBACK_CHECKS_ENABLED_MAP_PATH;
import static android.net.BpfNetMapsConstants.PERMISSION_PROPAGATION_ENABLED_MAP_PATH;
import static android.net.BpfNetMapsConstants.UID_MIGRATION_ENABLED_MAP_PATH;
import static android.net.BpfNetMapsConstants.UID_OWNER_MAP_PATH;
import static android.net.BpfNetMapsConstants.UID_PERMISSION_CHUNK_MAP_PATH;
import static android.net.BpfNetMapsConstants.UID_PERMISSION_MAP_PATH;
import static android.net.BpfNetMapsConstants.UID_RULES_CONFIGURATION_KEY;
import static android.net.BpfNetMapsUtils.getMatchByFirewallChain;
import static android.net.BpfNetMapsUtils.isFirewallAllowList;
import static android.net.BpfNetMapsUtils.matchToString;
import static android.net.ConnectivityManager.BLOCKED_METERED_REASON_MASK;
import static android.net.ConnectivityManager.BLOCKED_REASON_NONE;
import static android.net.ConnectivityManager.FIREWALL_RULE_ALLOW;
import static android.net.ConnectivityManager.FIREWALL_RULE_DENY;
import static android.permission.flags.Flags.accessLocalNetworkPermissionEnabled;
import static android.system.OsConstants.EINVAL;
import static android.system.OsConstants.ENODEV;
import static android.system.OsConstants.ENOENT;
import static android.system.OsConstants.ENOMEM;
import static android.system.OsConstants.ENOSPC;
import static android.system.OsConstants.EOPNOTSUPP;

import static com.android.modules.utils.build.SdkLevel.isAtLeastB;
import static com.android.net.module.util.bpf.UidPermissionChunk.CHUNK_INT64_COUNT;
import static com.android.net.module.util.bpf.UidPermissionChunk.CHUNK_UID_COUNT;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_ACCESS_LOCAL_NETWORK;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_FORCE_USE_LOOPBACK_INTERFACE;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_INTERACT_ACROSS_USERS_FULL;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_INTERACT_ACROSS_USERS_OR_PROFILES;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_NONE;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_NO_INTERNET;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_UPDATE_DEVICE_STATS;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_BIT_USE_LOOPBACK_INTERFACE;
import static com.android.net.module.util.bpf.UidPermissionChunk.PERMISSION_COUNT;
import static com.android.net.module.util.bpf.UidPermissionChunk.UIDS_PER_INT64;
import static com.android.net.module.util.bpf.UidPermissionChunk.UID_PERMISSION_MASK;
import static com.android.net.module.util.bpf.UidPermissionChunk.getChunkId;
import static com.android.net.module.util.bpf.UidPermissionChunk.getIndex;
import static com.android.net.module.util.bpf.UidPermissionChunk.getShift;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_HOST_ENOMEM;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_HOST_ENOSPC;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_HOST_ERROR;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_HOST_OK;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_NET_ENOMEM;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_NET_ENOSPC;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_NET_ERROR;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_NET_OK;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_NETD_SET_PERMISSION_INTERNET;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_NETD_SET_PERMISSION_NONE;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_NETD_SET_PERMISSION_UNINSTALLED;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_NETD_SET_PERMISSION_UPDATE_DEVICE_STATS;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_TERRIBLE_ERROR_OCCURRED;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_TERRIBLE_ERROR_OCCURRED__ERROR_TYPE__TYPE_INVALID_NET_PERM_SENT_TO_NETD;
import static com.android.server.ConnectivityStatsLog.NETWORK_BPF_MAP_INFO;
import static com.android.server.connectivity.NetworkPermissions.PERMISSION_NONE;
import static com.android.server.connectivity.NetworkPermissions.TRAFFIC_PERMISSION_ACCESS_LOCAL_NETWORK;
import static com.android.server.connectivity.NetworkPermissions.TRAFFIC_PERMISSION_INTERNET;
import static com.android.server.connectivity.NetworkPermissions.TRAFFIC_PERMISSION_UNINSTALLED;
import static com.android.server.connectivity.NetworkPermissions.TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.StatsManager;
import android.content.Context;
import android.net.BpfNetMapsUtils;
import android.net.INetd;
import android.net.UidOwnerValue;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.util.SparseIntArray;
import android.util.StatsEvent;

import androidx.annotation.RequiresApi;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfBoolean;
import com.android.net.module.util.BpfDump;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.IBpfMap.ThrowingBiConsumer;
import com.android.net.module.util.SdkUtil;
import com.android.net.module.util.SingleWriterBpfMap;
import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Bool;
import com.android.net.module.util.Struct.S32;
import com.android.net.module.util.Struct.S64;
import com.android.net.module.util.Struct.U32;
import com.android.net.module.util.Struct.U8;
import com.android.net.module.util.bpf.CookieTagMapValue;
import com.android.net.module.util.bpf.IngressDiscardKey;
import com.android.net.module.util.bpf.IngressDiscardValue;
import com.android.net.module.util.bpf.LocalNetAccessKey;
import com.android.net.module.util.bpf.LocalNetUidHostAllowlistKey;
import com.android.net.module.util.bpf.UidPermissionChunk;
import com.android.server.connectivity.InterfaceTracker;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * BpfNetMaps is responsible for providing traffic controller relevant functionality.
 *
 * @hide
 */
public class BpfNetMaps {
    static {
        if (SdkLevel.isAtLeastT()) {
            System.loadLibrary("service-connectivity");
        }
    }

    private static final String TAG = "BpfNetMaps";
    private final INetd mNetd;
    private final Dependencies mDeps;
    // Use legacy netd for releases before T.
    private static boolean sInitialized = false;

    // Lock for sConfigurationMap entry for UID_RULES_CONFIGURATION_KEY.
    // This entry is not accessed by others.
    // BpfNetMaps acquires this lock while sequence of read, modify, and write.
    private static final Object sUidRulesConfigBpfMapLock = new Object();

    // Lock for sConfigurationMap entry for CURRENT_STATS_MAP_CONFIGURATION_KEY.
    // BpfNetMaps acquires this lock while sequence of read, modify, and write.
    // BpfNetMaps is an only writer of this entry.
    private static final Object sCurrentStatsMapConfigLock = new Object();

    // Lock for updates to the local net access and generation ID maps.
    // BpfNetMaps acquires this lock while sequence of read, modify, and write.
    // BpfNetMaps is an only writer of this entry.
    private static final Object sLocalNetAccessLock = new Object();

    private static final long UID_RULES_DEFAULT_CONFIGURATION = 0;
    private static final long STATS_SELECT_MAP_A = 0;
    private static final long STATS_SELECT_MAP_B = 1;

    private static final int AID_USER_OFFSET = 100000;
    private static final U32 GENERATION_ID_KEY = new U32(0);

    private static IBpfMap<S32, U32> sConfigurationMap = null;
    // BpfMap for UID_OWNER_MAP_PATH. This map is not accessed by others.
    private static IBpfMap<S32, UidOwnerValue> sUidOwnerMap = null;
    private static IBpfMap<S32, U8> sUidPermissionMap = null;
    private static IBpfMap<S32, UidPermissionChunk> sUidPermissionChunkMap = null;
    private static IBpfMap<S64, CookieTagMapValue> sCookieTagMap = null;
    // TODO: Add BOOL class and replace U8?
    private static IBpfMap<S32, U8> sDataSaverEnabledMap = null;
    private static BpfBoolean sPermissionPropagationEnabledBpfBoolean = null;
    private static BpfBoolean sUidMigrationEnabledBpfBoolean = null;
    private static IBpfMap<IngressDiscardKey, IngressDiscardValue> sIngressDiscardMap = null;

    @GuardedBy("sLocalNetAccessLock")
    private static IBpfMap<LocalNetAccessKey, Bool> sLocalNetAccessMap = null;
    private static IBpfMap<U32, Bool> sLocalNetBlockedUidMap = null;
    // The allowlist map is accessed on different threads in ConnectivityService#allowLocalNetAccess
    @GuardedBy("sLocalNetAccessLock")
    private static IBpfMap<LocalNetUidHostAllowlistKey, Bool> sLocalNetUidHostAllowlistMap = null;
    @GuardedBy("sLocalNetAccessLock")
    private static IBpfMap<U32, S64> sLocalNetCacheGenerationIdMap = null;
    private static BpfBoolean sL4sEnabledMap = null;
    private static BpfBoolean sLoopbackAccessMetricsEnabledBpfBoolean = null;
    private static BpfBoolean sLoopbackChecksEnabledBpfBoolean = null;
    private static BpfBoolean sLocalNetNoteOpsEnabledBpfBoolean = null;

    private static final List<Pair<Integer, String>> PERMISSION_LIST = Arrays.asList(
            Pair.create(TRAFFIC_PERMISSION_INTERNET, "PERMISSION_INTERNET"),
            Pair.create(TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS, "PERMISSION_UPDATE_DEVICE_STATS"),
            Pair.create(TRAFFIC_PERMISSION_ACCESS_LOCAL_NETWORK, "PERMISSION_ACCESS_LOCAL_NETWORK")
    );
    private final InterfaceTracker mInterfaceTracker;
    private static boolean sPermissionMapUidMigrationEnabled = false;
    private static boolean sLoopbackAccessMetricsEnabled = false;
    private static boolean sLoopbackChecksEnabled = false;
    private static boolean sBetaMetricsEnabled = false;
    private static boolean sL4sSupported = false;

    @GuardedBy("sLocalNetAccessLock")
    private static long sLnpGenerationID = 0L;

    /**
     * Get the cached com.android.tethering.flags.Flags#permissionMapUidMigration() so the flag
     * value is process stable.
     */
    public boolean isUidMigrationEnabled() {
        return sPermissionMapUidMigrationEnabled;
    }

    /**
     * Get the cached com.android.tethering.flags.Flags#loopbackAccessMetrics() so the flag
     * value is process stable.
     */
    public boolean isLoopbackAccessMetricsEnabled() {
        return sLoopbackAccessMetricsEnabled;
    }

    /**
     * Get the cached android.permission.flags.Flags#useLoopbackInterfacePermissionEnabled() so the
     * flag value is process stable.
     */
    public boolean isLoopbackChecksEnabled() {
        return sLoopbackChecksEnabled;
    }

    /**
     * Get the cached com.android.tethering.flags.Flags#collectBetaMetrics() so the flag
     * value is process stable.
     */
    public boolean isLocalNetMetricsEnabled() {
        return sBetaMetricsEnabled;
    }

    /**
     * Get android.permission.flags.Flags#accessLocalNetworkPermissionEnabled()
     */
    public boolean isAccessLocalNetworkPermissionEnabled() {
        return mDeps.isAccessLocalNetworkPermissionEnabled();
    }

    /**
     * Enable new permission propagation API when uid migration is enabled
     */
    public boolean isPermissionPropagationEnabled() {
        return sPermissionMapUidMigrationEnabled && mDeps.isAccessLocalNetworkPermissionEnabled();
    }

    public static boolean isL4sSupported() {
        return sL4sSupported;
    }

    /**
     * Set configurationMap for test.
     */
    @VisibleForTesting
    public static void setConfigurationMapForTest(IBpfMap<S32, U32> configurationMap) {
        sConfigurationMap = configurationMap;
    }

    /**
     * Set uidOwnerMap for test.
     */
    @VisibleForTesting
    public static void setUidOwnerMapForTest(IBpfMap<S32, UidOwnerValue> uidOwnerMap) {
        sUidOwnerMap = uidOwnerMap;
    }

    /**
     * Set uidPermissionMap for test.
     */
    @VisibleForTesting
    public static void setUidPermissionMapForTest(IBpfMap<S32, U8> uidPermissionMap) {
        sUidPermissionMap = uidPermissionMap;
    }

    /**
     * Set uidPermissionChunkMap for test.
     */
    @VisibleForTesting
    public static void setUidPermissionChunkMapForTest(
        IBpfMap<S32, UidPermissionChunk> uidPermissionChunkMap) {
        sUidPermissionChunkMap = uidPermissionChunkMap;
    }

    /**
     * Set cookieTagMap for test.
     */
    @VisibleForTesting
    public static void setCookieTagMapForTest(
            IBpfMap<S64, CookieTagMapValue> cookieTagMap) {
        sCookieTagMap = cookieTagMap;
    }

    /**
     * Set dataSaverEnabledMap for test.
     */
    @VisibleForTesting
    public static void setDataSaverEnabledMapForTest(IBpfMap<S32, U8> dataSaverEnabledMap) {
        sDataSaverEnabledMap = dataSaverEnabledMap;
    }

    /**
     * Set uidMigrationEnabledBpfBoolean for test.
     */
    @VisibleForTesting
    public static void setUidMigrationEnabledBpfBooleanForTest(
            BpfBoolean uidMigrationEnabledBpfBoolean) {
        sUidMigrationEnabledBpfBoolean = uidMigrationEnabledBpfBoolean;
    }

    /**
     * Set loopbackAccessMetricsEnabledBpfBoolean for test.
     */
    @VisibleForTesting
    public static void setLoopbackAccessMetricsEnabledBpfBooleanForTest(
            BpfBoolean loopbackAccessMetricsEnabledBpfBoolean) {
        sLoopbackAccessMetricsEnabledBpfBoolean = loopbackAccessMetricsEnabledBpfBoolean;
    }

    /**
     * Set loopbackChecksEnabledBpfBoolean for test.
     */
    @VisibleForTesting
    public static void setLoopbackChecksEnabledBpfBooleanForTest(
            BpfBoolean loopbackChecksEnabledBpfBoolean) {
        sLoopbackChecksEnabledBpfBoolean = loopbackChecksEnabledBpfBoolean;
    }

    /**
     * Set localNetNoteOpsEnabledBpfBoolean for test.
     */
    @VisibleForTesting
    public static void setLocalNetNoteOpsEnabledBpfBooleanForTest(
            BpfBoolean localNetNoteOpsEnabledBpfBoolean) {
        sLocalNetNoteOpsEnabledBpfBoolean = localNetNoteOpsEnabledBpfBoolean;
    }

    /**
     * Set permissionPropagationEnabledBpfBoolean for test.
     */
    @VisibleForTesting
    public static void setPermissionPropagationEnabledBpfBooleanForTest(
            BpfBoolean permissionPropagationEnabledBpfBoolean) {
        sPermissionPropagationEnabledBpfBoolean = permissionPropagationEnabledBpfBoolean;
    }

    /**
     * Set ingressDiscardMap for test.
     */
    @VisibleForTesting
    public static void setIngressDiscardMapForTest(
            IBpfMap<IngressDiscardKey, IngressDiscardValue> ingressDiscardMap) {
        sIngressDiscardMap = ingressDiscardMap;
    }

    /**
     * Set localNetAccessMap for test.
     */
    @VisibleForTesting
    public static void setLocalNetAccessMapForTest(
            IBpfMap<LocalNetAccessKey, Bool> localNetAccessMap) {
        synchronized (sLocalNetAccessLock) {
            sLocalNetAccessMap = localNetAccessMap;
        }
    }

    /**
     * Set localNetBlockedUidMap for test.
     */
    @VisibleForTesting
    public static void setLocalNetBlockedUidMapForTest(
            IBpfMap<U32, Bool> localNetBlockedUidMap) {
        sLocalNetBlockedUidMap = localNetBlockedUidMap;
    }

    /**
     * Set l4sEnabledMap for test.
     */
    @VisibleForTesting
    public static void setL4sEnabledMapForTest(
            BpfBoolean l4sEnabledMap) {
        sL4sEnabledMap = l4sEnabledMap;
    }

    /**
     * Set localNetUidHostAllowlistMap for test.
     */
    @VisibleForTesting
    public static void setLocalNetUidHostAllowlistMapForTest(
            IBpfMap<LocalNetUidHostAllowlistKey, Bool> localNetUidHostAllowlistMap) {
        synchronized (sLocalNetAccessLock) {
            sLocalNetUidHostAllowlistMap = localNetUidHostAllowlistMap;
        }
    }

    /**
     * Set localNetCacheGenerationIdMap for test.
     */
    @VisibleForTesting
    public static void setLocalNetCacheGenerationIdMapForTest(
            IBpfMap<U32, S64> localNetCacheGenerationIdMap) {
        synchronized (sLocalNetAccessLock) {
            sLocalNetCacheGenerationIdMap = localNetCacheGenerationIdMap;
        }
    }

    /**
     * Set sInitialized for test.
     */
    @VisibleForTesting
    public static void setInitializedForTest(boolean initialized) {
        sInitialized = initialized;
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static IBpfMap<S32, U32> getConfigurationMap() {
        try {
            return SingleWriterBpfMap.getSingleton(
                    CONFIGURATION_MAP_PATH, S32.class, U32.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open netd configuration map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static IBpfMap<S32, UidOwnerValue> getUidOwnerMap() {
        try {
            return SingleWriterBpfMap.getSingleton(
                    UID_OWNER_MAP_PATH, S32.class, UidOwnerValue.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open uid owner map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static IBpfMap<S32, U8> getUidPermissionMap() {
        try {
            return SingleWriterBpfMap.getSingleton(
                    UID_PERMISSION_MAP_PATH, S32.class, U8.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open uid permission map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static IBpfMap<S32, UidPermissionChunk> getUidPermissionChunkMap() {
        try {
            return SingleWriterBpfMap.getSingleton(
                    UID_PERMISSION_CHUNK_MAP_PATH, S32.class, UidPermissionChunk.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open uid permission chunk map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static IBpfMap<S64, CookieTagMapValue> getCookieTagMap() {
        try {
            // Cannot use SingleWriterBpfMap because it's written by ClatCoordinator as well.
            return new BpfMap<>(COOKIE_TAG_MAP_PATH,
                    S64.class, CookieTagMapValue.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open cookie tag map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static IBpfMap<S32, U8> getDataSaverEnabledMap() {
        try {
            return SingleWriterBpfMap.getSingleton(
                    DATA_SAVER_ENABLED_MAP_PATH, S32.class, U8.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open data saver enabled map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static IBpfMap<IngressDiscardKey, IngressDiscardValue> getIngressDiscardMap() {
        try {
            return SingleWriterBpfMap.getSingleton(INGRESS_DISCARD_MAP_PATH,
                    IngressDiscardKey.class, IngressDiscardValue.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open ingress discard map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.CUR_DEVELOPMENT)
    private static IBpfMap<U32, Bool> getLocalNetBlockedUidMap() {
        try {
            return SingleWriterBpfMap.getSingleton(LOCAL_NET_BLOCKED_UID_MAP_PATH,
                    U32.class, Bool.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open local_net_blocked_uid map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private static IBpfMap<LocalNetUidHostAllowlistKey, Bool> getLocalNetUidHostAllowlistMap() {
        try {
            return SingleWriterBpfMap.getSingleton(LOCAL_NET_UID_HOST_ALLOWLIST_MAP_PATH,
                    LocalNetUidHostAllowlistKey.class, Bool.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open local_net_uid_host_access map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private static IBpfMap<U32, S64> getLocalNetCacheGenerationIdMap() {
        try {
            return SingleWriterBpfMap.getSingleton(LOCAL_NET_CACHE_GENERATION_ID_MAP_PATH,
                    U32.class, S64.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open local_net_cache_generation_id map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static BpfBoolean getUidMigrationEnabledBpfBoolean() {
        try {
            return new BpfBoolean(UID_MIGRATION_ENABLED_MAP_PATH, true);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open uid migration enabled map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static BpfBoolean getLoopbackAccessMetricsEnabledBpfBoolean() {
        try {
            return new BpfBoolean(LOOPBACK_ACCESS_METRICS_ENABLED_MAP_PATH, true);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open loopback access metrics enabled map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private static BpfBoolean getLoopbackChecksEnabledBpfBoolean() {
        try {
            return new BpfBoolean(LOOPBACK_CHECKS_ENABLED_MAP_PATH, true);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open loopback checks enabled map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private static BpfBoolean getPermissionPropagationEnabledBpfBoolean() {
        try {
            return new BpfBoolean(
                    PERMISSION_PROPAGATION_ENABLED_MAP_PATH, true);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open permission propagation enabled map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private static BpfBoolean getLocalNetNoteOpsEnabledBpfBoolean() {
        try {
            return new BpfBoolean(LOCAL_NET_NOTE_OP_ENABLED_MAP_PATH, true);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open LNP note op enabled map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.CUR_DEVELOPMENT)
    private static IBpfMap<LocalNetAccessKey, Bool> getLocalNetAccessMap() {
        try {
            return SingleWriterBpfMap.getSingleton(LOCAL_NET_ACCESS_MAP_PATH,
                    LocalNetAccessKey.class, Bool.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open local_net_access map", e);
        }
    }

    private static BpfBoolean getL4sEnabledMap() {
        try {
            return new BpfBoolean(L4S_ENABLED_MAP_PATH, true /* exclusive */);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open l4s_accecn_ map", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static void initBpfMaps(final Dependencies deps) {
        if (sConfigurationMap == null) {
            sConfigurationMap = getConfigurationMap();
        }
        try {
            sConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY,
                    new U32(UID_RULES_DEFAULT_CONFIGURATION));
        } catch (ErrnoException e) {
            throw new IllegalStateException("Failed to initialize uid rules configuration", e);
        }
        try {
            sConfigurationMap.updateEntry(CURRENT_STATS_MAP_CONFIGURATION_KEY,
                    new U32(STATS_SELECT_MAP_A));
        } catch (ErrnoException e) {
            throw new IllegalStateException("Failed to initialize current stats configuration", e);
        }

        if (sUidOwnerMap == null) {
            sUidOwnerMap = getUidOwnerMap();
        }
        try {
            sUidOwnerMap.clear();
        } catch (ErrnoException e) {
            throw new IllegalStateException("Failed to initialize uid owner map", e);
        }

        if (sUidPermissionMap == null) {
            sUidPermissionMap = getUidPermissionMap();
        }

        if (sCookieTagMap == null) {
            sCookieTagMap = getCookieTagMap();
        }

        if (sDataSaverEnabledMap == null) {
            sDataSaverEnabledMap = getDataSaverEnabledMap();
        }
        try {
            sDataSaverEnabledMap.updateEntry(DATA_SAVER_ENABLED_KEY, new U8(DATA_SAVER_DISABLED));
        } catch (ErrnoException e) {
            throw new IllegalStateException("Failed to initialize data saver configuration", e);
        }

        if (sIngressDiscardMap == null) {
            sIngressDiscardMap = getIngressDiscardMap();
        }
        try {
            sIngressDiscardMap.clear();
        } catch (ErrnoException e) {
            throw new IllegalStateException("Failed to initialize ingress discard map", e);
        }

        if (isAtLeastB()) {
            synchronized (sLocalNetAccessLock) {
                if (sLocalNetAccessMap == null) {
                    sLocalNetAccessMap = getLocalNetAccessMap();
                }
                try {
                    sLocalNetAccessMap.clear();
                } catch (ErrnoException e) {
                    throw new IllegalStateException("Failed to initialize local_net_access map", e);
                }
            }

            if (sLocalNetBlockedUidMap == null) {
                sLocalNetBlockedUidMap = getLocalNetBlockedUidMap();
            }
            try {
                sLocalNetBlockedUidMap.clear();
            } catch (ErrnoException e) {
                throw new IllegalStateException("Failed to initialize local_net_blocked_uid map",
                        e);
            }

            synchronized (sLocalNetAccessLock) {
                if (sLocalNetUidHostAllowlistMap == null) {
                    sLocalNetUidHostAllowlistMap = getLocalNetUidHostAllowlistMap();
                }
                try {
                    sLocalNetUidHostAllowlistMap.clear();
                } catch (ErrnoException e) {
                    throw new IllegalStateException(
                            "Failed to initialize local_net_uid_host_allowlist map", e);
                }
            }

            synchronized (sLocalNetAccessLock) {
                // Don't clear the cache generation ID map like we do other maps. We want to keep
                // the initialization value of 0.
                if (sLocalNetCacheGenerationIdMap == null) {
                    sLocalNetCacheGenerationIdMap = getLocalNetCacheGenerationIdMap();
                }
                try {
                    S64 currentGenId = sLocalNetCacheGenerationIdMap.getValue(GENERATION_ID_KEY);
                    sLnpGenerationID = (currentGenId != null) ? currentGenId.val : 0L;
                    if (sLnpGenerationID % 2 != 0) {
                        // The generation ID should always start off even to ensure the cache is in
                        // a valid state.
                        sLocalNetCacheGenerationIdMap.updateEntry(GENERATION_ID_KEY,
                                new S64(++sLnpGenerationID));
                    }
                } catch (ErrnoException e) {
                    throw new IllegalStateException(
                            "Failed to initialize local_net_cache_generation_id map", e);
                }
            }
        }

        if (isL4sSupported()) {
            if (sL4sEnabledMap == null) {
                sL4sEnabledMap = getL4sEnabledMap();
            }
            try {
                sL4sEnabledMap.clear();
            } catch (ErrnoException e) {
                throw new IllegalStateException("Failed to initialize l4s_accecn_status map",
                    e);
            }
        }

        if (sUidMigrationEnabledBpfBoolean == null) {
            sUidMigrationEnabledBpfBoolean = getUidMigrationEnabledBpfBoolean();
        }

        try {
            sUidMigrationEnabledBpfBoolean.set(sPermissionMapUidMigrationEnabled);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Failed to set uid migration enabled map", e);
        }

        // Local network permission will be supported from Android C+, update this when
        // isAtLeastC() is available.
        if (SdkLevel.isAtLeastB()) {
            if (sPermissionPropagationEnabledBpfBoolean == null) {
                sPermissionPropagationEnabledBpfBoolean =
                        getPermissionPropagationEnabledBpfBoolean();
            }
            try {
                // Enable new permission propagation API when uid migration is enabled
                sPermissionPropagationEnabledBpfBoolean.set(
                    sPermissionMapUidMigrationEnabled
                            && deps.isAccessLocalNetworkPermissionEnabled());
            } catch (ErrnoException e) {
                throw new IllegalStateException("Failed to set permission propagation enabled map",
                        e);
            }

            if (sLocalNetNoteOpsEnabledBpfBoolean == null) {
                sLocalNetNoteOpsEnabledBpfBoolean = getLocalNetNoteOpsEnabledBpfBoolean();
            }
            try {
                // We collect access events if we are enforcing local network permissions or if we
                // are collecting beta metrics
                sLocalNetNoteOpsEnabledBpfBoolean.set(
                        deps.isAccessLocalNetworkPermissionEnabled() || sBetaMetricsEnabled);
            } catch (ErrnoException e) {
                throw new IllegalStateException("Failed to set LNP note ops enabled map", e);
            }
        }

        if (sUidPermissionChunkMap == null) {
            sUidPermissionChunkMap = getUidPermissionChunkMap();
        }
        try {
            sUidPermissionChunkMap.clear();
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot clear uid permission chunk map", e);
        }

        if (SdkUtil.isAtLeast25Q4()) {
            if (sLoopbackAccessMetricsEnabledBpfBoolean == null) {
                sLoopbackAccessMetricsEnabledBpfBoolean =
                        getLoopbackAccessMetricsEnabledBpfBoolean();
            }
            try {
                sLoopbackAccessMetricsEnabledBpfBoolean.set(sLoopbackAccessMetricsEnabled);
            } catch (ErrnoException e) {
                throw new IllegalStateException("Failed to set loopback access metrics enabled map",
                        e);
            }
        }

        if (SdkLevel.isAtLeastB()) {
            if (sLoopbackChecksEnabledBpfBoolean == null) {
                sLoopbackChecksEnabledBpfBoolean = getLoopbackChecksEnabledBpfBoolean();
            }
            try {
                sLoopbackChecksEnabledBpfBoolean.set(sLoopbackChecksEnabled);
            } catch (ErrnoException e) {
                throw new IllegalStateException(
                        "Failed to set cross profile loopback checks enabled map", e);
            }
        }
    }

    /**
     * Initializes the class if it is not already initialized. This method will open maps but not
     * cause any other effects. This method may be called multiple times on any thread.
     */
    private static synchronized void ensureInitialized(final Context context,
            final Dependencies deps) {
        if (sInitialized) return;
        sPermissionMapUidMigrationEnabled = deps.isPermissionMapUidMigrationEnabled();
        sLoopbackAccessMetricsEnabled = deps.isLoopbackAccessMetricsEnabled();
        sLoopbackChecksEnabled = deps.isLoopbackChecksEnabled();
        sBetaMetricsEnabled = deps.isBetaMetricsEnabled();
        if (SdkLevel.isAtLeastT()) {
            sL4sSupported = deps.isL4sProgramLoaded();
            initBpfMaps(deps);
        }
        sInitialized = true;
    }

    /**
     * Dependencies of BpfNetMaps, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Get interface index.
         */
        public int getIfIndex(final String ifName) {
            return Os.if_nametoindex(ifName);
        }

        /**
         * Get interface name
         */
        public String getIfName(final int ifIndex) {
            return Os.if_indextoname(ifIndex);
        }

        /**
         * Synchronously call in to kernel to synchronize_rcu()
         */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        public int synchronizeKernelRCU() {
            try {
                BpfMap.synchronizeKernelRCU();
            } catch (ErrnoException e) {
                return -e.errno;
            }
            return 0;
        }

        /**
         * Build Stats Event for NETWORK_BPF_MAP_INFO atom
         */
        public StatsEvent buildStatsEvent(final int cookieTagMapSize, final int uidOwnerMapSize,
                final int uidPermissionMapSize) {
            return ConnectivityStatsLog.buildStatsEvent(NETWORK_BPF_MAP_INFO, cookieTagMapSize,
                    uidOwnerMapSize, uidPermissionMapSize);
        }

        /**
         * WARNING: DO NOT CALL THIS METHOD DIRECTLY FROM ANY CODE PATH other than permission
         * map uid migration. Wrapper around permissionMapUidMigration() so that it can be mocked
         * in unit test.
         *
         * @see com.android.tethering.flags.Flags#permissionMapUidMigration()
         */
        public boolean isPermissionMapUidMigrationEnabled() {
            return com.android.tethering.flags.Flags.permissionMapUidMigration();
        }

        /**
         * WARNING: DO NOT CALL THIS METHOD DIRECTLY FROM ANY CODE PATH other than loopback access
         * metrics enabling. Wrapper around loopbackAccessMetrics() so that it can be mocked
         * in unit test.
         *
         * @see com.android.tethering.flags.Flags#loopbackAccessMetrics()
         */
        public boolean isLoopbackAccessMetricsEnabled() {
            return SdkUtil.isAtLeast25Q4()
                    && com.android.tethering.flags.Flags.loopbackAccessMetrics();
        }

        /**
         * WARNING: DO NOT CALL THIS METHOD DIRECTLY FROM ANY CODE PATH other than cross profile
         * loopback checks enabling. Wrapper around useLoopbackInterfacePermissionEnabled() so that
         * it can be mocked in unit test.
         *
         * @see android.permission.flags.Flags#useLoopbackInterfacePermissionEnabled()
         */
        public boolean isLoopbackChecksEnabled() {
            return SdkLevel.isAtLeastB()
                    && android.permission.flags.Flags.useLoopbackInterfacePermissionEnabled();
        }

        /**
         * WARNING: DO NOT CALL THIS METHOD DIRECTLY FROM ANY CODE PATH other than lnp
         * permission propagation. Wrapper around accessLocalNetworkPermissionEnabled() so
         * that it can be mocked in unit test.
         *
         * @see android.permission.flags.Flags#accessLocalNetworkPermissionEnabled()
         */
        public boolean isAccessLocalNetworkPermissionEnabled() {
            // Local network permission will be supported from Android C+, update this when
            // isAtLeastC() is available.
            return SdkLevel.isAtLeastB() && accessLocalNetworkPermissionEnabled();
        }

        /**
         * WARNING: DO NOT CALL THIS METHOD DIRECTLY FROM ANY CODE PATH other than lnp
         * permission propagation. Wrapper around collectBetaMetrics() so that it can be mocked in
         * unit tests.
         *
         * @see com.android.tethering.flags.Flags#collectBetaMetrics()
         */
        public boolean isBetaMetricsEnabled() {
            return SdkLevel.isAtLeastB() && com.android.tethering.flags.Flags.collectBetaMetrics();
        }

        public boolean isL4sProgramLoaded() {
            // Silently returns false without throwing if any error occurs.
            return new File(L4S_SOCKOPS_PROGRAM_PATH).exists();
        }

        /**
         * Write CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED metrics
         */
        public void writeStats(final int eventType, final int count) {
            ConnectivityStatsLog.write_non_chained(CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED,
                    Process.SYSTEM_UID,
                    null,
                    eventType,
                    count);
        }

        /**
         * Write CORE_NETWORKING_TERRIBLE_ERROR_OCCURRED metrics
         */
        public void terribleError(final int errorType) {
            ConnectivityStatsLog.write(
                    CORE_NETWORKING_TERRIBLE_ERROR_OCCURRED,
                    errorType
            );
        }
    }

    /** Constructor used after T that doesn't need to use netd anymore. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public BpfNetMaps(final Context context, @NonNull final InterfaceTracker interfaceTracker) {
        this(context, null, interfaceTracker);

        if (!SdkLevel.isAtLeastT()) throw new IllegalArgumentException("BpfNetMaps need to use netd before T");
    }

    public BpfNetMaps(final Context context, final INetd netd, @NonNull final InterfaceTracker
            interfaceTracker) {
        this(context, netd, new Dependencies(), interfaceTracker);
    }

    @VisibleForTesting
    public BpfNetMaps(final Context context, final INetd netd, final Dependencies deps,
            @NonNull final  InterfaceTracker interfaceTracker) {
        Objects.requireNonNull(interfaceTracker);
        ensureInitialized(context, deps);
        mNetd = netd;
        mDeps = deps;
        mInterfaceTracker = interfaceTracker;
    }

    private void maybeThrow(final int err, final String msg) {
        if (err != 0) {
            throw new ServiceSpecificException(err, msg + ": " + Os.strerror(err));
        }
    }

    private void throwIfPreT(final String msg) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException(msg);
        }
    }

    private void throwIfPre25Q2(final String msg) {
        if (!isAtLeastB()) {
            throw new UnsupportedOperationException(msg);
        }
    }

    private void throwIfL4sNotSupported() {
        if (!sL4sSupported) {
            throw new UnsupportedOperationException("L4S not supported on this device");
        }
    }

    private void throwIfUidMigrationIsDisabled(final String msg) {
        if (!isUidMigrationEnabled()) {
            throw new UnsupportedOperationException(msg);
        }
    }

    private void removeRule(final int uid, final long match, final String caller) {
        try {
            synchronized (sUidOwnerMap) {
                final UidOwnerValue oldMatch = sUidOwnerMap.getValue(new S32(uid));

                if (oldMatch == null) {
                    throw new ServiceSpecificException(ENOENT,
                            "sUidOwnerMap does not have entry for uid: " + uid);
                }

                final UidOwnerValue newMatch = new UidOwnerValue(
                        (match == IIF_MATCH) ? 0 : oldMatch.iif,
                        oldMatch.rule & ~match
                );

                if (newMatch.rule == 0) {
                    sUidOwnerMap.deleteEntry(new S32(uid));
                } else {
                    sUidOwnerMap.updateEntry(new S32(uid), newMatch);
                }
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    caller + " failed to remove rule: " + Os.strerror(e.errno));
        }
    }

    private void addRule(final int uid, final long match, final int iif, final String caller) {
        if (match != IIF_MATCH && iif != 0) {
            throw new ServiceSpecificException(EINVAL,
                    "Non-interface match must have zero interface index");
        }

        try {
            synchronized (sUidOwnerMap) {
                final UidOwnerValue oldMatch = sUidOwnerMap.getValue(new S32(uid));

                final UidOwnerValue newMatch;
                if (oldMatch != null) {
                    newMatch = new UidOwnerValue(
                            (match == IIF_MATCH) ? iif : oldMatch.iif,
                            oldMatch.rule | match
                    );
                } else {
                    newMatch = new UidOwnerValue(
                            iif,
                            match
                    );
                }
                sUidOwnerMap.updateEntry(new S32(uid), newMatch);
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    caller + " failed to add rule: " + Os.strerror(e.errno));
        }
    }

    private void addRule(final int uid, final long match, final String caller) {
        addRule(uid, match, 0 /* iif */, caller);
    }

    /**
     * Set target firewall child chain
     *
     * @param childChain target chain to enable
     * @param enable     whether to enable or disable child chain.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setChildChain(final int childChain, final boolean enable) {
        throwIfPreT("setChildChain is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        try {
            synchronized (sUidRulesConfigBpfMapLock) {
                final U32 config = sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY);
                final long newConfig = enable ? (config.val | match) : (config.val & ~match);
                sConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(newConfig));
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to set child chain: " + Os.strerror(e.errno));
        }
    }

    /**
     * Get the specified firewall chain's status.
     *
     * @param childChain target chain
     * @return {@code true} if chain is enabled, {@code false} if chain is not enabled.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    @Deprecated
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public boolean isChainEnabled(final int childChain) {
        return BpfNetMapsUtils.isChainEnabled(sConfigurationMap, childChain);
    }

    private Set<Integer> asSet(final int[] uids) {
        final Set<Integer> uidSet = new ArraySet<>();
        for (final int uid : uids) {
            uidSet.add(uid);
        }
        return uidSet;
    }

    /**
     * Replaces the contents of the specified UID-based firewall chain.
     * Enables the chain for specified uids and disables the chain for non-specified uids.
     *
     * @param chain       Target chain.
     * @param uids        The list of UIDs to allow/deny.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws IllegalArgumentException if {@code chain} is not a valid chain.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void replaceUidChain(final int chain, final int[] uids) {
        throwIfPreT("replaceUidChain is not available on pre-T devices");

        final long match;
        try {
            match = getMatchByFirewallChain(chain);
        } catch (ServiceSpecificException e) {
            // Throws IllegalArgumentException to keep the behavior of
            // ConnectivityManager#replaceFirewallChain API
            throw new IllegalArgumentException("Invalid firewall chain: " + chain);
        }
        final Set<Integer> uidSet = asSet(uids);
        final Set<Integer> uidSetToRemoveRule = new ArraySet<>();
        try {
            synchronized (sUidOwnerMap) {
                sUidOwnerMap.forEach((uid, config) -> {
                    // config could be null if there is a concurrent entry deletion.
                    // http://b/220084230. But sUidOwnerMap update must be done while holding a
                    // lock, so this should not happen.
                    if (config == null) {
                        Log.wtf(TAG, "sUidOwnerMap entry was deleted while holding a lock");
                    } else if (!uidSet.contains((int) uid.val) && (config.rule & match) != 0) {
                        uidSetToRemoveRule.add((int) uid.val);
                    }
                });

                for (final int uid : uidSetToRemoveRule) {
                    removeRule(uid, match, "replaceUidChain");
                }
                for (final int uid : uids) {
                    addRule(uid, match, "replaceUidChain");
                }
            }
        } catch (ErrnoException | ServiceSpecificException e) {
            Log.e(TAG, "replaceUidChain failed: " + e);
        }
    }

    /**
     * Set firewall rule for uid
     *
     * @param childChain   target chain
     * @param uid          uid to allow/deny
     * @param firewallRule either FIREWALL_RULE_ALLOW or FIREWALL_RULE_DENY
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setUidRule(final int childChain, final int uid, final int firewallRule) {
        throwIfPreT("setUidRule is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        final boolean isAllowList = isFirewallAllowList(childChain);
        final boolean add = (firewallRule == FIREWALL_RULE_ALLOW && isAllowList)
                || (firewallRule == FIREWALL_RULE_DENY && !isAllowList);

        if (add) {
            addRule(uid, match, "setUidRule");
        } else {
            removeRule(uid, match, "setUidRule");
        }
    }

    /**
     * Get firewall rule of specified firewall chain on specified uid.
     *
     * @param childChain target chain
     * @param uid        target uid
     * @return either FIREWALL_RULE_ALLOW or FIREWALL_RULE_DENY
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public int getUidRule(final int childChain, final int uid) {
        return BpfNetMapsUtils.getUidRule(sUidOwnerMap, childChain, uid);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private Set<Integer> getUidsMatchEnabled(final int childChain) throws ErrnoException {
        final long match = getMatchByFirewallChain(childChain);
        Set<Integer> uids = new ArraySet<>();
        synchronized (sUidOwnerMap) {
            sUidOwnerMap.forEach((uid, val) -> {
                if (val == null) {
                    Log.wtf(TAG, "sUidOwnerMap entry was deleted while holding a lock");
                } else {
                    if ((val.rule & match) != 0) {
                        uids.add(uid.val);
                    }
                }
            });
        }
        return uids;
    }

    /**
     * Get uids that has FIREWALL_RULE_ALLOW on allowlist chain.
     * Allowlist means the firewall denies all by default, uids must be explicitly allowed.
     *
     * Note that uids that has FIREWALL_RULE_DENY on allowlist chain can not be computed from the
     * bpf map, since all the uids that does not have explicit FIREWALL_RULE_ALLOW rule in bpf map
     * are determined to have FIREWALL_RULE_DENY.
     *
     * @param childChain target chain
     * @return Set of uids
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public Set<Integer> getUidsWithAllowRuleOnAllowListChain(final int childChain)
            throws ErrnoException {
        if (!isFirewallAllowList(childChain)) {
            throw new IllegalArgumentException("getUidsWithAllowRuleOnAllowListChain is called with"
                    + " denylist chain:" + childChain);
        }
        // Corresponding match is enabled for uids that has FIREWALL_RULE_ALLOW on allowlist chain.
        return getUidsMatchEnabled(childChain);
    }

    /**
     * Get uids that has FIREWALL_RULE_DENY on denylist chain.
     * Denylist means the firewall allows all by default, uids must be explicitly denyed
     *
     * Note that uids that has FIREWALL_RULE_ALLOW on denylist chain can not be computed from the
     * bpf map, since all the uids that does not have explicit FIREWALL_RULE_DENY rule in bpf map
     * are determined to have the FIREWALL_RULE_ALLOW.
     *
     * @param childChain target chain
     * @return Set of uids
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public Set<Integer> getUidsWithDenyRuleOnDenyListChain(final int childChain)
            throws ErrnoException {
        if (isFirewallAllowList(childChain)) {
            throw new IllegalArgumentException("getUidsWithDenyRuleOnDenyListChain is called with"
                    + " allowlist chain:" + childChain);
        }
        // Corresponding match is enabled for uids that has FIREWALL_RULE_DENY on denylist chain.
        return getUidsMatchEnabled(childChain);
    }

    /**
     * Add ingress interface filtering rules to a list of UIDs
     *
     * For a given uid, once a filtering rule is added, the kernel will only allow packets from the
     * allowed interface and loopback to be sent to the list of UIDs.
     *
     * Calling this method on one or more UIDs with an existing filtering rule but a different
     * interface name will result in the filtering rule being updated to allow the new interface
     * instead. Otherwise calling this method will not affect existing rules set on other UIDs.
     *
     * @param ifName the name of the interface on which the filtering rules will allow packets to
     *               be received.
     * @param uids   an array of UIDs which the filtering rules will be set
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addUidInterfaceRules(final String ifName, final int[] uids) throws RemoteException {
        if (!SdkLevel.isAtLeastT()) {
            mNetd.firewallAddUidInterfaceRules(ifName, uids);
            return;
        }

        // Null ifName is a wildcard to allow apps to receive packets on all interfaces and
        // ifIndex is set to 0.
        final int ifIndex;
        if (ifName == null) {
            ifIndex = 0;
        } else {
            ifIndex = mDeps.getIfIndex(ifName);
            if (ifIndex == 0) {
                throw new ServiceSpecificException(ENODEV,
                        "Failed to get index of interface " + ifName);
            }
        }
        for (final int uid : uids) {
            try {
                addRule(uid, IIF_MATCH, ifIndex, "addUidInterfaceRules");
            } catch (ServiceSpecificException e) {
                Log.e(TAG, "addRule failed uid=" + uid + " ifName=" + ifName + ", " + e);
            }
        }
    }

    /**
     * Remove ingress interface filtering rules from a list of UIDs
     *
     * Clear the ingress interface filtering rules from the list of UIDs which were previously set
     * by addUidInterfaceRules(). Ignore any uid which does not have filtering rule.
     *
     * @param uids an array of UIDs from which the filtering rules will be removed
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeUidInterfaceRules(final int[] uids) throws RemoteException {
        if (!SdkLevel.isAtLeastT()) {
            mNetd.firewallRemoveUidInterfaceRules(uids);
            return;
        }

        for (final int uid : uids) {
            try {
                removeRule(uid, IIF_MATCH, "removeUidInterfaceRules");
            } catch (ServiceSpecificException e) {
                Log.e(TAG, "removeRule failed uid=" + uid + ", " + e);
            }
        }
    }

    /**
     * Update lockdown rule for uid
     *
     * @param  uid          target uid to add/remove the rule
     * @param  add          {@code true} to add the rule, {@code false} to remove the rule.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void updateUidLockdownRule(final int uid, final boolean add) {
        throwIfPreT("updateUidLockdownRule is not available on pre-T devices");

        if (add) {
            addRule(uid, LOCKDOWN_VPN_MATCH, "updateUidLockdownRule");
        } else {
            removeRule(uid, LOCKDOWN_VPN_MATCH, "updateUidLockdownRule");
        }
    }

    /**
     * Request netd to change the current active network stats map.
     *
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void swapActiveStatsMap() {
        throwIfPreT("swapActiveStatsMap is not available on pre-T devices");

        try {
            synchronized (sCurrentStatsMapConfigLock) {
                final long config = sConfigurationMap.getValue(
                        CURRENT_STATS_MAP_CONFIGURATION_KEY).val;
                final long newConfig = (config == STATS_SELECT_MAP_A)
                        ? STATS_SELECT_MAP_B : STATS_SELECT_MAP_A;
                sConfigurationMap.updateEntry(CURRENT_STATS_MAP_CONFIGURATION_KEY,
                        new U32(newConfig));
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno, "Failed to swap active stats map");
        }

        // After changing the config, it's needed to make sure all the current running eBPF
        // programs are finished and all the CPUs are aware of this config change before the old
        // map is modified. So special hack is needed here to wait for the kernel to do a
        // synchronize_rcu(). Once the kernel called synchronize_rcu(), the updated config will
        // be available to all cores and the next eBPF programs triggered inside the kernel will
        // use the new map configuration. So once this function returns it is safe to modify the
        // old stats map without concerning about race between the kernel and userspace.
        final int err = mDeps.synchronizeKernelRCU();
        maybeThrow(err, "synchronizeKernelRCU failed");
    }

    private void logAndSendNetPermToNetd(final int permissions, final int[] appIds)
            throws RemoteException {
        if (permissions == TRAFFIC_PERMISSION_UNINSTALLED) {
            mDeps.writeStats(
                    CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_NETD_SET_PERMISSION_UNINSTALLED,
                    appIds.length);
        } else if (permissions == PERMISSION_NONE) {
            mDeps.writeStats(
                    CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_NETD_SET_PERMISSION_NONE,
                    appIds.length);
        } else {
            if ((permissions & TRAFFIC_PERMISSION_INTERNET) != 0) {
                mDeps.writeStats(CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_NETD_SET_PERMISSION_INTERNET,
                        appIds.length);
            }
            if ((permissions & TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS) != 0) {
                mDeps.writeStats(CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_NETD_SET_PERMISSION_UPDATE_DEVICE_STATS,
                        appIds.length);
            }
        }

        mNetd.trafficSetNetPermForUids(permissions, appIds);
    }

    /**
     * Assigns android.permission.INTERNET and/or android.permission.UPDATE_DEVICE_STATS to the uids
     * specified. Or remove all permissions from the uids.
     *
     * @param permissions The permission to grant, it could be either PERMISSION_INTERNET and/or
     *                    PERMISSION_UPDATE_DEVICE_STATS. If the permission is NO_PERMISSIONS, then
     *                    revoke all permissions for the uids.
     * @param uids        uid of users to grant permission
     * @throws RemoteException when netd has crashed.
     */
    public void setNetPermForUids(final int permissions, final int[] uids) throws RemoteException {
        if (!SdkLevel.isAtLeastT()) {
            logAndSendNetPermToNetd(permissions, uids);
            return;
        }

        // Remove the entry if package is uninstalled or uid has only INTERNET permission.
        if (permissions == TRAFFIC_PERMISSION_UNINSTALLED
                || permissions == TRAFFIC_PERMISSION_INTERNET) {
            for (final int uid : uids) {
                try {
                    sUidPermissionMap.deleteEntry(new S32(uid));
                } catch (ErrnoException e) {
                    Log.e(TAG, "Failed to remove uid " + uid + " from permission map: " + e);
                }
            }
            return;
        }

        for (final int uid : uids) {
            try {
                sUidPermissionMap.updateEntry(new S32(uid), new U8((short) permissions));
            } catch (ErrnoException e) {
                Log.e(TAG, "Failed to set permission "
                        + permissions + " to uid " + uid + ": " + e);
            }
        }
    }

    private void setPermListForUidsToNetd(
        final SparseIntArray permissionsUids
    ) throws RemoteException {
        // merge permission for App ID
        final SparseIntArray permissionsAppIds = new SparseIntArray();
        final ArrayMap<Integer, ArraySet<Integer>> permissionIntegersByAppId =
                groupPermissionsIdsBy(
                        permissionsUids,
                        (uid, permissions) -> UserHandle.getAppId(uid), /* keyFunction */
                        (uid, permissions) -> permissions /* valueFunction */
                );
        for (int i = 0; i < permissionIntegersByAppId.size(); i++) {
            final Integer appId = permissionIntegersByAppId.keyAt(i);
            final ArraySet<Integer> permissionIntegers = permissionIntegersByAppId.valueAt(i);
            permissionsAppIds.put(appId, mergePermissionsForAppId(permissionIntegers));
        }

        // group App Ids by permissions
        final ArrayMap<Integer, ArraySet<Integer>> appIdsByPermissionInteger =
                groupPermissionsIdsBy(
                        permissionsAppIds,
                        (appId, permissions) -> permissions, /* keyFunction */
                        (appId, permissions) -> appId /* valueFunction */
                );
        for (int i = 0; i < appIdsByPermissionInteger.size(); i++) {
            final Integer permissions = appIdsByPermissionInteger.keyAt(i);
            final ArraySet<Integer> appIds = appIdsByPermissionInteger.valueAt(i);
            final int netdSupportedTrafficPerm = TRAFFIC_PERMISSION_INTERNET
                    | TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS;
            final int clearMask = ~netdSupportedTrafficPerm;
            if (permissions != TRAFFIC_PERMISSION_UNINSTALLED && (permissions & clearMask) != 0) {
                Log.e(TAG, "unknown permission type: " + permissions);
                mDeps.terribleError(CORE_NETWORKING_TERRIBLE_ERROR_OCCURRED__ERROR_TYPE__TYPE_INVALID_NET_PERM_SENT_TO_NETD);
                continue;
            }
            logAndSendNetPermToNetd(permissions, CollectionUtils.toIntArray(appIds));
        }
    }

    /**
     * Convert and sets traffic permission for each corresponding UID in the provided arrays
     * in UidPermissionChunk bfp map.
     * <p>
     * <b>Note:</b> This method is not thread-safe and is intended to be called from
     * PermissionMonitor thread.
     * </p>
     *
     * @param permissionsUids integer pairs of uids and the traffic permissions. If the
     *                        permission is 0, revoke all permissions of that uid.
     * @throws RemoteException when netd has crashed.
     */
    public void setPermListForUids(final SparseIntArray permissionsUids) throws RemoteException {
        throwIfUidMigrationIsDisabled(
            "setPermListForUids is not available when flag permission_map_uid_migration" +
            " is disabled");

        if (!SdkLevel.isAtLeastT()) {
            setPermListForUidsToNetd(permissionsUids);
            return;
        }

        // Convert traffic permission to a dense set of persistence bits
        for(int i = 0; i < permissionsUids.size(); i++) {
            final int uid = permissionsUids.keyAt(i);
            final int permissions = permissionsUids.valueAt(i);
            if(permissions == TRAFFIC_PERMISSION_UNINSTALLED) {
                permissionsUids.put(uid, PERMISSION_BIT_NONE);
            } else {
                permissionsUids.put(uid, convertToChunkPermission(permissions));
            }
        }

        setChunkPermListForUids(permissionsUids);
    }

    /**
     * Sets a specific chunk permission for each corresponding UID in the provided arrays
     * in UidPermissionChunk bpf map.
     * <p>
     * <b>Note:</b> This method is not thread-safe and is intended to be called from
     * PermissionMonitor thread.
     * </p>
     *
     * @param permissionsUids integer pairs of uids and the chunk permissions. If the
     *                        permission is 0, revoke all permissions of that uid.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setChunkPermListForUids(final SparseIntArray permissionsUids) {
        throwIfUidMigrationIsDisabled(
            "setChunkPermListForUids is not available when flag permission_map_uid_migration" +
            " is disabled");

        // group UIDs by chunkId
        final ArrayMap<Integer, ArraySet<Integer>> uidsByChunkId = groupPermissionsIdsBy(
            permissionsUids,
            (uid, permissions) -> getChunkId(uid), /* keyFunction */
            (uid, permissions) -> uid /* valueFunction */
        );

        // write permission bits into chunk
        final int numChunks = uidsByChunkId.size();
        for (int i = 0; i < numChunks; i++) {
            final Integer chunkId = uidsByChunkId.keyAt(i);
            final ArraySet<Integer> uidsInChunk = uidsByChunkId.valueAt(i);

            try {
                final UidPermissionChunk uidPermissionChunk = sUidPermissionChunkMap.getValue(
                    new S32(chunkId));
                final long[] chunk;
                if (uidPermissionChunk == null) {
                    chunk = new long[CHUNK_INT64_COUNT];
                } else {
                    chunk = uidPermissionChunk.val;
                }

                final int numUids = uidsInChunk.size();
                for (int j = 0; j < numUids; j++) {
                    final int uid = uidsInChunk.valueAt(j);
                    final int permissionBits = permissionsUids.get(uid);
                    // Clear permission mask for the uid.
                    chunk[getIndex(uid)] &= ~(UID_PERMISSION_MASK << getShift(uid));
                    // Set new permission for the uid
                    chunk[getIndex(uid)] |= (permissionBits & UID_PERMISSION_MASK) << getShift(uid);
                }

                boolean emptyChunk = true;
                for (int j = 0; j < CHUNK_INT64_COUNT; j++) if (chunk[j] != 0) {
                    emptyChunk = false;
                    break;
                }

                if (emptyChunk) {
                    sUidPermissionChunkMap.deleteEntry(new S32(chunkId));
                } else {
                    sUidPermissionChunkMap.updateEntry(
                        new S32(chunkId),
                        new UidPermissionChunk(chunk)
                    );
                }
            } catch (ErrnoException e) {
                throw new IllegalStateException("Failed to update uid permission chunk map", e);
            }
        }
    }

    /**
     * Convert traffic permission to a dense set of persistence bits
     *
     * @param trafficPermissions the traffic permission to be converted.
     * @return the permission bits which can be stored in UidPermissionChunkMap
     */
    private int convertToChunkPermission(int trafficPermissions) {
        int chunkPermissions = PERMISSION_BIT_NONE;
        if ((trafficPermissions & TRAFFIC_PERMISSION_ACCESS_LOCAL_NETWORK) != 0) {
            chunkPermissions |= PERMISSION_BIT_ACCESS_LOCAL_NETWORK;
        }
        if ((trafficPermissions & TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS) != 0) {
            chunkPermissions |= PERMISSION_BIT_UPDATE_DEVICE_STATS;
        }
        if ((trafficPermissions & TRAFFIC_PERMISSION_INTERNET) == 0) {
            chunkPermissions |= PERMISSION_BIT_NO_INTERNET;
        }
        return chunkPermissions;
    }

    private int convertToTrafficPermission(int chunkPermissions) {
        int trafficPermissions = PERMISSION_NONE;
        if ((chunkPermissions & PERMISSION_BIT_ACCESS_LOCAL_NETWORK) != 0) {
            trafficPermissions |= TRAFFIC_PERMISSION_ACCESS_LOCAL_NETWORK;
        }
        if ((chunkPermissions & PERMISSION_BIT_UPDATE_DEVICE_STATS) != 0) {
            trafficPermissions |= TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS;
        }
        if ((chunkPermissions & PERMISSION_BIT_NO_INTERNET) == 0) {
            trafficPermissions |= TRAFFIC_PERMISSION_INTERNET;
        }
        return trafficPermissions;
    }

    private int mergePermissionsForAppId(ArraySet<Integer> permissionIntegers) {
        int result = 0;
        boolean isAllTrafficPermissionUninstalled = true;
        for (int i = 0; i < permissionIntegers.size(); i++) {
            final int permissionInteger = permissionIntegers.valueAt(i);
            if (permissionInteger != TRAFFIC_PERMISSION_UNINSTALLED) {
                result |= permissionInteger;
                isAllTrafficPermissionUninstalled = false;
            }
        }
        return isAllTrafficPermissionUninstalled ? TRAFFIC_PERMISSION_UNINSTALLED : result;
    }

    private ArrayMap<Integer, ArraySet<Integer>> groupPermissionsIdsBy(
        SparseIntArray permissionsUids,
        BiFunction<Integer, Integer, Integer> keyFunction,
        BiFunction<Integer, Integer, Integer> valueFunction
    ){
        final ArrayMap<Integer, ArraySet<Integer>> groupByResult = new ArrayMap<>();
        for(int i = 0; i < permissionsUids.size(); i++) {
            final int uid = permissionsUids.keyAt(i);
            final int permissions = permissionsUids.valueAt(i);
            final int key = keyFunction.apply(uid, permissions);

            ArraySet<Integer> valueSet = groupByResult.get(key);
            if (valueSet == null) {
                valueSet = new ArraySet<>();
                groupByResult.put(key, valueSet);
            }
            valueSet.add(valueFunction.apply(uid, permissions));
        }
        return groupByResult;
    }

    /**
     * Remove permissions from the UidPermissionChunk bpf map for a given App ID.
     * <p>
     * <b>Note:</b> This method is not thread-safe and is intended to be called from
     * PermissionManager thread.
     * </p>
     *
     * @param appId App Id whose permissions should be removed from the UidPermissionChunk bpf map.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void removePermissionsForAppId(int appId) {
        throwIfUidMigrationIsDisabled(
            "removePermissionsForAppId is not available when flag permission_map_uid_migration" +
            " is disabled");
        try {
            removePermissionsIf(uid -> uid % AID_USER_OFFSET == appId);
        } catch (RemoteException | ErrnoException e) {
            Log.e(TAG, "Failed to remove permission for App Id "
                    + appId + ": " + e);
        }
    }

    /**
     * Remove permissions from the UidPermissionChunk bpf map for a given User ID.
     * <p>
     * <b>Note:</b> This method is not thread-safe and is intended to be called from
     * PermissionManager thread.
     * </p>
     *
     * @param userId User Id whose permissions should be removed from the UidPermissionChunk bpf
     *               map.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void removePermissionsForUserId(int userId) {
        throwIfUidMigrationIsDisabled(
            "removePermissionsForUserId is not available when flag permission_map_uid_migration" +
            " is disabled");
        try {
            removePermissionsIf(uid -> uid / AID_USER_OFFSET == userId);
        } catch (RemoteException | ErrnoException e) {
            Log.e(TAG, "Failed to remove permission for User Id "
                    + userId + ": " + e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void removePermissionsIf(
        Predicate<Integer> function
    ) throws ErrnoException, RemoteException {
        final SparseIntArray permissionsUids = new SparseIntArray();
        forEachUidPermission((uid, permissionBits) -> {
            if (function.test(uid)) {
                permissionsUids.put(uid, PERMISSION_BIT_NONE);
            }
        });
        setChunkPermListForUids(permissionsUids);
    }

    private void forEachUidPermission(
        ThrowingBiConsumer<Integer, Integer> action
    ) throws ErrnoException {
        sUidPermissionChunkMap.forEach((chunkId, chunk) -> {
            for(int index = 0; index < chunk.val.length; index++) {
                int shift = 0;
                long currentValue = chunk.val[index];
                while(currentValue != 0) {
                    int permissionBits = (int) (currentValue & UID_PERMISSION_MASK);
                    if (permissionBits > 0) {
                        int uid = CHUNK_UID_COUNT * chunkId.val + index * UIDS_PER_INT64
                                + shift / PERMISSION_COUNT;
                        action.accept(uid, permissionBits);
                    }
                    currentValue = currentValue >>> PERMISSION_COUNT;
                    shift += PERMISSION_COUNT;
                }
            }
        });
    }

    /**
     * Add configuration to local_net_access trie map.
     * @param lpmBitlen prefix length that will be used for longest matching
     * @param iface interface name
     * @param address remote address. ipv4 addresses would be mapped to v6
     * @param protocol required for longest match in special cases
     * @param remotePort src/dst port for ingress/egress
     * @param isAllowed is the local network call allowed or blocked.
     */
    @RequiresApi(Build.VERSION_CODES.CUR_DEVELOPMENT)
    public void addLocalNetAccess(final int lpmBitlen, @Nullable final String iface,
            final InetAddress address, final int protocol, final int remotePort,
            final boolean isAllowed) {
        throwIfPre25Q2("addLocalNetAccess is not available on pre-B devices");
        if (iface == null) {
            Log.e(TAG, "Null iface, skip addLocalNetAccess for " + address);
            return;
        }
        int ifIndex = mInterfaceTracker.getInterfaceIndex(iface);
        if (ifIndex == 0) {
            mInterfaceTracker.addInterface(iface);
            ifIndex = mInterfaceTracker.getInterfaceIndex(iface);
        }
        if (ifIndex == 0) {
            Log.e(TAG, "Failed to get if index, skip addLocalNetAccess for " + address
                    + "(" + iface + ")");
            return;
        }
        final LocalNetAccessKey localNetAccessKey = new LocalNetAccessKey(lpmBitlen, ifIndex,
                address, protocol, remotePort);

        synchronized (sLocalNetAccessLock) {
            incrementLnpGenerationId(true);
            updateLocalNetAccessMap(localNetAccessKey, isAllowed);
            incrementLnpGenerationId(false);
        }
    }

    /**
     * Remove configuration to local_net_access trie map.
     * @param lpmBitlen prefix length that will be used for longest matching
     * @param iface interface name
     * @param address remote address. ipv4 addresses would be mapped to v6
     * @param protocol required for longest match in special cases
     * @param remotePort src/dst port for ingress/egress
     */
    @RequiresApi(Build.VERSION_CODES.CUR_DEVELOPMENT)
    public void removeLocalNetAccess(final int lpmBitlen, @Nullable final String iface,
            final InetAddress address, final int protocol, final int remotePort) {
        throwIfPre25Q2("removeLocalNetAccess is not available on pre-B devices");
        final int ifIndex;
        if (iface == null) {
            ifIndex = 0;
        } else {
            ifIndex = mInterfaceTracker.getInterfaceIndex(iface);
        }
        if (ifIndex == 0) {
            Log.e(TAG, "Failed to get if index, skip removeLocalNetAccess for " + address
                    + "(" + iface + ")");
            return;
        }
        final LocalNetAccessKey localNetAccessKey = new LocalNetAccessKey(lpmBitlen, ifIndex,
                address, protocol, remotePort);

        synchronized (sLocalNetAccessLock) {
            incrementLnpGenerationId(true);
            try {
                sLocalNetAccessMap.deleteEntry(localNetAccessKey);
            } catch (ErrnoException e) {
                Log.wtf(TAG, "Failed to remove local network access for localNetAccessKey : "
                        + localNetAccessKey, e);
            }
            incrementLnpGenerationId(false);
        }
    }

    /**
     * Fetches value available in local_net_access bpf map for provided configuration
     * @param lpmBitlen  prefix length that will be used for longest matching
     * @param iface    interface name
     * @param address    remote address. ipv4 addresses would be mapped to v6
     * @param protocol   required for longest match in special cases
     * @param remotePort src/dst port for ingress/egress
     * @return false if the configuration is disallowed, true if the configuration is absent i.e. it
     * is not local network or if configuration is allowed like local dns servers.
     */
    @RequiresApi(Build.VERSION_CODES.CUR_DEVELOPMENT)
    public boolean getLocalNetAccess(final int lpmBitlen, @Nullable final String iface,
            final InetAddress address, final int protocol, final int remotePort) {
        throwIfPre25Q2("getLocalNetAccess is not available on pre-B devices");
        final int ifIndex;
        if (iface == null) {
            ifIndex = 0;
        } else {
            ifIndex = mInterfaceTracker.getInterfaceIndex(iface);
        }
        if (ifIndex == 0) {
            Log.e(TAG, "Failed to get if index, returning default from getLocalNetAccess for "
                    + address + "(" + iface + ")");
            return true;
        }
        final LocalNetAccessKey localNetAccessKey = new LocalNetAccessKey(lpmBitlen, ifIndex,
                address, protocol, remotePort);
        try {
            Bool value;
            synchronized (sLocalNetAccessLock) {
                value = sLocalNetAccessMap.getValue(localNetAccessKey);
            }
            return value == null || value.val;
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to find local network access configuration for "
                    + "localNetAccessKey : " + localNetAccessKey);
        }
        return true;
    }

    /**
     * Set the L4S enabled status.
     *
     * @param enabled The new status for L4S. Must be true or false.
     */
    public void setL4sEnabled(boolean enabled) {
        throwIfL4sNotSupported();

        try {
            sL4sEnabledMap.set(enabled);
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to set L4S enabled: " + enabled, e);
        }
    }

    /**
     * Get the L4S enabled status.
     *
     * @return The current L4S enabled status.
     */
    public boolean isL4sEnabled() {
        throwIfL4sNotSupported();

        try {
            return sL4sEnabledMap.get();
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to get L4S enabled", e);
        }
        return false;
    }

    /**
     * Add uid to local_net_blocked_uid map.
     * @param uid application uid that needs to block local network calls.
     */
    @RequiresApi(Build.VERSION_CODES.CUR_DEVELOPMENT)
    public void addUidToLocalNetBlockMap(final int uid) {
        throwIfPre25Q2("addUidToLocalNetBlockMap is not available on pre-B devices");
        try {
            sLocalNetBlockedUidMap.updateEntry(new U32(uid), new Bool(true));
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to add local network blocked for uid : " + uid);
        }
    }

    /**
     * True if local network calls are blocked for application.
     * @param uid application uid that needs check if local network calls are blocked.
     */
    @RequiresApi(Build.VERSION_CODES.CUR_DEVELOPMENT)
    public boolean isUidBlockedFromUsingLocalNetwork(final int uid) {
        throwIfPre25Q2("isUidBlockedFromUsingLocalNetwork is not available on pre-B devices");
        try {
            final Bool value = sLocalNetBlockedUidMap.getValue(new U32(uid));
            return value == null ? false : value.val;
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to find uid(" + uid
                    + ") is present in local network blocked map");
        }
        return false;
    }

    /**
     * Remove uid from local_net_blocked_uid map(if present).
     * @param uid application uid that needs check if local network calls are blocked.
     */
    @RequiresApi(Build.VERSION_CODES.CUR_DEVELOPMENT)
    public void removeUidFromLocalNetBlockMap(final int uid) {
        throwIfPre25Q2("removeUidFromLocalNetBlockMap is not available on pre-B devices");
        try {
            sLocalNetBlockedUidMap.deleteEntry(new U32(uid));
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to remove uid(" + uid + ") from local network blocked map");
        }
    }

    /**
     * Add an entry to map_netd_local_net_uid_host_allowlist_map.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    public void addLocalNetUidAccess(final int uid, @NonNull final String iface) {
        throwIfPre25Q2("addLocalNetUidAccess is not available on pre-B devices");
        int ifIndex = mInterfaceTracker.getInterfaceIndex(iface);
        if (ifIndex == 0) {
            Log.e(TAG, "Failed to get if index, skip addLocalNetUidAccess for uid: " + uid
                    + " on iface: " + iface);
            return;
        }

        final LocalNetUidHostAllowlistKey key = new LocalNetUidHostAllowlistKey(uid, ifIndex);
        synchronized (sLocalNetAccessLock) {
            incrementLnpGenerationId(true);
            updateLocalNetUidHostAllowlistMap(key, true);
            incrementLnpGenerationId(false);
        }
    }

    /**
     * Remove an entry from map_netd_local_net_uid_host_allowlist_map.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    public void removeLocalNetUidAccess(final int uid, @NonNull final String iface) {
        throwIfPre25Q2("removeLocalNetUidAccess is not available on pre-B devices");
        final int ifIndex = mInterfaceTracker.getInterfaceIndex(iface);
        if (ifIndex == 0) {
            Log.e(TAG, "Failed to get if index, skip removeLocalNetUidAccess for uid: " + uid
                    + " on iface: " + iface);
            return;
        }

        final LocalNetUidHostAllowlistKey key = new LocalNetUidHostAllowlistKey(uid, ifIndex);
        try {
            synchronized (sLocalNetAccessLock) {
                incrementLnpGenerationId(true);
                sLocalNetUidHostAllowlistMap.deleteEntry(key);
                incrementLnpGenerationId(false);
            }
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to remove local network access for uid: " + uid + " on ifIndex: "
                    + ifIndex);
        }
    }

    /**
     * Add an entry to map_netd_local_net_uid_host_allowlist_map.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    public void addLocalNetUidHostAccess(final int uid, final int ifIndex,
            @NonNull final InetAddress address) {
        throwIfPre25Q2("addLocalNetUidHostAccess is not available on pre-B devices");
        final LocalNetUidHostAllowlistKey key = new LocalNetUidHostAllowlistKey(
                uid, ifIndex, address);
        synchronized (sLocalNetAccessLock) {
            incrementLnpGenerationId(true);
            updateLocalNetUidHostAllowlistMap(key, true);
            incrementLnpGenerationId(false);
        }
    }

    /**
     * Remove all entries from map_netd_local_net_uid_host_allowlist_map that match the ifIndex.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    public void removeLocalNetHostAllowlistForInterface(final int ifIndex) {
        throwIfPre25Q2("removeLocalNetHostAllowlistForInterface is not available on pre-B devices");
        synchronized (sLocalNetAccessLock) {
            incrementLnpGenerationId(true);
            try {
                sLocalNetUidHostAllowlistMap.forEach((key, value) -> {
                    if (key.ifIndex == ifIndex) {
                        try {
                            sLocalNetUidHostAllowlistMap.deleteEntry(key);
                        } catch (ErrnoException e) {
                            Log.wtf(TAG, "Failed removing local net allowlist entries for ifIndex "
                                    + ifIndex, e);
                        }
                    }
                });
                incrementLnpGenerationId(false);
            } catch (ErrnoException e) {
                Log.wtf("Failed to iterate over sLocalNetUidHostAllowlistMap", e);
            }
        }
    }

    /**
     * Get granted permissions for specified uid. If uid is not in the map, this method returns
     * {@link android.net.INetd.PERMISSION_INTERNET} since this is a default permission.
     * See {@link #setNetPermForUids}
     *
     * @param uid target uid
     * @return    granted permissions.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public int getNetPermForUid(final int uid) {
        if (isUidMigrationEnabled()) {
            final int chunkPermissions = getChunkPermForUid(uid);
            return convertToTrafficPermission(chunkPermissions);
        }

        final int appId = UserHandle.getAppId(uid);
        try {
            // Key of uid permission map is appId
            // TODO: Rename map name
            final U8 permissions = sUidPermissionMap.getValue(new S32(appId));
            return permissions != null ? permissions.val : TRAFFIC_PERMISSION_INTERNET;
        } catch (ErrnoException e) {
            Log.wtf(TAG, "Failed to get permission for uid: " + uid);
            return TRAFFIC_PERMISSION_INTERNET;
        }
    }

    /**
     * Get granted chunk permissions for specified uid.
     *
     * @param uid the uid to get the granted permissions
     * @return    the granted chunk permissions.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @VisibleForTesting
    public int getChunkPermForUid(final int uid) {
        throwIfUidMigrationIsDisabled(
            "getChunkPermForUid is not available when flag" +
            " permission_map_uid_migration is disabled");
        try {
            UidPermissionChunk uidPermissionChunk = sUidPermissionChunkMap.getValue(
                new S32(getChunkId(uid)));
            if (uidPermissionChunk == null) {
                return PERMISSION_BIT_NONE;
            }
            long[] chunk = uidPermissionChunk.val;
            return (int)(
                (chunk[getIndex(uid)] >> getShift(uid)) & UID_PERMISSION_MASK);
        }
        catch (ErrnoException e) {
            Log.wtf(TAG, "Failed to get chunk permission for uid: " + uid);
            return PERMISSION_BIT_NONE;
        }
    }

    /**
     * Set Data Saver enabled or disabled
     *
     * @param enable     whether Data Saver is enabled or disabled.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setDataSaverEnabled(boolean enable) {
        throwIfPreT("setDataSaverEnabled is not available on pre-T devices");

        try {
            final short config = enable ? DATA_SAVER_ENABLED : DATA_SAVER_DISABLED;
            sDataSaverEnabledMap.updateEntry(DATA_SAVER_ENABLED_KEY, new U8(config));
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno, "Unable to set data saver: "
                    + Os.strerror(e.errno));
        }
    }

    /**
     * Set ingress discard rule
     *
     * @param address target address to set the ingress discard rule
     * @param iface allowed interface
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setIngressDiscardRule(final InetAddress address, final String iface) {
        throwIfPreT("setIngressDiscardRule is not available on pre-T devices");
        final int ifIndex = mDeps.getIfIndex(iface);
        if (ifIndex == 0) {
            Log.e(TAG, "Failed to get if index, skip setting ingress discard rule for " + address
                    + "(" + iface + ")");
            return;
        }
        try {
            sIngressDiscardMap.updateEntry(new IngressDiscardKey(address),
                    new IngressDiscardValue(ifIndex, ifIndex));
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to set ingress discard rule for " + address + "("
                    + iface + "), " + e);
        }
    }

    /**
     * Remove ingress discard rule
     *
     * @param address target address to remove the ingress discard rule
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void removeIngressDiscardRule(final InetAddress address) {
        throwIfPreT("removeIngressDiscardRule is not available on pre-T devices");
        try {
            sIngressDiscardMap.deleteEntry(new IngressDiscardKey(address));
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to remove ingress discard rule for " + address + ", " + e);
        }
    }

    /**
     * Get blocked reasons for specified uid
     *
     * @param uid Target Uid
     * @return Reasons of network access blocking for an UID
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public int getUidNetworkingBlockedReasons(final int uid) {
        return BpfNetMapsUtils.getUidNetworkingBlockedReasons(uid,
                sConfigurationMap, sUidOwnerMap, sDataSaverEnabledMap);
    }

    /**
     * Return whether the network access of specified uid is blocked on metered networks
     *
     * @param uid The target uid.
     * @return True if the network access is blocked on metered networks. Otherwise, false
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public boolean isUidRestrictedOnMeteredNetworks(final int uid) {
        final int blockedReasons = getUidNetworkingBlockedReasons(uid);
        return (blockedReasons & BLOCKED_METERED_REASON_MASK) != BLOCKED_REASON_NONE;
    }

    /*
     * Return whether the network is blocked by firewall chains for the given uid.
     *
     * Note that {@link #getDataSaverEnabled()} has a latency before V.
     *
     * @param uid The target uid.
     * @param isNetworkMetered Whether the target network is metered.
     *
     * @return True if the network is blocked. Otherwise, false.
     * @throws ServiceSpecificException if the read fails.
     *
     * @hide
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public boolean isUidNetworkingBlocked(final int uid, boolean isNetworkMetered) {
        return BpfNetMapsUtils.isUidNetworkingBlocked(uid, isNetworkMetered,
                sConfigurationMap, sUidOwnerMap, sDataSaverEnabledMap);
    }

    /** Register callback for statsd to pull atom. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setPullAtomCallback(final Context context) {
        throwIfPreT("setPullAtomCallback is not available on pre-T devices");

        final StatsManager statsManager = context.getSystemService(StatsManager.class);
        statsManager.setPullAtomCallback(NETWORK_BPF_MAP_INFO, null /* metadata */,
                BackgroundThread.getExecutor(), this::pullBpfMapInfoAtom);
    }

    private <K extends Struct, V extends Struct> int getMapSize(IBpfMap<K, V> map)
            throws ErrnoException {
        // forEach could restart iteration from the beginning if there is a concurrent entry
        // deletion. netd and skDestroyListener could delete CookieTagMap entry concurrently.
        // So using Set to count the number of entry in the map.
        Set<K> keySet = new ArraySet<>();
        map.forEach((k, v) -> keySet.add(k));
        return keySet.size();
    }

    private int getAppidPermissionCount() throws ErrnoException {
        // forEach could restart iteration from the beginning if there is a concurrent entry
        // deletion. So using Set to count the number of entry in the map.
        Set<Integer> keySet = new ArraySet<>();
        forEachUidPermission((uid, permissionBits) -> keySet.add(UserHandle.getAppId(uid)));
        return keySet.size();
    }

    /** Callback for StatsManager#setPullAtomCallback */
    @VisibleForTesting
    public int pullBpfMapInfoAtom(final int atomTag, final List<StatsEvent> data) {
        if (atomTag != NETWORK_BPF_MAP_INFO) {
            Log.e(TAG, "Unexpected atom tag: " + atomTag);
            return StatsManager.PULL_SKIP;
        }

        try {
            data.add(mDeps.buildStatsEvent(getMapSize(sCookieTagMap), getMapSize(sUidOwnerMap),
                    isUidMigrationEnabled()
                            ? getAppidPermissionCount() : getMapSize(sUidPermissionMap)));
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to pull NETWORK_BPF_MAP_INFO atom: " + e);
            return StatsManager.PULL_SKIP;
        }
        return StatsManager.PULL_SUCCESS;
    }

    private String permissionToString(int permissionMask) {
        if (permissionMask == PERMISSION_NONE) {
            return "PERMISSION_NONE";
        }
        if (permissionMask == TRAFFIC_PERMISSION_UNINSTALLED) {
            // PERMISSION_UNINSTALLED should never appear in the map
            return "PERMISSION_UNINSTALLED error!";
        }

        final StringJoiner sj = new StringJoiner(" ");
        for (Pair<Integer, String> permission: PERMISSION_LIST) {
            final int permissionFlag = permission.first;
            final String permissionName = permission.second;
            if ((permissionMask & permissionFlag) != 0) {
                sj.add(permissionName);
                permissionMask &= ~permissionFlag;
            }
        }
        if (permissionMask != 0) {
            sj.add("PERMISSION_UNKNOWN(" + permissionMask + ")");
        }
        return sj.toString();
    }

    private String permissionsInChunkToString(int permissions) {
        if (permissions < 0 || permissions > UID_PERMISSION_MASK) {
            return "PERMISSION_UNKNOWN(" + permissions + ")";
        }
        final StringJoiner sj = new StringJoiner(" ");
        if ((permissions & PERMISSION_BIT_ACCESS_LOCAL_NETWORK) != 0) {
            sj.add("PERMISSION_ACCESS_LOCAL_NETWORK");
        }
        if ((permissions & PERMISSION_BIT_UPDATE_DEVICE_STATS) != 0) {
            sj.add("PERMISSION_UPDATE_DEVICE_STATS");
        }
        if ((permissions & PERMISSION_BIT_NO_INTERNET) == 0) {
            sj.add("PERMISSION_INTERNET");
        }
        if ((permissions & PERMISSION_BIT_USE_LOOPBACK_INTERFACE) != 0) {
            sj.add("PERMISSION_USE_LOOPBACK_INTERFACE");
        }
        if ((permissions & PERMISSION_BIT_FORCE_USE_LOOPBACK_INTERFACE) != 0) {
            sj.add("PERMISSION_FORCE_USE_LOOPBACK_INTERFACE");
        }
        if ((permissions & PERMISSION_BIT_INTERACT_ACROSS_USERS_FULL) != 0) {
            sj.add("PERMISSION_INTERACT_ACROSS_USERS_FULL");
        }
        if ((permissions & PERMISSION_BIT_INTERACT_ACROSS_USERS_OR_PROFILES) != 0) {
            sj.add("PERMISSION_INTERACT_ACROSS_USERS_OR_PROFILES");
        }
        if (sj.length() == 0) {
            return "PERMISSION_NONE";
        }
        return sj.toString();
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void dumpOwnerMatchConfig(final IndentingPrintWriter pw) {
        try {
            final long match = sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val;
            pw.println("current ownerMatch configuration: " + match + " " + matchToString(match));
        } catch (ErrnoException e) {
            pw.println("Failed to read ownerMatch configuration: " + e);
        }
    }

    private void dumpCurrentStatsMapConfig(final IndentingPrintWriter pw) {
        try {
            final long config = sConfigurationMap.getValue(CURRENT_STATS_MAP_CONFIGURATION_KEY).val;
            final String currentStatsMap =
                    (config == STATS_SELECT_MAP_A) ? "SELECT_MAP_A" : "SELECT_MAP_B";
            pw.println("current statsMap configuration: " + config + " " + currentStatsMap);
        } catch (ErrnoException e) {
            pw.println("Falied to read current statsMap configuration: " + e);
        }
    }

    private void dumpDataSaverConfig(final IndentingPrintWriter pw) {
        try {
            final short config = sDataSaverEnabledMap.getValue(DATA_SAVER_ENABLED_KEY).val;
            // Any non-zero value converted from short to boolean is true by convention.
            pw.println("sDataSaverEnabledMap: " + (config != DATA_SAVER_DISABLED));
        } catch (ErrnoException e) {
            pw.println("Failed to read data saver configuration: " + e);
        }
    }

    @GuardedBy("sLocalNetAccessLock")
    private void incrementLnpGenerationId(boolean expectEven) throws IllegalStateException {
        if (expectEven != ((sLnpGenerationID & 1) == 0)) {
            throw new IllegalStateException(
                    "Parity error in the local net cache generation ID. This should never happen.");
        }
        try {
            sLocalNetCacheGenerationIdMap.updateEntry(GENERATION_ID_KEY,
                    new S64(++sLnpGenerationID));
        } catch (ErrnoException e) {
            Log.wtf("Failed to increment LNP generation ID to: " + sLnpGenerationID, e);
        }
    }

    @GuardedBy("sLocalNetAccessLock")
    private void updateLocalNetAccessMap(final LocalNetAccessKey key, final boolean isAllowed) {
        int eventType =
                CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_NET_OK;
        try {
            sLocalNetAccessMap.updateEntry(key, new Bool(isAllowed));
        } catch (ErrnoException e) {
            Log.wtf(TAG, "Failed to add local network access for localNetAccessKey: "
                    + key + ", isAllowed: " + isAllowed, e);
            if (e.errno == ENOMEM) {
                eventType = CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_NET_ENOMEM;
            } else if (e.errno == ENOSPC) {
                eventType = CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_NET_ENOSPC;
            } else {
                eventType = CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_NET_ERROR;
            }
        }
        mDeps.writeStats(eventType, 1);
    }

    @GuardedBy("sLocalNetAccessLock")
    private void updateLocalNetUidHostAllowlistMap(final LocalNetUidHostAllowlistKey key,
            final boolean isAllowed) {
        int eventType =
                CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_HOST_OK;
        try {
            sLocalNetUidHostAllowlistMap.updateEntry(key, new Bool(isAllowed));
        } catch (ErrnoException e) {
            Log.wtf(TAG, "Failed to add local network access for uid: " + key.uid
                    + " on ifIndex: " + key.ifIndex, e);
            if (e.errno == ENOMEM) {
                eventType = CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_HOST_ENOMEM;
            } else if (e.errno == ENOSPC) {
                eventType = CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_HOST_ENOSPC;
            } else {
                eventType = CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_LNP_TRIE_HOST_ERROR;
            }
        }
        mDeps.writeStats(eventType, 1);
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private void dumpPermissionPropagationConfig(final IndentingPrintWriter pw) {
        try {
            final boolean enabled = sPermissionPropagationEnabledBpfBoolean.get();
            pw.println("sPermissionPropagationEnabledMap: " + enabled);
        } catch (ErrnoException e) {
            pw.println("Failed to read permission propagation configuration: " + e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void dumpUidMigrationConfig(final IndentingPrintWriter pw) {
        try {
            final boolean enabled = sUidMigrationEnabledBpfBoolean.get();
            pw.println("sUidMigrationEnabledBpfBoolean: " + enabled);
        } catch (ErrnoException e) {
            pw.println("Failed to read uid migration enabled map: " + e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private void dumpLocalNetNoteOpsConfig(final IndentingPrintWriter pw) {
        try {
            final boolean enabled = sLocalNetNoteOpsEnabledBpfBoolean.get();
            pw.println("sLocalNetNoteOpsEnabledBpfBoolean: " + enabled);
        } catch (ErrnoException e) {
            pw.println("Failed to read LNP note ops enabled map: " + e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void dumpLoopbackAccessMetricsConfig(final IndentingPrintWriter pw) {
        try {
            final boolean enabled = sLoopbackAccessMetricsEnabledBpfBoolean.get();
            pw.println("sLoopbackAccessMetricsEnabledBpfBoolean: " + enabled);
        } catch (ErrnoException e) {
            pw.println("Failed to read loopback access metrics enabled map: " + e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private void dumpLoopbackChecksConfig(final IndentingPrintWriter pw) {
        try {
            final boolean enabled = sLoopbackChecksEnabledBpfBoolean.get();
            pw.println("sLoopbackChecksEnabledBpfBoolean: " + enabled);
        } catch (ErrnoException e) {
            pw.println("Failed to read loopback checks enabled map: " + e);
        }
    }

    private void dumpL4sEnabledConfig(final IndentingPrintWriter pw) {
        try {
            pw.println("sL4sEnabledMap: " + sL4sEnabledMap.get());
        } catch (ErrnoException e) {
            pw.println("Failed to read L4S enabled map: " + e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void dumpUidPermissionChunkMap(final IndentingPrintWriter pw) {
        pw.println("sUidPermissionChunkMap:" );
        pw.increaseIndent();
        try {
            forEachUidPermission((uid, permissionBits) -> {
                pw.println(uid + " " + permissionsInChunkToString(permissionBits));
            });
        } catch (ErrnoException e) {
            pw.println("Failed to read uid permission chunk map: " + e);
        }
        pw.decreaseIndent();
    }

    /**
     * Dump BPF maps
     *
     * @param pw print writer
     * @param fd file descriptor to output
     * @param verbose verbose dump flag, if true dump the BpfMap contents
     * @throws IOException when file descriptor is invalid.
     * @throws ServiceSpecificException when the method is called on an unsupported device.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void dump(final IndentingPrintWriter pw, final FileDescriptor fd, boolean verbose)
            throws IOException, ServiceSpecificException {
        if (!SdkLevel.isAtLeastT()) {
            throw new ServiceSpecificException(
                    EOPNOTSUPP, "dumpsys connectivity trafficcontroller dump not available on pre-T"
                    + " devices, use dumpsys netd trafficcontroller instead.");
        }

        pw.println("TrafficController");  // required by CTS testDumpBpfNetMaps

        pw.println();
        if (verbose) {
            pw.println();
            pw.println("BPF map content:");
            pw.increaseIndent();

            dumpOwnerMatchConfig(pw);
            dumpCurrentStatsMapConfig(pw);
            pw.println();

            // TODO: Remove CookieTagMap content dump
            // NetworkStatsService also dumps CookieTagMap and NetworkStatsService is a right place
            // to dump CookieTagMap. But the TagSocketTest in CTS depends on this dump so the tests
            // need to be updated before remove the dump from BpfNetMaps.
            BpfDump.dumpMap(sCookieTagMap, pw, "sCookieTagMap",
                    (key, value) -> "cookie=" + key.val
                            + " tag=0x" + Long.toHexString(value.tag)
                            + " uid=" + value.uid);
            BpfDump.dumpMap(sUidOwnerMap, pw, "sUidOwnerMap",
                    (uid, match) -> {
                        if ((match.rule & IIF_MATCH) != 0) {
                            // TODO: convert interface index to interface name by IfaceIndexNameMap
                            return uid.val + " " + matchToString(match.rule) + " " + match.iif;
                        } else {
                            return uid.val + " " + matchToString(match.rule);
                        }
                    });
            BpfDump.dumpMap(sUidPermissionMap, pw, "sUidPermissionMap",
                    (uid, permission) -> uid.val + " " + permissionToString(permission.val));
            BpfDump.dumpMap(sIngressDiscardMap, pw, "sIngressDiscardMap",
                    (key, value) -> "[" + key.dstAddr + "]: "
                            + value.iif1 + "(" + mDeps.getIfName(value.iif1) + "), "
                            + value.iif2 + "(" + mDeps.getIfName(value.iif2) + ")");
            synchronized (sLocalNetAccessLock) {
                if (sLocalNetAccessMap != null) {
                    BpfDump.dumpMap(sLocalNetAccessMap, pw,
                            "sLocalNetAccessMap (default is true meaning global)",
                            (key, value) -> " " + key + ": " + value.val);
                }
            }
            if (sLocalNetBlockedUidMap != null) {
                BpfDump.dumpMap(sLocalNetBlockedUidMap, pw, "sLocalNetBlockedUidMap",
                        (key, value) -> " " + key + ": " + value.val);
            }
            synchronized (sLocalNetAccessLock) {
                if (sLocalNetUidHostAllowlistMap != null) {
                    BpfDump.dumpMap(sLocalNetUidHostAllowlistMap, pw,
                            "sLocalNetUidHostAllowlistMap",
                            (key, value) -> " " + key + ": " + value.val);
                }
            }
            if (sLoopbackAccessMetricsEnabledBpfBoolean != null) {
                dumpLoopbackAccessMetricsConfig(pw);
            }
            dumpDataSaverConfig(pw);
            dumpUidMigrationConfig(pw);
            // Local network permission will be supported from Android C+, update this when
            // isAtLeastC() is available.
            if (SdkLevel.isAtLeastB()) {
                dumpPermissionPropagationConfig(pw);
            }
            if (SdkLevel.isAtLeastB()) {
                dumpLoopbackChecksConfig(pw);
            }
            if (SdkLevel.isAtLeastB()) {
                dumpLocalNetNoteOpsConfig(pw);
            }
            if (isL4sSupported()) {
                dumpL4sEnabledConfig(pw);
            }
            dumpUidPermissionChunkMap(pw);
            pw.decreaseIndent();
        }
    }
}
