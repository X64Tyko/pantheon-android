plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.pantheon.android"
    compileSdk = 37

    // Bump these two *together* whenever the major or minor version
    // changes: set versionBaseBuild to whatever ANDROID_VERSION_CODE (CI's
    // run_number, see below) is about to be at the time of the bump, so
    // versionName's trailing patch number restarts near 0 instead of
    // continuing the previous minor version's build count (e.g. jumping
    // straight to "0.2.150" instead of "0.2.1"). versionCode itself is
    // never reset this way — see its own comment below for why.
    val versionMajorMinor = "0.1"
    val versionBaseBuild = 0

    defaultConfig {
        applicationId = "com.pantheon.android"
        minSdk = 26
        targetSdk = 37
        // CI sets ANDROID_VERSION_CODE from the GitHub Actions run number
        // (see .github/workflows/build.yml) so every build's versionCode is
        // guaranteed to increase — sideloading is this app's only install
        // path (no Play Store/Amazon Appstore listing), and Android's
        // package manager silently refuses to install an APK over an
        // existing one whose versionCode isn't strictly higher, so a static
        // versionCode meant "update" installs could quietly no-op.
        // versionCode is NEVER reset, even across major/minor bumps — it
        // has to keep climbing forever regardless of what versionName says,
        // or a real device with an old build installed could get stuck
        // refusing a supposedly-newer sideload. Local (non-CI) builds fall
        // back to a fixed versionCode of 1, which is fine since a local
        // `installDebug` run uninstalls/reinstalls rather than
        // update-installs anyway.
        val ciVersionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull()
        versionCode = ciVersionCode ?: 1
        versionName = if (ciVersionCode != null) "$versionMajorMinor.${ciVersionCode - versionBaseBuild}" else "$versionMajorMinor.0-dev"
    }

    // Two independent axes: which store/services this build targets (Google
    // Play w/ GMS vs Amazon Appstore, no GMS), and which form factor it's for
    // (touch phone/tablet vs D-pad TV, leanback launcher). Every combination
    // shares the same manifest-rendering/API-client/player code in src/main —
    // see /ChimeraShare/Pantheon-Linux/kairos's TvManifestService and this
    // project's ARCHITECTURE notes for why: the whole point of the manifest
    // design was to avoid needing four (or more) independent app codebases.
    flavorDimensions += listOf("store", "formFactor")
    productFlavors {
        create("google") {
            dimension = "store"
            // GMS available on this flavor — nothing GMS-specific wired up yet.
        }
        create("amazon") {
            dimension = "store"
            applicationIdSuffix = ".amazon"
            // Fire OS — no Google Play Services. Anything GMS-dependent must be
            // gated out of this flavor's source set, not just left unused.
        }
        create("mobile") {
            dimension = "formFactor"
            // Touch phone/tablet — standard launcher, Jetpack Compose (see
            // src/mobile's Compose dependency, not Compose for TV).
        }
        create("tv") {
            dimension = "formFactor"
            applicationIdSuffix = ".tv"
            // D-pad TV — leanback launcher banner/intent-filter (src/tv's
            // AndroidManifest.xml), Compose for TV (tv-foundation/tv-material).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Shared (src/main) — plain Compose primitives used by both formFactor
    // flavors' own UI layer; the *TV-specific* row/carousel/focus primitives
    // come from tv-foundation/tv-material below, mobile-only.
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose for TV — only linked into the `tv` flavor's variants
    // (googleTv/amazonTv), via Gradle's auto-generated `tvImplementation`
    // configuration for a flavor literally named "tv".
    "tvImplementation"("androidx.tv:tv-foundation:1.0.0")
    "tvImplementation"("androidx.tv:tv-material:1.1.0")

    // API client — mirrors hades/src/api/client.ts's contract against Kairos/Hermes.
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.4.0")

    // Secure token storage — Keystore-backed, never plain SharedPreferences.
    implementation("androidx.security:security-crypto:1.1.0")

    // Poster/thumb/art images — authenticated via a ?token= query param on
    // the URL itself (same approach as hades/src/api/client.ts's mediaUrl(),
    // simpler than sharing OkHttp client/interceptor state with Coil).
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    // Library pagination (see plan: Paging3 + LazyGrid's stable-key diffing
    // is what avoids the "rebuild content from scratch" bug class that hurt
    // pantheon-roku's Library screen).
    implementation("androidx.paging:paging-runtime:3.5.0")
    implementation("androidx.paging:paging-compose:3.5.0")

    // Player — Media3/ExoPlayer against Hephaestus's HLS manifest_url.
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")

    // Unit tests (src/test) — plain JVM, no emulator/device needed. See
    // util/QueryParams.kt: pure functions extracted out of their ViewModels
    // specifically so this layer of logic is directly testable.
    testImplementation("junit:junit:4.13.2")
}
