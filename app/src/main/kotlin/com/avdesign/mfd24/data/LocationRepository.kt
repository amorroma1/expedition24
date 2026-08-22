// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Position fixes for the weather lookup and the 5 km site lock.
 *
 * Deliberately built on the framework [LocationManager] rather than on Play Services: it removes a
 * dependency, works on watches without an up-to-date Play Services, and gives direct control over
 * how often the GPS is woken. The brief asks for minimal background work, so nothing here holds a
 * continuous location subscription — a cached fix is used whenever one is fresh enough, and a
 * single-shot request is made only when it is not.
 */
class LocationRepository(private val context: Context) {

    private val manager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    fun hasPermission(): Boolean = fineGranted() || coarseGranted()

    private fun fineGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun coarseGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * True when the app may use location while it is not in the foreground.
     *
     * A watch face is a `WallpaperService` and the refresh worker is a background job, so on
     * API 29+ neither counts as foreground use: without this the platform hands back nulls rather
     * than an error, which looks exactly like "no signal".
     */
    fun hasBackgroundPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Our preferred providers first, then anything else the device reports. Order decides which
     * one an active request goes to; [lastKnown] asks all of them regardless.
     */
    private fun candidateProviders(lm: LocationManager): List<String> {
        val extras = try {
            lm.allProviders.filter { it !in PROVIDERS }
        } catch (e: Exception) {
            emptyList()
        }
        return if (extras.isEmpty()) PROVIDERS else PROVIDERS + extras
    }

    /** One line naming every provider, whether it exists, and whether it is switched on. */
    fun describeProviders(): String {
        val lm = manager ?: return "no LocationManager"
        val builder = StringBuilder()
        for (provider in candidateProviders(lm)) {
            val state = try {
                if (lm.isProviderEnabled(provider)) "on" else "off"
            } catch (e: IllegalArgumentException) {
                "absent"
            } catch (e: SecurityException) {
                "denied"
            }
            if (builder.isNotEmpty()) builder.append(", ")
            builder.append(provider).append('=').append(state)
        }
        return builder.toString()
    }

    /**
     * Freshest cached fix from any provider that has one, or null. Never wakes the GPS.
     *
     * Deliberately does **not** gate on `isProviderEnabled`. On Wear OS the legacy
     * `location_providers_allowed` setting can list `gps` alone while the fused provider is very
     * much alive and holding a good fix — skipping it on that basis left the watch face reading
     * `NO FIX` indoors with a 35-metre position sitting in the system. Asking a provider that has
     * nothing costs a null; asking none of them costs the feature.
     */
    fun lastKnown(): Location? {
        val lm = manager ?: return null
        if (!hasPermission()) return null
        var best: Location? = null
        var bestProvider: String? = null
        for (provider in candidateProviders(lm)) {
            val fix = try {
                lm.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission for provider $provider", e)
                null
            } catch (e: IllegalArgumentException) {
                // Provider not present on this device.
                null
            } ?: continue
            if (best == null || fix.time > best.time) {
                best = fix
                bestProvider = provider
            }
        }
        if (best == null) {
            Log.d(
                TAG,
                "no cached fix. providers[${describeProviders()}] " +
                    "fine=${fineGranted()} coarse=${coarseGranted()} " +
                    "background=${hasBackgroundPermission()}",
            )
        } else {
            val ageSeconds = (System.currentTimeMillis() - best.time) / 1000
            Log.d(TAG, "cached fix from $bestProvider, ${ageSeconds}s old")
        }
        return best
    }

    /**
     * Asks for one fresh fix, giving up after [timeoutMillis]. Returns null when permission is
     * missing, no provider is enabled, or the fix does not arrive in time.
     */
    suspend fun current(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): Location? {
        val lm = manager ?: return null
        if (!hasPermission()) return null

        val provider = candidateProviders(lm).firstOrNull {
            try {
                lm.isProviderEnabled(it)
            } catch (e: IllegalArgumentException) {
                false
            }
        }
        if (provider == null) {
            Log.w(TAG, "no usable provider. ${describeProviders()}")
            return null
        }

        Log.d(TAG, "requesting a fix from $provider, up to ${timeoutMillis / 1000}s")
        val fix = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    lm.getCurrentLocation(
                        provider,
                        signal,
                        context.mainExecutor,
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Location permission revoked mid-request", e)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        if (fix == null) {
            Log.w(
                TAG,
                "no fix from $provider within ${timeoutMillis / 1000}s. " +
                    "background=${hasBackgroundPermission()} fine=${fineGranted()}",
            )
        } else {
            Log.d(TAG, "got a fix from ${fix.provider}, accuracy ${fix.accuracy}m")
        }
        return fix
    }

    private companion object {
        const val TAG = "LocationRepository"

        /**
         * A cold GPS fix on a watch, outdoors, routinely takes half a minute; indoors it will not
         * come at all. Twenty seconds was giving up before the receiver had a chance.
         */
        const val DEFAULT_TIMEOUT_MS = 45_000L

        /**
         * Fused first (cheapest, already maintained by the platform), then network and passive,
         * with GPS last because it is the only one that costs real battery — and on a watch
         * indoors, the only one that never answers.
         */
        val PROVIDERS = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
    }
}
