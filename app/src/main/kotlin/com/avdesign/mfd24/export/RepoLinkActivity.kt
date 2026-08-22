// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import android.app.Activity
import android.os.Bundle
import com.avdesign.mfd24.BuildConfig
import com.avdesign.mfd24.R
import com.avdesign.mfd24.update.ReleaseCheck

/**
 * The repository, handed to the phone the only way a watch honestly can: as a QR for the camera
 * already pointed at it. A watch has no browser, and an address read off a 1.2-inch screen and
 * retyped is an address with a typo in it. Reached from the ABOUT section of the editor.
 */
class RepoLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            QrPanelView(
                context = this,
                payload = ReleaseCheck.REPO_URL,
                topCaption = getString(R.string.about_qr_top, BuildConfig.VERSION_NAME),
            )
        )
    }
}
