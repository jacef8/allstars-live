#include "ts_demuxer.h"

#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "TsDemuxer", __VA_ARGS__)

namespace {
constexpr size_t TS_PACKET = 188;
constexpr uint8_t TS_SYNC = 0x47;
}  // namespace

TsDemuxer::TsDemuxer(AccessUnitCallback cb, void *userData)
        : cb_(cb), userData_(userData) {}

void TsDemuxer::feed(const uint8_t *data, size_t size) {
    // TODO(M1): implement the real depacketizer. Concrete outline:
    //
    //  1) Re-sync: prepend spill_, then walk the buffer in 188-byte strides
    //     verifying byte[0] == 0x47 (TS_SYNC). Stash any trailing partial packet
    //     back into spill_ for the next feed().
    //
    //  2) Section parse:
    //       - PID 0  -> PAT  -> program_map_PID  => pmtPid_
    //       - pmtPid_-> PMT  -> first es with stream_type 0x1B (H.264) => videoPid_
    //     (Mevo carries one program; take the first video ES.)
    //
    //  3) For packets on videoPid_:
    //       - payload_unit_start_indicator == 1 marks a new PES. When it flips,
    //         flush the access unit assembled so far: cb_(pes_..., currentPtsUs_,
    //         keyframe, userData_); then clear pes_.
    //       - At a PES start, read the optional 33-bit PTS (90kHz) from the PES
    //         header: currentPtsUs_ = pts90k * 1000 / 90.
    //       - Strip the adaptation field; append PES payload bytes to pes_.
    //         (Mevo's payload is already Annex-B with in-band SPS/PPS, so the
    //          access unit can go straight to MediaCodec with csd == null.)
    //
    //  4) keyframe = the access unit contains an IDR NAL (nal_unit_type == 5).
    //
    // Until this is implemented, feed() is a no-op so the JNI + threading +
    // MediaCodec path can be exercised end-to-end first.
    (void) data;
    (void) size;
    (void) cb_;
    (void) userData_;
    (void) pmtPid_;
    (void) videoPid_;
    (void) currentPtsUs_;
    (void) TS_PACKET;
    (void) TS_SYNC;
}
