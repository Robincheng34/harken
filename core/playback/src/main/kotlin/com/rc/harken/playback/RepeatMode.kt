// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.playback

/** 列表循环模式：OFF → ALL → ONE → OFF（iOS `RepeatMode`）。 */
enum class RepeatMode {
    OFF,
    ALL,
    ONE,
    ;

    /** The next mode in the cycle (iOS `RepeatMode.next`). */
    val next: RepeatMode
        get() = when (this) {
            OFF -> ALL
            ALL -> ONE
            ONE -> OFF
        }
}
