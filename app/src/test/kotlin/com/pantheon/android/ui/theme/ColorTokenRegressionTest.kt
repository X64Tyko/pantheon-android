package com.pantheon.android.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression guard for CHANGELOG.md's Unreleased "Android: manifest-driven
// color theming, actually reaching the UI" fix: every hand-rolled screen used
// to define its own local `Color(0x...)` constants instead of reading
// LocalPantheonColors.current, so a manifest theme change never reached them.
//
// This is a plain source-text scan rather than a Compose UI test (heavy, and
// this app has no Compose testing infra set up) or a custom Android
// Lint/detekt rule — the repo has neither a detekt config nor a custom lint
// module anywhere (checked: no detekt.yml, no lint.xml, no custom
// IssueRegistry), and this app's existing test-infra precedent
// (QueryParamsTest.kt) is already "plain JUnit over plain Kotlin/text", so a
// grep-shaped JVM test fits the established pattern instead of introducing a
// whole new tooling dependency for one rule.
class ColorTokenRegressionTest {

    // Screens named in the CHANGELOG fix, both flavors where they exist —
    // PlayerScreen.kt is shared (src/main), the rest are per-flavor
    // (src/mobile, src/tv).
    private val screensToScan = listOf(
        "main/kotlin/com/pantheon/android/player/PlayerScreen.kt",
        "mobile/kotlin/com/pantheon/android/home/HomeScreen.kt",
        "tv/kotlin/com/pantheon/android/home/HomeScreen.kt",
        "mobile/kotlin/com/pantheon/android/detail/DetailScreen.kt",
        "tv/kotlin/com/pantheon/android/detail/DetailScreen.kt",
        "mobile/kotlin/com/pantheon/android/guide/GuideScreen.kt",
        "tv/kotlin/com/pantheon/android/guide/GuideScreen.kt",
        "mobile/kotlin/com/pantheon/android/library/LibraryScreen.kt",
        "tv/kotlin/com/pantheon/android/library/LibraryScreen.kt",
        "mobile/kotlin/com/pantheon/android/auth/ProfileSelectScreen.kt",
        "tv/kotlin/com/pantheon/android/auth/ProfileSelectScreen.kt",
    )

    // Deliberate, pre-existing exceptions — not HDS palette tokens at all, so
    // LocalPantheonColors has nothing to offer them (no "modal scrim" or
    // "pure black player backdrop" token exists in PantheonColors.kt's set).
    // Keyed by the exact matched literal so a *different* new hardcoded color
    // in the same file still fails the test.
    private val allowedLiterals: Map<String, Set<String>> = mapOf(
        "main/kotlin/com/pantheon/android/player/PlayerScreen.kt" to setOf(
            "Color(0x99000000)", // semi-transparent scrim behind the track-selection dialog
        ),
    )

    private val hardcodedColorPattern = Regex("""Color\(0x[0-9A-Fa-f]{6,8}\)""")

    @Test
    fun `screens read LocalPantheonColors, not local hardcoded Color(0x…) constants`() {
        val srcRoot = resolveAppSrcRoot()
        val violations = mutableListOf<String>()

        for (relPath in screensToScan) {
            val file = File(srcRoot, relPath)
            if (!file.isFile) {
                violations += "$relPath: file not found — update ColorTokenRegressionTest.screensToScan if it moved/renamed"
                continue
            }
            val allowed = allowedLiterals[relPath].orEmpty()
            file.readLines().forEachIndexed { idx, line ->
                for (match in hardcodedColorPattern.findAll(line)) {
                    if (match.value !in allowed) {
                        violations += "$relPath:${idx + 1}: ${match.value} — read LocalPantheonColors.current instead " +
                            "(or add to ColorTokenRegressionTest.allowedLiterals if this is a deliberate non-token color)"
                    }
                }
            }
        }

        assertTrue(
            "Hardcoded Color(0x...) constant(s) found in manifest-themed screens:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // Gradle's `test` task working directory defaults to the module dir
    // (app/), but this resolves robustly either way (module dir or repo
    // root) so the test doesn't silently no-op on a working-directory change.
    private fun resolveAppSrcRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(File(cwd, "src"), File(cwd, "app/src"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate app/src from working dir '$cwd' — adjust resolveAppSrcRoot().")
    }
}
