package com.dvstrip.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionTest {

    private fun dv(has: Boolean, profile: Int?) =
        DvInfo(has, profile, 60.0, "hevc", "matroska", 1_000_000_000L)

    @Test
    fun `no dolby vision forwards untouched`() {
        assertEquals(DvAction.FORWARD, Decision.actionFor(dv(false, null)))
    }

    @Test
    fun `profile 7 and 8 strip`() {
        assertEquals(DvAction.STRIP, Decision.actionFor(dv(true, 7)))
        assertEquals(DvAction.STRIP, Decision.actionFor(dv(true, 8)))
    }

    @Test
    fun `unknown profile with dv strips (best effort)`() {
        assertEquals(DvAction.STRIP, Decision.actionFor(dv(true, null)))
    }

    @Test
    fun `profile 5 warns`() {
        assertEquals(DvAction.WARN_P5, Decision.actionFor(dv(true, 5)))
    }

    private val GB = 1024L * 1024 * 1024

    @Test
    fun `local file with enough space uses temp file`() {
        assertEquals(
            StripMode.TEMP_FILE,
            Decision.stripMode(isLocalFile = true, sizeBytes = 2 * GB, freeBytes = 4 * GB, alwaysProxy = false)
        )
    }

    @Test
    fun `local file with insufficient space uses proxy`() {
        // free must exceed size + 500MB margin
        assertEquals(
            StripMode.PROXY,
            Decision.stripMode(isLocalFile = true, sizeBytes = 2 * GB, freeBytes = 2 * GB + 100, alwaysProxy = false)
        )
    }

    @Test
    fun `unknown size uses proxy`() {
        assertEquals(
            StripMode.PROXY,
            Decision.stripMode(isLocalFile = true, sizeBytes = null, freeBytes = 50 * GB, alwaysProxy = false)
        )
    }

    @Test
    fun `remote source always proxies`() {
        assertEquals(
            StripMode.PROXY,
            Decision.stripMode(isLocalFile = false, sizeBytes = 1 * GB, freeBytes = 50 * GB, alwaysProxy = false)
        )
    }

    @Test
    fun `alwaysProxy preference forces proxy`() {
        assertEquals(
            StripMode.PROXY,
            Decision.stripMode(isLocalFile = true, sizeBytes = 1 * GB, freeBytes = 50 * GB, alwaysProxy = true)
        )
    }
}
