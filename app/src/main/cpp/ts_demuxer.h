#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

// One complete H.264 access unit (Annex-B), its PTS in microseconds, and whether
// it begins an IDR (keyframe). Mirrors MediaCodecVideoDecoder.submitAccessUnit.
using AccessUnitCallback = void (*)(const uint8_t *data, size_t size,
                                    int64_t ptsUs, bool keyframe, void *userData);

/**
 * Minimal MPEG-TS depacketizer for the ONE case the Mevo emits: a single H.264
 * elementary stream inside a 188-byte TS multiplex.
 *
 * Deliberately not a general demuxer. It locates the video PID via PAT/PMT,
 * reassembles PES packets, and emits Annex-B access units with the 90kHz PTS
 * converted to microseconds. This is the work libsrt does NOT do for us (and the
 * work FFmpeg/libVLC would have hidden) -- see the ingest decision memo.
 *
 * SKELETON: the parse steps are stubbed with TODO(M1) markers in ts_demuxer.cpp.
 */
class TsDemuxer {
public:
    TsDemuxer(AccessUnitCallback cb, void *userData);

    // Feed raw bytes straight off the SRT socket. SRT delivers whole TS packets,
    // but feed() buffers a remainder so callers need not be packet-aligned.
    void feed(const uint8_t *data, size_t size);

private:
    void onPacket(const uint8_t *pkt);  // parse one 188-byte TS packet
    void flushAU();                     // emit the assembled access unit (if any)

    AccessUnitCallback cb_;
    void *userData_;

    int pmtPid_ = -1;          // discovered from the PAT
    int videoPid_ = -1;        // first H.264 (stream_type 0x1B) PID in the PMT
    std::vector<uint8_t> spill_;   // partial trailing TS packet between feeds
    std::vector<uint8_t> pes_;     // current PES (access unit) assembly buffer
    int64_t currentPtsUs_ = 0;     // PTS of the access unit being assembled
};
