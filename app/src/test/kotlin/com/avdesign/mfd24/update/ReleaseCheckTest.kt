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
 * Four ways this ends in the wrong install prompt: a string version compare (`2.10.0` sorts
 * below `2.9.1` and the face never sees the update), a parse that takes the wrong flavor's
 * asset (an Earth watch offered a Mars build the platform would refuse *after* the download),
 * a release with no APK treated as installable, and — the reason the check reads the release
 * *list* — GitHub's repo-global `latest`, which would let whichever face shipped most recently
 * hide the other's newest from its own updater for good. The payloads here are the real shape
 * of GitHub's `releases` response, trimmed to the fields the parser is allowed to rely on.
 */
class ReleaseCheckTest {

    private fun release(
        tag: String,
        asset: String,
        size: Long = 3_335_888L,
        flags: String = "",
    ) = """
        {
          "tag_name": "$tag",
          "name": "MFD-24 $tag",
          $flags
          "body": "notes",
          "assets": [
            { "name": "$asset",
              "size": $size,
              "browser_download_url": "https://github.com/amorroma1/expedition24/releases/download/$tag/$asset" }
          ]
        }
    """.trimIndent()

    /** Both faces published, Mars most recently — the exact case `releases/latest` got wrong. */
    private val bothFaces = """
        [
        ${release("mars-v0.1.0", "app-mars-release.apk")},
        ${release("v2.7.0", "app-earth-release.apk")}
        ]
    """.trimIndent()

    @Test
    fun `each face finds its own newest, whatever shipped last`() {
        val earth = ReleaseCheck.parse(bothFaces, "v", "app-earth-release.apk")!!
        assertEquals("2.7.0", earth.version)
        assertTrue(earth.assetUrl.endsWith("app-earth-release.apk"))
        assertEquals(3_335_888L, earth.assetBytes)

        val mars = ReleaseCheck.parse(bothFaces, "mars-v", "app-mars-release.apk")!!
        assertEquals("0.1.0", mars.version)
        assertTrue(mars.assetUrl.endsWith("app-mars-release.apk"))
    }

    @Test
    fun `another face's releases are invisible, in both directions`() {
        // The direction that looks dangerous is safe by construction:
        // "mars-v0.1.0".startsWith("v") is false.
        val earthOnly = "[${release("v2.7.0", "app-earth-release.apk")}]"
        val marsOnly = "[${release("mars-v0.1.0", "app-mars-release.apk")}]"
        // And the crossing that is not obvious: "vital-v0.1.0".startsWith("v") *is* true, so the
        // Earth face has to refuse it on the version it would be left holding as well as on the
        // asset. A wearer offered a different face's build is offered a different app.
        val vitalOnly = "[${release("vital-v0.1.0", "app-vital-release.apk")}]"
        assertNull(ReleaseCheck.parse(vitalOnly, "v", "app-earth-release.apk"))
        assertNull(ReleaseCheck.parse(vitalOnly, "v", "app-vital-release.apk"))
        assertEquals(
            "0.1.0",
            ReleaseCheck.parse(vitalOnly, "vital-v", "app-vital-release.apk")!!.version,
        )
        assertNull(ReleaseCheck.parse(earthOnly, "mars-v", "app-mars-release.apk"))
        assertNull(ReleaseCheck.parse(marsOnly, "v", "app-earth-release.apk"))
    }

    @Test
    fun `the highest version wins, not the newest listing`() {
        // GitHub orders by creation, and a release re-published to fix its notes floats to the
        // top — the compare has to be numeric by component, or 2.10.0 loses to 2.9.1 twice over.
        val reordered = """
            [
            ${release("v2.9.1", "app-earth-release.apk")},
            ${release("v2.10.0", "app-earth-release.apk")}
            ]
        """.trimIndent()
        assertEquals("2.10.0", ReleaseCheck.parse(reordered, "v", "app-earth-release.apk")!!.version)
    }

    @Test
    fun `a broken release is skipped, never installed around or allowed to eclipse`() {
        // The newest tag carries no APK for this face: an authoring mistake. It must not be
        // announced — and it must not hide the installable release beneath it either.
        val brokenNewest = """
            [
            ${release("v2.8.0", "wrong-name.apk")},
            ${release("v2.7.0", "app-earth-release.apk")}
            ]
        """.trimIndent()
        assertEquals("2.7.0", ReleaseCheck.parse(brokenNewest, "v", "app-earth-release.apk")!!.version)
        // Alone, it is simply not an answer.
        val onlyBroken = "[${release("v2.8.0", "wrong-name.apk")}]"
        assertNull(ReleaseCheck.parse(onlyBroken, "v", "app-earth-release.apk"))
    }

    @Test
    fun `drafts and prereleases are not offered to a wrist`() {
        val staged = """
            [
            ${release("v2.8.0", "app-earth-release.apk", flags = "\"prerelease\": true,")},
            ${release("v2.9.0", "app-earth-release.apk", flags = "\"draft\": true,")},
            ${release("v2.7.0", "app-earth-release.apk")}
            ]
        """.trimIndent()
        assertEquals("2.7.0", ReleaseCheck.parse(staged, "v", "app-earth-release.apk")!!.version)
    }

    @Test
    fun `an empty list and a malformed body are cannot-know, not up-to-date`() {
        assertNull(ReleaseCheck.parse("[]", "v", "app-earth-release.apk"))
        assertNull(ReleaseCheck.parse("{\"message\": \"rate limited\"}", "v", "app-earth-release.apk"))
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
