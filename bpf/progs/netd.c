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

// The resulting .o needs to load on Android T+
#define NETBPFLOAD_MINAPI_VER NETBPFLOAD_T_VER
#define BPF_OBJ_NAME "netd"
#define DEFAULT_BPF_PIN_SUBDIR "netd_shared"

#include "bpf_net_helpers.h"
#include "internal_net_api.h"
#include "netd.h"

// This is defined for cgroup bpf filter only.
static const int DROP = 0;
static const int PASS = 1;
static const int DROP_UNLESS_DNS = 2;  // internal to our program

// Used for setsockopt/lockdown_vpn_multicast.
static const int SETSOCKOPT_EPERM = 0;
static const int SETSOCKOPT_ALLOWED = 1;

// offsetof(struct iphdr, ihl) -- but that's a bitfield
#define IPPROTO_IHL_OFF 0

// Note that TCP_FLAG_{ACK,PSH,RST,SYN,FIN} are htonl(0x00{10,08,04,02,01}0000)
// see include/uapi/linux/tcp.h.
//
// Since we only support little endian, that effectively
// means they're 0x0000{10,08,04,02,01}00 which is the same as 0x{10,08,04,02,01}00,
// which in turn is the same as htons(0x{10,08,04,02,01})
//
// This means they can *also* be used to match against __be16 flags16 field.

// This is the offset of the 2nd byte of tcp flags
#define TCP_FLAG8_OFF (TCP_OFFSET(flags16) + 1)
#define TCP_FLAG8_FIN 0x01
#define TCP_FLAG8_SYN 0x02
#define TCP_FLAG8_RST 0x04

#define EPERM 1
#define EINVAL  22
#define EUNATCH 49

// For maps netd does not need to access
#define DEFINE_BPF_MAP_NO_NETD_API(the_map, TYPE, TypeOfKey, TypeOfValue, num_entries, minApi) \
    DEFINE_BPF_MAP_EXT(the_map, TYPE, TypeOfKey, TypeOfValue, num_entries, \
                       AID_ROOT, AID_NET_BW_ACCT, 0060, "net_shared", DEFAULT_BPF_PIN_SUBDIR, \
                       minApi, MAXAPI, 0)

#define DEFINE_BPF_MAP_NO_NETD(the_map, TYPE, TypeOfKey, TypeOfValue, num_entries) \
    DEFINE_BPF_MAP_NO_NETD_API(the_map, TYPE, TypeOfKey, TypeOfValue, num_entries, MINAPI)

// For maps netd only needs read only access to
#define DEFINE_BPF_MAP_RO_NETD(the_map, TYPE, TypeOfKey, TypeOfValue, num_entries) \
    DEFINE_BPF_MAP_EXT(the_map, TYPE, TypeOfKey, TypeOfValue, num_entries, \
                       AID_ROOT, AID_NET_BW_ACCT, 0460, "netd_readonly", DEFAULT_BPF_PIN_SUBDIR, \
                       MINAPI, MAXAPI, 0)

// For maps netd needs to be able to read and write
#define DEFINE_BPF_MAP_RW_NETD(the_map, TYPE, TypeOfKey, TypeOfValue, num_entries) \
    DEFINE_BPF_MAP_UGM(the_map, TYPE, TypeOfKey, TypeOfValue, num_entries, \
                       AID_ROOT, AID_NET_BW_ACCT, 0660)

// Keep in sync with config_multiuserMaximumUsers.
#define MAX_USERS 32
// AID_USER_OFFSET is equivalent to PER_USER_RANGE.
#define TOTAL_UIDS (MAX_USERS * AID_USER_OFFSET)

// Bpf map arrays on creation are preinitialized to 0 and do not support deletion of a key,
// see: kernel/bpf/arraymap.c array_map_delete_elem() returns -EINVAL (from both syscall and ebpf)
// Additionally on newer kernels the bpf jit can optimize out the lookups.
// only valid indexes are [0..CONFIGURATION_MAP_SIZE-1]
DEFINE_BPF_MAP_RO_NETD(configuration_map, ARRAY, uint32_t, uint32_t, CONFIGURATION_MAP_SIZE)

// TODO: consider whether we can merge some of these maps
// for example it might be possible to merge 2 or 3 of:
//   uid_counterset_map + uid_owner_map + uid_permission_map
DEFINE_BPF_MAP_NO_NETD(blocked_ports_map, MMAPABLE_ARRAY, int, uint64_t,
                       1024 /* 64K ports -> 1024 u64s */)
DEFINE_BPF_MAP_RW_NETD(cookie_tag_map, HASH, uint64_t, UidTagValue, 10000)
DEFINE_BPF_MAP_NO_NETD(uid_counterset_map, HASH, uint32_t, uint8_t, 4000)
DEFINE_BPF_MAP_NO_NETD(app_uid_stats_map, HASH, uint32_t, StatsValue, 10000)
DEFINE_BPF_MAP_RO_NETD(stats_map_A, HASH, StatsKey, StatsValue, STATS_MAP_SIZE)
DEFINE_BPF_MAP_RO_NETD(stats_map_B, HASH, StatsKey, StatsValue, STATS_MAP_SIZE)
DEFINE_BPF_MAP_NO_NETD(iface_stats_map, HASH, uint32_t, StatsValue, 1000)
// Cover total uid space with dynamic allocation. Size if fully allocated is ~48.83 MiB, although
// this won't be reached in practice for multiple reasons such as there being a concurrent running
// user limit and many of the uids being unusable.
DEFINE_BPF_MAP_RO_NETD(uid_owner_map, HASH, uint32_t, UidOwnerValue, -(TOTAL_UIDS))
// Since uid_migration_enabled_map stores true, this map is deprecated in favor of
// uid_permission_chunk_map. In case it gets reused, cover total uid space with dynamic allocation.
// Size if fully allocated is ~24.41 MiB, although won't be reached in practice.
DEFINE_BPF_MAP_RO_NETD(uid_permission_map, HASH, uint32_t, uint8_t, -(TOTAL_UIDS))
// Cover total uid space with dynamic allocation. Size if fully allocated is ~2.73 MiB, although
// won't be reached in practice.
DEFINE_BPF_MAP_RO_NETD(uid_permission_chunk_map, HASH, uint32_t,
                       UidPermissionChunk, -((TOTAL_UIDS / CHUNK_UID_COUNT)+1))
// TODO: The theoretical key space for this map is the entire IPv6 address space, so 100 is too
// small in theory and might also be too small in practice. Update this when reviewing ingress
// leaks.
DEFINE_BPF_MAP_NO_NETD(ingress_discard_map, HASH, IngressDiscardKey, IngressDiscardValue, 100)

DEFINE_BPF_MAP_RW_NETD(netd_pid_map, ARRAY, uint32_t, uint32_t, 1)
DEFINE_BPF_MAP_RW_NETD(lock_array_test_map, ARRAY, uint32_t, bool, 1)
DEFINE_BPF_MAP_RW_NETD(lock_hash_test_map, HASH, uint32_t, bool, 1)

DEFINE_BPF_SK_STORAGE(sk_storage, SkStorageValue)

// never used from eBpf, see cacheSize in service-t/native/libs/libnetworkstats/BpfNetworkStats.cpp
DEFINE_BPF_MAP_NO_NETD(iface_index_name_map, HASH, uint32_t, IfaceValue, 1000)

// A single-element configuration array, packet tracing is enabled when 'true'.
DEFINE_BPF_MAP_EXT(packet_trace_enabled_map, ARRAY, uint32_t, bool, 1,
                   AID_ROOT, AID_SYSTEM, 0060, "net_shared", DEFAULT_BPF_PIN_SUBDIR,
                   U, MAXAPI, 0)

// A ring buffer on which packet information is pushed.
DEFINE_BPF_RINGBUF_EXT(packet_trace_ringbuf, PacketTrace, 32 * 1024,
                       AID_ROOT, AID_SYSTEM, 0060, "net_shared", DEFAULT_BPF_PIN_SUBDIR,
                       U, MAXAPI);

DEFINE_BPF_MAP_RO_NETD(data_saver_enabled_map, ARRAY, uint32_t, bool, 1)

// It appears that there are < 10 entries into this map per interface, therefore the upstream size
// of 1000 is probably safe. Because LPM_TRIE is dynamically allocated by default, increasing it by
// 10x adds no overhead. Size if fully allocated is ~0.31 MiB, although won't be reached in
// practice.
DEFINE_BPF_MAP_NO_NETD_API(local_net_access_map, LPM_TRIE, LocalNetAccessKey, bool, 10000, 25Q2)

// Since permission_propagation_enabled_map stores true this map is deprecated in favor of
// uid_permission_chunk_map. In case it gets reused, cover total uid space with dynamic allocation.
// Size if fully allocated is ~24.41 MiB, although won't be reached in practice.
DEFINE_BPF_MAP_NO_NETD_API(local_net_blocked_uid_map, HASH, uint32_t, bool, -(TOTAL_UIDS), 25Q2)

// This trie holds exceptions to blocked access listed in local_net_access_map.
// Although it is a trie, it is only used as a map (all entries use the maximum
// prefix length). A trie is used because map keys are preallocated, which would
// be wasteful as the keys are much larger than the values.
DEFINE_BPF_MAP_NO_NETD_API(local_net_uid_host_allowlist_map, LPM_TRIE,
                           LocalNetUidHostAllowlistKey, bool, 1000, 25Q2)

DEFINE_BPF_MAP_RO_NETD(uid_migration_enabled_map, ARRAY, uint32_t, bool, 1)

DEFINE_BPF_MAP_NO_NETD(permission_propagation_enabled_map, ARRAY, uint32_t, bool, 1)
// A ring buffer on which note op event of local network access is pushed.
DEFINE_BPF_RINGBUF_EXT(local_net_note_op_ringbuf, LocalNetNoteOp, 8 * 512,
                       AID_ROOT, AID_NET_BW_ACCT, 0060, "net_shared", DEFAULT_BPF_PIN_SUBDIR,
                       25Q2, MAXAPI);
DEFINE_BPF_MAP_NO_NETD_API(local_net_note_op_cache_map, LRU_HASH, uint32_t, uint32_t, 100, 25Q2)
DEFINE_BPF_MAP_NO_NETD_API(local_net_note_op_enabled_map, ARRAY, uint32_t, bool, 1, 25Q2)

// A single-element array holding the current generation ID of the local network
// cache map. Updated by the system. Even IDs represent a stable cache state.
// Odd IDs represent an unstable state, during which the cache should not be
// used.
DEFINE_BPF_MAP_NO_NETD_API(local_net_cache_generation_id_map, ARRAY, uint32_t, uint64_t, 1, 25Q2)

// A ring buffer on which loopback access events are pushed.
DEFINE_BPF_RINGBUF_EXT(loopback_access_ringbuf, LoopbackAccessEvent, 16 * 512,
                       AID_ROOT, AID_SYSTEM, 0060, "net_shared", DEFAULT_BPF_PIN_SUBDIR,
                       25Q4, MAXAPI);
DEFINE_BPF_MAP_NO_NETD_API(loopback_access_cache_map, LRU_HASH, LoopbackAccessEvent, uint64_t, 100,
                           25Q4)
DEFINE_BPF_MAP_RO_NETD(loopback_access_metrics_enabled_map, ARRAY, uint32_t, bool, 1)
DEFINE_BPF_MAP_RO_NETD(loopback_checks_enabled_map, ARRAY, uint32_t, bool, 1)

// iptables xt_bpf programs need to be usable by both netd and netutils_wrappers
// selinux contexts, because even non-xt_bpf iptables mutations are implemented as
// a full table dump, followed by an update in userspace, and then a reload into the kernel,
// where any already in-use xt_bpf matchers are serialized as the path to the pinned
// program (see XT_BPF_MODE_PATH_PINNED) and then the iptables binary (or rather
// the kernel acting on behalf of it) must be able to retrieve the pinned program
// for the reload to succeed
#define DEFINE_XTBPF_PROG(TYPE, NAME) \
    DEFINE_BPF_PROG(TYPE, NAME, AID_NET_ADMIN)

// programs that need to be usable by netd, but not by netutils_wrappers
// (this is because these are currently attached by the mainline provided libnetd_updatable .so
// which is loaded into netd and thus runs as netd uid/gid/selinux context)
#define DEFINE_NETD_BPF_PROG_RANGES(TYPE, NAME, minKV, maxKV, min_api, max_api) \
    DEFINE_BPF_PROG_EXT(TYPE, NAME, AID_ROOT, AID_ROOT, minKV, maxKV, min_api, max_api, MANDATORY, \
                        "netd_readonly", DEFAULT_BPF_PIN_SUBDIR)

#define DEFINE_NETD_T_BPF_PROG_KVER_RANGE(TYPE, NAME, minKV, maxKV) \
    DEFINE_NETD_BPF_PROG_RANGES(TYPE, NAME, minKV, maxKV, T, MAXAPI)

#define DEFINE_NETD_T_BPF_PROG_KVER(TYPE, NAME, minKV) \
    DEFINE_NETD_T_BPF_PROG_KVER_RANGE(TYPE, NAME, minKV, INF)

// programs that only need to be usable by the system server
#define DEFINE_SYS_BPF_PROG(TYPE, NAME) \
    DEFINE_BPF_PROG_EXT(TYPE, NAME, AID_ROOT, AID_NET_ADMIN, 4_9, INF, MINAPI, MAXAPI, MANDATORY, \
                        "net_shared", DEFAULT_BPF_PIN_SUBDIR)

// tcpAccECN maps/programs need to load only on Android 26Q2+
#undef NETBPFLOAD_MINAPI_VER
#define NETBPFLOAD_MINAPI_VER NETBPFLOAD_26Q2_VER

#include "tcpAccECN.h"

// reset back to T+ minimum for the rest of the file
#undef NETBPFLOAD_MINAPI_VER
#define NETBPFLOAD_MINAPI_VER NETBPFLOAD_T_VER

/*
 * Note: this blindly assumes an MTU of 1500, and that packets > MTU are always TCP,
 * and that TCP is using the Linux default settings with TCP timestamp option enabled
 * which uses 12 TCP option bytes per frame.
 *
 * These are not unreasonable assumptions:
 *
 * The internet does not really support MTUs greater than 1500, so most TCP traffic will
 * be at that MTU, or slightly below it (worst case our upwards adjustment is too small).
 *
 * The chance our traffic isn't IP at all is basically zero, so the IP overhead correction
 * is bound to be needed.
 *
 * Furthermore, the likelyhood that we're having to deal with GSO (ie. > MTU) packets that
 * are not IP/TCP is pretty small (few other things are supported by Linux) and worse case
 * our extra overhead will be slightly off, but probably still better than assuming none.
 *
 * Most servers are also Linux and thus support/default to using TCP timestamp option
 * (and indeed TCP timestamp option comes from RFC 1323 titled "TCP Extensions for High
 * Performance" which also defined TCP window scaling and are thus absolutely ancient...).
 *
 * All together this should be more correct than if we simply ignored GSO frames
 * (ie. counted them as single packets with no extra overhead)
 *
 * Especially since the number of packets is important for any future clat offload correction.
 * (which adjusts upward by 20 bytes per packet to account for ipv4 -> ipv6 header conversion)
 */
#define DEFINE_UPDATE_STATS(the_stats_map, TypeOfKey)                                            \
    function void update_##the_stats_map(const struct __sk_buff* const skb,                      \
                                         const TypeOfKey* const key,                             \
                                         const struct egress_bool egress,                        \
                                         const struct kver_uint kver,                            \
                                         const struct undo_bool undo) {                          \
        StatsValue* value = bpf_##the_stats_map##_lookup_elem(key);                              \
        if (!value) {                                                                            \
            StatsValue newValue = {};                                                            \
            bpf_##the_stats_map##_update_elem(key, &newValue, BPF_NOEXIST);                      \
            value = bpf_##the_stats_map##_lookup_elem(key);                                      \
        }                                                                                        \
        if (value) {                                                                             \
            const bool is5_4 = KVER_IS_AT_LEAST(kver, 5, 4);                                     \
            const int mtu = 1500;                                                                \
            uint64_t packets = 1;                                                                \
            uint64_t bytes = skb->len;                                                           \
            volatile uint32_t gso_segs = is5_4 ? skb->gso_segs : 1;                              \
            if ((!is5_4 && bytes > mtu) || (is5_4 && gso_segs > 1)) {                            \
                const bool is_ipv6 = (skb->protocol == htons(ETH_P_IPV6));                       \
                const int ip_overhead = is_ipv6 ? sizeof(struct ipv6hdr) : sizeof(struct iphdr); \
                struct bpf_sock * const sk = is5_4 && skb->sk ? bpf_sk_fullsock(skb->sk) : NULL; \
                const bool is_tcp_or_unknown = !sk || sk->protocol == IPPROTO_TCP;               \
                const int tcphdr_size = sizeof(struct tcphdr);                                   \
                const int udphdr_size = sizeof(struct udphdr);                                   \
                const int L4_size = is_tcp_or_unknown ? tcphdr_size + 12 : udphdr_size;          \
                const int overhead = ip_overhead + L4_size;                                      \
                const int mss = mtu - overhead;                                                  \
                const uint64_t payload = bytes - overhead;                                       \
                packets = is5_4 ? gso_segs : (payload + mss - 1) / mss;                          \
                bytes = overhead * packets + payload;                                            \
            }                                                                                    \
            if (undo.undo) {                                                                     \
                packets = -packets;                                                              \
                bytes = -bytes;                                                                  \
            }                                                                                    \
            if (egress.egress) {                                                                 \
                __sync_fetch_and_add(&value->txPackets, packets);                                \
                __sync_fetch_and_add(&value->txBytes, bytes);                                    \
            } else {                                                                             \
                __sync_fetch_and_add(&value->rxPackets, packets);                                \
                __sync_fetch_and_add(&value->rxBytes, bytes);                                    \
            }                                                                                    \
        }                                                                                        \
    }

DEFINE_UPDATE_STATS(app_uid_stats_map, uint32_t)
DEFINE_UPDATE_STATS(iface_stats_map, uint32_t)
DEFINE_UPDATE_STATS(stats_map_A, StatsKey)
DEFINE_UPDATE_STATS(stats_map_B, StatsKey)

// both of these return 0 on success or -EFAULT on failure (and zero out the buffer)
function long bpf_skb_load_bytes_net(const struct __sk_buff* const skb,
                                     const int L3_off,
                                     void* const to,
                                     const int len,
                                     const struct kver_uint kver) {
    // 'kver' (here and throughout) is the compile time guaranteed minimum kernel version,
    // ie. we're building (a version of) the bpf program for kver (or newer!) kernels.
    //
    // 4.19+ kernels support the 'bpf_skb_load_bytes_relative()' bpf helper function,
    // so we can use it.  On pre-4.19 kernels we cannot use the relative load helper,
    // and thus will simply get things wrong if there's any L2 (ethernet) header in the skb.
    //
    // Luckily, for cellular traffic, there likely isn't any, as cell is usually 'rawip'.
    //
    // However, this does mean that wifi (and ethernet) on 4.14 is basically a lost cause:
    // we'll be making decisions based on the *wrong* bytes (fetched from the wrong offset),
    // because the 'L3_off' passed to bpf_skb_load_bytes() should be increased by l2_header_size,
    // which for ethernet is 14 and not 0 like it is for rawip.
    //
    // For similar reasons this will fail with non-offloaded VLAN tags on < 4.19 kernels,
    // since those extend the ethernet header from 14 to 18 bytes.
    return KVER_IS_AT_LEAST(kver, 4, 19)
        ? bpf_skb_load_bytes_relative(skb, L3_off, to, len, BPF_HDR_START_NET)
        : bpf_skb_load_bytes(skb, L3_off, to, len);
}

// False iff arguments are found with longest prefix match lookup and
// disallowed, and the allowlist does not contain an exception for the uid/host
// on the interface.
function bool is_local_net_access_allowed(const LocalNetAccessKey *query_key, const uint32_t uid) {
    bool* v = bpf_local_net_access_map_lookup_elem(query_key);
    if (!v || *v) {
        return true;
    }
    LocalNetUidHostAllowlistKey allowlist_query_key = {
        .lpm_bitlen =
            8 * (sizeof(uid) + sizeof(query_key->if_index) + sizeof(query_key->remote_ip6)),
        .uid = uid,
        .if_index = query_key->if_index,
        .remote_ip6 = query_key->remote_ip6,
    };
    v = bpf_local_net_uid_host_allowlist_map_lookup_elem(&allowlist_query_key);
    return v && *v;
}

function bool is_local_net_access_allowed_cached(struct __sk_buff *skb,
                                                 const uint32_t uid,
                                                 const uint32_t if_index,
                                                 const struct in6_addr *remote_ip6,
                                                 const uint16_t protocol,
                                                 const __be16 remote_port,
                                                 const struct kver_uint kver) {
    LocalNetAccessKey query_key = {
        .lpm_bitlen = 8 * (sizeof(if_index) + sizeof(*remote_ip6) + sizeof(protocol)
                           + sizeof(remote_port)),
        .if_index = if_index,
        .remote_ip6 = *remote_ip6,
        .protocol = protocol,
        .remote_port = remote_port
    };

    // Caching is enabled on kernel 5.10 and later, which supports BPF socket storage.
    if (!KVER_IS_AT_LEAST(kver, 5, 10)) {
        return is_local_net_access_allowed(&query_key, uid);
    }

    struct bpf_sock* sk = skb->sk;
    if (!sk) return is_local_net_access_allowed(&query_key, uid);
    SkStorageValue *sks = bpf_sk_storage_get(sk, 0, 0);
    if (!sks) return is_local_net_access_allowed(&query_key, uid);

    if (sks->lnp_cache.is_connected_tcp) return true;

    uint32_t zero = 0;
    uint64_t *gen_id = bpf_local_net_cache_generation_id_map_lookup_elem(&zero);
    if (!gen_id) return false; // Should not happen
    if (*gen_id == sks->lnp_cache.generation_id
        && !__builtin_memcmp(&sks->lnp_cache.key, &query_key, sizeof(sks->lnp_cache.key))) {
        return sks->lnp_cache.result;
    }

    bool isAllowed = is_local_net_access_allowed(&query_key, uid);

    if (!(*gen_id & 1)) {
        sks->lnp_cache.result = isAllowed;
        sks->lnp_cache.generation_id = *gen_id;
        sks->lnp_cache.key = query_key;
    }
    if (protocol == IPPROTO_TCP && isAllowed) sks->lnp_cache.is_connected_tcp = true;

    return isAllowed;
}

function uint8_t get_chunk_permissions(const uint32_t uid) {
    // All chunks has the same size CHUNK_INT64_COUNT
    uint32_t chunkId = uid / CHUNK_UID_COUNT;
    uint32_t index = uid / UIDS_PER_INT64 % CHUNK_INT64_COUNT;
    int shift = (uid % UIDS_PER_INT64 * PERMISSION_COUNT) & 63;

    UidPermissionChunk *chunk =
        bpf_uid_permission_chunk_map_lookup_elem(&chunkId);
    return chunk ? ((chunk->block[index] >> shift) & UID_PERMISSION_MASK)
                 : PERMISSION_BIT_NONE;
}

#define NS_PER_MINUTE (60ULL * 1000ULL * 1000ULL * 1000ULL)

function bool is_local_network_access_blocked(const uint32_t uid) {
    uint32_t mapKey = 0;
    bool *permissionPropagationEnabled =
        bpf_permission_propagation_enabled_map_lookup_elem(&mapKey);
    if (permissionPropagationEnabled && *permissionPropagationEnabled) {
        if (is_system_or_root(uid)) return false;
        if (get_chunk_permissions(uid) & PERMISSION_BIT_ACCESS_LOCAL_NETWORK)
            return false;
    } else {
        // Continue exempting system UIDs for the old map. This branch will be
        // deprecated soon.
        if (is_system_uid(uid)) return false;

        // Uid that is not in the blocked uid map has access to restricted local network
        bool* block_local_net = bpf_local_net_blocked_uid_map_lookup_elem(&uid);
        if (!block_local_net) return false; // uid not found in map
        if (!*block_local_net) return false; // lookup returned 'bool false'
    }
    return true;
}

function bool should_block_local_network_packets(const SkbIpPacketData *const packet,
                                                 struct __sk_buff *skb,
                                                 const uint32_t uid,
                                                 const uint32_t if_index,
                                                 const struct egress_bool egress,
                                                 const struct kver_uint kver) {
    bool reportLocalAccess = false;
    if (KVER_IS_AT_LEAST(kver, 5, 10)) {
        uint32_t key = 0;
        bool *noteOpEnabled = bpf_local_net_note_op_enabled_map_lookup_elem(&key);
        reportLocalAccess = noteOpEnabled && *noteOpEnabled;
    }
    bool isAllowed;
    const struct in6_addr *remote_ip6 =
        egress.egress ? &packet->daddr : &packet->saddr;
    const __be16 remote_port = egress.egress ? packet->dport : packet->sport;
    if (reportLocalAccess) {
        isAllowed = is_local_net_access_allowed_cached(skb, uid, if_index, remote_ip6,
                                                       packet->ip_proto, remote_port, kver);
        // Currently, generate events for all local network access, regardless of the UID's
        // permission status.
        // This is to identify all UIDs that are accessing the local network.
        if (!isAllowed) {
            // Cache to report only once per minute per UID.
            uint32_t* lastReportMinutes = bpf_local_net_note_op_cache_map_lookup_elem(&uid);
            uint32_t bootMinutes = (uint32_t) (bpf_ktime_get_boot_ns() / NS_PER_MINUTE);
            if (!lastReportMinutes || *lastReportMinutes < bootMinutes) {
                LocalNetNoteOp *noteOp = bpf_local_net_note_op_ringbuf_reserve();
                if (noteOp != NULL) {
                    noteOp->uid = uid;
                    bpf_local_net_note_op_ringbuf_submit(noteOp);
                    bpf_local_net_note_op_cache_map_update_elem(&uid, &bootMinutes, BPF_ANY);
                }
            }
        }
    }

    if (!is_local_network_access_blocked(uid)) {
        return false;
    }

    if (!reportLocalAccess) {
        isAllowed = is_local_net_access_allowed_cached(skb, uid, if_index, remote_ip6,
                                                       packet->ip_proto, remote_port, kver);
    }
    return !isAllowed;
}

function void add_loopback_access_event(const uint32_t src_uid,
                                        const uint32_t dst_uid,
                                        const enum LoopbackAccessResult result) {
    LoopbackAccessEvent key = {
        .src_uid = src_uid,
        .dst_uid = dst_uid,
        .result = result,
    };
    uint64_t *lastReportNs = bpf_loopback_access_cache_map_lookup_elem(&key);
    uint64_t currentBootNs = bpf_ktime_get_boot_ns();
    if (lastReportNs && (currentBootNs - *lastReportNs) < NS_PER_MINUTE) return;

    LoopbackAccessEvent *event = bpf_loopback_access_ringbuf_reserve();
    if (!event) return;

    if (lastReportNs) {
        *lastReportNs = currentBootNs;
    } else {
        if (bpf_loopback_access_cache_map_update_elem(&key, &currentBootNs,
                                                      BPF_NOEXIST) != 0) {
            bpf_loopback_access_ringbuf_discard(event);
            return;
        }
    }

    event->src_uid = src_uid;
    event->dst_uid = dst_uid;
    event->result = result;
    bpf_loopback_access_ringbuf_submit(event);
}

function bool loopback_metrics_enabled() {
    const uint32_t zero = 0;
    bool *enabled = bpf_loopback_access_metrics_enabled_map_lookup_elem(&zero);
    return enabled && *enabled;
}

function bool loopback_checks_enabled() {
    const uint32_t zero = 0;
    bool *enabled = bpf_loopback_checks_enabled_map_lookup_elem(&zero);
    return enabled && *enabled;
}

function bool can_force_loopback(const uint32_t permissions) {
    return (permissions & PERMISSION_BIT_FORCE_USE_LOOPBACK_INTERFACE)
        && (permissions & PERMISSION_BIT_INTERACT_ACROSS_USERS_FULL);
}

function bool uids_have_loopback_permissions(const uint32_t sender_uid,
                                             const uint32_t receiver_uid) {
    // TODO: be more specific about which system uids we should exempt
    if (is_system_uid(sender_uid) || is_system_uid(receiver_uid)) return true;

    bool same_profile =
        sender_uid / AID_USER_OFFSET == receiver_uid / AID_USER_OFFSET;
    if (same_profile) return true;

    uint32_t sender_perms = get_chunk_permissions(sender_uid);
    uint32_t receiver_perms = get_chunk_permissions(receiver_uid);
    if (can_force_loopback(sender_perms)
        || can_force_loopback(receiver_perms)) return true;

    // TODO: check loopback interface permissions for both sender and receiver
    bool same_app_id =
        sender_uid % AID_USER_OFFSET == receiver_uid % AID_USER_OFFSET;
    if (same_app_id) {
        return
            (sender_perms & PERMISSION_BIT_INTERACT_ACROSS_USERS_OR_PROFILES) ||
            (sender_perms & PERMISSION_BIT_INTERACT_ACROSS_USERS_FULL);
    } else {
        return sender_perms & PERMISSION_BIT_INTERACT_ACROSS_USERS_FULL;
    }
}

function bool parse_skb(SkbIpPacketData *const packet,
                        const struct __sk_buff *const skb,
                        const struct kver_uint kver) {
    // Errors from bpf_skb_load_bytes_net are ignored to favor returning
    // something over returning nothing. In the event of an error, the kernel
    // will fill in zero for the destination memory.
    uint8_t proto = 0;
    uint8_t L4_off = 0;
    if (skb->protocol == htons(ETH_P_IP)) {
        packet->ip_version = 4;
        packet->saddr.s6_addr32[2] = htonl(0xFFFF);
        (void)bpf_skb_load_bytes_net(skb, IP4_OFFSET(saddr),
                                     &packet->saddr.s6_addr32[3],
                                     sizeof(__be32), kver);

        packet->daddr.s6_addr32[2] = htonl(0xFFFF);
        (void)bpf_skb_load_bytes_net(skb, IP4_OFFSET(daddr),
                                     &packet->daddr.s6_addr32[3],
                                     sizeof(__be32), kver);

        (void)bpf_skb_load_bytes_net(skb, IP4_OFFSET(protocol), &proto,
                                     sizeof(proto), kver);
        // IHL calculation
        (void)bpf_skb_load_bytes_net(skb, IPPROTO_IHL_OFF, &L4_off,
                                     sizeof(L4_off), kver);
        if (L4_off < 0x45 || L4_off > 0x4F) return false;
        L4_off = (L4_off & 0x0F) * 4;
    } else if (skb->protocol == htons(ETH_P_IPV6)) {
        packet->ip_version = 6;
        (void)bpf_skb_load_bytes_net(skb, IP6_OFFSET(saddr), &packet->saddr,
                                     sizeof(packet->saddr), kver);
        (void)bpf_skb_load_bytes_net(skb, IP6_OFFSET(daddr), &packet->daddr,
                                     sizeof(packet->daddr), kver);
        (void)bpf_skb_load_bytes_net(skb, IP6_OFFSET(nexthdr), &proto,
                                     sizeof(proto), kver);
        L4_off = sizeof(struct ipv6hdr);
        // skip over a *single* HOPOPTS or DSTOPTS extension header (if present)
        if (proto == IPPROTO_HOPOPTS || proto == IPPROTO_DSTOPTS) {
            struct {
                uint8_t proto, len;
            } ext_hdr;
            if (!bpf_skb_load_bytes_net(skb, L4_off, &ext_hdr, sizeof(ext_hdr), kver)) {
                proto = ext_hdr.proto;
                L4_off += (ext_hdr.len + 1) * 8;
            }
        }
    } else {
        // Not an IP packet. Don't continue parsing.
        return false;
    }
    packet->ip_proto = proto;

    switch (proto) {
        case IPPROTO_TCP:
            (void)bpf_skb_load_bytes_net(skb, L4_off + TCP_FLAG8_OFF,
                                         &packet->tcp_flags,
                                         sizeof(packet->tcp_flags), kver);
            // fallthrough
        case IPPROTO_DCCP:
        case IPPROTO_UDP:
        case IPPROTO_UDPLITE:
        case IPPROTO_SCTP:
            (void)bpf_skb_load_bytes_net(skb, L4_off + 0, &packet->sport,
                                         sizeof(packet->sport), kver);
            (void)bpf_skb_load_bytes_net(skb, L4_off + 2, &packet->dport,
                                         sizeof(packet->dport), kver);
            break;
        case IPPROTO_ICMP:
        case IPPROTO_ICMPV6:
            // Both IPv4 and IPv6 icmp start with u8 type & code, which we store
            // in the bottom (ie. second) byte of sport/dport (which are be16s),
            // the top byte is already zero.
            (void)bpf_skb_load_bytes_net(skb, L4_off + 0,
                                         (char *)&packet->sport + 1, 1,
                                         kver); // type
            (void)bpf_skb_load_bytes_net(skb, L4_off + 1,
                                         (char *)&packet->dport + 1, 1,
                                         kver); // code
            break;
    }
    return true;
}

procedure bool should_block_loopback_access(const SkbIpPacketData *const packet_data,
                                            struct __sk_buff *const skb,
                                            const uint32_t sender_uid,
                                            const bool checks_enabled,
                                            const bool metrics_enabled) {
    struct bpf_sock_tuple sock_tuple = {};
    uint32_t tuple_size;

    if (packet_data->ip_version == 4) {
        // IPv4-mapped-v6
        sock_tuple.ipv4.saddr = packet_data->saddr.s6_addr32[3];
        sock_tuple.ipv4.daddr = packet_data->daddr.s6_addr32[3];
        sock_tuple.ipv4.sport = packet_data->sport;
        sock_tuple.ipv4.dport = packet_data->dport;
        tuple_size = sizeof(sock_tuple.ipv4);
    } else if (packet_data->ip_version == 6) {
        __builtin_memcpy(&sock_tuple.ipv6.saddr, &packet_data->saddr,
                         sizeof(sock_tuple.ipv6.saddr));
        __builtin_memcpy(&sock_tuple.ipv6.daddr, &packet_data->daddr,
                         sizeof(sock_tuple.ipv6.daddr));
        sock_tuple.ipv6.sport = packet_data->sport;
        sock_tuple.ipv6.dport = packet_data->dport;
        tuple_size = sizeof(sock_tuple.ipv6);
    } else {
        return false;
    }

    struct bpf_sock *local_sk;
    if (packet_data->ip_proto == IPPROTO_TCP) {
        local_sk = bpf_sk_lookup_tcp(skb, &sock_tuple, tuple_size,
                                     BPF_F_CURRENT_NETNS, 0);
    } else if (packet_data->ip_proto == IPPROTO_UDP) {
        local_sk = bpf_sk_lookup_udp(skb, &sock_tuple, tuple_size,
                                     BPF_F_CURRENT_NETNS, 0);
    } else {
        return false;
    }
    if (!local_sk) return false;

    SkStorageValue *sks = bpf_sk_storage_get(local_sk, 0, 0);
    const uint32_t receiver_uid = sks ? sks->uid : 0;
    bpf_sk_release(local_sk);
    if (!sks) return false;

    // We don't care about cases where apps are sending loopback traffic to
    // themselves.
    if (sender_uid == receiver_uid) return false;

    bool allowed = true;
    if (checks_enabled) {
        allowed = uids_have_loopback_permissions(sender_uid, receiver_uid);
    }
    if (metrics_enabled) {
        add_loopback_access_event(
                sender_uid, receiver_uid,
                allowed ? LOOPBACK_ACCESS_ALLOWED : LOOPBACK_ACCESS_BLOCKED);
    }
    return !allowed;
}

#define LOOPBACK_CACHE_EXPIRATION_NS (10ULL * 1000ULL * 1000ULL) // 10ms

procedure bool should_block_loopback_access_cached(const SkbIpPacketData *const packet_data,
                                            struct __sk_buff *const skb,
                                            const uint32_t sender_uid) {
    bool checks_enabled = loopback_checks_enabled();
    bool metrics_enabled = loopback_metrics_enabled();
    if (!checks_enabled && !metrics_enabled) return false;

    // TCP connections that already passed loopback access check
    if (packet_data->ip_proto == IPPROTO_TCP
        && !(packet_data->tcp_flags & TCP_FLAG8_SYN)) return false;
    // Remaining TCP will only trigger on SYN to avoid redundant lookups for established connections

    struct bpf_sock* sk = skb->sk;
    if (!sk) return should_block_loopback_access(packet_data, skb, sender_uid,
                                                 checks_enabled, metrics_enabled);
    SkStorageValue *sks = bpf_sk_storage_get(sk, 0, 0);
    if (!sks) return should_block_loopback_access(packet_data, skb, sender_uid,
                                                 checks_enabled, metrics_enabled);

    LoopbackCache *lc = &sks->loopback_cache;
    uint64_t current_ns = bpf_ktime_get_boot_ns();
    if (packet_data->ip_proto == IPPROTO_UDP
        && current_ns - lc->cached_at_ns < LOOPBACK_CACHE_EXPIRATION_NS
        && !__builtin_memcmp(&lc->daddr, &packet_data->daddr, sizeof(struct in6_addr))
        && lc->dport == packet_data->dport) {
        return lc->result;
    }

    bool result = should_block_loopback_access(packet_data, skb, sender_uid,
                                               checks_enabled, metrics_enabled);

    if (packet_data->ip_proto == IPPROTO_UDP) {
        lc->cached_at_ns = current_ns;
        __builtin_memcpy(&lc->daddr, &packet_data->daddr, sizeof(struct in6_addr));
        lc->dport = packet_data->dport;
        lc->result = result;
    }
    return result;
}

function void do_packet_tracing(const struct __sk_buff* const skb,
                                const SkbIpPacketData* const packet,
                                const struct egress_bool egress,
                                const uint32_t uid,
                                const uint32_t tag,
                                const struct kver_uint kver) {
    if (!KVER_IS_AT_LEAST(kver, 5, 10)) return;

    uint32_t mapKey = 0;
    bool* traceConfig = bpf_packet_trace_enabled_map_lookup_elem(&mapKey);
    if (traceConfig == NULL) return;
    if (*traceConfig == false) return;

    PacketTrace* pkt = bpf_packet_trace_ringbuf_reserve();
    if (pkt == NULL) return;

    pkt->timestampNs = bpf_ktime_get_boot_ns();
    pkt->ifindex = skb->ifindex;
    pkt->length = skb->len;

    pkt->uid = uid;
    pkt->tag = tag;
    pkt->sport = packet->sport;
    pkt->dport = packet->dport;

    pkt->egress = egress.egress;
    pkt->wakeup = !egress.egress && (skb->mark & 0x80000000);  // Fwmark.ingress_cpu_wakeup
    pkt->ipProto = packet->ip_proto;
    pkt->tcpFlags = packet->tcp_flags;
    pkt->ipVersion = packet->ip_version;

    bpf_packet_trace_ringbuf_submit(pkt);
}

function bool skip_owner_match(const SkbIpPacketData* const packet,
                               const struct egress_bool egress) {
    if (packet->ip_version == 0) return false;

    if (packet->ip_proto == IPPROTO_ESP) return true;

    if (packet->ip_proto != IPPROTO_TCP) return false;

    // Always allow RST's, and additionally allow ingress FINs
    return packet->tcp_flags & (TCP_FLAG8_RST | (egress.egress ? 0 : TCP_FLAG8_FIN));
}

function BpfConfig getConfig(uint32_t configKey) {
    uint32_t mapSettingKey = configKey;
    BpfConfig* config = bpf_configuration_map_lookup_elem(&mapSettingKey);
    if (!config) {
        // Couldn't read configuration entry. Assume everything is disabled.
        return DEFAULT_CONFIG;
    }
    return *config;
}

function bool ingress_should_discard(const SkbIpPacketData* const packet,
                                     struct __sk_buff* skb,
                                     const struct kver_uint kver) {
    // Require 4.19, since earlier kernels don't have bpf_skb_load_bytes_relative() which
    // provides relative to L3 header reads.  Without that we could fetch the wrong bytes.
    // Additionally earlier bpf verifiers are much harder to please.
    if (!KVER_IS_AT_LEAST(kver, 4, 19)) return false;

    if (packet->ip_version == 0) return false;

    IngressDiscardKey k = { .daddr = packet->daddr };

    // we didn't check for load success, because destination bytes will be zeroed if
    // bpf_skb_load_bytes_net() fails, instead we rely on daddr of '::' and '::ffff:0.0.0.0'
    // never being present in the map itself

    IngressDiscardValue* v = bpf_ingress_discard_map_lookup_elem(&k);
    if (!v) return false;  // lookup failure -> no protection in place -> allow
    // if (skb->ifindex == 1) return false;  // allow 'lo', but can't happen - see callsite
    if (skb->ifindex == v->iif[0]) return false;  // allowed interface
    if (skb->ifindex == v->iif[1]) return false;  // allowed interface
    return true;  // disallowed interface
}

static __always_inline inline bool is_multicast(struct __sk_buff* skb,
                                                const struct kver_uint kver) {
    uint8_t addr_first_octet;
    if (skb->protocol == htons(ETH_P_IP)) {
        __u32 daddr4;
        (void) bpf_skb_load_bytes_net(skb, IP4_OFFSET(daddr), &daddr4, sizeof(daddr4), kver);
        addr_first_octet = (ntohl(daddr4) >> 24) & 0xFF;
        if (addr_first_octet >= 224 && addr_first_octet <= 239) return true;
    } else if (skb->protocol == htons(ETH_P_IPV6)) {
        __u32 daddr6[4];
        (void) bpf_skb_load_bytes_net(skb, IP6_OFFSET(daddr), &daddr6, sizeof(daddr6), kver);
        addr_first_octet = (ntohl(daddr6[0]) >> 24) & 0xFF;
        if (addr_first_octet == 0xFF) return true;
    }
    return false;
}

function int bpf_owner_match(const SkbIpPacketData* const packet,
                             struct __sk_buff* skb,
                             uint32_t uid,
                             const struct egress_bool egress,
                             const struct kver_uint kver,
                             const struct sdk_level_uint lvl) {
    BpfConfig enabledRules = getConfig(UID_RULES_CONFIGURATION_KEY);

    // BACKGROUND match does not apply to loopback traffic
    if (skb->ifindex == 1) enabledRules &= ~BACKGROUND_MATCH;

    UidOwnerValue* uidEntry = bpf_uid_owner_map_lookup_elem(&uid);
    uint32_t uidRules = uidEntry ? uidEntry->rule : 0;
    uint32_t allowed_iif = uidEntry ? uidEntry->iif : 0;

    if ((uidRules & LOCKDOWN_VPN_MATCH) && is_multicast(skb, kver)) {
        return DROP;
    }

    if (is_system_uid(uid)) return PASS;

    {
        SkbIpPacketData packet_data = {};
        bool parsed = parse_skb(&packet_data, skb, kver);
        if (parsed && skip_owner_match(&packet_data, egress)) {
            return PASS;
        }
    }

    if (isBlockedByUidRules(enabledRules, uidRules)) return DROP;

    if (!egress.egress && skb->ifindex != 1) {
        if (ingress_should_discard(packet, skb, kver)) return DROP;
        if (uidRules & IIF_MATCH) {
            if (allowed_iif && skb->ifindex != allowed_iif) {
                // Drops packets not coming from lo nor the allowed interface
                // allowed interface=0 is a wildcard and does not drop packets
                return DROP_UNLESS_DNS;
            }
        } else if (uidRules & LOCKDOWN_VPN_MATCH) {
            // Drops packets not coming from lo and rule does not have IIF_MATCH but has
            // LOCKDOWN_VPN_MATCH
            return DROP_UNLESS_DNS;
        }
    }

    if (API_IS_AT_LEAST(lvl, 25Q2) && skb->ifindex == 1) {
        // TODO: sdksandbox localhost restrictions
    }

    return PASS;
}

function void update_stats_with_config(const uint32_t selectedMap,
                                       const struct __sk_buff* const skb,
                                       const StatsKey* const key,
                                       const struct egress_bool egress,
                                       const struct kver_uint kver,
                                       const struct undo_bool undo) {
    if (selectedMap == SELECT_MAP_A) {
        update_stats_map_A(skb, key, egress, kver, undo);
    } else {
        update_stats_map_B(skb, key, egress, kver, undo);
    }
}

function int bpf_traffic_account(struct __sk_buff* skb,
                                 const struct egress_bool egress,
                                 const struct kver_uint kver,
                                 const struct sdk_level_uint lvl) {
    // sock_uid will be 'overflowuid' if !sk_fullsock(sk_to_full_sk(skb->sk))
    uint32_t sock_uid = bpf_get_socket_uid(skb);

    // kernel's DEFAULT_OVERFLOWUID is 65534, this is the overflow 'nobody' uid,
    // usually this being returned means that skb->sk is NULL during RX
    // (early decap socket lookup failure), which commonly happens for incoming
    // packets to an unconnected udp socket.
    // But it can also happen for egress from a timewait socket.
    // Let's treat such cases as 'root' which is_system_uid()
    // TODO: Verify that this can never occur for multicast traffic. Have done manual testing, but
    //  need to read the kernel networking code to see if it is possible.
    if (sock_uid == 65534) sock_uid = 0;

    uint64_t cookie = bpf_get_socket_cookie(skb);  // 0 iff !skb->sk
    UidTagValue* utag = bpf_cookie_tag_map_lookup_elem(&cookie);
    uint32_t statsUid, tag;
    if (utag) {
        statsUid = utag->uid;
        tag = utag->tag;
    } else {
        statsUid = sock_uid;
        tag = 0;
    }

    // Always allow and never count clat traffic. Only the IPv4 traffic on the stacked
    // interface is accounted for and subject to usage restrictions.
    // CLAT IPv6 TX sockets are *always* tagged with CLAT uid, see tagSocketAsClat()
    // CLAT daemon receives via an untagged AF_PACKET socket.
    if (egress.egress && statsUid == AID_CLAT) return PASS;

    SkbIpPacketData packet_data = {};
    bool parsed = parse_skb(&packet_data, skb, kver);

    int match = bpf_owner_match(&packet_data, skb, sock_uid, egress, kver, lvl);

    bool dns = false;

// Workaround for secureVPN with VpnIsolation enabled, refer to b/159994981 for details.
// Keep TAG_SYSTEM_DNS in sync with DnsResolver/include/netd_resolv/resolv.h
// and TrafficStatsConstants.java
#define TAG_SYSTEM_DNS 0xFFFFFF82
    if (tag == TAG_SYSTEM_DNS && statsUid == AID_DNS) {
        dns = true;
        statsUid = sock_uid;
        if (match == DROP_UNLESS_DNS) match = PASS;
    } else {
        if (match == DROP_UNLESS_DNS) match = DROP;
    }

    if (API_IS_AT_LEAST(lvl, 25Q4) && parsed && (match != DROP) && egress.egress
        && skb->ifindex == 1) {
        if (should_block_loopback_access_cached(&packet_data, skb, sock_uid)) {
            match = DROP;
        }
    }

    if (API_IS_AT_LEAST(lvl, 25Q2) && parsed && (match != DROP) && !dns) {
        if (should_block_local_network_packets(&packet_data, skb, sock_uid,
                                               skb->ifindex, egress, kver)) {
            if (KVER_IS_AT_LEAST(kver, 5, 10) && skb->sk && egress.egress) {
                SkStorageValue *sks = bpf_sk_storage_get(skb->sk, 0, 0);
                if (sks) sks->dropReasons |= DROP_REASON_LNP;
            }
            match = DROP;
        }
    }

    // If an outbound packet is going to be dropped, we do not count that traffic.
    if (egress.egress && (match == DROP)) {
        uint32_t key = skb->ifindex;
        update_iface_stats_map(skb, &key, EGRESS, KVER_4_9, UNDO);
        return DROP;
    }

    StatsKey key = {.uid = statsUid, .tag = tag, .counterSet = 0, .ifaceIndex = skb->ifindex};

    uint8_t* counterSet = bpf_uid_counterset_map_lookup_elem(&statsUid);
    if (counterSet) key.counterSet = (uint32_t)*counterSet;

    uint32_t mapSettingKey = CURRENT_STATS_MAP_CONFIGURATION_KEY;
    uint32_t* selectedMap = bpf_configuration_map_lookup_elem(&mapSettingKey);

    if (!selectedMap) return PASS;  // cannot happen, needed to keep bpf verifier happy

    do_packet_tracing(skb, &packet_data, egress, statsUid, tag, kver);
    update_stats_with_config(*selectedMap, skb, &key, egress, kver, ACCOUNT);
    update_app_uid_stats_map(skb, &statsUid, egress, kver, ACCOUNT);

    // We've already handled DROP_UNLESS_DNS up above, thus when we reach here the only
    // possible values of match are DROP(0) or PASS(1), however we need to use
    // "match &= 1" before 'return match' to help the kernel's bpf verifier,
    // so that it can be 100% certain that the returned value is always 0 or 1.
    // We use assembly so that it cannot be optimized out by a too smart compiler.
    asm("%0 &= 1" : "+r"(match));
    return match;
}

// -----

// Supported kernel + platform/os version combinations:
//
//      | 4.9 | 4.14 | 4.19 | 5.4 | 5.10 | 5.15 | 6.1 | 6.6 | 6.12 | 6.18 |
// 26Q4 |     |      |      |     |      |  x   |  x  |  x  |  x   |  x   |
// 26Q2 |     |      |      |     |  x   |  x   |  x  |  x  |  x   |  x   |
// 25Q4 |     |      |      |     |  x   |  x   |  x  |  x  |  x   |
// 25Q2 |     |      |      |  x  |  x   |  x   |  x  |  x  |  x   |
//    V |     |      |  x   |  x  |  x   |  x   |  x  |  x  |      | (netbpfload)
//    U |     |  x   |  x   |  x  |  x   |  x   |  x  |     |      |
//    T |  x  |  x   |  x   |  x  |  x   |  x   |     |     |      | (magic netbpfload)
//    S |  x  |  x   |  x   |  x  |  x   |      |     |     |      | (dns netbpfload for offload)

// ----- ingress/stats -----

// Android 26Q2+ 6.18+ (full featured + without tcpAccECN)
DEFINE_NETD_BPF_PROG_RANGES(ingress, stats, 6_18, INF, 26Q2, MAXAPI)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, INGRESS, KVER_6_18, API(26Q2));
}

// Android 26Q2+ 6.1/6.6/6.12 (full featured + tcpAccECN)
DEFINE_NETD_BPF_PROG_RANGES(ingress, stats, 6_1, 6_18, 26Q2, MAXAPI)
(struct __sk_buff* skb) {
    update_accecn_counter(skb);
    return bpf_traffic_account(skb, INGRESS, KVER_6_1, API(26Q2));
}

// Android 26Q2+ 5.10/5.15 (full featured)
DEFINE_NETD_BPF_PROG_RANGES(ingress, stats, 5_10, 6_1, 26Q2, MAXAPI)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, INGRESS, KVER_5_10, API(26Q2));
}

// Android 25Q4/26Q1 (full featured)
DEFINE_NETD_BPF_PROG_RANGES(ingress, stats, 5_10, INF, 25Q4, 26Q2)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, INGRESS, KVER_5_10, API(25Q4));
}

// Android 25Q2/25Q3 5.10+ (localnet protection + tracing)
DEFINE_NETD_BPF_PROG_RANGES(ingress, stats, 5_10, INF, 25Q2, 25Q4)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, INGRESS, KVER_5_10, API(25Q2));
}

// Android 25Q2/25Q3 5.4 (localnet protection)
DEFINE_NETD_BPF_PROG_RANGES(ingress, stats, 5_4, 5_10, 25Q2, 25Q4)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, INGRESS, KVER_5_4, API(25Q2));
}

// Android U/V 5.10+ (tracing)
DEFINE_NETD_BPF_PROG_RANGES(ingress, stats, 5_10, INF, U, 25Q2)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, INGRESS, KVER_5_10, API(U));
}

// Android T/U/V/25Q2 5.4 & T 5.10/5.15
DEFINE_NETD_BPF_PROG_RANGES(ingress, stats, 5_4, INF, T, 25Q4)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, INGRESS, KVER_5_4, API(T));
}

// Android T/U/V 4.19
DEFINE_NETD_BPF_PROG_RANGES(ingress, stats, 4_19, 5_4, T, 25Q2)
(struct __sk_buff* skb) {
return bpf_traffic_account(skb, INGRESS, KVER_4_19, API(T));
}

// Android T 4.9 & T/U 4.14
DEFINE_NETD_BPF_PROG_RANGES(ingress, stats, 4_9, 4_19, T, V)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, INGRESS, KVER_4_9, API(T));
}

// ----- egress/stats -----

// Android 26Q2+ 6.1+ (full featured)
DEFINE_NETD_BPF_PROG_RANGES(egress, stats, 6_1, INF, 26Q2, MAXAPI)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, EGRESS, KVER_6_1, API(26Q2));
}

// Android 26Q2+ 5.10/5.15 (full featured)
DEFINE_NETD_BPF_PROG_RANGES(egress, stats, 5_10, 6_1, 26Q2, MAXAPI)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, EGRESS, KVER_5_10, API(26Q2));
}

// Android 25Q4/26Q1 (full featured)
DEFINE_NETD_BPF_PROG_RANGES(egress, stats, 5_10, INF, 25Q4, 26Q2)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, EGRESS, KVER_5_10, API(25Q4));
}

// Android 25Q2/25Q3 5.10+ (localnet protection + tracing)
DEFINE_NETD_BPF_PROG_RANGES(egress, stats, 5_10, INF, 25Q2, 25Q4)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, EGRESS, KVER_5_10, API(25Q2));
}

// Android 25Q2/25Q3 5.4 (localnet protection)
DEFINE_NETD_BPF_PROG_RANGES(egress, stats, 5_4, 5_10, 25Q2, 25Q4)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, EGRESS, KVER_5_4, API(25Q2));
}

// Android U/V 5.10+ (tracing)
DEFINE_NETD_BPF_PROG_RANGES(egress, stats, 5_10, INF, U, 25Q2)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, EGRESS, KVER_5_10, API(U));
}

// Android T/U/V/25Q2 5.4 & T 5.10/5.15
DEFINE_NETD_BPF_PROG_RANGES(egress, stats, 5_4, INF, T, 25Q4)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, EGRESS, KVER_5_4, API(T));
}

// Android T/U/V 4.19
DEFINE_NETD_BPF_PROG_RANGES(egress, stats, 4_19, 5_4, T, 25Q2)
(struct __sk_buff* skb) {
return bpf_traffic_account(skb, EGRESS, KVER_4_19, API(T));
}

// Android T 4.9 & T/U 4.14
DEFINE_NETD_BPF_PROG_RANGES(egress, stats, 4_9, 4_19, T, V)
(struct __sk_buff* skb) {
    return bpf_traffic_account(skb, EGRESS, KVER_4_9, API(T));
}

// -----

// WARNING: Android T's non-updatable netd depends on the name of this program.
DEFINE_XTBPF_PROG(skfilter, egress_xtbpf)
(struct __sk_buff* skb) {
    // Clat daemon does not generate new traffic, all its traffic is accounted for already
    // on the v4-* interfaces (except for the 20 (or 28) extra bytes of IPv6 vs IPv4 overhead,
    // but that can be corrected for later when merging v4-foo stats into interface foo's).
    // CLAT sockets are created by system server and tagged as uid CLAT, see tagSocketAsClat()
    uint32_t sock_uid = bpf_get_socket_uid(skb);
    if (sock_uid == AID_SYSTEM) {
        uint64_t cookie = bpf_get_socket_cookie(skb);
        UidTagValue* utag = bpf_cookie_tag_map_lookup_elem(&cookie);
        if (utag && utag->uid == AID_CLAT) return XTBPF_NOMATCH;
    }

    uint32_t key = skb->ifindex;
    update_iface_stats_map(skb, &key, EGRESS, KVER_4_9, ACCOUNT);
    return XTBPF_MATCH;
}

// WARNING: Android T's non-updatable netd depends on the name of this program.
DEFINE_XTBPF_PROG(skfilter, ingress_xtbpf)
(struct __sk_buff* skb) {
    // Clat daemon traffic is not accounted by virtue of iptables raw prerouting drop rule
    // (in clat_raw_PREROUTING chain), which triggers before this (in bw_raw_PREROUTING chain).
    // It will be accounted for on the v4-* clat interface instead.
    // Keep that in mind when moving this out of iptables xt_bpf and into tc ingress (or xdp).

    uint32_t key = skb->ifindex;
    update_iface_stats_map(skb, &key, INGRESS, KVER_4_9, ACCOUNT);
    return XTBPF_MATCH;
}

DEFINE_SYS_BPF_PROG(schedact, ingress_account)
(struct __sk_buff* skb) {
    if (is_received_skb(skb)) {
        // Account for ingress traffic before tc drops it.
        uint32_t key = skb->ifindex;
        update_iface_stats_map(skb, &key, INGRESS, KVER_4_9, ACCOUNT);
    }
    return TC_ACT_UNSPEC;
}

// WARNING: Android T's non-updatable netd depends on the name of this program.
DEFINE_XTBPF_PROG(skfilter, allowlist_xtbpf)
(struct __sk_buff* skb) {
    uint32_t sock_uid = bpf_get_socket_uid(skb);
    if (is_system_uid(sock_uid)) return XTBPF_MATCH;

    // kernel's DEFAULT_OVERFLOWUID is 65534, this is the overflow 'nobody' uid,
    // usually this being returned means that skb->sk is NULL during RX
    // (early decap socket lookup failure), which commonly happens for incoming
    // packets to an unconnected udp socket.
    // But it can also happen for egress from a timewait socket.
    // Let's treat such cases as 'root' which is_system_uid()
    if (sock_uid == 65534) return XTBPF_MATCH;

    UidOwnerValue* allowlistMatch = bpf_uid_owner_map_lookup_elem(&sock_uid);
    if (allowlistMatch) return allowlistMatch->rule & HAPPY_BOX_MATCH ? XTBPF_MATCH : XTBPF_NOMATCH;
    return XTBPF_NOMATCH;
}

// WARNING: Android T's non-updatable netd depends on the name of this program.
DEFINE_XTBPF_PROG(skfilter, denylist_xtbpf)
(struct __sk_buff* skb) {
    uint32_t sock_uid = bpf_get_socket_uid(skb);
    UidOwnerValue* denylistMatch = bpf_uid_owner_map_lookup_elem(&sock_uid);
    uint32_t penalty_box = PENALTY_BOX_USER_MATCH | PENALTY_BOX_ADMIN_MATCH;
    if (denylistMatch) return denylistMatch->rule & penalty_box ? XTBPF_MATCH : XTBPF_NOMATCH;
    return XTBPF_NOMATCH;
}

function uint8_t get_app_permissions() {
    uint64_t gid_uid = bpf_get_current_uid_gid();
    uint32_t uid = (gid_uid & 0xffffffff);
    /*
     * A given app is guaranteed to have the same app ID in all the profiles
     * in which it is installed, and install permission is granted to app
     * for all user at install time so we only check the appId part of a
     * request uid at run time. See UserHandle#isSameApp for detail.
     */
    uint32_t appId = uid % AID_USER_OFFSET; // == PER_USER_RANGE == 100000
    uint8_t *permissions = bpf_uid_permission_map_lookup_elem(&appId);
    // if UID not in map, then default to just INTERNET permission.
    return permissions ? *permissions : BPF_PERMISSION_INTERNET;
}

function int inet_socket_create(struct bpf_sock* sk, const struct kver_uint kver) {
    uint64_t gid_uid = bpf_get_current_uid_gid();

    if (KVER_IS_AT_LEAST(kver, 5, 10)) {
        SkStorageValue *sks = bpf_sk_storage_get(sk, 0, BPF_SK_STORAGE_GET_F_CREATE);
        if (sks) {
            sks->cookie = bpf_get_sk_cookie(sk);
            sks->uid = gid_uid;
            sks->gid = (gid_uid >> 32);
            sks->l4s.enabled = (sk->type == SOCK_STREAM && sk->protocol == IPPROTO_TCP);
        }
    }

    uint32_t mapKey = 0;
    bool *uidMigrationEnabled = bpf_uid_migration_enabled_map_lookup_elem(&mapKey);
    if (uidMigrationEnabled && *uidMigrationEnabled) {
        uint32_t uid = (gid_uid & 0xffffffff);
        return (get_chunk_permissions(uid) & PERMISSION_BIT_NO_INTERNET)
                   ? BPF_DISALLOW
                   : BPF_ALLOW;
    } else {
        return (get_app_permissions() & BPF_PERMISSION_INTERNET) ? BPF_ALLOW : BPF_DISALLOW;
    }
}

DEFINE_NETD_T_BPF_PROG_KVER(cgroupsock, inet_create, 5_10)
(struct bpf_sock* sk) {
    return inet_socket_create(sk, KVER_5_10);
}

DEFINE_NETD_T_BPF_PROG_KVER_RANGE(cgroupsock, inet_create, 4_14, 5_10)
(struct bpf_sock* sk) {
    return inet_socket_create(sk, KVER_4_14);
}

DEFINE_NETD_T_BPF_PROG_KVER(cgroupsockrelease, inet_release, 5_10)
(struct bpf_sock* sk) {
    uint64_t cookie = bpf_get_sk_cookie(sk);
    if (cookie) bpf_cookie_tag_map_delete_elem(&cookie);

    return 1;
}

// --- BIND CGROUP HOOKS ---

function bool block_bind_port(__u32 protocol, __be16 user_port) {
    if (!user_port) return false;

    switch (protocol) {
        case IPPROTO_TCP:
        case IPPROTO_MPTCP:
        case IPPROTO_UDP:
        case IPPROTO_UDPLITE:
        case IPPROTO_DCCP:
        case IPPROTO_SCTP:
            break;
        default:
            return false; // unknown protocols are allowed
    }

    // Note: user_port is in network byte order, so bitmap ordering is funky.
    int key = user_port >> 6;
    int shift = user_port & 63;

    uint64_t *val = bpf_blocked_ports_map_lookup_elem(&key);
    // Lookup should never fail in reality, but if it does return here to keep the
    // BPF verifier happy.
    if (!val) return false;

    if ((*val >> shift) & 1) return true;
    return false;
}

function bool is_netd() {
    uint32_t uid = bpf_get_current_uid_gid();  // low 32 bits is uid
    if (uid) return false;  // netd runs as root

    const uint32_t key = 0;
    uint32_t *pid = bpf_netd_pid_map_lookup_elem(&key);
    if (!pid) return false;

    // userspace system call 'getpid()' returns what kernel/ebpf calls 'tgid' (thread group id)
    // (while what kernel/ebpf calls 'pid' is returned by linux specific system call 'gettid()')
    uint32_t tgid = bpf_get_current_pid_tgid() >> 32;  // high 32 bits is tgid
    return tgid == *pid;
}

function bool is_root_or_shell() {
    uint32_t uid = bpf_get_current_uid_gid();  // low 32 bits is uid
    return (uid == AID_ROOT) || (uid == AID_SHELL);
}

function bool is_unpriv_tcp_port(__be16 port) {
    switch (port) {
        case htons(20):   // ftp (active mode data)
        case htons(21):   // ftp (control)
        case htons(22):   // ssh (incl. sftp)
        case htons(23):   // telnet
        case htons(80):   // http
        case htons(443):  // https
        case htons(445):  // smb over ip (direct host)
        case htons(515):  // lpd
        case htons(631):  // ipp
            return true;
        default:
            return false;
    }
}

function bool is_unpriv_udp_port(__be16 port) {
    switch (port) {
        case htons(319):  // ptp
        case htons(320):  // ptp
        case htons(443):  // http/3
            return true;
        default:
            return false;
    }
}

function bool is_unpriv_port(__u32 protocol, __be16 port) {
    switch (protocol) {
        case IPPROTO_TCP: return is_unpriv_tcp_port(port);
        case IPPROTO_UDP: return is_unpriv_udp_port(port);
        default:          return false;
    }
}

// kernel's include/linux/bpf.h defines flag BPF_RET_BIND_NO_CAP_NET_BIND_SERVICE as (1 << 0) == 1,
// as a flag, it must be shifted up by 1 (making it == 2) and combined with 'generic' ALLOW (== 1)
static const int BPF_ALLOW_IGNORING_CAP_NET_BIND = BPF_ALLOW + 2;

function int inet_bind(struct bpf_sock_addr *ctx, const struct kver_uint kver) {
    const bool is5_15 = KVER_IS_AT_LEAST(kver, 5, 15);
    if (block_bind_port(ctx->protocol, ctx->user_port)) return BPF_DISALLOW;
    if (is5_15) {
        if (ctx->user_port == htons(53) && is_netd())
            return BPF_ALLOW_IGNORING_CAP_NET_BIND;
        if (ctx->protocol == IPPROTO_TCP && ctx->user_port == htons(555) && is_root_or_shell())
            return BPF_ALLOW_IGNORING_CAP_NET_BIND;
        if (is_unpriv_port(ctx->protocol, ctx->user_port))
            return BPF_ALLOW_IGNORING_CAP_NET_BIND;
    }
    return BPF_ALLOW;
}

DEFINE_NETD_T_BPF_PROG_KVER(bind4, inet4_bind, 5_15)
(struct bpf_sock_addr *ctx) {
    return inet_bind(ctx, KVER_5_15);
}

DEFINE_NETD_T_BPF_PROG_KVER_RANGE(bind4, inet4_bind, 4_19, 5_15)
(struct bpf_sock_addr *ctx) {
    return inet_bind(ctx, KVER_4_19);
}

DEFINE_NETD_T_BPF_PROG_KVER(bind6, inet6_bind, 5_15)
(struct bpf_sock_addr *ctx) {
    return inet_bind(ctx, KVER_5_15);
}

DEFINE_NETD_T_BPF_PROG_KVER_RANGE(bind6, inet6_bind, 4_19, 5_15)
(struct bpf_sock_addr *ctx) {
    return inet_bind(ctx, KVER_4_19);
}

// --- CONNECT CGROUP HOOKS ---

DEFINE_NETD_BPF_PROG_RANGES(connect4, inet4_connect, 4_19, INF, V, MAXAPI)
(__unused struct bpf_sock_addr *ctx) {
    return BPF_ALLOW;
}

DEFINE_NETD_BPF_PROG_RANGES(connect6, inet6_connect, 4_19, INF, V, MAXAPI)
(__unused struct bpf_sock_addr *ctx) {
    return BPF_ALLOW;
}

// --- UDP RECVMSG HOOKS ---

DEFINE_NETD_BPF_PROG_RANGES(recvmsg4, udp4_recvmsg, 4_19, INF, V, MAXAPI)
(__unused struct bpf_sock_addr *ctx) {
    return BPF_ALLOW;
}

DEFINE_NETD_BPF_PROG_RANGES(recvmsg6, udp6_recvmsg, 4_19, INF, V, MAXAPI)
(__unused struct bpf_sock_addr *ctx) {
    return BPF_ALLOW;
}

// --- UDP SENDMSG HOOKS ---

DEFINE_NETD_BPF_PROG_RANGES(sendmsg4, udp4_sendmsg, 4_19, INF, V, MAXAPI)
(__unused struct bpf_sock_addr *ctx) {
    return BPF_ALLOW;
}

DEFINE_NETD_BPF_PROG_RANGES(sendmsg6, udp6_sendmsg, 4_19, INF, V, MAXAPI)
(__unused struct bpf_sock_addr *ctx) {
    return BPF_ALLOW;
}

// --- GETSOCKOPT HOOK ---

function int inet_getsockopt(struct bpf_sockopt *ctx,
                             const struct kver_uint kver,
                             const struct sdk_level_uint lvl) {
    SkStorageValue *sks = KVER_IS_AT_LEAST(kver, 5, 10) ? bpf_sk_storage_get(ctx->sk, 0, 0) : NULL;
    uint8_t *optval_end = ctx->optval_end;
    uint8_t *optval = ctx->optval;

    if (API_IS_AT_LEAST(lvl, 26Q2)
        && KVER_IS_BETWEEN(6, 1, kver, 6, 18)
        && ctx->level == SOL_TCP
        && ctx->optname == TCP_ANDROID_L4S
        && ctx->sk->type == SOCK_STREAM
        && ctx->sk->protocol == IPPROTO_TCP) {

        if (!is_netd()) return bpf_disallow(EPERM);

        if (optval + sizeof(uint8_t) > optval_end) return bpf_disallow(EINVAL);

        if (!sks) return bpf_disallow(EUNATCH);

        *optval = sks->l4s.enabled;
        WRITE_ONCE(ctx->retval, 0);
        WRITE_ONCE(ctx->optlen, sizeof(uint8_t));
        return BPF_ALLOW;
    }

    if (KVER_IS_AT_LEAST(kver, 5, 10)
        && ctx->level == SOL_SOCKET
        && ctx->optname == SO_ANDROID_DROP_REASON) {

        if (optval + sizeof(uint64_t) > optval_end) return bpf_disallow(EINVAL);

        if (!sks) return bpf_disallow(EUNATCH);

        *(uint64_t *)optval = sks->dropReasons;
        sks->dropReasons = DROP_REASON_NONE;
        WRITE_ONCE(ctx->retval, 0);
        WRITE_ONCE(ctx->optlen, sizeof(uint64_t));
        return BPF_ALLOW;
    }

    // Tell kernel to return 'original' kernel reply (instead of the bpf modified buffer)
    // This is important if the answer is larger than PAGE_SIZE (max size this bpf hook can provide)
    ctx->optlen = 0;
    return BPF_ALLOW;
}

DEFINE_NETD_BPF_PROG_RANGES(getsockopt, prog, 6_18, INF, V, MAXAPI)
(struct bpf_sockopt *ctx) {
    return inet_getsockopt(ctx, KVER_6_18, API(V));
}

DEFINE_NETD_BPF_PROG_RANGES(getsockopt, prog, 6_1, 6_18, 26Q2, MAXAPI)
(struct bpf_sockopt *ctx) {
    return inet_getsockopt(ctx, KVER_6_1, API(26Q2));
}

DEFINE_NETD_BPF_PROG_RANGES(getsockopt, prog, 6_1, 6_18, V, 26Q2)
(struct bpf_sockopt *ctx) {
    return inet_getsockopt(ctx, KVER_6_1, API(V));
}

DEFINE_NETD_BPF_PROG_RANGES(getsockopt, prog, 5_10, 6_1, V, MAXAPI)
(struct bpf_sockopt *ctx) {
    return inet_getsockopt(ctx, KVER_5_10, API(V));
}

DEFINE_NETD_BPF_PROG_RANGES(getsockopt, prog, 5_4, 5_10, V, MAXAPI)
(struct bpf_sockopt *ctx) {
    return inet_getsockopt(ctx, KVER_5_4, API(V));
}

// --- SETSOCKOPT HOOK ---

function int inet_setsockopt(struct bpf_sockopt *ctx,
                             const struct kver_uint kver,
                             const struct sdk_level_uint lvl) {
    SkStorageValue *sks = KVER_IS_AT_LEAST(kver, 5, 10) ? bpf_sk_storage_get(ctx->sk, 0, 0) : NULL;
    uint8_t *optval_end = ctx->optval_end;
    uint8_t *optval = ctx->optval;

    if (API_IS_AT_LEAST(lvl, 26Q2)
        && KVER_IS_BETWEEN(6, 1, kver, 6, 18)
        && ctx->level == SOL_TCP
        && ctx->optname == TCP_ANDROID_L4S
        && ctx->sk->type == SOCK_STREAM
        && ctx->sk->protocol == IPPROTO_TCP) {

        if (!is_netd()) return bpf_disallow(EPERM);

        if (optval + sizeof(uint8_t) > optval_end) return bpf_disallow(EINVAL);

        if (!sks) return bpf_disallow(EUNATCH);

        sks->l4s.enabled = !!*optval;
        WRITE_ONCE(ctx->optlen, -1);
        return BPF_ALLOW;
    }

    // Tell kernel to use/process original buffer provided by userspace.
    // This is important if it is larger than PAGE_SIZE (max size this bpf hook can handle).
    ctx->optlen = 0;

    uint64_t gid_uid = bpf_get_current_uid_gid();
    uint32_t uid = (gid_uid & 0xFFFFFFFF);

    UidOwnerValue* uidEntry = bpf_uid_owner_map_lookup_elem(&uid);
    uint32_t uidRule = uidEntry ? uidEntry->rule : 0;

    if (!(uidRule & LOCKDOWN_VPN_MATCH)) {
        return SETSOCKOPT_ALLOWED;
    }

    {
        // Prevent SO_BINDTODEVICE from being triggered by a UID that is under a lockdown VPN as
        // this can leak unicast traffic. Can only do this for regular apps as some core system and
        // system apps rely on this being allowed.
        // TODO: Review IP_UNICAST_IF and IP_PKTINFO.
        // TODO: Review PermissionMonitor#hasRestrictedNetworkPermission to see if this covers all
        //  of the system uids that need to SO_BINDTODEVICE. These uids do not have
        //  LOCKDOWN_VPN_MATCH.
        if ((uidRule & LOCKDOWN_VPN_REGULAR_APP_MATCH)
                && ctx->level == SOL_SOCKET
                && ctx->optname == SO_BINDTODEVICE) {
            return SETSOCKOPT_EPERM;
        }
    }

    {
        // Prevent kernel-generated multicast traffic (IGMP, MLD) from being triggered by a
        // UID that is under a lockdown VPN. A known leak that still exists is when a UID joins a multicast
        // group prior to being under a lockdown VPN and then becomes under a lockdown VPN. In this case the
        // IGMP/MLD will be generated when the kernel destroys the thread. This is considered very low
        // severity.
        if (ctx->level == IPPROTO_IP
                && (ctx->optname == IP_ADD_MEMBERSHIP
                || ctx->optname == IP_ADD_SOURCE_MEMBERSHIP
                || ctx->optname == IP_DROP_MEMBERSHIP
                || ctx->optname == IP_DROP_SOURCE_MEMBERSHIP
                || ctx->optname == IP_BLOCK_SOURCE
                || ctx->optname == IP_UNBLOCK_SOURCE
                || ctx->optname == IP_MSFILTER)) {
            return SETSOCKOPT_EPERM;
        }

        if (ctx->level == IPPROTO_IPV6
                && (ctx->optname == IPV6_ADD_MEMBERSHIP /** IPV6_JOIN_GROUP **/
                || ctx->optname == IPV6_DROP_MEMBERSHIP /** IPV6_LEAVE_GROUP **/)) {
            return SETSOCKOPT_EPERM;
        }

        if ((ctx->level == IPPROTO_IP || ctx->level == IPPROTO_IPV6)
                && (ctx->optname == MCAST_JOIN_GROUP
                || ctx->optname == MCAST_LEAVE_GROUP
                || ctx->optname == MCAST_BLOCK_SOURCE
                || ctx->optname == MCAST_UNBLOCK_SOURCE
                || ctx->optname == MCAST_JOIN_SOURCE_GROUP
                || ctx->optname == MCAST_LEAVE_SOURCE_GROUP)) {
            return SETSOCKOPT_EPERM;
        }
    }

    return SETSOCKOPT_ALLOWED;
}

DEFINE_NETD_BPF_PROG_RANGES(setsockopt, prog, 6_18, INF, V, MAXAPI)
(struct bpf_sockopt *ctx) {
    return inet_setsockopt(ctx, KVER_6_18, API(V));
}

DEFINE_NETD_BPF_PROG_RANGES(setsockopt, prog, 6_1, 6_18, 26Q2, MAXAPI)
(struct bpf_sockopt *ctx) {
    return inet_setsockopt(ctx, KVER_6_1, API(26Q2));
}

DEFINE_NETD_BPF_PROG_RANGES(setsockopt, prog, 6_1, 6_18, V, 26Q2)
(struct bpf_sockopt *ctx) {
    return inet_setsockopt(ctx, KVER_6_1, API(V));
}

DEFINE_NETD_BPF_PROG_RANGES(setsockopt, prog, 5_10, 6_1, V, MAXAPI)
(struct bpf_sockopt *ctx) {
    return inet_setsockopt(ctx, KVER_5_10, API(V));
}

DEFINE_NETD_BPF_PROG_RANGES(setsockopt, prog, 5_4, 5_10, V, MAXAPI)
(struct bpf_sockopt *ctx) {
    return inet_setsockopt(ctx, KVER_5_4, API(V));
}

LICENSE("Apache 2.0");
