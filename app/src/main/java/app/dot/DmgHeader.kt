package app.dot

// Dot — DMG header validation, pure Kotlin, no Android dependency.
//
// The header facts are the plan's (§1C): the Nintendo logo is 48
// bytes at 0x104-0x133 (bytes verified against the mGBA core's own
// logo table in src/gb/gb.c), the CGB flag is at 0x143, the header
// checksum is at 0x14D and covers 0x134-0x14C as
// x = 0; for each byte a: x = (x - rom[a] - 1) AND 0xFF.
// The global checksum at 0x14E-0x14F is NOT checked by the console
// and is not checked here.
//
// Verdicts, per the plan's DMG ownership decision: a grey cart
// (0x00) is accepted; a black dual-mode cart (0x80) is accepted in
// DMG mode; a colour-only cart (0xC0) is refused plainly and pointed
// at the GBC app. Every refusal names the failing bytes, so a bad
// validator is diagnosable instead of silent (the Atomic alpha.1-5
// lesson).
// Copyright (c) 2026 Dot contributors. All rights reserved.

object DmgHeader {

    const val LOGO_OFFSET = 0x104
    const val LOGO_LENGTH = 48
    const val TITLE_OFFSET = 0x134
    const val TITLE_LENGTH = 16
    const val CGB_FLAG_OFFSET = 0x143
    const val HEADER_CHECKSUM_OFFSET = 0x14D
    const val CHECKSUM_FIRST = 0x134
    const val CHECKSUM_LAST = 0x14C
    const val MIN_SIZE = 0x150
    const val MAX_SIZE = 8 * 1024 * 1024

    const val CGB_FLAG_DMG_ONLY = 0x00
    const val CGB_FLAG_DUAL_MODE = 0x80
    const val CGB_FLAG_COLOR_ONLY = 0xC0

    /** The Nintendo logo, exactly as the console checks it. */
    val NINTENDO_LOGO = byteArrayOf(
        0xCE.toByte(), 0xED.toByte(), 0x66, 0x66, 0xCC.toByte(),
        0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83.toByte(),
        0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F,
        0x88.toByte(), 0x89.toByte(), 0x00, 0x0E, 0xDC.toByte(),
        0xCC.toByte(), 0x6E, 0xE6.toByte(), 0xDD.toByte(),
        0xDD.toByte(), 0xD9.toByte(), 0x99.toByte(), 0xBB.toByte(),
        0xBB.toByte(), 0x67, 0x63, 0x6E, 0x0E, 0xEC.toByte(),
        0xCC.toByte(), 0xDD.toByte(), 0xDC.toByte(), 0x99.toByte(),
        0x9F.toByte(), 0xBB.toByte(), 0xB9.toByte(), 0x33, 0x3E,
    )

    sealed interface Verdict {
        data class Accepted(val title: String, val dualMode: Boolean) : Verdict
        data class Refused(val reason: String) : Verdict
    }

    fun validate(bytes: ByteArray): Verdict {
        if (bytes.size < MIN_SIZE) {
            return Verdict.Refused(
                "File is ${bytes.size} bytes, smaller than a Game Boy " +
                    "header ($MIN_SIZE bytes) — not a Game Boy ROM.",
            )
        }
        if (bytes.size > MAX_SIZE) {
            return Verdict.Refused(
                "File is ${bytes.size} bytes, over the ${MAX_SIZE / 1024 / 1024} MB " +
                    "import cap — Game Boy ROMs are 8 MB at most.",
            )
        }
        for (i in NINTENDO_LOGO.indices) {
            if (bytes[LOGO_OFFSET + i] != NINTENDO_LOGO[i]) {
                return Verdict.Refused(
                    "Nintendo logo mismatch at header offset " +
                        "0x${(LOGO_OFFSET + i).toString(16)}: " +
                        "expected 0x${(NINTENDO_LOGO[i].toInt() and 0xFF).toString(16)}, " +
                        "found 0x${(bytes[LOGO_OFFSET + i].toInt() and 0xFF).toString(16)} — " +
                        "not a Game Boy ROM.",
                )
            }
        }
        val cgbFlag = bytes[CGB_FLAG_OFFSET].toInt() and 0xFF
        if (cgbFlag == CGB_FLAG_COLOR_ONLY) {
            return Verdict.Refused(
                "Game Boy Color only (CGB flag 0xC0) — Dot plays original " +
                    "Game Boy games only. Open this game in the Game Boy " +
                    "Color app instead.",
            )
        }
        if (cgbFlag != CGB_FLAG_DMG_ONLY && cgbFlag != CGB_FLAG_DUAL_MODE) {
            return Verdict.Refused(
                "Unknown CGB flag 0x${cgbFlag.toString(16)} at header " +
                    "offset 0x143 — expected 0x00 (Game Boy) or 0x80 " +
                    "(dual-mode).",
            )
        }
        var checksum = 0
        for (a in CHECKSUM_FIRST..CHECKSUM_LAST) {
            checksum = (checksum - (bytes[a].toInt() and 0xFF) - 1) and 0xFF
        }
        val stored = bytes[HEADER_CHECKSUM_OFFSET].toInt() and 0xFF
        if (checksum != stored) {
            return Verdict.Refused(
                "Header checksum mismatch: computed " +
                    "0x${checksum.toString(16)}, stored " +
                    "0x${stored.toString(16)} at 0x14D — the header is " +
                    "corrupt.",
            )
        }
        val title = bytes
            .slice(TITLE_OFFSET until TITLE_OFFSET + TITLE_LENGTH)
            .map { (it.toInt() and 0xFF).toChar() }
            .joinToString("")
            .trim { it == '\u0000' || it == ' ' }
        return Verdict.Accepted(title = title, dualMode = cgbFlag == CGB_FLAG_DUAL_MODE)
    }
}
