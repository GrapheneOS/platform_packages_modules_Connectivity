/*
 * Copyright (C) 2018 The Android Open Source Project
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

#pragma once

#include <cutils/android_filesystem_config.h>
#include <linux/if.h>
#include <linux/if_ether.h>
#include <linux/in.h>
#include <linux/in6.h>

#ifdef __cplusplus
#include <string_view>
#include "XtBpfProgLocations.h"
#endif

// This header file is shared by eBPF kernel programs (C) and netd (C++) and
// some of the maps are also accessed directly from Java mainline module code.
//
// Hence: explicitly pad all relevant structures and assert that their size
// is the sum of the sizes of their fields.
#define STRUCT_SIZE(name, size) _Static_assert(sizeof(name) == (size), "Incorrect struct size.")

typedef struct {
    uint32_t uid;
    uint32_t tag;
} UidTagValue;
STRUCT_SIZE(UidTagValue, 2 * 4);  // 8

typedef struct {
    uint32_t uid;
    uint32_t tag;
    uint32_t counterSet;
    uint32_t ifaceIndex;
} StatsKey;
STRUCT_SIZE(StatsKey, 4 * 4);  // 16

typedef struct {
    uint64_t rxPackets;
    uint64_t rxBytes;
    uint64_t txPackets;
    uint64_t txBytes;
} StatsValue;
STRUCT_SIZE(StatsValue, 4 * 8);  // 32

#ifdef __cplusplus
static inline StatsValue& operator+=(StatsValue& lhs, const StatsValue& rhs) {
    lhs.rxPackets += rhs.rxPackets;
    lhs.rxBytes += rhs.rxBytes;
    lhs.txPackets += rhs.txPackets;
    lhs.txBytes += rhs.txBytes;
    return lhs;
}
#endif

typedef struct {
    char name[IFNAMSIZ];
} __attribute__((aligned(16))) IfaceValue;
STRUCT_SIZE(IfaceValue, 16);  // 16 (aligned to 16 for atomicity)

typedef struct {
  uint64_t timestampNs;
  uint32_t ifindex;
  uint32_t length;

  uint32_t uid;
  uint32_t tag;

  __be16 sport;
  __be16 dport;

  bool egress:1,
       wakeup:1;
  uint8_t ipProto;
  uint8_t tcpFlags;
  uint8_t ipVersion; // 4=IPv4, 6=IPv6, 0=unknown
} PacketTrace;
STRUCT_SIZE(PacketTrace, 8+4+4 + 4+4 + 2+2 + 1+1+1+1);

typedef struct {
    // Longest prefix match length in bits (value from 0 to 192).
    uint32_t lpm_bitlen;
    uint32_t if_index;
    // IPv4 uses IPv4-mapped IPv6 address format.
    struct in6_addr remote_ip6;
    // u16 instead of u8 to avoid padding due to alignment requirement.
    uint16_t protocol;
    __be16 remote_port;
} LocalNetAccessKey;
STRUCT_SIZE(LocalNetAccessKey, 4 + 4 + 16 + 2 + 2); // 28

typedef struct {
    uint64_t ceb;
    uint64_t e0b;
    uint64_t e1b;
    uint32_t ce_count;
    bool enabled;
    bool ce_inited;
    bool byte_inited;
    uint8_t pad;
} L4SStorage;
STRUCT_SIZE(L4SStorage, 8 + 8 + 8 + 4 + 1 + 1 + 1 + 1); // 32

typedef struct {
    // Generation ID of the LNP cache when the `result` was stored
    uint64_t generation_id;
    LocalNetAccessKey key;
    // Whether this socket is currently connected. Can only be true for TCP
    bool is_connected_tcp;
    // The cached result of LNP permission checks
    bool result;
    uint8_t padding[2];
} LnpCache;
STRUCT_SIZE(LnpCache, 8 + 28 + 1 + 1 + 2); // 40

typedef struct {
    uint64_t cached_at_ns;
    struct in6_addr daddr; // Stores v6 or v4-mapped-v6
    __be16 dport;
    bool result;
    uint8_t padding[5];
} LoopbackCache;
STRUCT_SIZE(LoopbackCache, 8 + 16 + 2 + 1 + 5); // 32

typedef struct {
    uint64_t cookie;
    // Store gid and uid to make them available outside the program types that
    // support `bpf_get_socket_uid`
    uint32_t gid;
    uint32_t uid;
    // A bitmask of enum values in DropReasonType.
    uint64_t dropReasons;
    LnpCache lnp_cache;
    L4SStorage l4s;
    LoopbackCache loopback_cache;
} SkStorageValue;
STRUCT_SIZE(SkStorageValue, 8 + 4 + 4 + 8 + 40 + 32 + 32); // 128

enum LoopbackAccessResult : uint32_t {
  LOOPBACK_ACCESS_ALLOWED = 0,
  LOOPBACK_ACCESS_BLOCKED = (1 << 0),
};

typedef struct {
  uint32_t src_uid;
  uint32_t dst_uid;
  enum LoopbackAccessResult result;
} LoopbackAccessEvent;
STRUCT_SIZE(LoopbackAccessEvent, 4 + 4 + 4);

#define STATS_MAP_SIZE 5000
#define CONFIGURATION_MAP_SIZE 2

#ifdef __cplusplus

#define BPF_NETD_PATH "/sys/fs/bpf/netd_shared/"

#define BPF_EGRESS_PROG_PATH BPF_NETD_PATH "prog_netd_egress_stats"
#define BPF_INGRESS_PROG_PATH BPF_NETD_PATH "prog_netd_ingress_stats"

#define ASSERT_STRING_EQUAL(s1, s2) \
    static_assert(std::string_view(s1) == std::string_view(s2), "mismatch vs Android T netd")

/* -=-=-=-=- WARNING -=-=-=-=-
 *
 * These 4 xt_bpf program paths are actually defined by:
 *   //system/netd/include/mainline/XtBpfProgLocations.h
 * which is intentionally a non-automerged location.
 *
 * They are *UNCHANGEABLE* due to being hard coded in Android T's netd binary
 * as such we have compile time asserts that things match.
 * (which will be validated during build on mainline-prod branch against old system/netd)
 *
 * If you break this, netd on T will fail to start with your tethering mainline module.
 */
ASSERT_STRING_EQUAL(XT_BPF_INGRESS_PROG_PATH,   BPF_NETD_PATH "prog_netd_skfilter_ingress_xtbpf");
ASSERT_STRING_EQUAL(XT_BPF_EGRESS_PROG_PATH,    BPF_NETD_PATH "prog_netd_skfilter_egress_xtbpf");
ASSERT_STRING_EQUAL(XT_BPF_ALLOWLIST_PROG_PATH, BPF_NETD_PATH "prog_netd_skfilter_allowlist_xtbpf");
ASSERT_STRING_EQUAL(XT_BPF_DENYLIST_PROG_PATH,  BPF_NETD_PATH "prog_netd_skfilter_denylist_xtbpf");

#define CGROUP_INET_CREATE_PROG_PATH BPF_NETD_PATH "prog_netd_cgroupsock_inet_create"
#define CGROUP_INET_RELEASE_PROG_PATH BPF_NETD_PATH "prog_netd_cgroupsockrelease_inet_release"
#define CGROUP_BIND4_PROG_PATH BPF_NETD_PATH "prog_netd_bind4_inet4_bind"
#define CGROUP_BIND6_PROG_PATH BPF_NETD_PATH "prog_netd_bind6_inet6_bind"
#define CGROUP_CONNECT4_PROG_PATH BPF_NETD_PATH "prog_netd_connect4_inet4_connect"
#define CGROUP_CONNECT6_PROG_PATH BPF_NETD_PATH "prog_netd_connect6_inet6_connect"
#define CGROUP_UDP4_RECVMSG_PROG_PATH BPF_NETD_PATH "prog_netd_recvmsg4_udp4_recvmsg"
#define CGROUP_UDP6_RECVMSG_PROG_PATH BPF_NETD_PATH "prog_netd_recvmsg6_udp6_recvmsg"
#define CGROUP_UDP4_SENDMSG_PROG_PATH BPF_NETD_PATH "prog_netd_sendmsg4_udp4_sendmsg"
#define CGROUP_UDP6_SENDMSG_PROG_PATH BPF_NETD_PATH "prog_netd_sendmsg6_udp6_sendmsg"
#define CGROUP_GETSOCKOPT_PROG_PATH BPF_NETD_PATH "prog_netd_getsockopt_prog"
#define CGROUP_SETSOCKOPT_PROG_PATH BPF_NETD_PATH "prog_netd_setsockopt_prog"

#define TC_BPF_INGRESS_ACCOUNT_PROG_NAME "prog_netd_schedact_ingress_account"
#define TC_BPF_INGRESS_ACCOUNT_PROG_PATH BPF_NETD_PATH TC_BPF_INGRESS_ACCOUNT_PROG_NAME

#define COOKIE_TAG_MAP_PATH BPF_NETD_PATH "map_netd_cookie_tag_map"
#define UID_COUNTERSET_MAP_PATH BPF_NETD_PATH "map_netd_uid_counterset_map"
#define APP_UID_STATS_MAP_PATH BPF_NETD_PATH "map_netd_app_uid_stats_map"
#define STATS_MAP_A_PATH BPF_NETD_PATH "map_netd_stats_map_A"
#define STATS_MAP_B_PATH BPF_NETD_PATH "map_netd_stats_map_B"
#define IFACE_INDEX_NAME_MAP_PATH BPF_NETD_PATH "map_netd_iface_index_name_map"
#define IFACE_STATS_MAP_PATH BPF_NETD_PATH "map_netd_iface_stats_map"
#define CONFIGURATION_MAP_PATH BPF_NETD_PATH "map_netd_configuration_map"
#define UID_OWNER_MAP_PATH BPF_NETD_PATH "map_netd_uid_owner_map"
#define UID_PERMISSION_CHUNK_MAP_PATH                                          \
    BPF_NETD_PATH "map_netd_uid_permission_chunk_map"
#define UID_PERMISSION_MAP_PATH BPF_NETD_PATH "map_netd_uid_permission_map"
#define INGRESS_DISCARD_MAP_PATH BPF_NETD_PATH "map_netd_ingress_discard_map"
#define NETD_PID_MAP_PATH BPF_NETD_PATH "map_netd_netd_pid_map"
#define PACKET_TRACE_RINGBUF_PATH BPF_NETD_PATH "map_netd_packet_trace_ringbuf"
#define PACKET_TRACE_ENABLED_MAP_PATH BPF_NETD_PATH "map_netd_packet_trace_enabled_map"
#define DATA_SAVER_ENABLED_MAP_PATH BPF_NETD_PATH "map_netd_data_saver_enabled_map"
#define LOCAL_NET_ACCESS_MAP_PATH BPF_NETD_PATH "map_netd_local_net_access_map"
#define LOCAL_NET_BLOCKED_UID_MAP_PATH BPF_NETD_PATH "map_netd_local_net_blocked_uid_map"
#define LOCAL_NET_UID_HOST_ALLOWLIST_MAP_PATH                                  \
    BPF_NETD_PATH "map_netd_local_net_uid_host_allowlist_map"
#define UID_MIGRATION_ENABLED_MAP_PATH                                         \
    BPF_NETD_PATH "map_netd_uid_migration_enabled_map"
#define PERMISSION_PROPAGATION_ENABLED_MAP_PATH                                                 \
    BPF_NETD_PATH "map_netd_permission_propagation_enabled_map"
#define LOCAL_NET_NOTE_OP_RINGBUF_PATH BPF_NETD_PATH "map_netd_local_net_note_op_ringbuf"
#define LOCAL_NET_NOTE_OP_CACHE_MAP_PATH BPF_NETD_PATH "map_netd_local_net_note_op_cache_map"
#define LOCAL_NET_NOTE_OP_ENABLED_MAP_PATH BPF_NETD_PATH "map_netd_local_net_note_op_enabled_map"
#define LOCAL_NET_CACHE_GENERATION_ID_MAP_PATH                                 \
    BPF_NETD_PATH "map_netd_local_net_cache_generation_id_map"
#define LOOPBACK_ACCESS_RINGBUF_NETD_PATH BPF_NETD_PATH "map_netd_loopback_access_ringbuf"
#define LOOPBACK_ACCESS_CACHE_MAP_NETD_PATH BPF_NETD_PATH "map_netd_loopback_access_cache_map"
#define LOOPBACK_ACCESS_METRICS_ENABLED_MAP_NETD_PATH                                         \
    BPF_NETD_PATH "map_netd_loopback_access_metrics_enabled_map"
#define LOOPBACK_CHECKS_ENABLED_MAP_NETD_PATH                                  \
    BPF_NETD_PATH "map_netd_loopback_checks_enabled_map"

#define L4S_EGRESS_ETHER_PROG_PATH    BPF_NETD_PATH "prog_netd_schedcls_egress_accecn_eth"
#define L4S_EGRESS_RAWIP_PROG_PATH    BPF_NETD_PATH "prog_netd_schedcls_egress_accecn_rawip"
#define L4S_OPTIONS_SOCKOPS_PROG_PATH BPF_NETD_PATH "prog_netd_sockops_accecn_option"

#define L4S_CONN_COUNTER_MAP_PATH        BPF_NETD_PATH "map_netd_l4s_conn_counter"
#define L4S_ACCECN_ENABLED_MAP_PATH   BPF_NETD_PATH "map_netd_l4s_accecn_enabled_map"

#endif // __cplusplus

// LINT.IfChange(match_type)
enum UidOwnerMatchType : uint32_t {
    NO_MATCH = 0,
    HAPPY_BOX_MATCH = (1 << 0),
    PENALTY_BOX_USER_MATCH = (1 << 1),
    DOZABLE_MATCH = (1 << 2),
    STANDBY_MATCH = (1 << 3),
    POWERSAVE_MATCH = (1 << 4),
    RESTRICTED_MATCH = (1 << 5),
    LOW_POWER_STANDBY_MATCH = (1 << 6),
    IIF_MATCH = (1 << 7),
    LOCKDOWN_VPN_MATCH = (1 << 8),
    OEM_DENY_1_MATCH = (1 << 9),
    OEM_DENY_2_MATCH = (1 << 10),
    OEM_DENY_3_MATCH = (1 << 11),
    BACKGROUND_MATCH = (1 << 12),
    PENALTY_BOX_ADMIN_MATCH = (1 << 13),
};
// LINT.ThenChange(../framework/src/android/net/BpfNetMapsConstants.java)

// TODO(b/436242702): remove this permission masks once the uid migration flag is rolled out
// The following are used in uid_permission_map
enum BpfPermissionMatch : uint8_t {
    BPF_PERMISSION_INTERNET = 1 << 2,
    BPF_PERMISSION_UPDATE_DEVICE_STATS = 1 << 3,
};
// In production we use two identical stats maps to record per uid stats and
// do swap and clean based on the configuration specified here. The statsMapType
// value in configuration map specified which map is currently in use.
enum StatsMapType : uint32_t {
    SELECT_MAP_A,
    SELECT_MAP_B,
};

// TODO: change the configuration object from a bitmask to an object with clearer
// semantics, like a struct.
typedef uint32_t BpfConfig;
static const BpfConfig DEFAULT_CONFIG = 0;

typedef struct {
    // Allowed interface index. Only applicable if IIF_MATCH is set in the rule bitmask above.
    uint32_t iif;
    // A bitmask of enum values in UidOwnerMatchType.
    uint32_t rule;
} UidOwnerValue;
STRUCT_SIZE(UidOwnerValue, 2 * 4);  // 8

typedef struct {
    // The destination ip of the incoming packet.  IPv4 uses IPv4-mapped IPv6 address format.
    struct in6_addr daddr;
} IngressDiscardKey;
STRUCT_SIZE(IngressDiscardKey, 16);  // 16

typedef struct {
    // Allowed interface indexes.  Use same value multiple times if you just want to match 1 value.
    uint32_t iif[2];
} IngressDiscardValue;
STRUCT_SIZE(IngressDiscardValue, 2 * 4);  // 8

typedef struct {
    // Longest prefix match length in bits (value from 0 to 192).
    uint32_t lpm_bitlen;
    uint32_t uid;
    uint32_t if_index;
    // IPv4 uses IPv4-mapped IPv6 address format.
    struct in6_addr remote_ip6;
} LocalNetUidHostAllowlistKey;
STRUCT_SIZE(LocalNetUidHostAllowlistKey, 4 + 4 + 4 + 16);  // 28

// LINT.IfChange(uid_permission_chunk_type)
// Each UID costs 7 bits (permissions ACCESS_LOCAL_NETWORK / INTERNET /
// UPDATE_DEVICE_STATS or loopback related perms)
// One int64 can store up to 9 UIDs (7 * 9 = 63 bits per int64)
#define PERMISSION_COUNT 7
#define UIDS_PER_INT64 (64 / PERMISSION_COUNT)
#define CHUNK_INT64_COUNT 128
// One chunk can store 128 * 9 = 1152 UIDs using 128 int64
#define CHUNK_UID_COUNT (CHUNK_INT64_COUNT * UIDS_PER_INT64)
#define UID_PERMISSION_MASK ((1 << PERMISSION_COUNT) - 1)
#define PERMISSION_BIT_NONE 0
#define PERMISSION_BIT_ACCESS_LOCAL_NETWORK (1 << 0)
#define PERMISSION_BIT_UPDATE_DEVICE_STATS (1 << 1)
#define PERMISSION_BIT_NO_INTERNET (1 << 2)
// Required for an app to interact with other applications via IP packets on the
// loopback interface.
#define PERMISSION_BIT_USE_LOOPBACK_INTERFACE (1 << 3)
// Required to be able to interact with other applications via IP packets on the
// loopback interface without requiring permissions from the other app
#define PERMISSION_BIT_FORCE_USE_LOOPBACK_INTERFACE (1 << 4)
// Permissions below are required to interact across users/profiles via IP
// packets on the loopback interface.
#define PERMISSION_BIT_INTERACT_ACROSS_USERS_FULL (1 << 5)
#define PERMISSION_BIT_INTERACT_ACROSS_USERS_OR_PROFILES (1 << 6)
// LINT.ThenChange(../../common/src/com/android/net/module/util/bpf/UidPermissionChunk.java)

typedef struct {
    uint64_t block[CHUNK_INT64_COUNT];
} UidPermissionChunk;
STRUCT_SIZE(UidPermissionChunk, 8 * CHUNK_INT64_COUNT); // 8 * 128 = 1024

// Uid and Pid that have local network permission and access local network
typedef struct {
    uint32_t uid;
    uint32_t pid;
} LocalNetNoteOp;
STRUCT_SIZE(LocalNetNoteOp, 4 + 4); // 8

// IP packet data from an __sk_buff
typedef struct {
    struct in6_addr saddr; // Stores v6 or v4-mapped-v6
    struct in6_addr daddr; // Stores v6 or v4-mapped-v6
    __be16 sport;
    __be16 dport;
    uint8_t ip_proto;
    uint8_t tcp_flags;
    uint8_t ip_version;
    uint8_t pad;
} SkbIpPacketData;
STRUCT_SIZE(SkbIpPacketData, 16 + 16 + 2 + 2 + 1 + 1 + 1 + 1); // 40

// Entry in the configuration map that stores which UID rules are enabled.
#define UID_RULES_CONFIGURATION_KEY 0
// Entry in the configuration map that stores which stats map is currently in use.
#define CURRENT_STATS_MAP_CONFIGURATION_KEY 1
// Entry in the data saver enabled map that stores whether data saver is enabled or not.
#define DATA_SAVER_ENABLED_KEY 0

// DROP_IF_SET is set of rules that DROP if rule is globally enabled, and per-uid bit is set
#define DROP_IF_SET (STANDBY_MATCH | OEM_DENY_1_MATCH | OEM_DENY_2_MATCH | OEM_DENY_3_MATCH)
// DROP_IF_UNSET is set of rules that should DROP if globally enabled, and per-uid bit is NOT set
#define DROP_IF_UNSET (DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH \
                        | LOW_POWER_STANDBY_MATCH | BACKGROUND_MATCH)

// Warning: funky bit-wise arithmetic: in parallel, for all DROP_IF_SET/UNSET rules
// check whether the rules are globally enabled, and if so whether the rules are
// set/unset for the specific uid.  DROP if that is the case for ANY of the rules.
// We achieve this by masking out only the bits/rules we're interested in checking,
// and negating (via bit-wise xor) the bits/rules that should drop if unset.
static inline bool isBlockedByUidRules(BpfConfig enabledRules, uint32_t uidRules) {
    return enabledRules & (DROP_IF_SET | DROP_IF_UNSET) & (uidRules ^ DROP_IF_UNSET);
}

static inline bool is_system_uid(uint32_t uid) {
    // MIN_SYSTEM_UID is AID_ROOT == 0, so uint32_t is *always* >= 0
    // MAX_SYSTEM_UID is AID_NOBODY == 9999, while AID_APP_START == 10000
    return ((uid % AID_USER_OFFSET) < AID_APP_START);
}

static inline bool is_system_or_root(uint32_t uid) {
    return (uid == AID_SYSTEM) || (uid == AID_ROOT);
}
