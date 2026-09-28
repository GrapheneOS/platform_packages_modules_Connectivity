// Based on Connectivity/service/native/libs/libeventpolling/include/libeventpolling/LocalNetEventHandler.h

#pragma once

#include <vector>

#include <android-base/unique_fd.h>
#include <bpf/RingbufEventPoller.h>

#include "netd.h"

namespace android::net::eventpolling {

class SkBindToDeviceEventHandler {
    public:
        SkBindToDeviceEventHandler() = delete;
        SkBindToDeviceEventHandler(const SkBindToDeviceEventHandler &) = delete;
        SkBindToDeviceEventHandler &operator=(const SkBindToDeviceEventHandler &) = delete;

        static android::base::unique_fd GetNewRingbufFd() {
            return GetRingbuf()->GetDuplicateFd();
        }

        // Consumes all available events in the ring buffer. Returns a list of
        // alternating UIDs / PIDs, where each UID / PID pair represents a single
        // access event.
        static std::vector<uint32_t> ConsumeAll();

    private:
        class SkBindToDeviceEventRingbuf : public bpf::BpfRingbuf<SkBindToDeviceEvent> {
        public:
            SkBindToDeviceEventRingbuf(const char *path)
                    : BpfRingbuf<SkBindToDeviceEvent>(path) {}

            android::base::unique_fd GetDuplicateFd() {
                return android::base::unique_fd(
                        fcntl(mRingFd.get(), F_DUPFD_CLOEXEC, 0));
            }
        };

        static SkBindToDeviceEventRingbuf *GetRingbuf();
};

} // namespace android::net::eventpolling
