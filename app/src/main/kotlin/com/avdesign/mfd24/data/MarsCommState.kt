// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import com.avdesign.mfd24.astro.DteWindows

/**
 * The comm windows as the renderer reads them: absolute epoch millis, never angles, for the same
 * reason the duty arc is stored that way — a zone glide or a dial-top flip changes every angle
 * on the face, and instants mapped per frame follow it for free.
 *
 * Written by [MarsCommRepository] on its own threads and read by the render thread, so it
 * follows [TelemetryState]'s discipline: a version counter the renderer compares each frame,
 * and copy methods that hand over the arrays under one short lock only when the version moved.
 * `render()` itself never takes the lock — it works from its own copies.
 */
class MarsCommState {

    /** Bumped by every publication; the renderer re-copies only when it moves. */
    @Volatile
    var version: Int = 0
        private set

    /**
     * Whether the relay windows on file reach past now. False until ephemerides first load and
     * false again once they age out — the dial then drops the outer line and prints
     * `NO EPHEMERIS`, because an empty line that could as well mean "no passes today" is the
     * kind of quiet failure an operator learns to distrust.
     */
    @Volatile
    var relayValid: Boolean = false
        private set

    /**
     * The twilight shoulder around the stored daylight, written with it at every refresh. Two
     * volatiles rather than a versioned pair: they move once a sol by seconds, and a frame that
     * reads one old edge draws a shoulder one frame stale, which no eye can catch.
     */
    @Volatile
    var twilightStartMillis: Long = 0L
        private set

    @Volatile
    var twilightEndMillis: Long = 0L
        private set

    fun setTwilight(startMillis: Long, endMillis: Long) {
        twilightStartMillis = startMillis
        twilightEndMillis = endMillis
    }

    private var dteCount = 0
    private val dteStartMillis = LongArray(DteWindows.MAX)
    private val dteEndMillis = LongArray(DteWindows.MAX)

    private var relayCount = 0
    private val relayStartMillis = LongArray(MAX_RELAY_WINDOWS)
    private val relayEndMillis = LongArray(MAX_RELAY_WINDOWS)

    /** Publishes a fresh direct-to-Earth set; always-down publishes the honest zero windows. */
    @Synchronized
    fun setDte(windows: DteWindows) {
        dteCount = windows.count
        System.arraycopy(windows.startMillis, 0, dteStartMillis, 0, windows.count)
        System.arraycopy(windows.endMillis, 0, dteEndMillis, 0, windows.count)
        version++
    }

    /** Publishes the relay union; [valid] false clears the line and raises the notice. */
    @Synchronized
    fun setRelay(startMillis: LongArray, endMillis: LongArray, count: Int, valid: Boolean) {
        val n = if (count > MAX_RELAY_WINDOWS) MAX_RELAY_WINDOWS else count
        relayCount = n
        System.arraycopy(startMillis, 0, relayStartMillis, 0, n)
        System.arraycopy(endMillis, 0, relayEndMillis, 0, n)
        relayValid = valid
        version++
    }

    /** Copies the DTE windows into the caller's arrays; returns the count. */
    @Synchronized
    fun copyDte(outStart: LongArray, outEnd: LongArray): Int {
        System.arraycopy(dteStartMillis, 0, outStart, 0, dteCount)
        System.arraycopy(dteEndMillis, 0, outEnd, 0, dteCount)
        return dteCount
    }

    /** Copies the relay windows into the caller's arrays; returns the count. */
    @Synchronized
    fun copyRelay(outStart: LongArray, outEnd: LongArray): Int {
        System.arraycopy(relayStartMillis, 0, outStart, 0, relayCount)
        System.arraycopy(relayEndMillis, 0, outEnd, 0, relayCount)
        return relayCount
    }

    companion object {
        /**
         * Four orbiters, each rising a handful of times per sol above the shared threshold —
         * a generous ceiling, and the union only shrinks the count.
         */
        const val MAX_RELAY_WINDOWS: Int = 64
    }
}
