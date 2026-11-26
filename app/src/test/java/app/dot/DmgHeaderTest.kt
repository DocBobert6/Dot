package app.dot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Dot — DMG header validator tests. Synthetic ROMs are built here
// with an independent copy of the checksum rule, so the validator
// is checked against the rule as stated in the plan (§1C), not
// against itself.
// Copyright (c) 2026 Dot contributors. All rights reserved.

class DmgHeaderTest {

    private fun buildRom(
        cgbFlag: Int = 0x00,
        logoOk: Boolean = true,
        checksumOk: Boolean = true,
        size: Int = 0x8000,
        title: String = "TEST GAME",
    ): ByteArray {
        val rom = ByteArray(size)
        if (logoOk) {
            DmgHeader.NINTENDO_LOGO.copyInto(rom, DmgHeader.LOGO_OFFSET)
        }
        val titleBytes = title.toByteArray(Charsets.US_ASCII)
        titleBytes.copyInto(rom, DmgHeader.TITLE_OFFSET)
        rom[DmgHeader.CGB_FLAG_OFFSET] = cgbFlag.toByte()
        var checksum = 0
        for (a in DmgHeader.CHECKSUM_FIRST..DmgHeader.CHECKSUM_LAST) {
            checksum = (checksum - (rom[a].toInt() and 0xFF) - 1) and 0xFF
        }
        rom[DmgHeader.HEADER_CHECKSUM_OFFSET] =
            (if (checksumOk) checksum else (checksum + 1) and 0xFF).toByte()
        return rom
    }

    @Test
    fun validDmgGameIsAccepted() {
        val verdict = DmgHeader.validate(buildRom())
        assertTrue(verdict is DmgHeader.Verdict.Accepted)
        val accepted = verdict as DmgHeader.Verdict.Accepted
        assertEquals("TEST GAME", accepted.title)
        assertEquals(false, accepted.dualMode)
    }

    @Test
    fun dualModeGameIsAcceptedInDmgMode() {
        val verdict = DmgHeader.validate(buildRom(cgbFlag = 0x80))
        assertTrue(verdict is DmgHeader.Verdict.Accepted)
        val accepted = verdict as DmgHeader.Verdict.Accepted
        assertEquals(true, accepted.dualMode)
    }

    @Test
    fun colorOnlyGameIsRefusedPlainly() {
        val verdict = DmgHeader.validate(buildRom(cgbFlag = 0xC0))
        assertTrue(verdict is DmgHeader.Verdict.Refused)
        val refused = verdict as DmgHeader.Verdict.Refused
        assertTrue(refused.reason.contains("Game Boy Color only"))
    }

    @Test
    fun badLogoIsRefusedWithOffset() {
        val verdict = DmgHeader.validate(buildRom(logoOk = false))
        assertTrue(verdict is DmgHeader.Verdict.Refused)
        val refused = verdict as DmgHeader.Verdict.Refused
        assertTrue(refused.reason.contains("logo mismatch"))
        assertTrue(refused.reason.contains("0x104"))
    }

    @Test
    fun badChecksumIsRefusedWithValues() {
        val verdict = DmgHeader.validate(buildRom(checksumOk = false))
        assertTrue(verdict is DmgHeader.Verdict.Refused)
        val refused = verdict as DmgHeader.Verdict.Refused
        assertTrue(refused.reason.contains("checksum mismatch"))
    }

    @Test
    fun tooSmallIsRefused() {
        val verdict = DmgHeader.validate(ByteArray(0x100))
        assertTrue(verdict is DmgHeader.Verdict.Refused)
        val refused = verdict as DmgHeader.Verdict.Refused
        assertTrue(refused.reason.contains("smaller than"))
    }

    @Test
    fun tooLargeIsRefused() {
        val verdict = DmgHeader.validate(ByteArray(DmgHeader.MAX_SIZE + 1))
        assertTrue(verdict is DmgHeader.Verdict.Refused)
        val refused = verdict as DmgHeader.Verdict.Refused
        assertTrue(refused.reason.contains("over the 8 MB"))
    }

    @Test
    fun titleTrailingPaddingIsTrimmed() {
        val verdict = DmgHeader.validate(buildRom(title = "TETRIS   "))
        assertTrue(verdict is DmgHeader.Verdict.Accepted)
        assertEquals("TETRIS", (verdict as DmgHeader.Verdict.Accepted).title)
    }
}
