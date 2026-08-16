# Reel reçu en message — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a reel someone sent in a conversation play, and block the suggested reels that follow it.

**Architecture:** No new surface, no state, no counter. Two optional fields are added to the rule engine — `requireOnScreen` (accept only nodes of strictly positive area) and `absentViewIds` (veto the signal when a guard node is visible) — and one HIGH signal is added to the existing `REELS` surface: the reel viewer's pager, on screen, with no reply bar. A reel received in a conversation carries the reply bar, so no signal matches it; the suggested reel that follows carries none of it, so it is blocked by the machinery already in place.

**Tech Stack:** Kotlin, `:detection` (pure JVM, no `android.*`), kotlinx.serialization, JUnit 4, Gradle version catalog.

**Spec:** `docs/superpowers/specs/2026-08-16-reel-recu-en-message-design.md`

## Global Constraints

- **`:detection` must contain no `android.*` import.** This is the structural constraint of the project.
- **Node `text` is never read, logged or persisted.** Only `viewIdResourceName`, `contentDescription`, `className`, `isSelected`, `isClickable`, `boundsInScreen` may be read.
- **No network dependency, no network call, ever.**
- **No exception may escape `onAccessibilityEvent` or `onServiceConnected`.** Android may permanently disable a service that crashes, leaving the user believing they are protected.
- **Versions live in `gradle/libs.versions.toml`.** No hard-coded coordinates. This plan adds no dependency.
- **UI copy in French; code, symbols and commit messages in English.**
- **Do not reintroduce a MEDIUM tier for REELS.** `requireOnScreen` makes it look viable again; the leftover "Reels" node that forced its removal in v1 is off-screen too. Out of scope here — see the spec's "Interdit explicitement".
- **Never commit an Instagram screenshot or a raw view-tree capture.** Fixtures are scrubbed before they enter the repository.
- Test commands:
  - JVM: `./gradlew :detection:test :app:testDebugUnitTest`
  - A single class: `./gradlew :detection:test --tests '*ScreenClassifierTest*'`
  - Instrumented (`--tests` does NOT work on this AGP): `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `detection/src/main/kotlin/com/insta/detection/ScreenSnapshot.kt` | Neutral view-tree types | Add `Bounds.isOnScreen` |
| `detection/src/main/kotlin/com/insta/detection/Rules.kt` | Rule vocabulary | Add two optional `Signal` fields |
| `detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt` | Screen recognition | Honour both fields in `matches` |
| `detection/src/main/kotlin/com/insta/detection/RuleSetParser.kt` | Fault-tolerant rule loading | Validate `absentViewIds` entries |
| `detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt` | Logic, synthetic trees | New cases |
| `detection/src/test/kotlin/com/insta/detection/RuleSetParserTest.kt` | Parsing, malformed input | New cases |
| `detection/src/test/resources/fixtures/*.json` | Ground truth, real trees | Three new scrubbed fixtures |
| `detection/src/test/kotlin/com/insta/detection/RealFixtureTest.kt` | Shipped rules vs reality | New assertions |
| `app/src/main/assets/rules.json` | Shipped rules — also the `:detection` test resource, via `sourceSets` in `detection/build.gradle.kts` | New HIGH signal |
| `app/src/main/res/values/strings.xml` | UI copy | Widen one label |

`app/src/main/assets/rules.json` is wired into `:detection`'s test resources, so editing it changes both the shipped rules and what `RealFixtureTest` runs against. There is no second copy to keep in sync.

---

### Task 1: `requireOnScreen` on a signal

Leftover nodes from the previous screen stay in Instagram's tree with degenerate bounds. Measured: the feed's leftover pager is `left=1080, right=1080` (0 wide), the profile's is `right=-2160`. The real one is 1080 wide. Without this field the rule added in Task 3 would block the user's own feed.

**Files:**
- Modify: `detection/src/main/kotlin/com/insta/detection/ScreenSnapshot.kt`
- Modify: `detection/src/main/kotlin/com/insta/detection/Rules.kt`
- Modify: `detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt`
- Test: `detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt`

**Interfaces:**
- Consumes: `Bounds`, `NodeSummary`, `Signal`, `SignalType`, `Tier`, `ScreenClassifier.classify`, and the test helpers `node(...)`, `snapshot(...)` in `detection/src/test/kotlin/com/insta/detection/TestNodes.kt`.
- Produces:
  - `val Bounds.isOnScreen: Boolean` — true when `right > left && bottom > top`
  - `Signal(..., val requireOnScreen: Boolean = false)`

- [ ] **Step 1: Write the failing tests**

Append to `detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt`:

```kotlin
    private fun pagerRules(requireOnScreen: Boolean) = RuleSet(
        version = 1,
        surfaces = mapOf(
            Surface.REELS to SurfaceRules(
                listOf(
                    Signal(
                        tier = Tier.HIGH,
                        type = SignalType.VIEW_ID,
                        value = "pager",
                        requireSelected = false,
                        requireOnScreen = requireOnScreen,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a zero-width node does not satisfy an on-screen signal`() {
        // Measured on the real feed: Instagram leaves the previous screen's
        // pager in the tree at left=1080, right=1080.
        val leftover = snapshot(
            listOf(node(index = 0, viewId = "pager", bounds = Bounds(1080, 152, 1080, 2235))),
        )

        val result = ScreenClassifier(pagerRules(requireOnScreen = true)).classify(leftover)

        assertEquals(Surface.OTHER, result.surface)
    }

    @Test
    fun `a negative-width node does not satisfy an on-screen signal`() {
        // Measured on the real profile screen: right=-2160.
        val leftover = snapshot(
            listOf(node(index = 0, viewId = "pager", bounds = Bounds(0, 152, -2160, 2235))),
        )

        val result = ScreenClassifier(pagerRules(requireOnScreen = true)).classify(leftover)

        assertEquals(Surface.OTHER, result.surface)
    }

    @Test
    fun `a zero-height node does not satisfy an on-screen signal`() {
        val flat = snapshot(
            listOf(node(index = 0, viewId = "pager", bounds = Bounds(0, 152, 1080, 152))),
        )

        val result = ScreenClassifier(pagerRules(requireOnScreen = true)).classify(flat)

        assertEquals(Surface.OTHER, result.surface)
    }

    @Test
    fun `a full-size node satisfies an on-screen signal`() {
        val visible = snapshot(
            listOf(node(index = 0, viewId = "pager", bounds = Bounds(0, 152, 1080, 2235))),
        )

        val result = ScreenClassifier(pagerRules(requireOnScreen = true)).classify(visible)

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `without the flag a degenerate node still matches`() {
        // Every shipped rule leaves requireOnScreen at its default, so this is
        // the guarantee that none of them changes meaning.
        val leftover = snapshot(
            listOf(node(index = 0, viewId = "pager", bounds = Bounds(1080, 152, 1080, 2235))),
        )

        val result = ScreenClassifier(pagerRules(requireOnScreen = false)).classify(leftover)

        assertEquals(Surface.REELS, result.surface)
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew :detection:test --tests '*ScreenClassifierTest*'`
Expected: FAIL to compile — `No value passed for parameter 'requireOnScreen'` / unresolved reference.

- [ ] **Step 3: Add `Bounds.isOnScreen`**

In `detection/src/main/kotlin/com/insta/detection/ScreenSnapshot.kt`, directly after the `Bounds` class:

```kotlin
/**
 * True when the node occupies real space.
 *
 * Instagram does not tear down the previous screen, so its nodes linger in the
 * tree with collapsed or negative bounds. Measured on real captures: a leftover
 * reel pager reports width 0 on the feed and -2160 on the profile, while the
 * displayed one reports 1080. No threshold is needed to tell them apart.
 */
val Bounds.isOnScreen: Boolean
    get() = right > left && bottom > top
```

- [ ] **Step 4: Add the field to `Signal`**

In `detection/src/main/kotlin/com/insta/detection/Rules.kt`, replace the `Signal` class with:

```kotlin
@Serializable
data class Signal(
    val tier: Tier,
    val type: SignalType,
    val value: String? = null,
    val anyOf: List<String> = emptyList(),
    val requireSelected: Boolean = true,
    /**
     * Restricts the signal to nodes of strictly positive area. Off by default:
     * turning it on globally would change the meaning of rules already
     * calibrated and verified on a device.
     */
    val requireOnScreen: Boolean = false,
)
```

- [ ] **Step 5: Honour the field in the classifier**

In `detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt`, replace `matches` and `satisfies` with:

```kotlin
    private fun matches(
        signal: Signal,
        snapshot: ScreenSnapshot,
        navBar: List<NodeSummary>?,
    ): Boolean {
        val nodes =
            if (signal.requireOnScreen) snapshot.nodes.filter { it.bounds.isOnScreen }
            else snapshot.nodes

        return when (signal.type) {
            SignalType.VIEW_ID -> nodes.any { node ->
                node.viewId == signal.value && node.satisfies(signal)
            }

            SignalType.CONTENT_DESCRIPTION -> nodes.any { node ->
                node.contentDescription != null &&
                    signal.anyOf.any { it.equals(node.contentDescription, ignoreCase = true) } &&
                    node.satisfies(signal)
            }

            SignalType.NAV_BAR_INDEX -> {
                val index = signal.value?.toIntOrNull()
                val tab = if (index == null) null else navBar?.getOrNull(index)
                tab != null && tab.satisfies(signal) &&
                    (!signal.requireOnScreen || tab.bounds.isOnScreen)
            }
        }
    }

    private fun NodeSummary.satisfies(signal: Signal): Boolean =
        !signal.requireSelected || isSelected
```

The nav bar is located from the whole tree, not the filtered list, so its geometric heuristic keeps behaving exactly as before; the on-screen condition is applied to the chosen tab instead.

- [ ] **Step 6: Run the tests and watch them pass**

Run: `./gradlew :detection:test --tests '*ScreenClassifierTest*'`
Expected: PASS, including every pre-existing case.

- [ ] **Step 7: Run the whole JVM suite**

Run: `./gradlew :detection:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 87 tests plus the 5 added here, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add detection/src/main/kotlin/com/insta/detection/ScreenSnapshot.kt \
        detection/src/main/kotlin/com/insta/detection/Rules.kt \
        detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt \
        detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt
git commit -m "feat: let a signal require a node that occupies real space"
```

---

### Task 2: `absentViewIds` on a signal

The signal that recognises a suggested reel has to say "pager present **and** reply bar absent". The engine can only express presence today.

**Files:**
- Modify: `detection/src/main/kotlin/com/insta/detection/Rules.kt`
- Modify: `detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt`
- Modify: `detection/src/main/kotlin/com/insta/detection/RuleSetParser.kt`
- Test: `detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt`
- Test: `detection/src/test/kotlin/com/insta/detection/RuleSetParserTest.kt`

**Interfaces:**
- Consumes: everything Task 1 produced — `Bounds.isOnScreen`, `Signal.requireOnScreen`.
- Produces: `Signal(..., val absentViewIds: List<String> = emptyList())`.

- [ ] **Step 1: Write the failing classifier tests**

Append to `detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt`:

```kotlin
    private fun guardedRules(requireOnScreen: Boolean = true) = RuleSet(
        version = 1,
        surfaces = mapOf(
            Surface.REELS to SurfaceRules(
                listOf(
                    Signal(
                        tier = Tier.HIGH,
                        type = SignalType.VIEW_ID,
                        value = "pager",
                        requireSelected = false,
                        requireOnScreen = requireOnScreen,
                        absentViewIds = listOf("reply_bar", "sender_name"),
                    ),
                ),
            ),
        ),
    )

    private val visiblePager =
        node(index = 0, viewId = "pager", bounds = Bounds(0, 152, 1080, 2235))

    @Test
    fun `a guarded signal matches when no guard is present`() {
        val result = ScreenClassifier(guardedRules()).classify(snapshot(listOf(visiblePager)))

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `a single guard suppresses the signal`() {
        // The reel a contact sent: the reply bar is what makes it exempt.
        val withReplyBar = snapshot(
            listOf(
                visiblePager,
                node(index = 1, viewId = "reply_bar", bounds = Bounds(0, 2000, 1080, 2200)),
            ),
        )

        assertEquals(Surface.OTHER, ScreenClassifier(guardedRules()).classify(withReplyBar).surface)
    }

    @Test
    fun `any one of several guards is enough`() {
        val withSender = snapshot(
            listOf(
                visiblePager,
                node(index = 1, viewId = "sender_name", bounds = Bounds(0, 300, 600, 360)),
            ),
        )

        assertEquals(Surface.OTHER, ScreenClassifier(guardedRules()).classify(withSender).surface)
    }

    @Test
    fun `a degenerate guard does not suppress an on-screen signal`() {
        // Symmetry with Task 1: if a leftover reply bar counted as present, the
        // trap would fire in reverse and silently cancel a legitimate block.
        val leftoverGuard = snapshot(
            listOf(
                visiblePager,
                node(index = 1, viewId = "reply_bar", bounds = Bounds(1080, 2000, 1080, 2200)),
            ),
        )

        assertEquals(Surface.REELS, ScreenClassifier(guardedRules()).classify(leftoverGuard).surface)
    }

    @Test
    fun `an empty guard list changes nothing`() {
        val rules = RuleSet(
            version = 1,
            surfaces = mapOf(
                Surface.REELS to SurfaceRules(
                    listOf(
                        Signal(
                            tier = Tier.HIGH,
                            type = SignalType.VIEW_ID,
                            value = "pager",
                            requireSelected = false,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(Surface.REELS, ScreenClassifier(rules).classify(snapshot(listOf(visiblePager))).surface)
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew :detection:test --tests '*ScreenClassifierTest*'`
Expected: FAIL to compile — `Cannot find a parameter with this name: absentViewIds`.

- [ ] **Step 3: Add the field to `Signal`**

In `detection/src/main/kotlin/com/insta/detection/Rules.kt`, add the field after `requireOnScreen`:

```kotlin
    /**
     * The signal counts only if NONE of these ids is found. Searched over the
     * same node set as the positive match, so a leftover, degenerate guard does
     * not cancel a real block when [requireOnScreen] is set.
     */
    val absentViewIds: List<String> = emptyList(),
```

- [ ] **Step 4: Apply the guard in the classifier**

In `detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt`, insert the guard check in `matches`, between the `nodes` declaration and the `return when (...)`:

```kotlin
        if (signal.absentViewIds.isNotEmpty() &&
            nodes.any { it.viewId != null && it.viewId in signal.absentViewIds }
        ) {
            return false
        }
```

The guard search ignores `requireSelected` on purpose: a reply bar exists or it does not, it is never "selected".

- [ ] **Step 5: Run the classifier tests and watch them pass**

Run: `./gradlew :detection:test --tests '*ScreenClassifierTest*'`
Expected: PASS.

- [ ] **Step 6: Write the failing parser tests**

Append to `detection/src/test/kotlin/com/insta/detection/RuleSetParserTest.kt`:

```kotlin
    @Test
    fun `reads the new signal fields`() {
        val raw = """
            {
              "version": 1,
              "surfaces": {
                "REELS": {
                  "signals": [
                    { "tier": "HIGH", "type": "VIEW_ID", "value": "pager",
                      "requireSelected": false, "requireOnScreen": true,
                      "absentViewIds": ["reply_bar", "sender_name"] }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Success)
        val signal = (result as ParseResult.Success).ruleSet.surfaces.getValue(Surface.REELS).signals.single()
        assertTrue(signal.requireOnScreen)
        assertEquals(listOf("reply_bar", "sender_name"), signal.absentViewIds)
    }

    @Test
    fun `the new fields default to the previous behaviour when omitted`() {
        val raw = """
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

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Success)
        val signal = (result as ParseResult.Success).ruleSet.surfaces.getValue(Surface.REELS).signals.single()
        assertFalse(signal.requireOnScreen)
        assertTrue(signal.absentViewIds.isEmpty())
    }

    @Test
    fun `a blank guard id is rejected`() {
        // A hand-edited rules file with a stray empty string would otherwise
        // silently match nothing, which reads exactly like a working rule.
        val raw = """
            {
              "version": 1,
              "surfaces": {
                "REELS": {
                  "signals": [
                    { "tier": "HIGH", "type": "VIEW_ID", "value": "pager",
                      "absentViewIds": ["reply_bar", "  "] }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Failure)
    }
```

If `assertFalse` is not already imported in this file, add `import org.junit.Assert.assertFalse`.

- [ ] **Step 7: Run the parser tests and watch them fail**

Run: `./gradlew :detection:test --tests '*RuleSetParserTest*'`
Expected: the first two PASS (kotlinx.serialization fills the defaults), `a blank guard id is rejected` FAILS — the parser accepts it today.

- [ ] **Step 8: Validate guard ids in the parser**

In `detection/src/main/kotlin/com/insta/detection/RuleSetParser.kt`, replace the whole `validate` function with this block-bodied version:

```kotlin
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
```

- [ ] **Step 9: Run the whole JVM suite**

Run: `./gradlew :detection:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 10: Commit**

```bash
git add detection/src/main/kotlin/com/insta/detection/Rules.kt \
        detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt \
        detection/src/main/kotlin/com/insta/detection/RuleSetParser.kt \
        detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt \
        detection/src/test/kotlin/com/insta/detection/RuleSetParserTest.kt
git commit -m "feat: let a signal be vetoed by the presence of other view ids"
```

---

### Task 3: Scrubbed fixtures and the shipped rule

The synthetic tests prove the logic; these fixtures prove reality. They are also the only thing standing between this feature and blocking the user's own feed.

**Files:**
- Create: `detection/src/test/resources/fixtures/direct_thread.json`
- Create: `detection/src/test/resources/fixtures/dm_reel.json`
- Create: `detection/src/test/resources/fixtures/suggested_reel.json`
- Modify: `app/src/main/assets/rules.json`
- Test: `detection/src/test/kotlin/com/insta/detection/RealFixtureTest.kt`

**Interfaces:**
- Consumes: `Signal.requireOnScreen` and `Signal.absentViewIds` from Tasks 1 and 2; the existing `RealFixtureTest.fixture(name)` helper, which reads `/fixtures/<name>.json` from test resources.
- Produces: three fixtures, and the shipped rule the on-device verification in Task 4 exercises.

- [ ] **Step 1: Derive the three fixtures, scrubbing every label**

The raw captures live in this session's scratch directory and contain real conversation content. They are never committed. If the directory is gone, ask the user to redo one capture (open a reel a contact sent, press "Capturer 60 secondes", tap the reel, swipe once) rather than inventing a tree.

```bash
python3 - <<'PY'
import json, pathlib
SRC = pathlib.Path('/home/user/.claude/jobs/70efdde3/tmp')
OUT = pathlib.Path('detection/src/test/resources/fixtures')
pairs = [
    (SRC/'caps/captures/capture-1786917898417-000.json',  OUT/'direct_thread.json'),
    (SRC/'caps2/captures/capture-1786918280353-000.json', OUT/'dm_reel.json'),
    (SRC/'caps/captures/capture-1786917898417-004.json',  OUT/'suggested_reel.json'),
]
for src, dst in pairs:
    d = json.loads(src.read_text())
    for n in d['nodes']:
        if n.get('contentDescription') is not None:
            n['contentDescription'] = '[scrubbed]'
    dst.write_text(json.dumps(d, indent=2))
    print(dst, len(d['nodes']), 'nodes')
PY
```

Unlike the v1 fixtures, **every** label is scrubbed with no allowlist: no rule in this feature reads a `contentDescription`, so no string needs to survive.

- [ ] **Step 2: Prove the scrub left nothing behind**

```bash
python3 - <<'PY'
import json, glob
for f in ['direct_thread','dm_reel','suggested_reel']:
    d = json.load(open('detection/src/test/resources/fixtures/%s.json' % f))
    vals = {n['contentDescription'] for n in d['nodes'] if n.get('contentDescription') is not None}
    assert vals <= {'[scrubbed]'}, (f, sorted(vals)[:5])
    print(f, 'ok —', len(d['nodes']), 'nodes')
PY
```

Expected: three `ok` lines, no assertion error. **If this fails, stop and do not commit.**

- [ ] **Step 3: Write the failing fixture tests**

Append to `detection/src/test/kotlin/com/insta/detection/RealFixtureTest.kt`:

```kotlin
    @Test
    fun `a reel someone sent is not blocked`() {
        // The one that matters: if this ever returns REELS, the feature does the
        // opposite of what was asked.
        assertEquals(Surface.OTHER, classifier.classify(fixture("dm_reel")).surface)
    }

    @Test
    fun `the conversation itself is not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("direct_thread")).surface)
    }

    @Test
    fun `the suggested reel that follows is blocked at the high tier`() {
        val result = classifier.classify(fixture("suggested_reel"))

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `the shipped reel-viewer rule requires an on-screen node`() {
        // feed, profile and direct all carry a leftover clips_viewer_view_pager.
        // Drop requireOnScreen from rules.json and this fails — which is the
        // point: the failure is what stops the feed being blocked.
        val signal = ruleSet.surfaces.getValue(Surface.REELS).signals
            .single { it.value?.endsWith("clips_viewer_view_pager") == true }

        assertTrue("the reel-viewer rule must require an on-screen node", signal.requireOnScreen)
        assertTrue("the reel-viewer rule must be guarded", signal.absentViewIds.isNotEmpty())
    }
```

- [ ] **Step 4: Run them and watch the right ones fail**

Run: `./gradlew :detection:test --tests '*RealFixtureTest*'`
Expected: `a reel someone sent is not blocked` and `the conversation itself is not blocked` PASS (no rule matches yet), `the suggested reel that follows is blocked at the high tier` and `the shipped reel-viewer rule requires an on-screen node` FAIL.

- [ ] **Step 5: Add the rule**

In `app/src/main/assets/rules.json`, add a second HIGH signal to `REELS`, after the `clips_tab` one:

```json
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
        { "tier": "LOW", "type": "NAV_BAR_INDEX",
          "value": "1", "requireSelected": true }
      ]
    },
```

`requireSelected: false` is not optional here: the field defaults to `true`, and every node involved measures `isSelected=false`, so without it the rule would never fire — and a rule that never fires is indistinguishable from one that works.

- [ ] **Step 6: Run the fixture tests and watch them all pass**

Run: `./gradlew :detection:test --tests '*RealFixtureTest*'`
Expected: PASS, including the five pre-existing v1 fixture tests. `feed is not blocked`, `profile is not blocked` and `direct messages are not blocked` passing here is the proof that the leftover pagers are handled.

- [ ] **Step 7: Run the whole JVM suite**

Run: `./gradlew :detection:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 8: Confirm nothing raw is staged**

```bash
git status --porcelain
python3 - <<'PY'
import json
for f in ['direct_thread','dm_reel','suggested_reel']:
    d = json.load(open('detection/src/test/resources/fixtures/%s.json' % f))
    bad = [n['contentDescription'] for n in d['nodes']
           if n.get('contentDescription') not in (None, '[scrubbed]')]
    print(f, 'labels non nettoyés:', len(bad))
    assert not bad, bad[:5]
PY
```

Expected: `git status` lists only the three new fixtures, `rules.json` and `RealFixtureTest.kt`; the script prints `0` for all three and raises nothing. Never `git add` anything from the scratch directory.

- [ ] **Step 9: Commit**

```bash
git add detection/src/test/resources/fixtures/direct_thread.json \
        detection/src/test/resources/fixtures/dm_reel.json \
        detection/src/test/resources/fixtures/suggested_reel.json \
        detection/src/test/kotlin/com/insta/detection/RealFixtureTest.kt \
        app/src/main/assets/rules.json
git commit -m "feat: block the suggested reels that follow one sent in a message"
```

---

### Task 4: Widen the switch label and verify on the device

The rule now covers more than the tab, so the label must stop promising less than it does. Then the two things captures cannot answer get measured.

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: the rule shipped in Task 3. `HomeScreen.kt` reads `R.string.block_reels`; the key does not change, so no Kotlin edit is needed.
- Produces: nothing later tasks depend on. This is the last task.

- [ ] **Step 1: Widen the label**

In `app/src/main/res/values/strings.xml`, replace the `block_reels` line:

```xml
    <string name="block_reels">Bloquer les Reels</string>
```

- [ ] **Step 2: Build and install**

```bash
./gradlew :app:installDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Re-enable the accessibility service, in this order**

Reinstalling resets the package's restricted-settings flag, and force-stopping the app makes Android drop the service from the enabled list several seconds later — an immediate read-back still looks fine. Enable last, never force-stop afterwards, and verify with `dumpsys`, which is the only source of truth.

```bash
adb shell appops set com.insta.reelsoff ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services \
  com.insta.reelsoff/com.insta.reelsoff.service.InstagramWatcherService
adb shell settings put secure accessibility_enabled 1
sleep 6
adb shell dumpsys accessibility | grep -A1 "Bound services"
```

Expected: a line containing `label=Digue`. If not, repeat; do not continue without it — a pass run with the service off looks exactly like a pass.

- [ ] **Step 4: Measure that a received reel is left alone**

Ask the user to open a reel a contact sent them and **not touch the screen for 20 seconds**. Meanwhile:

```bash
adb logcat -c
sleep 25
adb logcat -d | grep "ReelsOff"
```

Expected: **no** `blocked REELS` line. This is the acceptance criterion of the whole feature; if it fails, stop and report rather than tune thresholds.

- [ ] **Step 5: Measure that the next one is blocked, and where it lands**

Ask the user to swipe once to the next reel, then stay still for 12 seconds.

```bash
adb logcat -c
sleep 15
adb logcat -d | grep "ReelsOff"
```

Expected: **exactly one** `blocked REELS via HIGH`, then silence. Silence is what proves the screen left the reel viewer instead of sitting on it — a stuck viewer would log a fresh episode roughly every 2 seconds.

Ask the user where they landed. The spec expects the conversation. If back only moved one reel inside the pager, the existing escalation (three backs in three seconds, then home) takes over — note the observation in the commit message and raise it; do not redesign it here.

- [ ] **Step 6: Record the result and commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat: widen the Reels switch label to match what it now blocks"
```

Include in the commit body what steps 4 and 5 actually observed, quoting the decisive logcat lines.

- [ ] **Step 7: Leave the device usable**

```bash
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure accessibility_enabled
adb shell rm -f /sdcard/Android/data/com.insta.reelsoff/files/captures/*.json
```

Expected: the component listed, `1`, and no capture files left on the phone.
