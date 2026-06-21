#include "ts_demuxer.h"

#include <android/log.h>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "TsDemuxer", __VA_ARGS__)

namespace {
constexpr size_t TS_PACKET = 188;
constexpr uint8_t TS_SYNC = 0x47;

// Scan an Annex-B access unit for an IDR NAL (nal_unit_type == 5) => keyframe.
bool hasIdr(const std::vector<uint8_t> &au) {
    const size_t n = au.size();
    for (size_t i = 0; i + 4 < n; ++i) {
        const bool sc3 = au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1;
        const bool sc4 = au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 0 && au[i + 3] == 1;
        if (sc3 || sc4) {
            const size_t nal = sc3 ? i + 3 : i + 4;
            if (nal < n && (au[nal] & 0x1F) == 5) return true;
        }
    }
    return false;
}
}  // namespace

TsDemuxer::TsDemuxer(AccessUnitCallback cb, void *userData)
        : cb_(cb), userData_(userData) {}

void TsDemuxer::flushAU() {
    if (pes_.empty()) return;
    cb_(pes_.data(), pes_.size(), currentPtsUs_, hasIdr(pes_), userData_);
    pes_.clear();
}

void TsDemuxer::onPacket(const uint8_t *p) {
    if (p[0] != TS_SYNC) return;
    const bool pusi = (p[1] & 0x40) != 0;
    const int pid = ((p[1] & 0x1F) << 8) | p[2];
    const int afc = (p[3] >> 4) & 0x3;
    size_t off = 4;
    if (afc & 0x2) off += 1 + p[4];   // skip adaptation field
    if (!(afc & 0x1) || off >= TS_PACKET) return;  // no payload
    const uint8_t *pl = p + off;
    const size_t pl_len = TS_PACKET - off;

    // ---- PAT (PID 0): find the PMT PID ----
    if (pid == 0) {
        const uint8_t *s = pl; int slen = (int) pl_len;
        if (pusi) { int ptr = s[0]; s += 1 + ptr; slen -= 1 + ptr; }
        if (slen < 8) return;
        int section_length = ((s[1] & 0x0F) << 8) | s[2];
        int end = 3 + section_length - 4;            // exclude 4-byte CRC
        if (end > slen) end = slen;
        for (int i = 8; i + 4 <= end; i += 4) {
            int prog = (s[i] << 8) | s[i + 1];
            int pmt = ((s[i + 2] & 0x1F) << 8) | s[i + 3];
            if (prog != 0) { pmtPid_ = pmt; break; }
        }
        return;
    }

    // ---- PMT: find the first H.264 (stream_type 0x1B) elementary PID ----
    if (pid == pmtPid_ && videoPid_ < 0) {
        const uint8_t *s = pl; int slen = (int) pl_len;
        if (pusi) { int ptr = s[0]; s += 1 + ptr; slen -= 1 + ptr; }
        if (slen < 12) return;
        int section_length = ((s[1] & 0x0F) << 8) | s[2];
        int end = 3 + section_length - 4;
        if (end > slen) end = slen;
        int prog_info_len = ((s[10] & 0x0F) << 8) | s[11];
        for (int i = 12 + prog_info_len; i + 5 <= end;) {
            int stype = s[i];
            int epid = ((s[i + 1] & 0x1F) << 8) | s[i + 2];
            int esinfo = ((s[i + 3] & 0x0F) << 8) | s[i + 4];
            if (stype == 0x1B) { videoPid_ = epid; LOGI("video PID = %d", epid); break; }
            i += 5 + esinfo;
        }
        return;
    }

    // ---- Video ES: reassemble PES into Annex-B access units ----
    if (pid == videoPid_ && videoPid_ >= 0) {
        if (pusi) {
            flushAU();  // the previous access unit is complete
            if (pl_len >= 9 && pl[0] == 0 && pl[1] == 0 && pl[2] == 1) {
                int pts_dts = (pl[7] >> 6) & 0x3;
                int hdrlen = pl[8];
                if ((pts_dts & 0x2) && pl_len >= 14) {
                    const uint8_t *t = pl + 9;
                    int64_t pts = ((int64_t) (t[0] & 0x0E) << 29) | ((int64_t) t[1] << 22) |
                                  ((int64_t) (t[2] & 0xFE) << 14) | ((int64_t) t[3] << 7) |
                                  ((int64_t) (t[4] >> 1));
                    currentPtsUs_ = pts * 100 / 9;   // 90kHz ticks -> microseconds
                }
                size_t start = 9 + hdrlen;
                if (start < pl_len) pes_.insert(pes_.end(), pl + start, pl + pl_len);
            } else {
                pes_.insert(pes_.end(), pl, pl + pl_len);
            }
        } else {
            pes_.insert(pes_.end(), pl, pl + pl_len);
        }
    }
}

void TsDemuxer::feed(const uint8_t *data, size_t size) {
    std::vector<uint8_t> buf;
    buf.reserve(spill_.size() + size);
    buf.insert(buf.end(), spill_.begin(), spill_.end());
    buf.insert(buf.end(), data, data + size);
    spill_.clear();

    size_t i = 0, n = buf.size();
    while (i + TS_PACKET <= n) {
        if (buf[i] != TS_SYNC) {            // re-sync to the next 0x47
            size_t j = i + 1;
            while (j < n && buf[j] != TS_SYNC) ++j;
            i = j;
            continue;
        }
        onPacket(&buf[i]);
        i += TS_PACKET;
    }
    if (i < n) spill_.assign(buf.begin() + i, buf.end());
}
