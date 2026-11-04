#ifndef AV_SYNC_H
#define AV_SYNC_H

#include <atomic>
#include <climits>
#include <cstdint>

#include "audio_time.h"

/*
 * A/V sync anchor: maps a sender presentation time (ntp_time_local, CLOCK_REALTIME ns) to
 * the local CLOCK_MONOTONIC instant it becomes audible. Published by the audio output
 * callback, read by the video renderer so frames are released when their audio plays
 * (System.nanoTime() is CLOCK_MONOTONIC on Android).
 *
 * Without it video was anchored to the arrival of its first decoded frame while audio
 * followed its own adaptive cushion, so the A/V offset differed per session and drifted
 * while the cushion converged during the first seconds.
 */
namespace av_sync {

inline std::atomic<int64_t> gOffsetNs{0};     // audible mono time - pts
inline std::atomic<int64_t> gUpdatedNs{0};    // monoNs() of last publish, 0 = never

// audio output callback: samples starting at ptsNs become audible at monoNs() + outputLatencyNs
inline void publish(int64_t ptsNs, int64_t outputLatencyNs) {
    const int64_t now = monoNs();
    gOffsetNs.store(now + outputLatencyNs - ptsNs, std::memory_order_relaxed);
    gUpdatedNs.store(now, std::memory_order_release);
}

// stream torn down: video falls back to its own pacing until audio plays again
inline void reset() { gUpdatedNs.store(0, std::memory_order_release); }

// offset, or INT64_MIN when audio has not played recently. Short gaps keep the last value:
// senders pause audio during silence and the mapping stays valid across it
inline int64_t current(int64_t maxAgeNs) {
    const int64_t at = gUpdatedNs.load(std::memory_order_acquire);
    if (at == 0 || monoNs() - at > maxAgeNs) return INT64_MIN;
    return gOffsetNs.load(std::memory_order_relaxed);
}

}  // namespace av_sync

#endif  // AV_SYNC_H
