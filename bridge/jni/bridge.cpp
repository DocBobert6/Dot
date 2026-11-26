// Dot — JNI bridge to the mGBA libretro core, Phase 0.
//
// One function only: the libretro API version of the linked core.
// It proves the core .so links and the JNI plumbing works, with no
// emulator state involved — retro_api_version() is a pure constant
// read. The player-facing bridge (load game, run frame, audio,
// input, save states) lands in Phase 1.
//
// SPDX-License-Identifier: GPL-3.0-or-later

#include <jni.h>

#include "libretro.h"

extern "C" JNIEXPORT jint JNICALL
Java_app_dot_Bridge_nativeApiVersion(JNIEnv* /* env */, jclass /* clazz */) {
    return retro_api_version();
}
