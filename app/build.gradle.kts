// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The POI binary asset is produced by the :tools:poi generator from the CSV sources in
// tools/poi/data. It lands in a generated assets dir rather than being committed, so the .bin can
// never drift from the CSVs.
//
// The task declares its output as a DirectoryProperty so that AGP's own
// `addGeneratedSourceDirectory` can wire it into the variant below. That API is the only one that
// gets both halves of the job right at once. `assets.srcDir(...)` packages the directory but
// carries no task dependency, which is why this used to need a hand-written `dependsOn` on every
// merge*Assets task -- and why `assembleRelease` still failed in the lint model task, which reads
// the same directory and was never in that list. Handing `srcDir` the TaskProvider or a
// `files(...).builtBy(...)` instead is worse than either: the build goes green and poi_v1.bin is
// quietly left out of the APK.
abstract class PackPoi : JavaExec() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        mainClass.set("com.avdesign.mfd24.tools.PoiPackKt")
        argumentProviders.add {
            listOf(dataDir.get().asFile.absolutePath, outputDir.get().asFile.absolutePath)
        }
    }
}

val packPoi = tasks.register<PackPoi>("packPoi") {
    group = "build"
    description = "Packs the airport/port/spaceport CSV sources into a Morton-ordered binary asset."
    // Resolved lazily: AGP realises this task while `android { }` is still being configured, which
    // is before :tools:poi has had the Kotlin plugin applied, and reaching for its
    // SourceSetContainer at that point fails with "Extension of type does not exist".
    classpath = files({ project(":tools:poi").the<SourceSetContainer>()["main"].runtimeClasspath })
    dataDir.set(rootProject.layout.projectDirectory.dir("tools/poi/data"))
    outputDir.set(layout.buildDirectory.dir("generated/poi/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(packPoi, PackPoi::outputDir)
    }
}

android {
    namespace = "com.avdesign.mfd24"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.avdesign.mfd24"
        minSdk = 30
        targetSdk = 34
        // Bump both for every published release. versionCode is what the platform compares when
        // deciding whether an APK is an update, so a release that forgets it cannot be installed
        // over its predecessor.
        versionCode = 19
        versionName = "2.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The app's own strings are English only — the six translated documents are documentation,
        // not resources. Without this the APK carries every AndroidX and Compose string in some
        // eighty locales, none of which this face can ever display.
        resourceConfigurations += listOf("en")
    }

    // Release signing, in order of preference. The key is a property of the machine, never of the
    // project: nothing here is committed, and a build with no key at all still succeeds unsigned,
    // which is what lets CI prove that R8 and the resource shrinker work.
    //
    //  1. A real release key, configured in ~/.gradle/gradle.properties (outside the repository):
    // The property names still say expedition24, and so does the key alias inside the
    // keystore: they name the release key, which has not changed and cannot be renamed
    // without minting a new one. A new key would mean the next build could not install over
    // this one.
    //         expedition24StoreFile=/path/to/expedition24-release.jks
    //         expedition24StorePassword=...
    //         expedition24KeyAlias=expedition24
    //         expedition24KeyPassword=...
    //     This is what published APKs are signed with. Anything downloadable has to be: the debug
    //     key's password is "android" and its alias is public knowledge, so a debug-signed public
    //     build is one anybody can forge an update for.
    //
    //  2. Failing that, the debug key, for sideloading onto your own watch.
    //
    //  3. Failing that, unsigned.
    val releaseStoreFile = (findProperty("expedition24StoreFile") as String?)?.let(::File)
    val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

    signingConfigs {
        if (releaseStoreFile != null && releaseStoreFile.exists()) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = findProperty("expedition24StorePassword") as String
                keyAlias = findProperty("expedition24KeyAlias") as String
                keyPassword = findProperty("expedition24KeyPassword") as String
            }
        } else if (releaseStoreFile != null) {
            // Configured but absent is a mistake worth stopping for. Falling back to the debug key
            // here would produce an APK that cannot update the one already in people's hands.
            throw GradleException(
                "expedition24StoreFile points at $releaseStoreFile, which does not exist"
            )
        } else if (debugKeystore.exists()) {
            create("sideload") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.findByName("sideload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // A product flavor of one, on a dimension of one. It looks redundant and is not: the
    // dimension is what puts the flavor name into every task and output path
    // (`assembleEarthRelease`, `app-earth-release.apk`), which is what release assets, the CI
    // workflow and the updater's asset name are all written against. Collapsing it into
    // defaultConfig would rename all of them for no gain.
    flavorDimensions += "world"
    productFlavors {
        create("earth") {
            dimension = "world"
            // The updater looks for its own releases only: the tag prefix and the asset name are
            // build fields rather than constants so a build can never offer itself an APK that
            // is not the one it was compiled as.
            buildConfigField("String", "UPDATE_TAG_PREFIX", "\"v\"")
            buildConfigField("String", "UPDATE_ASSET", "\"app-earth-release.apk\"")
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    androidResources {
        // The POI database is read through a random-access ByteBuffer, so it must stay uncompressed.
        noCompress += "bin"
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir(rootProject.file("shared/kotlin"))
            // Assets come from packPoi via androidComponents above, not from here.
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.watchface)
    implementation(libs.watchface.style)
    implementation(libs.watchface.editor)

    implementation(libs.work.runtime.ktx)
    // The platform's own daily step total. The raw TYPE_STEP_COUNTER sensor counts from the last
    // reboot, so a freshly installed face can only start counting from the moment it is installed —
    // which shows a wearer who has already walked four thousand steps a readout of zero. Health
    // Services keeps the figure the rest of the watch agrees with. The fallback path stays, for
    // hardware or builds where the service is not there.
    implementation(libs.health.services)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
