// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.update

import android.app.Activity
import android.os.Bundle
import com.avdesign.mfd24.R
import com.avdesign.mfd24.export.QrPanelView

/**
 * The release page, as a QR code. The whole of what this app does about updates.
 *
 * It used to do more, and the more was a mistake. There was a screen that fetched the release
 * notes, laid them out four words to a line, offered an install button and a way back to the
 * previous version — and none of it could finish the job, because **Wear OS 3 does not let an app
 * install an app**: the session commits, the platform asks for confirmation, and its own installer
 * answers "Install/Uninstall actions not supported on Wear". Verified on the API 30 emulator and on
 * a TicWatch Pro 3 Ultra.
 *
 * What was left was a wall of text on a 1.2-inch screen ending in a button that could not work. So
 * the watch now does only the part a watch is good at: it says *there is a release*, and it hands
 * the address to a camera. The notes are on the page being pointed at, in a browser, at a readable
 * size — which is where release notes have always belonged. Two seconds with a phone beats two
 * minutes of scrolling.
 *
 * The same view as the incident log's own exit, for the same reason: a camera is the shortest link
 * between a wrist and a machine that can act.
 */
class ReleaseLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A known release gets its own tag; otherwise the page that always resolves to the newest.
        val version = intent?.getStringExtra(EXTRA_VERSION)?.takeIf { it.isNotEmpty() }
        setContentView(
            QrPanelView(
                context = this,
                payload = ReleaseCheck.releasePageUrl(version),
                topCaption = version?.let { getString(R.string.release_qr_version, it) }
                    ?: getString(R.string.release_qr_latest),
            )
        )
    }

    companion object {
        const val EXTRA_VERSION: String = "version"
    }
}
