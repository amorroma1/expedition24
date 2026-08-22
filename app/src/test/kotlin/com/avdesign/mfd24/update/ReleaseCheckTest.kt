// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update decision, pinned.
 *
 * Three ways this ends in the wrong install prompt: a string version compare (`2.10.0` sorts
 * below `2.9.1` and the face never sees the update), a parse that takes the wrong flavor's asset
 * (an Earth watch offered a Mars build the platform would refuse *after* the download), and a
 * release with no APK treated as installable. The payload here is the real shape of GitHub's
 * `releases/latest` response, trimmed to the fields the parser is allowed to rely on.
 */
class ReleaseCheckTest {

    // The real v2.1.0 response, reduced: same field names, same nesting, same value shapes.
    private val payload = """
        {
          "tag_name": "v2.1.0",
          "name": "MFD-24 2.1.0 — the lunar mark",
          "body": "**The moon joins the compass.**\n\n| a | b |\n|---|---|\n![shot](https://x/y.png)\nSee the [README](https://github.com/amorroma1/expedition24) for more.",
          "assets": [
            { "name": "app-earth-release.apk",
              "size": 3335888,
              "browser_download_url": "https://github.com/amorroma1/expedition24/releases/download/v2.1.0/app-earth-release.apk" }
          ]
        }
    """.trimIndent()

    @Test
    fun `parse finds this flavor's asset and strips the tag prefix`() {
        val release = ReleaseCheck.parse(payload, "v", "app-earth-release.apk")!!
        assertEquals("2.1.0", release.version)
        assertEquals(3_335_888L, release.assetBytes)
        assertTrue(release.assetUrl.endsWith("app-earth-release.apk"))
    }

    @Test
    fun `another face's tag prefix declines the release`() {
        // A latest release that is Earth's must be invisible to the Mars face, and vice versa —
        // the platform would refuse the cross-install anyway, but only after a wasted download.
        assertNull(ReleaseCheck.parse(payload, "other-v", "app-other-release.apk"))
    }

    @Test
    fun `a release without this flavor's APK is not installable`() {
        assertNull(ReleaseCheck.parse(payload, "v", "app-moon-release.apk"))
    }

    @Test
    fun `versions compare numerically by component, not as strings`() {
        assertTrue(ReleaseCheck.isNewer("2.10.0", "2.9.1"))
        assertTrue(ReleaseCheck.isNewer("2.2.0", "2.1.0"))
        assertTrue(ReleaseCheck.isNewer("3.0", "2.9.9"))
        assertTrue(ReleaseCheck.isNewer("2.1.1", "2.1.0"))
        assertFalse(ReleaseCheck.isNewer("2.1.0", "2.1.0"))
        assertFalse(ReleaseCheck.isNewer("2.1.0", "2.2.0"))
        // An exotic tag must compare older, never newer: the failure direction of a bad tag is a
        // spurious install prompt.
        assertFalse(ReleaseCheck.isNewer("nightly", "2.1.0"))
    }


    @Test
    fun `a finding older than the running build is not offered`() {
        // The defect this pins, seen on the watch: 2.6.0 installed by hand, the last check's
        // finding of 2.5.1 still on file, and the settings chip announcing UPDATE AVAILABLE with a
        // QR pointing at a release the wearer had already passed. A stored answer outlives its
        // question — the check runs once a day and never during a watch — so the answer has to be
        // re-tested against the build asking.
        assertNull(ReleaseCheck.offerable("2.5.1", "2.6.0"))
        assertNull(ReleaseCheck.offerable("2.6.0", "2.6.0"))
        assertNull(ReleaseCheck.offerable(null, "2.6.0"))
        assertNull(ReleaseCheck.offerable("", "2.6.0"))
        assertEquals("2.7.0", ReleaseCheck.offerable("2.7.0", "2.6.0"))
        assertEquals("2.10.0", ReleaseCheck.offerable("2.10.0", "2.9.1"))
    }
}
