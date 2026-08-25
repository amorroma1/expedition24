// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.update

import android.util.Log
import com.avdesign.mfd24.BuildConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub what the latest release of this face is.
 *
 * The releases page is the product's only distribution channel — there is no store listing on
 * purpose (the README says why) — so "is there an update" has one authoritative answer: the
 * repository's release list. The *list*, not `releases/latest`: GitHub's "latest" is one release
 * per repository, and with two faces publishing, whichever shipped most recently would hide the
 * other's newest from its own updater for good. One request, no token, no library: the same bare
 * `HttpURLConnection` + platform `org.json` shape as `MetarClient` and `OpenMeteoClient`.
 *
 * The faces share the repository but not the releases: each flavor carries its tag prefix and
 * asset name in `BuildConfig`, filters the list to its own, and takes the highest version — by
 * numeric compare, not list order, because a re-published release floats on `created_at`. The
 * Earth face therefore never offers itself a Mars build, and neither face can eclipse the other.
 */
object ReleaseCheck {

    const val REPO: String = "amorroma1/expedition24"
    const val REPO_URL: String = "https://github.com/$REPO"

    /** One release, reduced to what a watch can use.
     *
     * The asset fields are kept even though nothing downloads: their *presence* is how a release
     * is known to be real and to belong to this face. A release with no APK for this flavour is an
     * authoring mistake, and [parse] declines it rather than announcing it.
     */
    data class Release(
        /** Bare version, tag prefix stripped: `2.6.0`. */
        val version: String,
        /** Direct download URL of this flavour's APK asset. */
        val assetUrl: String,
        /** Asset size in bytes. */
        val assetBytes: Long,
    )

    /**
     * The human page for a release, or for whatever is newest when the version is not known.
     *
     * This is what the watch points a camera at. The notes, the checksum and the APK are all on
     * that page, in a browser, at a size a person can read — which is why none of them are laid out
     * on the watch any more.
     */
    fun releasePageUrl(version: String?): String =
        if (version.isNullOrEmpty()) {
            "$REPO_URL/releases/latest"
        } else {
            "$REPO_URL/releases/tag/" + BuildConfig.UPDATE_TAG_PREFIX + version
        }

    /**
     * Blocking; call from a background dispatcher. Null on any failure — network, rate limit,
     * malformed body, or a latest release that is not this face's. The caller must keep "cannot
     * know" apart from "up to date": null is never the latter.
     */
    fun latest(url: String = RELEASES_URL): Release? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                // api.github.com refuses requests without a User-Agent.
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "release check got HTTP ${connection.responseCode}")
                return null
            }
            parse(
                connection.inputStream.bufferedReader().use { it.readText() },
                BuildConfig.UPDATE_TAG_PREFIX,
                BuildConfig.UPDATE_ASSET,
            )
        } catch (e: IOException) {
            Log.w(TAG, "release check failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Pure parse of a release-list payload: this flavor's newest installable release, or null
     * when the list holds none. A release counts only when its tag carries [tagPrefix], it is
     * neither a draft nor a prerelease, and the expected [assetName] is attached — one with no
     * APK for this flavour is an authoring mistake, skipped with a log rather than installed
     * around *or* allowed to eclipse the installable release beneath it. Among the survivors the
     * highest version wins, compared numerically: the list arrives ordered by creation, and a
     * release re-published to fix its notes would otherwise outrank everything after it.
     */
    fun parse(json: String, tagPrefix: String, assetName: String): Release? {
        return try {
            val releases = JSONArray(json)
            var best: Release? = null
            for (i in 0 until releases.length()) {
                val candidate = parseOne(releases.getJSONObject(i), tagPrefix, assetName)
                    ?: continue
                if (best == null || isNewer(candidate.version, best.version)) best = candidate
            }
            best
        } catch (e: JSONException) {
            Log.w(TAG, "release payload did not parse", e)
            null
        }
    }

    private fun parseOne(root: JSONObject, tagPrefix: String, assetName: String): Release? {
        val tag = root.optString("tag_name")
        if (!tag.startsWith(tagPrefix)) return null
        val version = tag.removePrefix(tagPrefix)
        if (version.isEmpty()) return null
        // What is left has to be a plain version. The Earth face's prefix is a bare `v`, which is
        // also the first letter of `vital-v0.1.0`: without this the Vital release would arrive
        // here as a version of "ital-v0.1.0", and only the asset name would be keeping the two
        // faces apart. Two locks on a door that opens onto an install prompt.
        if (!version.all { it.isDigit() || it == '.' }) return null
        // A draft is not published and a prerelease is not offered to a wrist: both exist so a
        // release can be staged without every watch in the field announcing it mid-authoring.
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") != assetName) continue
            val url = asset.optString("browser_download_url")
            val size = asset.optLong("size")
            if (url.isEmpty() || size <= 0L) break
            return Release(version, url, size)
        }
        Log.w(TAG, "release $tag carries no usable $assetName; skipped")
        return null
    }

    /**
     * True when [candidate] is strictly newer than [installed]. Numeric by dot-component, so
     * `2.10.0` beats `2.9.1` — a string compare would sort them the other way, and that is the
     * failure mode this function exists to prevent. Missing components count as zero; a component
     * that is not a number counts as zero too, which makes any exotic tag compare older rather
     * than newer — the safe direction for something that ends in an install prompt.
     */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.')
        val b = installed.split('.')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /**
     * What may be offered, given what the last check found and what is installed now.
     *
     * Null unless [stored] is strictly newer than [installed]. This exists because a stored answer
     * outlives the question: the check runs once a day and never during a watch, so a build
     * installed by hand in between leaves the old finding on file — and the settings chip then
     * announced `UPDATE AVAILABLE` for a version *older* than the one running, pointing a QR code
     * at a release the wearer had already passed. Seen on the watch at 2.6.0 offering 2.5.1.
     *
     * Deciding it here rather than trusting the store means it self-corrects on the next read: no
     * network, no waiting for the daily slot, and no state that can disagree with the APK it is
     * running inside.
     */
    fun offerable(stored: String?, installed: String): String? =
        if (!stored.isNullOrEmpty() && isNewer(stored, installed)) stored else null

    private const val TAG = "ReleaseCheck"

    /**
     * Fifteen covers years of the standing policy — one release object per face, superseded in
     * place — while staying one page even if history is ever allowed to accumulate.
     */
    private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=15"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "MFD-24 watch face (github.com/$REPO)"
}
