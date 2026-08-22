// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    named("main") {
        // Morton.kt / PoiFormat.kt are shared verbatim with the watch face so the writer and the reader
        // can never disagree about the on-disk layout.
        java.srcDir(rootProject.file("shared/kotlin"))
    }
}
