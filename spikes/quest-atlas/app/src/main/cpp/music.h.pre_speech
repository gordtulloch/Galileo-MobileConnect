// Background music: plays the .mp3 / .mp4 / .m4a files in assets/music in random order for as long as the app runs.
//
// Java-free (the app is a plain NativeActivity): AMediaExtractor pulls the audio track out of the MP4, AMediaCodec
// decodes it to PCM on a worker thread, and AAudio plays it. Assets are opened by file descriptor, which needs them
// stored uncompressed in the APK (aapt2 does that for these extensions by default). The order is a shuffle that never repeats a
// track until all have played, and never starts a new round with the track that just finished.
#pragma once

#include <aaudio/AAudio.h>
#include <android/asset_manager.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <random>
#include <string>
#include <thread>
#include <vector>

namespace music {

constexpr float kVolume = 0.6f;

inline std::atomic<bool> gStop{false}, gPaused{false};
inline std::thread gThread;

#define MLOG(...) __android_log_print(ANDROID_LOG_INFO, "GalileoMusic", __VA_ARGS__)

inline void nap(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

struct Out {
    AAudioStream* s = nullptr;
    int32_t rate = 0, ch = 0;
    bool f32 = false;

    void close() {
        if (s) {
            AAudioStream_requestStop(s);
            AAudioStream_close(s);
            s = nullptr;
        }
    }
    bool open(int32_t r, int32_t c, bool isFloat) {
        close();
        AAudioStreamBuilder* b = nullptr;
        if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
        AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSampleRate(b, r);
        AAudioStreamBuilder_setChannelCount(b, c);
        AAudioStreamBuilder_setFormat(b, isFloat ? AAUDIO_FORMAT_PCM_FLOAT : AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
        aaudio_result_t res = AAudioStreamBuilder_openStream(b, &s);
        AAudioStreamBuilder_delete(b);
        if (res != AAUDIO_OK) {
            MLOG("AAudio open failed: %s", AAudio_convertResultToText(res));
            s = nullptr;
            return false;
        }
        AAudioStream_requestStart(s);
        rate = r;
        ch = c;
        f32 = isFloat;
        return true;
    }
    void pause() { if (s) AAudioStream_requestPause(s); }
    void resume() { if (s) AAudioStream_requestStart(s); }

    // blocking write of interleaved PCM, scaled by the volume
    void write(const uint8_t* data, size_t bytes) {
        if (!s || ch <= 0) return;
        const size_t sampleBytes = f32 ? 4 : 2;
        size_t frames = bytes / (sampleBytes * size_t(ch));
        std::vector<uint8_t> scaled(frames * sampleBytes * size_t(ch));
        const size_t n = frames * size_t(ch);
        if (f32) {
            const float* in = reinterpret_cast<const float*>(data);
            float* o = reinterpret_cast<float*>(scaled.data());
            for (size_t i = 0; i < n; ++i) o[i] = in[i] * kVolume;
        } else {
            const int16_t* in = reinterpret_cast<const int16_t*>(data);
            int16_t* o = reinterpret_cast<int16_t*>(scaled.data());
            for (size_t i = 0; i < n; ++i) o[i] = int16_t(float(in[i]) * kVolume);
        }
        size_t done = 0;
        while (done < frames && !gStop) {
            if (gPaused) { nap(50); continue; }
            aaudio_result_t w = AAudioStream_write(s, scaled.data() + done * sampleBytes * size_t(ch),
                                                   int32_t(frames - done), 200 * 1000 * 1000LL);
            if (w < 0) { MLOG("AAudio write failed: %s", AAudio_convertResultToText(w)); return; }
            done += size_t(w);
        }
    }
};

// Decode one file to the end (or until stopped). False when it could not be played at all.
inline bool playTrack(AAssetManager* am, const std::string& path) {
    AAsset* asset = AAssetManager_open(am, path.c_str(), AASSET_MODE_UNKNOWN);
    if (!asset) return false;
    off_t off = 0, len = 0;
    int fd = AAsset_openFileDescriptor(asset, &off, &len);
    if (fd < 0) {
        MLOG("%s is stored compressed in the APK; it must be uncompressed to play", path.c_str());
        AAsset_close(asset);
        return false;
    }
    AMediaExtractor* ex = AMediaExtractor_new();
    AMediaCodec* codec = nullptr;
    Out out;
    bool played = false;

    do {
        if (AMediaExtractor_setDataSourceFd(ex, fd, off, len) != AMEDIA_OK) { MLOG("cannot open %s", path.c_str()); break; }
        int track = -1;
        const char* mime = nullptr;
        AMediaFormat* fmt = nullptr;
        for (size_t i = 0; i < AMediaExtractor_getTrackCount(ex); ++i) {
            AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, i);
            const char* m = nullptr;
            if (AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m) && m && !strncmp(m, "audio/", 6)) {
                track = int(i);
                mime = m;
                fmt = f;
                break;
            }
            AMediaFormat_delete(f);
        }
        if (track < 0) { MLOG("%s has no audio track", path.c_str()); break; }
        AMediaExtractor_selectTrack(ex, size_t(track));
        codec = AMediaCodec_createDecoderByType(mime);
        if (!codec || AMediaCodec_configure(codec, fmt, nullptr, nullptr, 0) != AMEDIA_OK ||
            AMediaCodec_start(codec) != AMEDIA_OK) {
            MLOG("no decoder for %s (%s)", path.c_str(), mime);
            AMediaFormat_delete(fmt);
            break;
        }
        AMediaFormat_delete(fmt);
        MLOG("playing %s (%s)", path.c_str(), mime);

        bool inEos = false, outEos = false;
        int idle = 0;
        while (!outEos && !gStop) {
            if (gPaused) {
                out.pause();
                while (gPaused && !gStop) nap(100);
                out.resume();
                continue;
            }
            if (!inEos) {
                ssize_t i = AMediaCodec_dequeueInputBuffer(codec, 0);
                if (i >= 0) {
                    size_t cap = 0;
                    uint8_t* buf = AMediaCodec_getInputBuffer(codec, size_t(i), &cap);
                    ssize_t sz = AMediaExtractor_readSampleData(ex, buf, cap);
                    if (sz < 0) {
                        AMediaCodec_queueInputBuffer(codec, size_t(i), 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                        inEos = true;
                    } else {
                        AMediaCodec_queueInputBuffer(codec, size_t(i), 0, size_t(sz), uint64_t(AMediaExtractor_getSampleTime(ex)), 0);
                        AMediaExtractor_advance(ex);
                    }
                }
            }
            AMediaCodecBufferInfo info;
            ssize_t o = AMediaCodec_dequeueOutputBuffer(codec, &info, 10000);
            if (o >= 0) {
                idle = 0;
                if (info.size > 0) {
                    if (!out.s) {  // no format-changed event seen: assume 44.1 kHz stereo
                        if (!out.open(44100, 2, false)) { AMediaCodec_releaseOutputBuffer(codec, size_t(o), false); break; }
                    }
                    size_t osz = 0;
                    uint8_t* data = AMediaCodec_getOutputBuffer(codec, size_t(o), &osz);
                    if (data) out.write(data + info.offset, size_t(info.size));
                    played = true;
                }
                AMediaCodec_releaseOutputBuffer(codec, size_t(o), false);
                if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) outEos = true;
            } else if (o == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat* of = AMediaCodec_getOutputFormat(codec);
                int32_t rate = 44100, ch = 2, enc = 2;  // 2 = ENCODING_PCM_16BIT, 4 = ENCODING_PCM_FLOAT
                AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_SAMPLE_RATE, &rate);
                AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
                AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_PCM_ENCODING, &enc);
                AMediaFormat_delete(of);
                if (!out.open(rate, ch, enc == 4)) break;
                MLOG("output %d Hz, %d ch%s", rate, ch, enc == 4 ? ", float" : "");
            } else if (o == AMEDIACODEC_INFO_TRY_AGAIN_LATER && inEos && ++idle > 300) {
                break;  // the decoder never signalled the end: give up on this track
            }
        }
    } while (false);

    out.close();
    if (codec) {
        AMediaCodec_stop(codec);
        AMediaCodec_delete(codec);
    }
    AMediaExtractor_delete(ex);
    close(fd);
    AAsset_close(asset);
    return played;
}

inline std::vector<std::string> listTracks(AAssetManager* am) {
    std::vector<std::string> v;
    if (AAssetDir* dir = AAssetManager_openDir(am, "music")) {
        while (const char* n = AAssetDir_getNextFileName(dir)) {
            std::string s = n;
            std::string low = s;
            std::transform(low.begin(), low.end(), low.begin(), [](unsigned char c) { return char(tolower(c)); });
            auto ends = [&](const char* ext) { size_t n = strlen(ext); return low.size() > n && low.compare(low.size() - n, n, ext) == 0; };
            if (ends(".mp3") || ends(".mp4") || ends(".m4a")) v.push_back("music/" + s);
        }
        AAssetDir_close(dir);
    }
    return v;
}

// Starts the worker; does nothing when there are no tracks.
inline void start(AAssetManager* am) {
    std::vector<std::string> tracks = listTracks(am);
    if (tracks.empty()) {
        MLOG("no .mp3 / .mp4 / .m4a files in assets/music");
        return;
    }
    MLOG("%zu tracks", tracks.size());
    gStop = false;
    gThread = std::thread([am, tracks]() mutable {
        std::mt19937 rng{std::random_device{}()};
        std::string last;
        int failures = 0;
        while (!gStop) {
            std::shuffle(tracks.begin(), tracks.end(), rng);
            if (tracks.size() > 1 && tracks.front() == last) std::swap(tracks.front(), tracks.back());
            for (const std::string& t : tracks) {
                if (gStop) break;
                if (playTrack(am, t)) failures = 0; else ++failures;
                last = t;
                if (failures >= int(tracks.size())) { MLOG("no track could be played; music stopped"); return; }
            }
        }
    });
}

inline void setPaused(bool p) { gPaused = p; }

inline void stop() {
    gStop = true;
    if (gThread.joinable()) gThread.join();
}

#undef MLOG

}  // namespace music
