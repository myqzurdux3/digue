# Shorts YouTube et Spotlight Snapchat — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Block YouTube Shorts and Snapchat Spotlight from the same app, without permanently widening what the accessibility service is allowed to see.

**Architecture:** The rules file gains a package dimension (version 2) and the classifier picks an app's rules from the snapshot's own package name. The service redeclares its `packageNames` at runtime from the settings, so a package is visible to it only while its blocking is on. Settings move from two named booleans to a set of surfaces.

**Tech Stack:** Kotlin, `:detection` (pure JVM, no `android.*`), kotlinx.serialization, Room, DataStore Preferences, Jetpack Compose (Material3 only), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-17-multi-apps-design.md`

## Global Constraints

- **`:detection` must contain no `android.*` import.**
- **Node `text` is never read, logged or persisted.** Only `viewIdResourceName`, `contentDescription`, `className`, `isSelected`, `isClickable`, `boundsInScreen`.
- **No network dependency, no network call, ever.**
- **No exception may escape `onAccessibilityEvent` or `onServiceConnected`** — Android may permanently disable a service that crashes, and the user would believe they are still protected. This now also covers the `setServiceInfo` call.
- **`RuleSetParser` must never throw.** The rules file is hand-editable on the phone.
- **Versions live in `gradle/libs.versions.toml`.** This plan adds no dependency.
- **The app is 100% Compose. `com.google.android.material` is absent and must stay absent.** Button shapes come from `ButtonDefaults`, not `MaterialTheme.shapes`, so pass `shape =` explicitly on every button.
- **UI copy in French; code, symbols and commit messages in English.**
- **New surfaces ship switched off. Instagram's stay on.** Turning off what the user already had would be a silent regression.
- **Fail closed on both axes:** unreadable settings keep Instagram's surfaces blocked and add no other package.
- Test commands:
  - JVM: `./gradlew :detection:test :app:testDebugUnitTest`
  - One class: `./gradlew :detection:test --tests '*RuleSetParserTest*'`
  - Instrumented (`--tests` does NOT work on this AGP): `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`
- Baseline before Task 1: **116 JVM tests, 0 failures.**

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `detection/.../Surface.kt` | Screen vocabulary | Add `SHORTS`, `SPOTLIGHT` |
| `detection/.../Rules.kt` | Rule vocabulary | Add `AppRules`; `RuleSet` keyed by package |
| `detection/.../RuleSetParser.kt` | Fault-tolerant loading | Parse v2, reject v1 cleanly |
| `detection/.../ScreenClassifier.kt` | Screen recognition | Pick rules by the snapshot's package |
| `app/src/main/assets/rules.json` | Shipped rules, also `:detection`'s test resource | Rewrite as v2, add YouTube and Snapchat |
| `app/.../data/SettingsStore.kt` | Persisted settings | Surface set + migration |
| `app/.../service/RuleSetLoader.kt` | Loading and fallback | Empty rule set shape |
| `app/.../service/InstagramWatcherService.kt` | The service | Dynamic packages, package-agnostic handling |
| `app/.../ui/HomeViewModel.kt`, `HomeScreen.kt` | The screen | Total + breakdown, switches grouped by app |
| `app/src/main/res/values/strings.xml` | UI copy | New labels |

---

### Task 1: Rules keyed by package

**Files:**
- Modify: `detection/src/main/kotlin/com/insta/detection/Surface.kt`
- Modify: `detection/src/main/kotlin/com/insta/detection/Rules.kt`
- Modify: `detection/src/main/kotlin/com/insta/detection/RuleSetParser.kt`
- Modify: `detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt`
- Test: `detection/src/test/kotlin/com/insta/detection/RuleSetParserTest.kt`
- Test: `detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt`
- Test: `detection/src/test/kotlin/com/insta/detection/TestNodes.kt` (the shared `TEST_RULES`)

**Interfaces:**
- Consumes: `Signal`, `SurfaceRules`, `ScreenSnapshot` (which already carries `packageName`), `Classification`.
- Produces:
  - `enum class Surface { REELS, EXPLORE, SHORTS, SPOTLIGHT, OTHER }`
  - `data class AppRules(val surfaces: Map<Surface, SurfaceRules>)`
  - `data class RuleSet(val version: Int, val apps: Map<String, AppRules>)`
  - `ScreenClassifier(ruleSet).classify(snapshot)` unchanged in signature; it now selects `ruleSet.apps[snapshot.packageName]`.
  - `const val RULES_VERSION = 2` in `Rules.kt`

- [ ] **Step 1: Write the failing parser tests**

Append to `detection/src/test/kotlin/com/insta/detection/RuleSetParserTest.kt`:

```kotlin
    private val v2 = """
        {
          "version": 2,
          "apps": {
            "com.instagram.android": {
              "surfaces": {
                "REELS": {
                  "signals": [
                    { "tier": "HIGH", "type": "VIEW_ID", "value": "clips_tab" }
                  ]
                }
              }
            },
            "com.google.android.youtube": {
              "surfaces": {
                "SHORTS": {
                  "signals": [
                    { "tier": "HIGH", "type": "VIEW_ID", "value": "reel_progress_bar",
                      "requireSelected": false, "requireOnScreen": true }
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `reads rules for several apps`() {
        val result = RuleSetParser.parse(v2)

        assertTrue(result is ParseResult.Success)
        val ruleSet = (result as ParseResult.Success).ruleSet
        assertEquals(setOf("com.instagram.android", "com.google.android.youtube"), ruleSet.apps.keys)
        assertEquals(
            setOf(Surface.SHORTS),
            ruleSet.apps.getValue("com.google.android.youtube").surfaces.keys,
        )
    }

    @Test
    fun `rejects the version 1 format instead of silently migrating it`() {
        // A v1 file left in filesDir must read as a clean failure so the loader
        // falls back to the bundled rules and the banner says why. Quietly
        // treating it as "no rules" would block nothing behind a healthy screen.
        val v1 = """
            {
              "version": 1,
              "surfaces": {
                "REELS": {
                  "signals": [
                    { "tier": "HIGH", "type": "VIEW_ID", "value": "clips_tab" }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(v1)

        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `rejects an unknown surface name`() {
        val raw = """
            {
              "version": 2,
              "apps": {
                "com.instagram.android": {
                  "surfaces": {
                    "TIKTOK": {
                      "signals": [
                        { "tier": "HIGH", "type": "VIEW_ID", "value": "x" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        assertTrue(RuleSetParser.parse(raw) is ParseResult.Failure)
    }

    @Test
    fun `an empty package name is rejected`() {
        val raw = """
            {
              "version": 2,
              "apps": {
                "": {
                  "surfaces": {
                    "REELS": {
                      "signals": [
                        { "tier": "HIGH", "type": "VIEW_ID", "value": "clips_tab" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        assertTrue(RuleSetParser.parse(raw) is ParseResult.Failure)
    }
```

Every pre-existing test in this file uses the v1 shape and must be rewritten to the v2 shape: wrap the existing `"surfaces": { ... }` body inside `"apps": { "com.instagram.android": { ... } }` and change `"version": 1` to `"version": 2`. Their assertions then read `ruleSet.apps.getValue("com.instagram.android").surfaces` instead of `ruleSet.surfaces`. Do not delete any of them.

- [ ] **Step 2: Write the failing classifier test**

Append to `detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt`:

```kotlin
    @Test
    fun `one app's rules never fire on another app`() {
        // The system-level package filter is the first guard; this is the second.
        // A YouTube id that happened to collide with an Instagram one must not
        // block Instagram, and vice versa.
        val rules = RuleSet(
            version = 2,
            apps = mapOf(
                "com.google.android.youtube" to AppRules(
                    mapOf(
                        Surface.SHORTS to SurfaceRules(
                            listOf(
                                Signal(
                                    tier = Tier.HIGH,
                                    type = SignalType.VIEW_ID,
                                    value = "shared_id",
                                    requireSelected = false,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val instagramScreen = ScreenSnapshot(
            packageName = "com.instagram.android",
            capturedAtMillis = 0L,
            nodes = listOf(node(index = 0, viewId = "shared_id")),
        )

        assertEquals(Surface.OTHER, ScreenClassifier(rules).classify(instagramScreen).surface)
    }

    @Test
    fun `a package with no rules is never blocked`() {
        val rules = RuleSet(version = 2, apps = emptyMap())
        val screen = ScreenSnapshot("com.whatever.app", 0L, listOf(node(index = 0, viewId = "x")))

        assertEquals(Surface.OTHER, ScreenClassifier(rules).classify(screen).surface)
    }
```

- [ ] **Step 3: Run both test classes and watch them fail**

Run: `./gradlew :detection:test --tests '*RuleSetParserTest*' --tests '*ScreenClassifierTest*'`
Expected: FAIL to compile — `Unresolved reference 'AppRules'`, `No value passed for parameter 'surfaces'`.

- [ ] **Step 4: Widen `Surface`**

Replace `detection/src/main/kotlin/com/insta/detection/Surface.kt` with:

```kotlin
package com.insta.detection

/**
 * A screen this app cares about. Surfaces stay app-specific rather than being
 * abstracted into one "short video feed": each one carries its own switch and
 * its own counter, which is what the screen already knows how to show.
 *
 * [OTHER] means "nothing to do here" and never carries rules.
 */
enum class Surface {
    REELS,
    EXPLORE,
    SHORTS,
    SPOTLIGHT,
    OTHER,
}
```

- [ ] **Step 5: Key the rule set by package**

In `detection/src/main/kotlin/com/insta/detection/Rules.kt`, replace the `RuleSet` declaration with:

```kotlin
/** The rules for one application. */
data class AppRules(val surfaces: Map<Surface, SurfaceRules>)

data class RuleSet(
    val version: Int,
    val apps: Map<String, AppRules>,
)

/**
 * The rules-file format this build understands. Bumped from 1 when rules gained
 * a package dimension; a file from the older format is rejected rather than
 * guessed at.
 */
const val RULES_VERSION = 2
```

- [ ] **Step 6: Parse the new format**

Replace the whole of `detection/src/main/kotlin/com/insta/detection/RuleSetParser.kt` with:

```kotlin
package com.insta.detection

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed interface ParseResult {
    data class Success(val ruleSet: RuleSet) : ParseResult
    data class Failure(val message: String) : ParseResult
}

@Serializable
private data class RawAppRules(val surfaces: Map<String, SurfaceRules>)

@Serializable
private data class RawRuleSet(
    val version: Int,
    val apps: Map<String, RawAppRules>,
)

/**
 * Reads the rules file. Never throws: a hand-edited rules file will be broken
 * one day, and that day the app must degrade, not crash.
 */
object RuleSetParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): ParseResult {
        val decoded = try {
            json.decodeFromString<RawRuleSet>(raw)
        } catch (e: Exception) {
            return ParseResult.Failure("malformed rules file: ${e.message}")
        }

        if (decoded.version != RULES_VERSION) {
            return ParseResult.Failure(
                "unsupported rules version ${decoded.version}, expected $RULES_VERSION",
            )
        }

        val apps = mutableMapOf<String, AppRules>()
        for ((packageName, rawApp) in decoded.apps) {
            if (packageName.isBlank()) {
                return ParseResult.Failure("a package name must not be blank")
            }
            val surfaces = mutableMapOf<Surface, SurfaceRules>()
            for ((name, rules) in rawApp.surfaces) {
                val surface = Surface.entries.firstOrNull { it.name == name }
                    ?: return ParseResult.Failure("unknown surface \"$name\"")
                if (surface == Surface.OTHER) {
                    return ParseResult.Failure("surface \"OTHER\" cannot carry rules")
                }
                rules.signals.forEach { signal ->
                    validate(signal)?.let { return ParseResult.Failure(it) }
                }
                surfaces[surface] = rules
            }
            apps[packageName] = AppRules(surfaces)
        }

        return ParseResult.Success(RuleSet(decoded.version, apps))
    }

    /** Returns an error message, or null when the signal is usable. */
    private fun validate(signal: Signal): String? {
        if (signal.absentViewIds.any { it.isBlank() }) {
            return "absentViewIds must not contain a blank id"
        }
        return when (signal.type) {
            SignalType.VIEW_ID ->
                if (signal.value.isNullOrBlank()) "VIEW_ID signal needs a non-empty value" else null

            SignalType.CONTENT_DESCRIPTION ->
                if (signal.anyOf.isEmpty()) "CONTENT_DESCRIPTION signal needs a non-empty anyOf" else null

            SignalType.NAV_BAR_INDEX -> {
                val index = signal.value?.toIntOrNull()
                if (index == null || index < 0) "NAV_BAR_INDEX signal needs a non-negative integer value" else null
            }
        }
    }
}
```

- [ ] **Step 7: Select rules by the snapshot's package**

In `detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt`, replace the body of `classify` with:

```kotlin
    fun classify(snapshot: ScreenSnapshot): Classification {
        // The snapshot names its own package, so the rules for another app can
        // never fire here — a second guard behind the system-level package
        // filter, which the service narrows at runtime.
        val appRules = ruleSet.apps[snapshot.packageName] ?: return Classification.OTHER
        val navBar by lazy { findNavBar(snapshot) }

        for (tier in Tier.entries) {
            for ((surface, rules) in appRules.surfaces) {
                val matched = rules.signals
                    .filter { it.tier == tier }
                    .any { matches(it, snapshot, navBar) }
                if (matched) return Classification(surface, tier, rules.clickViewId)
            }
        }
        return Classification.OTHER
    }
```

- [ ] **Step 8: Update the shared test rules**

In `detection/src/test/kotlin/com/insta/detection/TestNodes.kt`, replace the `TEST_RULES` value with:

```kotlin
val TEST_RULES = RuleSet(
    version = RULES_VERSION,
    apps = mapOf(
        "com.instagram.android" to AppRules(
            mapOf(
                Surface.REELS to SurfaceRules(
                    listOf(
                        Signal(Tier.HIGH, SignalType.VIEW_ID, value = "com.instagram.android:id/clips_tab"),
                        Signal(Tier.MEDIUM, SignalType.CONTENT_DESCRIPTION, anyOf = listOf("Reels", "Réels")),
                        Signal(Tier.LOW, SignalType.NAV_BAR_INDEX, value = "2"),
                    ),
                ),
                Surface.EXPLORE to SurfaceRules(
                    listOf(
                        Signal(Tier.HIGH, SignalType.VIEW_ID, value = "com.instagram.android:id/search_tab"),
                        Signal(Tier.MEDIUM, SignalType.CONTENT_DESCRIPTION, anyOf = listOf("Search and explore", "Recherche et exploration")),
                        Signal(Tier.LOW, SignalType.NAV_BAR_INDEX, value = "1"),
                    ),
                ),
            ),
        ),
    ),
)
```

Any test in `ScreenClassifierTest.kt` that builds its own `RuleSet(version = 1, surfaces = mapOf(...))` must be rewritten the same way: wrap the surface map in `AppRules(...)` under the key `"com.instagram.android"`, and use `RULES_VERSION`. Their snapshots come from `snapshot(...)` in `TestNodes.kt`, which already stamps `com.instagram.android`, so no snapshot changes are needed.

- [ ] **Step 9: Run the two test classes and watch them pass**

Run: `./gradlew :detection:test --tests '*RuleSetParserTest*' --tests '*ScreenClassifierTest*'`
Expected: PASS.

- [ ] **Step 10: Commit**

`:app` will not compile yet — `RuleSetLoader` still builds `RuleSet(version = 0, surfaces = emptyMap())` and `rules.json` is still v1. That is Task 2's job. Commit `:detection` alone.

```bash
git add detection/
git commit -m "feat: key detection rules by application package"
```

---

### Task 2: The shipped rules file, in version 2

**Files:**
- Modify: `app/src/main/assets/rules.json`
- Modify: `app/src/main/kotlin/com/insta/reelsoff/service/RuleSetLoader.kt`
- Test: `detection/src/test/kotlin/com/insta/detection/RealFixtureTest.kt`

**Interfaces:**
- Consumes: `RuleSet`, `AppRules`, `RULES_VERSION` from Task 1.
- Produces: a v2 `rules.json` whose Instagram half is byte-equivalent in behaviour to today's, plus YouTube and Snapchat surfaces.

- [ ] **Step 1: Rewrite `app/src/main/assets/rules.json`**

Replace the whole file with this. The Instagram signals are unchanged in content — only their nesting moves.

```json
{
  "version": 2,
  "apps": {
    "com.instagram.android": {
      "surfaces": {
        "REELS": {
          "signals": [
            { "tier": "HIGH", "type": "VIEW_ID",
              "value": "com.instagram.android:id/clips_tab", "requireSelected": true },
            { "tier": "HIGH", "type": "VIEW_ID",
              "value": "com.instagram.android:id/clips_viewer_view_pager",
              "requireSelected": false, "requireOnScreen": true,
              "absentViewIds": [
                "com.instagram.android:id/reel_viewer_message_composer",
                "com.instagram.android:id/reply_bar_container",
                "com.instagram.android:id/sender_username_or_fullname"
              ] },
            { "tier": "HIGH", "type": "VIEW_ID",
              "value": "com.instagram.android:id/suggested_title",
              "requireSelected": false, "requireOnScreen": true },
            { "tier": "LOW", "type": "NAV_BAR_INDEX",
              "value": "1", "requireSelected": true }
          ]
        },
        "EXPLORE": {
          "clickViewId": "com.instagram.android:id/action_bar_search_edit_text",
          "signals": [
            { "tier": "HIGH", "type": "VIEW_ID",
              "value": "com.instagram.android:id/search_tab", "requireSelected": true },
            { "tier": "MEDIUM", "type": "CONTENT_DESCRIPTION",
              "anyOf": ["Rechercher et explorer"], "requireSelected": true },
            { "tier": "LOW", "type": "NAV_BAR_INDEX",
              "value": "3", "requireSelected": true }
          ]
        }
      }
    },
    "com.google.android.youtube": {
      "surfaces": {
        "SHORTS": {
          "signals": [
            { "tier": "HIGH", "type": "VIEW_ID",
              "value": "com.google.android.youtube:id/reel_player_page_container",
              "requireSelected": false, "requireOnScreen": true },
            { "tier": "HIGH", "type": "VIEW_ID",
              "value": "com.google.android.youtube:id/reel_progress_bar",
              "requireSelected": false, "requireOnScreen": true }
          ]
        }
      }
    },
    "com.google.android.apps.youtube.kids": {
      "surfaces": {
        "SHORTS": {
          "signals": [
            { "tier": "HIGH", "type": "VIEW_ID",
              "value": "com.google.android.apps.youtube.kids:id/reel_player_page_container",
              "requireSelected": false, "requireOnScreen": true }
          ]
        }
      }
    },
    "app.revanced.android.youtube": {
      "surfaces": {
        "SHORTS": {
          "signals": [
            { "tier": "HIGH", "type": "VIEW_ID",
              "value": "app.revanced.android.youtube:id/reel_player_page_container",
              "requireSelected": false, "requireOnScreen": true }
          ]
        }
      }
    },
    "com.snapchat.android": {
      "surfaces": {
        "SPOTLIGHT": {
          "signals": [
            { "tier": "HIGH", "type": "VIEW_ID",
              "value": "com.snapchat.android:id/spotlight_container",
              "requireSelected": false, "requireOnScreen": true }
          ]
        }
      }
    }
  }
}
```

- [ ] **Step 2: Fix the loader's empty rule set**

In `app/src/main/kotlin/com/insta/reelsoff/service/RuleSetLoader.kt`, in the private companion, replace the `EMPTY_RULE_SET` line with:

```kotlin
        val EMPTY_RULE_SET = RuleSet(version = 0, apps = emptyMap())
```

Version 0 rather than `RULES_VERSION` on purpose: this value is only ever reached when loading failed, and a version that no file can legitimately carry makes that unmistakable.

- [ ] **Step 3: Add the fixture tests**

Append to `detection/src/test/kotlin/com/insta/detection/RealFixtureTest.kt`:

```kotlin
    @Test
    fun `the shipped file is in the current format`() {
        assertEquals(RULES_VERSION, ruleSet.version)
    }

    @Test
    fun `youtube and snapchat rules are shipped`() {
        assertTrue(
            "every YouTube variant must carry SHORTS",
            listOf(
                "com.google.android.youtube",
                "com.google.android.apps.youtube.kids",
                "app.revanced.android.youtube",
            ).all { ruleSet.apps[it]?.surfaces?.containsKey(Surface.SHORTS) == true },
        )
        assertTrue(
            "Snapchat must carry SPOTLIGHT",
            ruleSet.apps["com.snapchat.android"]?.surfaces?.containsKey(Surface.SPOTLIGHT) == true,
        )
    }

    @Test
    fun `every shipped signal names an id from its own package`() {
        // A copy-paste between app blocks would produce a rule that can never
        // match, which is this project's worst failure mode: indistinguishable
        // from one that works.
        for ((packageName, app) in ruleSet.apps) {
            for ((surface, rules) in app.surfaces) {
                for (signal in rules.signals.filter { it.type == SignalType.VIEW_ID }) {
                    assertTrue(
                        "$packageName/$surface names ${signal.value}",
                        signal.value?.startsWith("$packageName:id/") == true,
                    )
                }
            }
        }
    }
```

Every pre-existing test in this file that reads `ruleSet.surfaces` must become `ruleSet.apps.getValue("com.instagram.android").surfaces`. Do not weaken or delete any of them — they are the regression net that keeps the user's own feed unblocked.

- [ ] **Step 4: Run the whole JVM suite**

Run: `./gradlew :detection:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures. Every pre-existing fixture test still passes, in particular `feed is not blocked`, `a reel someone sent is not blocked` and `the suggested reel that follows is blocked at the high tier`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/rules.json \
        app/src/main/kotlin/com/insta/reelsoff/service/RuleSetLoader.kt \
        detection/src/test/kotlin/com/insta/detection/RealFixtureTest.kt
git commit -m "feat: ship YouTube Shorts and Snapchat Spotlight rules"
```

---

### Task 3: Settings as a set of surfaces

**Files:**
- Modify: `app/src/main/kotlin/com/insta/reelsoff/data/SettingsStore.kt`
- Test: `app/src/androidTest/kotlin/com/insta/reelsoff/data/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: `Surface` from Task 1.
- Produces:
  - `data class BlockSettings(val blockedSurfaces: Set<Surface> = setOf(Surface.REELS, Surface.EXPLORE))`
  - `suspend fun SettingsStore.setSurfaceBlocked(surface: Surface, blocked: Boolean)`
  - `SettingsStore.settings` unchanged as a `Flow<BlockSettings>`

- [ ] **Step 1: Write the failing tests**

Instrumented test names must stay camelCase — backtick names with spaces fail to DEX at this project's minSdk.

Append to `app/src/androidTest/kotlin/com/insta/reelsoff/data/SettingsStoreTest.kt`:

```kotlin
    @Test
    fun newSurfacesAreOffByDefault() = runBlocking {
        // Turning them on would both start blocking and widen what the service is
        // allowed to see, neither of which the user asked for.
        val blocked = store.settings.first().blockedSurfaces

        assertEquals(setOf(Surface.REELS, Surface.EXPLORE), blocked)
    }

    @Test
    fun aSurfaceCanBeSwitchedOnAndOff() = runBlocking {
        store.setSurfaceBlocked(Surface.SHORTS, true)
        assertTrue(Surface.SHORTS in store.settings.first().blockedSurfaces)

        store.setSurfaceBlocked(Surface.SHORTS, false)
        assertFalse(Surface.SHORTS in store.settings.first().blockedSurfaces)
    }

    @Test
    fun everySurfaceCanBeSwitchedOff() = runBlocking {
        for (surface in listOf(Surface.REELS, Surface.EXPLORE)) {
            store.setSurfaceBlocked(surface, false)
        }

        assertTrue(store.settings.first().blockedSurfaces.isEmpty())
    }

    @Test
    fun theOldBooleansAreCarriedOver() = runBlocking {
        // A user upgrading from the previous build has the two booleans and no
        // surface set. Ignoring them would silently re-enable something they had
        // turned off.
        store.clear()
        store.setBlockExplore(false)

        assertEquals(setOf(Surface.REELS), store.settings.first().blockedSurfaces)
    }
```

`setBlockReels` and `setBlockExplore` are kept for this migration test and for compatibility; do not delete them.

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.insta.reelsoff.data.SettingsStoreTest`
Expected: FAIL to compile — `Unresolved reference 'setSurfaceBlocked'`.

- [ ] **Step 3: Rewrite `BlockSettings` and the store**

In `app/src/main/kotlin/com/insta/reelsoff/data/SettingsStore.kt`, replace `BlockSettings` with:

```kotlin
/**
 * Which surfaces are blocked. A set rather than one boolean per surface: the
 * list grows with every app supported, and the service also derives the packages
 * it declares to Android from exactly this set.
 */
data class BlockSettings(
    val blockedSurfaces: Set<Surface> = setOf(Surface.REELS, Surface.EXPLORE),
)
```

Replace the `settings` flow with:

```kotlin
    val settings: Flow<BlockSettings> = context.dataStore.data.map { preferences ->
        val stored = preferences[BLOCKED_SURFACES]
        if (stored != null) {
            // Unknown names are dropped rather than failing: a downgrade must not
            // leave the store unreadable.
            BlockSettings(stored.mapNotNull { name -> Surface.entries.firstOrNull { it.name == name } }.toSet())
        } else {
            // Migration from the two named booleans. Absent means true, which was
            // their default, so a fresh install lands on REELS + EXPLORE.
            buildSet {
                if (preferences[BLOCK_REELS] ?: true) add(Surface.REELS)
                if (preferences[BLOCK_EXPLORE] ?: true) add(Surface.EXPLORE)
            }.let(::BlockSettings)
        }
    }
```

Add the setter next to the existing ones:

```kotlin
    suspend fun setSurfaceBlocked(surface: Surface, blocked: Boolean) {
        context.dataStore.edit { preferences ->
            val current = preferences[BLOCKED_SURFACES]
                ?: buildSet {
                    if (preferences[BLOCK_REELS] ?: true) add(Surface.REELS.name)
                    if (preferences[BLOCK_EXPLORE] ?: true) add(Surface.EXPLORE.name)
                }
            preferences[BLOCKED_SURFACES] =
                if (blocked) current + surface.name else current - surface.name
        }
    }
```

Add the key to the private companion:

```kotlin
        val BLOCKED_SURFACES = stringSetPreferencesKey("blocked_surfaces")
```

and the import `androidx.datastore.preferences.core.stringSetPreferencesKey`.

- [ ] **Step 4: Fix the call sites the compiler flags**

`HomeViewModel.setBlockReels`/`setBlockExplore` and `HomeScreen`'s switches read `state.settings.blockReels`. Change them to `Surface.REELS in state.settings.blockedSurfaces` and `viewModel::setSurfaceBlocked`; Task 5 rewrites that section properly, so the goal here is only to compile and keep behaviour identical.

- [ ] **Step 5: Run the instrumented tests, then the JVM suite**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.insta.reelsoff.data.SettingsStoreTest`
Expected: PASS.

Run: `./gradlew :detection:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: store blocked surfaces as a set, migrating the old booleans"
```

---

### Task 4: The service declares only what it needs

**Files:**
- Modify: `app/src/main/res/xml/accessibility_service_config.xml`
- Modify: `app/src/main/kotlin/com/insta/reelsoff/service/InstagramWatcherService.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/DeclaredPackages.kt`
- Test: `app/src/test/kotlin/com/insta/reelsoff/service/DeclaredPackagesTest.kt`

**Interfaces:**
- Consumes: `RuleSet`, `AppRules`, `Surface`, `BlockSettings`.
- Produces: `fun declaredPackages(ruleSet: RuleSet, blocked: Set<Surface>): Set<String>`

The derivation is pulled into its own pure function so it can be tested on the JVM; the service only applies it.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/insta/reelsoff/service/DeclaredPackagesTest.kt`:

```kotlin
package com.insta.reelsoff.service

import com.insta.detection.AppRules
import com.insta.detection.RULES_VERSION
import com.insta.detection.RuleSet
import com.insta.detection.Signal
import com.insta.detection.SignalType
import com.insta.detection.Surface
import com.insta.detection.SurfaceRules
import com.insta.detection.Tier
import org.junit.Assert.assertEquals
import org.junit.Test

private fun rules(vararg entries: Pair<String, Surface>) = RuleSet(
    version = RULES_VERSION,
    apps = entries.groupBy({ it.first }, { it.second }).mapValues { (pkg, surfaces) ->
        AppRules(
            surfaces.associateWith {
                SurfaceRules(listOf(Signal(Tier.HIGH, SignalType.VIEW_ID, value = "$pkg:id/x")))
            },
        )
    },
)

class DeclaredPackagesTest {

    private val all = rules(
        "com.instagram.android" to Surface.REELS,
        "com.instagram.android" to Surface.EXPLORE,
        "com.google.android.youtube" to Surface.SHORTS,
        "com.snapchat.android" to Surface.SPOTLIGHT,
    )

    @Test
    fun `declares only the packages whose surfaces are switched on`() {
        val declared = declaredPackages(all, setOf(Surface.REELS, Surface.SHORTS))

        assertEquals(setOf("com.instagram.android", "com.google.android.youtube"), declared)
    }

    @Test
    fun `a package is dropped once its last surface is switched off`() {
        // This is the whole point of the design: switching Snapchat off must make
        // the service incapable of receiving Snapchat's screens, not merely
        // uninterested in them.
        val declared = declaredPackages(all, setOf(Surface.REELS))

        assertEquals(setOf("com.instagram.android"), declared)
    }

    @Test
    fun `one enabled surface is enough to keep its package`() {
        val declared = declaredPackages(all, setOf(Surface.EXPLORE))

        assertEquals(setOf("com.instagram.android"), declared)
    }

    @Test
    fun `blocking nothing declares nothing`() {
        assertEquals(emptySet<String>(), declaredPackages(all, emptySet()))
    }

    @Test
    fun `an empty rule set declares nothing`() {
        assertEquals(
            emptySet<String>(),
            declaredPackages(RuleSet(version = 0, apps = emptyMap()), setOf(Surface.REELS)),
        )
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*DeclaredPackagesTest*'`
Expected: FAIL to compile — `Unresolved reference 'declaredPackages'`.

- [ ] **Step 3: Write the function**

Create `app/src/main/kotlin/com/insta/reelsoff/service/DeclaredPackages.kt`:

```kotlin
package com.insta.reelsoff.service

import com.insta.detection.RuleSet
import com.insta.detection.Surface

/**
 * The packages the service should declare to Android, given the rules and what
 * the user has switched on.
 *
 * `android:packageNames` is enforced by the system, not by this app: a package
 * left out of it cannot reach the service at all. Deriving the list from the
 * settings is what makes "the permission follows the switch" true rather than
 * merely intended — switching Snapchat off makes the service incapable of
 * receiving Snapchat's screens, not just uninterested in them.
 */
fun declaredPackages(ruleSet: RuleSet, blocked: Set<Surface>): Set<String> =
    ruleSet.apps
        .filterValues { app -> app.surfaces.keys.any { it in blocked } }
        .keys
```

- [ ] **Step 4: Run and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*DeclaredPackagesTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Let the manifest config carry no package list**

In `app/src/main/res/xml/accessibility_service_config.xml`, delete the `android:packageNames` attribute and add this comment above the element:

```xml
<!--
  No android:packageNames here on purpose: the list is set at runtime from the
  user's settings, in InstagramWatcherService.applyDeclaredPackages, so a package
  is visible to the service only while its blocking is switched on. The static
  attribute would be the union of everything and could not narrow.

  The service sets the list before it processes anything, and an empty list is
  written as one unmatchable package rather than as null — null means "every
  app" to Android, which is the opposite of what an empty setting should mean.
-->
```

- [ ] **Step 6: Apply the list at runtime, and stop hardcoding Instagram**

In `app/src/main/kotlin/com/insta/reelsoff/service/InstagramWatcherService.kt`:

Add a field next to the other service state:

```kotlin
    @Volatile
    private var ruleSet: RuleSet = RuleSet(version = 0, apps = emptyMap())
```

In `onServiceConnected`, after `classifier = ScreenClassifier(loaded.ruleSet)`, add:

```kotlin
            ruleSet = loaded.ruleSet
            applyDeclaredPackages(settings.blockedSurfaces)
```

In the settings collector, replace `.collectLatest { settings = it }` with:

```kotlin
                        .collectLatest {
                            settings = it
                            applyDeclaredPackages(it.blockedSurfaces)
                        }
```

Add the method:

```kotlin
    /**
     * Narrows what Android is allowed to send this service to the packages whose
     * blocking is switched on.
     *
     * Never lets anything escape: this runs inside the settings collector, and an
     * exception here would take the service down, which Android may answer by
     * disabling it for good — leaving the user believing they are protected.
     */
    private fun applyDeclaredPackages(blocked: Set<Surface>) {
        runCatching {
            val packages = declaredPackages(ruleSet, blocked)
            serviceInfo = serviceInfo.apply {
                // A null packageNames means "every app" to Android, so an empty
                // selection must be expressed as a package that cannot match.
                packageNames = if (packages.isEmpty()) arrayOf(NO_PACKAGE) else packages.toTypedArray()
            }
            Log.i(TAG, "declared packages: ${packages.size}")
        }.onFailure { Log.e(TAG, "could not narrow declared packages", it) }
    }
```

In `handle`, replace the Instagram check and the snapshot's package with the event's own package:

```kotlin
    private fun handle(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (!throttle.shouldProcess()) return

        val root = rootInActiveWindow ?: return
        val snapshot = walker.walk(
            root = AccessibilityNodeLike(root),
            packageName = packageName,
            capturedAtMillis = System.currentTimeMillis(),
        )
```

Delete the `INSTAGRAM_PACKAGE` constant and add to the companion:

```kotlin
        /** Matches no installed app; see applyDeclaredPackages. */
        private const val NO_PACKAGE = "com.insta.reelsoff.none"
```

Add the imports `com.insta.detection.RuleSet` and `com.insta.detection.Surface` if they are not already present.

- [ ] **Step 7: Run the whole JVM suite**

Run: `./gradlew :detection:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add app/
git commit -m "feat: declare only the packages whose blocking is switched on"
```

---

### Task 5: One total, and switches grouped by app

**Files:**
- Modify: `app/src/main/kotlin/com/insta/reelsoff/ui/HomeViewModel.kt`
- Modify: `app/src/main/kotlin/com/insta/reelsoff/ui/HomeScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/com/insta/reelsoff/ui/SurfaceGroupsTest.kt` (create)

**Interfaces:**
- Consumes: `BlockSettings.blockedSurfaces`, `SettingsStore.setSurfaceBlocked`, `DailyCount`.
- Produces: `fun surfaceGroups(installed: Set<String>): List<SurfaceGroup>` and `data class SurfaceGroup(val packageName: String, val labelResId: Int, val surfaces: List<Surface>)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/insta/reelsoff/ui/SurfaceGroupsTest.kt`:

```kotlin
package com.insta.reelsoff.ui

import com.insta.detection.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceGroupsTest {

    @Test
    fun `offers only the apps that are installed`() {
        // Offering to block Snapchat to someone who does not have it is noise.
        val groups = surfaceGroups(installed = setOf("com.instagram.android"))

        assertEquals(listOf("com.instagram.android"), groups.map { it.packageName })
    }

    @Test
    fun `groups every youtube variant under one heading`() {
        val groups = surfaceGroups(
            installed = setOf("com.google.android.youtube", "app.revanced.android.youtube"),
        )

        assertEquals(1, groups.size)
        assertEquals(listOf(Surface.SHORTS), groups.single().surfaces)
    }

    @Test
    fun `instagram comes first`() {
        val groups = surfaceGroups(
            installed = setOf("com.snapchat.android", "com.instagram.android"),
        )

        assertEquals("com.instagram.android", groups.first().packageName)
    }

    @Test
    fun `nothing installed means nothing to show`() {
        assertTrue(surfaceGroups(installed = emptySet()).isEmpty())
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SurfaceGroupsTest*'`
Expected: FAIL to compile — `Unresolved reference 'surfaceGroups'`.

- [ ] **Step 3: Write the grouping**

Create the function in `app/src/main/kotlin/com/insta/reelsoff/ui/SurfaceGroups.kt`:

```kotlin
package com.insta.reelsoff.ui

import com.insta.detection.Surface

/** One block of switches in the screen: an app, and the surfaces it owns. */
data class SurfaceGroup(
    val packageName: String,
    val labelResId: Int,
    val surfaces: List<Surface>,
)

/**
 * The switch groups worth showing, given what is installed.
 *
 * YouTube's three installable variants share one heading and one switch: from
 * the user's side they are the same product, and offering "Shorts" three times
 * would be noise. The first installed variant names the group.
 */
fun surfaceGroups(installed: Set<String>): List<SurfaceGroup> {
    val catalogue = listOf(
        Triple(
            listOf("com.instagram.android"),
            com.insta.reelsoff.R.string.app_instagram,
            listOf(Surface.REELS, Surface.EXPLORE),
        ),
        Triple(
            listOf(
                "com.google.android.youtube",
                "com.google.android.apps.youtube.kids",
                "app.revanced.android.youtube",
            ),
            com.insta.reelsoff.R.string.app_youtube,
            listOf(Surface.SHORTS),
        ),
        Triple(
            listOf("com.snapchat.android"),
            com.insta.reelsoff.R.string.app_snapchat,
            listOf(Surface.SPOTLIGHT),
        ),
    )

    return catalogue.mapNotNull { (packages, labelResId, surfaces) ->
        val present = packages.firstOrNull { it in installed } ?: return@mapNotNull null
        SurfaceGroup(present, labelResId, surfaces)
    }
}
```

The test asserts on `packageName` and `surfaces` only, so the label resource is not exercised there — it is exercised by the screen.

- [ ] **Step 4: Add the strings**

In `app/src/main/res/values/strings.xml`, add:

```xml
    <string name="app_instagram">Instagram</string>
    <string name="app_youtube">YouTube</string>
    <string name="app_snapchat">Snapchat</string>
    <string name="block_shorts">Bloquer les Shorts</string>
    <string name="block_spotlight">Bloquer Spotlight</string>
    <string name="today_total">Retenu %1$d fois</string>
    <string name="declared_packages">Applications observées : %1$s</string>
```

and change `today` to `<string name="today">Aujourd\'hui</string>` if it is not already exactly that.

- [ ] **Step 5: Report installed packages from the ViewModel**

In `app/src/main/kotlin/com/insta/reelsoff/ui/HomeViewModel.kt`:

Add to `HomeUiState`: `val installedPackages: Set<String> = emptySet()`.

Add a private helper and call it where `refreshServiceStatus` is called, storing into a `MutableStateFlow<Set<String>>` that joins the `combine`:

```kotlin
    private val installedPackages = MutableStateFlow(emptySet<String>())

    /** Read on resume: the user can install or remove an app while this screen is closed. */
    fun refreshInstalledPackages() {
        val manager = getApplication<Application>().packageManager
        installedPackages.value = ALL_KNOWN_PACKAGES.filter { candidate ->
            runCatching { manager.getPackageInfo(candidate, 0) }.isSuccess
        }.toSet()
    }
```

with, at file scope:

```kotlin
private val ALL_KNOWN_PACKAGES = setOf(
    "com.instagram.android",
    "com.google.android.youtube",
    "com.google.android.apps.youtube.kids",
    "app.revanced.android.youtube",
    "com.snapchat.android",
)
```

`combine` takes at most five flows in its typed overloads and this screen now needs six. Use the vararg form:

```kotlin
    val uiState: StateFlow<HomeUiState> = combine(
        serviceEnabled,
        settingsStore.settings,
        events,
        settingsStore.ruleLoadStatus,
        settingsStore.captureStatus,
        installedPackages,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        HomeUiState(
            serviceEnabled = values[0] as Boolean,
            settings = values[1] as BlockSettings,
            history = dailyCounts(values[2] as List<BlockEvent>, zone, LocalDate.now(zone), HISTORY_DAYS),
            degraded = isDegraded(values[2] as List<BlockEvent>) || (values[3] as RuleLoadStatus).error != null,
            ruleLoadError = (values[3] as RuleLoadStatus).error,
            captureStatus = values[4] as CaptureStatus,
            installedPackages = values[5] as Set<String>,
        )
    }
```

Add the import `com.insta.reelsoff.data.BlockEvent` and keep the existing `.catch { }` and `.stateIn(...)` untouched.

Replace `setBlockReels`/`setBlockExplore` with:

```kotlin
    fun setSurfaceBlocked(surface: Surface, blocked: Boolean) {
        viewModelScope.launch { settingsStore.setSurfaceBlocked(surface, blocked) }
    }
```

Call `refreshInstalledPackages()` from `MainActivity.onResume`, next to `refreshServiceStatus()`.

- [ ] **Step 6: Rework the screen**

In `app/src/main/kotlin/com/insta/reelsoff/ui/HomeScreen.kt`:

Replace the `TodayCounters` section with a single large total and a small breakdown line. `HomeUiState` gains, next to `todayReels`:

```kotlin
    val todayTotal: Int get() = history.lastOrNull()?.total ?: 0
```

and the screen renders `Counter(state.todayTotal, stringResource(R.string.today))` followed by a `bodySmall` line listing each blocked surface and its count for today, in `EncreDouce`. `DailyCount` only carries `reels` and `explore`, so add `shorts` and `spotlight` fields to it and to `dailyCounts` in `app/src/main/kotlin/com/insta/reelsoff/data/DailyCount.kt`, mapping `Surface.SHORTS.name` and `Surface.SPOTLIGHT.name` the same way the existing two are mapped. `DailyCountTest` must gain a case for one of the new surfaces.

Replace the single `Section(title = blocking_title)` block with one `Section` per entry of `surfaceGroups(installed = state.installedPackages)`, titled by the group's label, each containing a `SwitchRow` per surface whose label is:

| Surface | String |
|---|---|
| `REELS` | `R.string.block_reels` |
| `EXPLORE` | `R.string.block_explore` |
| `SHORTS` | `R.string.block_shorts` |
| `SPOTLIGHT` | `R.string.block_spotlight` |

with `checked = surface in state.settings.blockedSurfaces` and `onCheckedChange = { onSurfaceBlockedChanged(surface, it) }`.

In the Maintenance section, add one `bodySmall` line in `EncreDouce` reading `R.string.declared_packages` formatted with the sorted, comma-joined group labels of the currently blocked surfaces, so the user can see what the service is allowed to observe right now.

`HomeScreen`'s parameters `onBlockReelsChanged` and `onBlockExploreChanged` collapse into one: `onSurfaceBlockedChanged: (Surface, Boolean) -> Unit`. Update `MainActivity` accordingly.

- [ ] **Step 7: Run the whole JVM suite and build**

Run: `./gradlew :detection:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/
git commit -m "feat: group the switches by app and show one daily total"
```

---

### Task 6: On-device verification

Not a code task. Runs in the controller session with the user, because it needs their phone and their apps.

- [ ] **Step 1: Install and enable, in this order**

Reinstalling resets the package's restricted-settings flag, and force-stopping the app makes Android drop the service from the enabled list seconds later while an immediate read-back still looks fine. Enable last, never force-stop afterwards, and trust `dumpsys`, not `settings get`.

```bash
./gradlew :app:installDebug
adb shell appops set com.insta.reelsoff ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services \
  com.insta.reelsoff/com.insta.reelsoff.service.InstagramWatcherService
adb shell settings put secure accessibility_enabled 1
sleep 8
adb shell dumpsys accessibility | grep -A1 "Bound services"
```

- [ ] **Step 2: Confirm Instagram is unchanged**

Reels tab blocked, a received reel still watchable, Explore still lands on the search field. Any regression here outranks every new feature in this plan.

- [ ] **Step 3: Confirm the declared list actually narrows**

With YouTube blocking off, then on:

```bash
adb shell dumpsys accessibility | grep -i "packageNames"
```

Expected: YouTube absent from the list while its switch is off, present while it is on. This is the claim the whole design rests on; if it does not hold, say so rather than shipping the privacy story.

- [ ] **Step 4: Try the borrowed rules for real**

Open YouTube, open a Short. Then Snapchat, open Spotlight. Record what happens — including nothing happening, which is the likely outcome for at least one of them, since these ids were taken from another project and have never been seen on this device.

- [ ] **Step 5: Capture what did not work**

For any surface that failed, run a capture on that app and analyse the tree, reading only `viewId` and `className`. Then write real rules, derive scrubbed fixtures the way the Instagram ones were derived, and add fixture tests. This is what closes the gap the spec left open.
