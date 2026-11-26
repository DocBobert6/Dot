// Dot — JNI bridge to the mGBA libretro core, Phase 1.
//
// A thin binding over the libretro API (a public standard defined in
// mgba's own tree, src/platform/libretro/libretro.h). JNI symbol names
// are Java_app_dot_Bridge_nativeXxx. The shape follows Atomic's bridge
// (same threading discipline, same teardown order, same error-string
// rule) with DMG geometry throughout: the core reports 160x144
// RGB565 frames at 59.7275 fps, and anything else is an error, not
// a frame.
//
// Threading: one global mutex serialises every retro_* call.
// Callbacks never take it (they run inside retro_run while it is
// held). The input mask is a separate atomic; frame and audio have
// their own mutexes. Every failure sets g_lastError; nothing here
// crashes on bad input.
//
// Core options are seeded for DMG and never change at runtime except
// through nativeSetOption: mgba_gb_model is forced to "Game Boy" so
// dual-mode (0x80) games run in DMG mode; mgba_gb_colors is
// "Grayscale"; SGB borders are OFF; colour correction and interframe
// blending are OFF. The low-pass pair is seeded enabled/60 and is
// driven by the Settings slider (low-pass always on, strength only).
//
// SPDX-License-Identifier: GPL-3.0-or-later

#include <jni.h>

#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "libretro.h"

#define DOT_LOG_TAG "DotCore"
#define DOT_LOG(...) __android_log_print(ANDROID_LOG_INFO, DOT_LOG_TAG, __VA_ARGS__)

// DMG geometry. The core must report exactly this; anything else is
// rejected in the video callback with an error, never rendered.
static constexpr int kFrameWidth = 160;
static constexpr int kFrameHeight = 144;
// Audio ring capacity, in stereo frames, re-derived from the AV info
// sample rate at load time (GB is 131072 Hz in this core); the
// constant here is only the pre-load fallback.
static constexpr int kAudioRingFrames = 131072;

// ---------------------------------------------------------------- core
// state

static std::mutex g_coreMutex;
static bool g_coreLive = false;
static bool g_gameLoaded = false;
static std::string g_lastError;
static std::mutex g_errorMutex;

static void setError(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_errorMutex);
    g_lastError = message;
    DOT_LOG("error: %s", message.c_str());
}

// Directories the core holds pointers to (via the environment
// callback), so they live in fixed buffers that never move.
static char g_systemDir[1024];
static char g_saveDir[1024];

// Core options. Values live in fixed char arrays in stable map nodes
// so core-held pointers never dangle; the dirty flag tells the core
// to re-read on the next retro_run.
struct CoreOption {
    char value[64];
};
static std::map<std::string, CoreOption> g_options;
static std::atomic<bool> g_optionsDirty{false};

static void seedDefaultOptions() {
    g_options.clear();
    auto set = [](const char* key, const char* value) {
        CoreOption opt{};
        std::strncpy(opt.value, value, sizeof(opt.value) - 1);
        g_options[key] = opt;
    };
    set("mgba_gb_model", "Game Boy");
    set("mgba_use_bios", "ON");
    set("mgba_skip_bios", "OFF");
    set("mgba_gb_colors", "Grayscale");
    set("mgba_gb_colors_preset", "0");
    set("mgba_sgb_borders", "OFF");
    set("mgba_color_correction", "OFF");
    set("mgba_interframe_blending", "OFF");
    set("mgba_audio_low_pass_filter", "enabled");
    set("mgba_audio_low_pass_range", "60");
    set("mgba_allow_opposing_directions", "no");
    set("mgba_solar_sensor_level", "0");
    set("mgba_force_gbp", "OFF");
    set("mgba_idle_optimization", "Remove Known");
    set("mgba_frameskip", "disabled");
    set("mgba_frameskip_threshold", "33");
    set("mgba_frameskip_interval", "0");
    g_optionsDirty.store(false);
}

// ------------------------------------------------------------- video
// frame

static std::mutex g_frameMutex;
static uint32_t g_frameBuffer[kFrameWidth * kFrameHeight];
static bool g_frameReady = false;
static enum retro_pixel_format g_pixelFormat = RETRO_PIXEL_FORMAT_RGB565;

static uint32_t expandRgb565(uint16_t v) {
    uint32_t r = (v >> 11) & 31;
    uint32_t g = (v >> 5) & 63;
    uint32_t b = v & 31;
    r = (r << 3) | (r >> 2);
    g = (g << 2) | (g >> 4);
    b = (b << 3) | (b >> 2);
    return 0xFF000000u | (r << 16) | (g << 8) | b;
}

static uint32_t expandRgb1555(uint16_t v) {
    uint32_t r = (v >> 10) & 31;
    uint32_t g = (v >> 5) & 31;
    uint32_t b = v & 31;
    r = (r << 3) | (r >> 2);
    g = (g << 3) | (g >> 2);
    b = (b << 3) | (b >> 2);
    return 0xFF000000u | (r << 16) | (g << 8) | b;
}

static void videoRefreshCallback(const void* data, unsigned width,
                                 unsigned height, size_t pitch) {
    if (!data) {
        return;  // skipped frame: keep the last one
    }
    if ((int)width != kFrameWidth || (int)height != kFrameHeight) {
        setError("Unexpected frame size " + std::to_string(width) + "x" +
                 std::to_string(height) + " (expected 160x144)");
        return;
    }
    const uint8_t* src = static_cast<const uint8_t*>(data);
    std::lock_guard<std::mutex> lock(g_frameMutex);
    if (g_pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
        for (int y = 0; y < kFrameHeight; ++y) {
            const uint16_t* row =
                reinterpret_cast<const uint16_t*>(src + (size_t)y * pitch);
            for (int x = 0; x < kFrameWidth; ++x) {
                g_frameBuffer[y * kFrameWidth + x] = expandRgb565(row[x]);
            }
        }
    } else if (g_pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
        for (int y = 0; y < kFrameHeight; ++y) {
            const uint32_t* row =
                reinterpret_cast<const uint32_t*>(src + (size_t)y * pitch);
            for (int x = 0; x < kFrameWidth; ++x) {
                g_frameBuffer[y * kFrameWidth + x] = row[x] | 0xFF000000u;
            }
        }
    } else if (g_pixelFormat == RETRO_PIXEL_FORMAT_0RGB1555) {
        for (int y = 0; y < kFrameHeight; ++y) {
            const uint16_t* row =
                reinterpret_cast<const uint16_t*>(src + (size_t)y * pitch);
            for (int x = 0; x < kFrameWidth; ++x) {
                g_frameBuffer[y * kFrameWidth + x] = expandRgb1555(row[x]);
            }
        }
    } else {
        setError("Unsupported pixel format from the core");
        return;
    }
    g_frameReady = true;
}

// ------------------------------------------------------------- audio
// ring

static std::mutex g_audioMutex;
static std::vector<int16_t> g_audioRing;  // interleaved stereo s16
static size_t g_audioRead = 0;
static size_t g_audioWrite = 0;
static size_t g_audioCount = 0;  // frames currently in the ring
static bool g_audioDiscard = false;

static void audioRingInit(int sampleRate) {
    size_t frames = sampleRate > 0 ? (size_t)sampleRate : kAudioRingFrames;
    g_audioRing.assign(frames * 2, 0);
    g_audioRead = 0;
    g_audioWrite = 0;
    g_audioCount = 0;
}

static size_t audioBatchCallback(const int16_t* data, size_t frames) {
    if (g_audioDiscard || g_audioRing.empty()) {
        return frames;
    }
    std::lock_guard<std::mutex> lock(g_audioMutex);
    const size_t capacity = g_audioRing.size() / 2;
    for (size_t i = 0; i < frames; ++i) {
        if (g_audioCount == capacity) {
            // Ring full: drop the oldest frame so the stream stays
            // live, never silent behind stale data.
            g_audioRead = (g_audioRead + 1) % capacity;
            --g_audioCount;
        }
        g_audioRing[g_audioWrite * 2] = data[i * 2];
        g_audioRing[g_audioWrite * 2 + 1] = data[i * 2 + 1];
        g_audioWrite = (g_audioWrite + 1) % capacity;
        ++g_audioCount;
    }
    return frames;
}

// ------------------------------------------------------------- input

static std::atomic<int32_t> g_inputMask{0};

static void inputPollCallback() {
    // No-op: the mask is pushed from Kotlin.
}

static int16_t inputStateCallback(unsigned port, unsigned device,
                                  unsigned index, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) {
        return 0;
    }
    int32_t mask = g_inputMask.load();
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
        return static_cast<int16_t>(mask & 0x01FF);
    }
    if (id > 15) {
        return 0;
    }
    (void)index;
    return (mask & (1 << id)) != 0 ? 1 : 0;
}

// --------------------------------------------------------- environment

// Core logs are noise for Dot; the function must be a plain static
// function, not a lambda — clang cannot convert a variadic lambda to
// a C function pointer.
static void logCallback(retro_log_level level, const char* fmt, ...) {
    (void)level;
    (void)fmt;
}

static bool environmentCallback(unsigned cmd, void* data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        const auto* fmt = static_cast<const retro_pixel_format*>(data);
        g_pixelFormat = *fmt;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
        *static_cast<const char**>(data) = g_systemDir;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
        *static_cast<const char**>(data) = g_saveDir;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        auto* var = static_cast<retro_variable*>(data);
        auto it = g_options.find(var->key ? var->key : "");
        if (it == g_options.end()) {
            return false;
        }
        var->value = it->second.value;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
        *static_cast<bool*>(data) = g_optionsDirty.exchange(false);
        return true;
    }
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
        auto* logCb = static_cast<retro_log_callback*>(data);
        logCb->log = &logCallback;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
    case RETRO_ENVIRONMENT_GET_SENSOR_INTERFACE:
    case RETRO_ENVIRONMENT_GET_CAMERA_INTERFACE:
    case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
        return false;
    default:
        return false;
    }
}

// ------------------------------------------------------------ AV info

static int g_avWidth = 0;
static int g_avHeight = 0;
static int g_avFpsMilli = 0;
static int g_avSampleRate = 0;

static void cacheAvInfo() {
    retro_system_av_info av{};
    retro_get_system_av_info(&av);
    g_avWidth = (int)av.geometry.base_width;
    g_avHeight = (int)av.geometry.base_height;
    g_avFpsMilli = (int)(av.timing.fps * 1000.0 + 0.5);
    g_avSampleRate = (int)(av.timing.sample_rate + 0.5);
}

// ---------------------------------------------------------------- JNI

static std::string jstringToStd(JNIEnv* env, jstring str) {
    if (!str) {
        return std::string();
    }
    const char* chars = env->GetStringUTFChars(str, nullptr);
    if (!chars) {
        return std::string();
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(str, chars);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_dot_Bridge_nativeApiVersion(JNIEnv* /* env */, jclass /* clazz */) {
    return retro_api_version();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeInit(JNIEnv* env, jclass /* clazz */,
                               jstring systemDir, jstring saveDir) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreLive) {
        // Clean restart: unload and deinit first, then start over.
        if (g_gameLoaded) {
            retro_unload_game();
            g_gameLoaded = false;
        }
        retro_deinit();
        g_coreLive = false;
    }
    std::string sysDir = jstringToStd(env, systemDir);
    std::string savDir = jstringToStd(env, saveDir);
    if (sysDir.empty() || savDir.empty()) {
        setError("init called with an empty directory");
        return JNI_FALSE;
    }
    std::strncpy(g_systemDir, sysDir.c_str(), sizeof(g_systemDir) - 1);
    g_systemDir[sizeof(g_systemDir) - 1] = '\0';
    std::strncpy(g_saveDir, savDir.c_str(), sizeof(g_saveDir) - 1);
    g_saveDir[sizeof(g_saveDir) - 1] = '\0';
    seedDefaultOptions();
    {
        std::lock_guard<std::mutex> frameLock(g_frameMutex);
        g_frameReady = false;
    }
    audioRingInit(0);
    g_audioDiscard = false;
    retro_set_environment(environmentCallback);
    retro_set_video_refresh(videoRefreshCallback);
    retro_set_audio_sample(nullptr);
    retro_set_audio_sample_batch(audioBatchCallback);
    retro_set_input_poll(inputPollCallback);
    retro_set_input_state(inputStateCallback);
    retro_init();
    g_coreLive = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeLoadGame(JNIEnv* env, jclass /* clazz */,
                                   jstring path) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive) {
        setError("loadGame called before init");
        return JNI_FALSE;
    }
    std::string romPath = jstringToStd(env, path);
    if (romPath.empty()) {
        setError("loadGame called with an empty path");
        return JNI_FALSE;
    }
    // Probe first: a useful error instead of a bare VFS failure.
    FILE* probe = fopen(romPath.c_str(), "rb");
    if (!probe) {
        setError("Cannot open ROM file: " + romPath);
        return JNI_FALSE;
    }
    fclose(probe);
    if (g_gameLoaded) {
        retro_unload_game();
        g_gameLoaded = false;
    }
    retro_game_info game{};
    game.path = romPath.c_str();
    game.data = nullptr;
    game.size = 0;
    if (!retro_load_game(&game)) {
        setError("The core refused to load this game");
        return JNI_FALSE;
    }
    g_gameLoaded = true;
    cacheAvInfo();
    audioRingInit(g_avSampleRate);
    {
        std::lock_guard<std::mutex> frameLock(g_frameMutex);
        g_frameReady = false;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_dot_Bridge_nativeGetAvInfo(JNIEnv* env, jclass /* clazz */) {
    jintArray out = env->NewIntArray(4);
    if (!out) {
        return nullptr;
    }
    jint values[4] = {g_avWidth, g_avHeight, g_avFpsMilli, g_avSampleRate};
    env->SetIntArrayRegion(out, 0, 4, values);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_app_dot_Bridge_nativeRunFrame(JNIEnv* /* env */, jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return;
    }
    retro_run();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeGetFrame(JNIEnv* env, jclass /* clazz */,
                                   jintArray pixels, jintArray dims) {
    std::lock_guard<std::mutex> lock(g_frameMutex);
    if (!g_frameReady) {
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(pixels);
    if (len < kFrameWidth * kFrameHeight) {
        setError("nativeGetFrame: pixel buffer too small");
        return JNI_FALSE;
    }
    env->SetIntArrayRegion(pixels, 0, kFrameWidth * kFrameHeight,
                           reinterpret_cast<const jint*>(g_frameBuffer));
    jint d[2] = {kFrameWidth, kFrameHeight};
    env->SetIntArrayRegion(dims, 0, 2, d);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_dot_Bridge_nativeReadAudio(JNIEnv* env, jclass /* clazz */,
                                    jshortArray samples, jint maxFrames) {
    std::lock_guard<std::mutex> lock(g_audioMutex);
    if (g_audioRing.empty() || g_audioCount == 0) {
        return 0;
    }
    const size_t capacity = g_audioRing.size() / 2;
    int frames = (int)g_audioCount;
    if (frames > maxFrames) {
        frames = maxFrames;
    }
    jsize len = env->GetArrayLength(samples);
    if (len < frames * 2) {
        frames = len / 2;
    }
    if (frames <= 0) {
        return 0;
    }
    // Copy out in ring order, possibly in two segments.
    std::vector<int16_t> tmp((size_t)frames * 2);
    size_t first = capacity - g_audioRead;
    if (first > (size_t)frames) {
        first = frames;
    }
    std::memcpy(tmp.data(), &g_audioRing[g_audioRead * 2], first * 2 * sizeof(int16_t));
    if (first < (size_t)frames) {
        std::memcpy(tmp.data() + first * 2, g_audioRing.data(),
                    ((size_t)frames - first) * 2 * sizeof(int16_t));
    }
    env->SetShortArrayRegion(samples, 0, frames * 2,
                            reinterpret_cast<const jshort*>(tmp.data()));
    g_audioRead = (g_audioRead + (size_t)frames) % capacity;
    g_audioCount -= (size_t)frames;
    return frames;
}

extern "C" JNIEXPORT void JNICALL
Java_app_dot_Bridge_nativeSetAudioDiscard(JNIEnv* /* env */,
                                          jclass /* clazz */,
                                          jboolean discard) {
    g_audioDiscard = discard == JNI_TRUE;
    if (g_audioDiscard) {
        std::lock_guard<std::mutex> lock(g_audioMutex);
        g_audioRead = 0;
        g_audioWrite = 0;
        g_audioCount = 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_dot_Bridge_nativeSetInput(JNIEnv* /* env */, jclass /* clazz */,
                                   jint mask) {
    g_inputMask.store(mask);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeSetOption(JNIEnv* env, jclass /* clazz */,
                                    jstring key, jstring value) {
    std::string k = jstringToStd(env, key);
    std::string v = jstringToStd(env, value);
    auto it = g_options.find(k);
    if (it == g_options.end()) {
        return JNI_FALSE;
    }
    if (std::string(it->second.value) == v) {
        return JNI_TRUE;
    }
    std::strncpy(it->second.value, v.c_str(), sizeof(it->second.value) - 1);
    it->second.value[sizeof(it->second.value) - 1] = '\0';
    g_optionsDirty.store(true);
    return JNI_TRUE;
}

static bool serializeToVector(std::vector<uint8_t>& out) {
    size_t size = retro_serialize_size();
    if (size == 0) {
        return false;
    }
    out.resize(size);
    return retro_serialize(out.data(), size);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_dot_Bridge_nativeSerializeSize(JNIEnv* /* env */,
                                        jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return 0;
    }
    return (jint)retro_serialize_size();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeSerialize(JNIEnv* env, jclass /* clazz */,
                                    jbyteArray data) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return JNI_FALSE;
    }
    std::vector<uint8_t> state;
    if (!serializeToVector(state)) {
        setError("State serialization failed");
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(data);
    if (len < (jsize)state.size()) {
        setError("State buffer too small");
        return JNI_FALSE;
    }
    env->SetByteArrayRegion(data, 0, (jsize)state.size(),
                            reinterpret_cast<const jbyte*>(state.data()));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeUnserialize(JNIEnv* env, jclass /* clazz */,
                                      jbyteArray data) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> state((size_t)len);
    env->GetByteArrayRegion(data, 0, len,
                            reinterpret_cast<jbyte*>(state.data()));
    if (!retro_unserialize(state.data(), state.size())) {
        setError("State load failed");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static void* memoryRegion(unsigned id, size_t* sizeOut) {
    void* data = retro_get_memory_data(id);
    size_t size = retro_get_memory_size(id);
    if (!data || size == 0) {
        return nullptr;
    }
    *sizeOut = size;
    return data;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_dot_Bridge_nativeSaveRamSize(JNIEnv* /* env */,
                                      jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return 0;
    }
    size_t size = 0;
    if (!memoryRegion(RETRO_MEMORY_SAVE_RAM, &size)) {
        return 0;
    }
    return (jint)size;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeReadSaveRam(JNIEnv* env, jclass /* clazz */,
                                      jbyteArray data) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return JNI_FALSE;
    }
    size_t size = 0;
    void* region = memoryRegion(RETRO_MEMORY_SAVE_RAM, &size);
    if (!region) {
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(data);
    if (len < (jsize)size) {
        return JNI_FALSE;
    }
    env->SetByteArrayRegion(data, 0, (jsize)size,
                            static_cast<const jbyte*>(region));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeWriteSaveRam(JNIEnv* env, jclass /* clazz */,
                                       jbyteArray data) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return JNI_FALSE;
    }
    size_t size = 0;
    void* region = memoryRegion(RETRO_MEMORY_SAVE_RAM, &size);
    if (!region) {
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(data);
    if (len < (jsize)size) {
        return JNI_FALSE;
    }
    env->GetByteArrayRegion(data, 0, (jsize)size,
                            static_cast<jbyte*>(region));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_dot_Bridge_nativeRtcSize(JNIEnv* /* env */, jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return 0;
    }
    size_t size = 0;
    if (!memoryRegion(RETRO_MEMORY_RTC, &size)) {
        return 0;
    }
    return (jint)size;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeReadRtc(JNIEnv* env, jclass /* clazz */,
                                  jbyteArray data) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return JNI_FALSE;
    }
    size_t size = 0;
    void* region = memoryRegion(RETRO_MEMORY_RTC, &size);
    if (!region) {
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(data);
    if (len < (jsize)size) {
        return JNI_FALSE;
    }
    env->SetByteArrayRegion(data, 0, (jsize)size,
                            static_cast<const jbyte*>(region));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeWriteRtc(JNIEnv* env, jclass /* clazz */,
                                   jbyteArray data) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return JNI_FALSE;
    }
    size_t size = 0;
    void* region = memoryRegion(RETRO_MEMORY_RTC, &size);
    if (!region) {
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(data);
    if (len < (jsize)size) {
        return JNI_FALSE;
    }
    env->GetByteArrayRegion(data, 0, (jsize)size,
                            static_cast<jbyte*>(region));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_dot_Bridge_nativeReadVram(JNIEnv* env, jclass /* clazz */,
                                   jbyteArray data) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreLive || !g_gameLoaded) {
        return JNI_FALSE;
    }
    size_t size = 0;
    void* region = memoryRegion(RETRO_MEMORY_VIDEO_RAM, &size);
    if (!region || size < 8192) {
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(data);
    if (len < 8192) {
        return JNI_FALSE;
    }
    env->SetByteArrayRegion(data, 0, 8192,
                            static_cast<const jbyte*>(region));
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_dot_Bridge_nativeUnloadGame(JNIEnv* /* env */,
                                     jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_gameLoaded) {
        retro_unload_game();
        g_gameLoaded = false;
    }
    {
        std::lock_guard<std::mutex> frameLock(g_frameMutex);
        g_frameReady = false;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_dot_Bridge_nativeDeinit(JNIEnv* /* env */, jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_gameLoaded) {
        retro_unload_game();
        g_gameLoaded = false;
    }
    if (g_coreLive) {
        retro_deinit();
        g_coreLive = false;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_dot_Bridge_nativeLastError(JNIEnv* env, jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(g_errorMutex);
    return env->NewStringUTF(g_lastError.c_str());
}
