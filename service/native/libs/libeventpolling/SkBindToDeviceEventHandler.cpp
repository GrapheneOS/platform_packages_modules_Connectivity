// Based on Connectivity/service/native/libs/libeventpolling/LocalNetEventHandler.cpp

#define LOG_TAG "SkBindToDeviceEventHandler"

#include "libeventpolling/SkBindToDeviceEventHandler.h"

#include <memory>
#include <vector>

#include <bpf/BpfRingbuf.h>
#include <bpf/BpfUtils.h>
#include <log/log.h>
#include <statslog_connectivity_sdk30.h>

#include "netd.h"

namespace android::net::eventpolling {

using bpf::BpfRingbuf;
using bpf::RingbufEventPoller;

// static
SkBindToDeviceEventHandler::SkBindToDeviceEventRingbuf *SkBindToDeviceEventHandler::GetRingbuf() {
    static SkBindToDeviceEventRingbuf *const sRingbuf =
        []() -> SkBindToDeviceEventRingbuf * {
        auto rb = std::make_unique<SkBindToDeviceEventRingbuf>(
                SK_BIND_TO_DEVICE_EVENT_RINGBUF_PATH);
        return rb.release();
    }();
    return sRingbuf;
}

// static
std::vector<uint32_t> SkBindToDeviceEventHandler::ConsumeAll() {
    std::vector<uint32_t> uids_pids;
    base::Result<int> ret =
        GetRingbuf()->ConsumeAll([&](const SkBindToDeviceEvent &event) {
            uids_pids.push_back(event.uid);
            uids_pids.push_back(event.pid);
        });
    if (!ret.ok()) {
        ALOGW("Failed to poll ringbuf: %s", ret.error().message().c_str());
        return {};
    }
    return uids_pids;
}

} // namespace android::net::eventpolling