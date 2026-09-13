package com.dvstrip.player

enum class DvAction { FORWARD, STRIP, WARN_P5 }
enum class StripMode { TEMP_FILE, PROXY }

object Decision {

    /** Safety margin kept free on top of the expected output size. */
    private const val FREE_SPACE_MARGIN = 500L * 1024 * 1024

    fun actionFor(dv: DvInfo): DvAction = when {
        !dv.hasDolbyVision -> DvAction.FORWARD
        dv.profile == 5 -> DvAction.WARN_P5
        else -> DvAction.STRIP
    }

    fun stripMode(
        isLocalFile: Boolean,
        sizeBytes: Long?,
        freeBytes: Long,
        alwaysProxy: Boolean
    ): StripMode = when {
        alwaysProxy -> StripMode.PROXY
        !isLocalFile -> StripMode.PROXY
        sizeBytes == null -> StripMode.PROXY
        freeBytes > sizeBytes + FREE_SPACE_MARGIN -> StripMode.TEMP_FILE
        else -> StripMode.PROXY
    }
}
