# Quota quotidien, plage horaire et verrou par délai — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Digue a daily budget of short-video time, openable only inside a chosen window and only by an explicit unlock, with a cooldown lock that makes every loosening of the settings wait instead of applying at once.

**Architecture:** All the deciding is pure Kotlin in `:app`'s `service/` package, next to `Blocker` — no `android.*` import, JVM-tested. The accessibility service keeps its existing decision path and only swaps the set of blocked surfaces it hands to `Blocker`: an open pass means an empty set, which is the already-tested "not a blocked surface" path. Persistence rides on the existing `SettingsStore` DataStore. The UI gets one new panel file.

**Tech Stack:** Kotlin, Gradle multi-module (`:detection` pure / `:app`), kotlinx.serialization, DataStore Preferences, Jetpack Compose (Material3 only), JUnit4 + kotlin.test on JVM, AndroidJUnit4 for instrumented tests.

**Spec:** `docs/superpowers/specs/2026-08-17-quota-et-plage-horaire-design.md`

## Global Constraints

Copied from `CLAUDE.md`; every task's requirements implicitly include these.

- **`:detection` must contain no `android.*` import.** This feature adds nothing to `:detection` — all new pure code goes in `app/src/main/kotlin/com/insta/reelsoff/service/`, the same place `Blocker.kt` lives.
- **The `text` field of a view is never read, logged or persisted.**
- **No network dependency and no network call, ever.**
- **No exception may escape `onAccessibilityEvent` or `onServiceConnected`.** Android may permanently disable a service that crashes, leaving the user believing they are protected.
- **Versions live in `gradle/libs.versions.toml`**, never a hard-coded coordinate. This feature adds no dependency.
- **UI text in French; code, symbols, commit messages in English.**
- **`com.google.android.material` must stay absent.** Compose only.
- **Button shapes must be passed explicitly** (`shape = MaterialTheme.shapes.small`): `ButtonDefaults.shape` comes from Material tokens and is `CornerFull`, which would leave pills on an otherwise sharp-cornered screen.
- **Fail closed.** Anything unreadable, incoherent or absent means the pass is shut and normal blocking applies.

Test commands:

```bash
./gradlew :detection:test :app:testDebugUnitTest        # JVM suite
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>
```

`--tests` does not work on this AGP version; use the `-Pandroid...class=` form shown above.

**After any instrumented run**, the app is uninstalled by AGP's cleanup and the accessibility service goes with it. Reinstall, re-run `adb shell appops set com.insta.reelsoff ACCESS_RESTRICTED_SETTINGS allow`, re-enable the service, and re-read `dumpsys` at least 5 s later.

---

### Task 1: The allowance core — window, quota, remaining time

**Files:**
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/Allowance.kt`
- Test: `app/src/test/kotlin/com/insta/reelsoff/service/AllowanceTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `AllowanceSettings`, `AllowanceState`, `minuteOfDay(Long, ZoneId): Int`, `epochDayOf(Long, ZoneId): Long`, `windowContains(AllowanceSettings, Int): Boolean`, `consumedMillisAt(AllowanceSettings, AllowanceState, Long, ZoneId): Long`, `remainingMillis(AllowanceSettings, AllowanceState, Long, ZoneId): Long`, `passIsOpen(AllowanceSettings, AllowanceState, Long, ZoneId): Boolean`.

Note on time: these functions take **wall-clock epoch millis plus a `ZoneId`**, not the `Clock` interface used by `Blocker`. `Clock` yields `elapsedRealtime`, which cannot be turned into a local hour, and a time window is a question about the local hour. The cooldown lock in Task 3 uses both clocks precisely because of this.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/insta/reelsoff/service/AllowanceTest.kt`:

```kotlin
package com.insta.reelsoff.service

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

/** Local wall-clock time in [PARIS], as epoch millis. */
private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(2026, 8, day, hour, minute).atZone(PARIS).toInstant().toEpochMilli()

private fun dayOf(day: Int): Long = epochDayOf(at(day, 12), PARIS)

class AllowanceWindowTest {

    private val evening = AllowanceSettings(
        enabled = true,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
    )

    @Test
    fun `the window includes its start minute and excludes its end minute`() {
        assertTrue(windowContains(evening, 20 * 60))
        assertTrue(windowContains(evening, 20 * 60 + 59))
        assertFalse(windowContains(evening, 21 * 60))
        assertFalse(windowContains(evening, 19 * 60 + 59))
    }

    @Test
    fun `a window whose end precedes its start straddles midnight`() {
        val night = evening.copy(windowStartMinutes = 22 * 60, windowEndMinutes = 60)
        assertTrue(windowContains(night, 23 * 60))
        assertTrue(windowContains(night, 0))
        assertTrue(windowContains(night, 59))
        assertFalse(windowContains(night, 60))
        assertFalse(windowContains(night, 12 * 60))
    }

    @Test
    fun `a window whose end equals its start is empty, not a whole day`() {
        val empty = evening.copy(windowStartMinutes = 9 * 60, windowEndMinutes = 9 * 60)
        assertFalse(windowContains(empty, 9 * 60))
        assertFalse(windowContains(empty, 0))
        assertFalse(windowContains(empty, 12 * 60))
    }

    @Test
    fun `the minute of day is read in the given zone`() {
        assertEquals(20 * 60 + 30, minuteOfDay(at(17, 20, 30), PARIS))
    }
}

class AllowanceQuotaTest {

    private val settings = AllowanceSettings(enabled = true, quotaMillis = 300_000)

    @Test
    fun `a closed pass consumes nothing beyond what was already banked`() {
        val state = AllowanceState(day = dayOf(17), consumedMillis = 60_000)
        assertEquals(60_000, consumedMillisAt(settings, state, at(17, 20, 30), PARIS))
        assertEquals(240_000, remainingMillis(settings, state, at(17, 20, 30), PARIS))
    }

    @Test
    fun `an open pass consumes wall-clock time as it runs`() {
        val opened = at(17, 20, 30)
        val state = AllowanceState(day = dayOf(17), consumedMillis = 60_000, passOpenedAtEpochMillis = opened)
        assertEquals(120_000, consumedMillisAt(settings, state, opened + 60_000, PARIS))
        assertEquals(180_000, remainingMillis(settings, state, opened + 60_000, PARIS))
    }

    @Test
    fun `a state from an earlier day reads as a fresh quota`() {
        val state = AllowanceState(day = dayOf(16), consumedMillis = 300_000)
        assertEquals(0, consumedMillisAt(settings, state, at(17, 20, 30), PARIS))
        assertEquals(300_000, remainingMillis(settings, state, at(17, 20, 30), PARIS))
    }

    @Test
    fun `remaining time never goes negative`() {
        val state = AllowanceState(day = dayOf(17), consumedMillis = 400_000)
        assertEquals(0, remainingMillis(settings, state, at(17, 20, 30), PARIS))
    }
}

class PassIsOpenTest {

    private val settings = AllowanceSettings(
        enabled = true,
        quotaMillis = 300_000,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
    )
    private val open = AllowanceState(day = dayOf(17), passOpenedAtEpochMillis = at(17, 20, 0))

    @Test
    fun `a pass opened inside the window with quota left is open`() {
        assertTrue(passIsOpen(settings, open, at(17, 20, 30), PARIS))
    }

    @Test
    fun `a pass that was never opened is shut`() {
        assertFalse(passIsOpen(settings, AllowanceState(day = dayOf(17)), at(17, 20, 30), PARIS))
    }

    @Test
    fun `a pass is shut once the clock leaves the window`() {
        assertFalse(passIsOpen(settings, open, at(17, 21, 1), PARIS))
    }

    @Test
    fun `a pass is shut once the quota runs out`() {
        assertFalse(passIsOpen(settings, open, at(17, 20, 0) + 300_001, PARIS))
    }

    @Test
    fun `a pass opened on a previous day is shut, whatever the hour`() {
        val stale = AllowanceState(day = dayOf(16), passOpenedAtEpochMillis = at(16, 20, 0))
        assertFalse(passIsOpen(settings, stale, at(17, 20, 30), PARIS))
    }

    @Test
    fun `a disabled quota never opens, which is the strictest state`() {
        assertFalse(passIsOpen(settings.copy(enabled = false), open, at(17, 20, 30), PARIS))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: FAIL — compilation errors, `Unresolved reference: AllowanceSettings` and friends.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/insta/reelsoff/service/Allowance.kt`:

```kotlin
package com.insta.reelsoff.service

import java.time.Instant
import java.time.ZoneId

/**
 * The daily budget of short-video time, and when it may be spent.
 *
 * [enabled] reads the opposite way to intuition, and the whole lock depends on
 * getting it right: the quota *grants* time, it never removes any — blocking a
 * surface is governed by the switches, not by this. So `enabled = false` is the
 * strictest state (no pass can ever open), and a fresh install lands there, the
 * same way every new surface arrives switched off.
 *
 * The window is minutes since local midnight, not a timestamp. End strictly
 * below start straddles midnight (`22:00 -> 01:00`). End equal to start is an
 * empty window, never openable — between the two readings, the one that blocks.
 *
 * [cooldownMillis] starts at zero, and that is what makes the lock usable:
 * switching the quota on is itself a loosening, so a nonzero default would make
 * a fresh install wait a day before the feature it just enabled did anything.
 * At zero, the settings are free to arrange; choosing a delay is a tightening
 * and lands at once; from that moment every loosening waits. The lock is armed
 * by the user, deliberately, in one gesture.
 */
data class AllowanceSettings(
    val enabled: Boolean = false,
    val quotaMillis: Long = 5 * 60_000,
    val windowStartMinutes: Int = 20 * 60,
    val windowEndMinutes: Int = 21 * 60,
    val cooldownMillis: Long = 0,
)

/**
 * How much of today's budget is already spent, and whether a pass is running.
 *
 * [day] is the local epoch day the [consumedMillis] belongs to. A state whose
 * day is not today reads as a fresh quota — that is the whole of the daily
 * reset, and it needs no scheduled job to happen on time.
 *
 * [passOpenedAtEpochMillis] is 0 when shut. Wall clock, not elapsed real time:
 * the countdown has to survive the process dying and be comparable with the
 * local hour the window is expressed in.
 */
data class AllowanceState(
    val day: Long = 0,
    val consumedMillis: Long = 0,
    val passOpenedAtEpochMillis: Long = 0,
)

fun epochDayOf(nowEpochMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate().toEpochDay()

fun minuteOfDay(nowEpochMillis: Long, zone: ZoneId): Int =
    Instant.ofEpochMilli(nowEpochMillis).atZone(zone).let { it.hour * 60 + it.minute }

fun windowContains(settings: AllowanceSettings, minute: Int): Boolean {
    val start = settings.windowStartMinutes
    val end = settings.windowEndMinutes
    return when {
        start == end -> false
        start < end -> minute >= start && minute < end
        else -> minute >= start || minute < end
    }
}

/**
 * Today's spent time, including the pass currently running.
 *
 * Deliberately not capped at the quota: a pass whose process died can run past
 * it, and reporting the true figure beats reporting a tidy one. Every consumer
 * goes through [remainingMillis], which floors at zero.
 */
fun consumedMillisAt(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): Long {
    if (state.day != epochDayOf(nowEpochMillis, zone)) return 0
    val running = if (state.passOpenedAtEpochMillis == 0L) {
        0L
    } else {
        // Floored at zero: a wall clock moved backwards must not refund time.
        (nowEpochMillis - state.passOpenedAtEpochMillis).coerceAtLeast(0)
    }
    return state.consumedMillis + running
}

fun remainingMillis(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): Long = (settings.quotaMillis - consumedMillisAt(settings, state, nowEpochMillis, zone))
    .coerceAtLeast(0)

/**
 * Whether blocking is currently suspended.
 *
 * Every condition must hold, and the day check is what closes a pass across
 * midnight without anyone having to notice midnight passing.
 */
fun passIsOpen(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): Boolean =
    settings.enabled &&
        state.passOpenedAtEpochMillis != 0L &&
        state.day == epochDayOf(nowEpochMillis, zone) &&
        windowContains(settings, minuteOfDay(nowEpochMillis, zone)) &&
        remainingMillis(settings, state, nowEpochMillis, zone) > 0
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: PASS, all of `AllowanceWindowTest`, `AllowanceQuotaTest`, `PassIsOpenTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/insta/reelsoff/service/Allowance.kt app/src/test/kotlin/com/insta/reelsoff/service/AllowanceTest.kt
git commit -m "feat: a daily quota of short-video time, spendable inside a window"
```

---

### Task 2: Opening and closing the pass

**Files:**
- Modify: `app/src/main/kotlin/com/insta/reelsoff/service/Allowance.kt`
- Test: `app/src/test/kotlin/com/insta/reelsoff/service/AllowancePassTest.kt`

**Interfaces:**
- Consumes: everything Task 1 produced.
- Produces: `canOpenPass(AllowanceSettings, AllowanceState, Long, ZoneId): Boolean`, `openPass(AllowanceSettings, AllowanceState, Long, ZoneId): AllowanceState`, `closePass(AllowanceState, Long, ZoneId): AllowanceState`, `settle(AllowanceSettings, AllowanceState, Long, ZoneId): AllowanceState`.

`settle` is the single entry point every reader calls before using a state: it shuts a pass that has expired, left the window, or belongs to a past day. Both the service and the UI call it, so neither has to own the closing rules.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/insta/reelsoff/service/AllowancePassTest.kt`:

```kotlin
package com.insta.reelsoff.service

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(2026, 8, day, hour, minute).atZone(PARIS).toInstant().toEpochMilli()

private fun dayOf(day: Int): Long = epochDayOf(at(day, 12), PARIS)

class AllowancePassTest {

    private val settings = AllowanceSettings(
        enabled = true,
        quotaMillis = 300_000,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
    )

    @Test
    fun `a pass opens inside the window and stamps today`() {
        val now = at(17, 20, 30)
        val opened = openPass(settings, AllowanceState(), now, PARIS)
        assertEquals(now, opened.passOpenedAtEpochMillis)
        assertEquals(dayOf(17), opened.day)
        assertEquals(0, opened.consumedMillis)
        assertTrue(passIsOpen(settings, opened, now, PARIS))
    }

    @Test
    fun `opening carries over time already spent today`() {
        val state = AllowanceState(day = dayOf(17), consumedMillis = 120_000)
        val opened = openPass(settings, state, at(17, 20, 30), PARIS)
        assertEquals(120_000, opened.consumedMillis)
        assertEquals(180_000, remainingMillis(settings, opened, at(17, 20, 30), PARIS))
    }

    @Test
    fun `opening discards time spent on a previous day`() {
        val state = AllowanceState(day = dayOf(16), consumedMillis = 300_000)
        val opened = openPass(settings, state, at(17, 20, 30), PARIS)
        assertEquals(dayOf(17), opened.day)
        assertEquals(0, opened.consumedMillis)
    }

    @Test
    fun `a pass cannot open outside the window, out of quota, or disabled`() {
        val fresh = AllowanceState()
        assertFalse(canOpenPass(settings, fresh, at(17, 19, 0), PARIS))
        assertFalse(canOpenPass(settings.copy(enabled = false), fresh, at(17, 20, 30), PARIS))
        val spent = AllowanceState(day = dayOf(17), consumedMillis = 300_000)
        assertFalse(canOpenPass(settings, spent, at(17, 20, 30), PARIS))
    }

    @Test
    fun `opening a pass that cannot open changes nothing`() {
        val fresh = AllowanceState()
        assertEquals(fresh, openPass(settings, fresh, at(17, 19, 0), PARIS))
    }

    @Test
    fun `an already open pass is not reopened, which would refund its elapsed time`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val again = openPass(settings, opened, at(17, 20, 30), PARIS)
        assertEquals(opened, again)
    }

    @Test
    fun `closing banks the elapsed time and shuts the pass`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val closed = closePass(opened, at(17, 20, 0) + 90_000, PARIS)
        assertEquals(90_000, closed.consumedMillis)
        assertEquals(0, closed.passOpenedAtEpochMillis)
        assertEquals(210_000, remainingMillis(settings, closed, at(17, 20, 30), PARIS))
    }

    @Test
    fun `closing a shut pass changes nothing`() {
        val closed = AllowanceState(day = dayOf(17), consumedMillis = 90_000)
        assertEquals(closed, closePass(closed, at(17, 20, 30), PARIS))
    }

    @Test
    fun `closing a pass opened on a previous day starts today fresh`() {
        val stale = AllowanceState(day = dayOf(16), consumedMillis = 60_000, passOpenedAtEpochMillis = at(16, 23, 59))
        val closed = closePass(stale, at(17, 0, 30), PARIS)
        assertEquals(dayOf(17), closed.day)
        assertEquals(0, closed.consumedMillis)
        assertEquals(0, closed.passOpenedAtEpochMillis)
    }

    @Test
    fun `settle shuts a pass that ran out of quota`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val settled = settle(settings, opened, at(17, 20, 0) + 300_001, PARIS)
        assertEquals(0, settled.passOpenedAtEpochMillis)
        assertEquals(0, remainingMillis(settings, settled, at(17, 20, 30), PARIS))
    }

    @Test
    fun `settle shuts a pass once the clock leaves the window`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 55), PARIS)
        val settled = settle(settings, opened, at(17, 21, 5), PARIS)
        assertEquals(0, settled.passOpenedAtEpochMillis)
        assertEquals(600_000, settled.consumedMillis)
    }

    @Test
    fun `settle leaves a running pass alone`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        assertEquals(opened, settle(settings, opened, at(17, 20, 30), PARIS))
    }

    @Test
    fun `settle is idempotent`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val once = settle(settings, opened, at(17, 21, 5), PARIS)
        assertEquals(once, settle(settings, once, at(17, 21, 5), PARIS))
    }
}
```

Note on `settle shuts a pass once the clock leaves the window`: the pass opened at 20:55 and settles at 21:05, so ten minutes of wall clock elapsed — 600 000 ms — even though the quota is five. Banking the true elapsed time rather than the quota is deliberate: the figure has to stay honest, and `remainingMillis` floors at zero anyway.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: FAIL — `Unresolved reference: openPass`.

- [ ] **Step 3: Write the implementation**

Append to `app/src/main/kotlin/com/insta/reelsoff/service/Allowance.kt`:

```kotlin
/**
 * Whether the user may spend from today's budget right now.
 *
 * An already-open pass answers false: reopening one would move
 * [AllowanceState.passOpenedAtEpochMillis] forward and hand back every minute
 * it had already run.
 */
fun canOpenPass(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): Boolean =
    settings.enabled &&
        state.passOpenedAtEpochMillis == 0L &&
        windowContains(settings, minuteOfDay(nowEpochMillis, zone)) &&
        remainingMillis(settings, state, nowEpochMillis, zone) > 0

/** No-op when [canOpenPass] is false, so a caller never has to check twice. */
fun openPass(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): AllowanceState {
    if (!canOpenPass(settings, state, nowEpochMillis, zone)) return state
    val today = epochDayOf(nowEpochMillis, zone)
    return AllowanceState(
        day = today,
        // Time banked on an earlier day is not today's problem.
        consumedMillis = if (state.day == today) state.consumedMillis else 0,
        passOpenedAtEpochMillis = nowEpochMillis,
    )
}

/**
 * Banks the running pass's elapsed time and shuts it. Idempotent: a shut pass
 * comes back unchanged, so whichever of the UI and the service notices first
 * can close it without coordinating with the other.
 */
fun closePass(state: AllowanceState, nowEpochMillis: Long, zone: ZoneId): AllowanceState {
    if (state.passOpenedAtEpochMillis == 0L) return state
    val today = epochDayOf(nowEpochMillis, zone)
    // A pass opened on an earlier day: its time belongs to that day, which no
    // longer has a budget to charge. Today simply starts clean.
    if (state.day != today) return AllowanceState(day = today)
    val elapsed = (nowEpochMillis - state.passOpenedAtEpochMillis).coerceAtLeast(0)
    return state.copy(
        consumedMillis = state.consumedMillis + elapsed,
        passOpenedAtEpochMillis = 0,
    )
}

/**
 * Brings a stored state up to date with the clock: shuts a pass that expired,
 * left the window, or belongs to a past day. Every reader calls this before
 * using a state, so the three ways a pass ends live in one place instead of
 * being re-derived by each caller.
 */
fun settle(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): AllowanceState =
    if (state.passOpenedAtEpochMillis != 0L && !passIsOpen(settings, state, nowEpochMillis, zone)) {
        closePass(state, nowEpochMillis, zone)
    } else {
        state
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/insta/reelsoff/service/Allowance.kt app/src/test/kotlin/com/insta/reelsoff/service/AllowancePassTest.kt
git commit -m "feat: open, close and settle a scrolling pass"
```

---

### Task 3: The cooldown lock

**Files:**
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/AllowanceLock.kt`
- Test: `app/src/test/kotlin/com/insta/reelsoff/service/AllowanceLockTest.kt`

**Interfaces:**
- Consumes: `AllowanceSettings` and `windowContains` from Task 1.
- Produces: `LockedSettings(allowance: AllowanceSettings, blockedSurfaces: Set<Surface>)`, `PendingChange(proposed: LockedSettings, effectiveAtEpochMillis: Long, armedAtElapsedRealtime: Long, cooldownMillis: Long)`, `isLoosening(LockedSettings, LockedSettings): Boolean`, `armChange(LockedSettings, LockedSettings, Long, Long): PendingChange?`, `hasMatured(PendingChange, Long, Long): Boolean`, `effectiveSettings(LockedSettings, PendingChange?, Long, Long): LockedSettings`.

`PendingChange` is `@Serializable` — it is stored as one JSON string in DataStore in Task 4. `LockedSettings` and `AllowanceSettings` therefore need `@Serializable` too, and `Surface` already is (`:detection` uses kotlinx.serialization for the rule set).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/insta/reelsoff/service/AllowanceLockTest.kt`:

```kotlin
package com.insta.reelsoff.service

import com.insta.detection.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val BASE = LockedSettings(
    allowance = AllowanceSettings(
        enabled = true,
        quotaMillis = 300_000,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
        cooldownMillis = 24 * 3_600_000,
    ),
    blockedSurfaces = setOf(Surface.REELS, Surface.EXPLORE),
)

private fun withAllowance(block: AllowanceSettings.() -> AllowanceSettings) =
    BASE.copy(allowance = BASE.allowance.block())

class IsLooseningTest {

    @Test
    fun `an unchanged setting is not a loosening`() {
        assertFalse(isLoosening(BASE, BASE))
    }

    @Test
    fun `more quota loosens, less quota tightens`() {
        assertTrue(isLoosening(BASE, withAllowance { copy(quotaMillis = 600_000) }))
        assertFalse(isLoosening(BASE, withAllowance { copy(quotaMillis = 60_000) }))
    }

    @Test
    fun `a shorter cooldown loosens, a longer one tightens`() {
        assertTrue(isLoosening(BASE, withAllowance { copy(cooldownMillis = 3_600_000) }))
        assertFalse(isLoosening(BASE, withAllowance { copy(cooldownMillis = 48 * 3_600_000) }))
    }

    @Test
    fun `turning the quota on loosens, turning it off tightens`() {
        val off = withAllowance { copy(enabled = false) }
        assertFalse(isLoosening(BASE, off))
        assertTrue(isLoosening(off, BASE))
    }

    @Test
    fun `a wider window loosens, a narrower one tightens`() {
        assertTrue(isLoosening(BASE, withAllowance { copy(windowEndMinutes = 22 * 60) }))
        assertTrue(isLoosening(BASE, withAllowance { copy(windowStartMinutes = 19 * 60) }))
        assertFalse(isLoosening(BASE, withAllowance { copy(windowEndMinutes = 20 * 60 + 30) }))
    }

    @Test
    fun `a window moved sideways loosens, because it opens minutes that were shut`() {
        assertTrue(isLoosening(BASE, withAllowance { copy(windowStartMinutes = 21 * 60, windowEndMinutes = 22 * 60) }))
    }

    @Test
    fun `a window that straddles midnight is compared minute by minute`() {
        val night = withAllowance { copy(windowStartMinutes = 22 * 60, windowEndMinutes = 60) }
        val shorterNight = withAllowance { copy(windowStartMinutes = 22 * 60, windowEndMinutes = 30) }
        assertFalse(isLoosening(night, shorterNight))
        assertTrue(isLoosening(shorterNight, night))
    }

    @Test
    fun `unblocking a surface loosens, blocking one tightens`() {
        assertTrue(isLoosening(BASE, BASE.copy(blockedSurfaces = setOf(Surface.EXPLORE))))
        assertFalse(
            isLoosening(BASE, BASE.copy(blockedSurfaces = setOf(Surface.REELS, Surface.EXPLORE, Surface.SHORTS))),
        )
    }

    @Test
    fun `a change that both tightens and loosens counts wholly as a loosening`() {
        val mixed = BASE.copy(
            allowance = BASE.allowance.copy(quotaMillis = 60_000),
            blockedSurfaces = setOf(Surface.EXPLORE),
        )
        assertTrue(isLoosening(BASE, mixed))
    }
}

class ArmAndMatureTest {

    private val loosened = withAllowance { copy(quotaMillis = 600_000) }
    private val tightened = withAllowance { copy(quotaMillis = 60_000) }

    @Test
    fun `a tightening arms nothing, so the caller applies it at once`() {
        assertNull(armChange(BASE, tightened, nowEpochMillis = 1_000_000, nowElapsedRealtime = 50_000))
    }

    @Test
    fun `a loosening is armed for the current cooldown`() {
        val pending = armChange(BASE, loosened, nowEpochMillis = 1_000_000, nowElapsedRealtime = 50_000)!!
        assertEquals(loosened, pending.proposed)
        assertEquals(1_000_000 + 24 * 3_600_000, pending.effectiveAtEpochMillis)
        assertEquals(50_000, pending.armedAtElapsedRealtime)
        assertEquals(24 * 3_600_000, pending.cooldownMillis)
    }

    @Test
    fun `the cooldown in force is the current one, not the proposed one`() {
        val shorter = withAllowance { copy(cooldownMillis = 1_000) }
        val pending = armChange(BASE, shorter, nowEpochMillis = 1_000_000, nowElapsedRealtime = 50_000)!!
        assertEquals(24 * 3_600_000, pending.cooldownMillis)
    }

    @Test
    fun `a change is not mature before its wall-clock deadline`() {
        val pending = armChange(BASE, loosened, 1_000_000, 50_000)!!
        assertFalse(hasMatured(pending, nowEpochMillis = 1_000_000 + 3_600_000, nowElapsedRealtime = 50_000 + 3_600_000))
    }

    @Test
    fun `a change matures when both clocks have run out the cooldown`() {
        val pending = armChange(BASE, loosened, 1_000_000, 50_000)!!
        val cooldown = 24 * 3_600_000L
        assertTrue(hasMatured(pending, 1_000_000 + cooldown, 50_000 + cooldown))
    }

    @Test
    fun `winding the wall clock forward does not mature a change on its own`() {
        val pending = armChange(BASE, loosened, 1_000_000, 50_000)!!
        // Wall clock jumped a week; the device has been awake ten minutes.
        assertFalse(hasMatured(pending, 1_000_000 + 7 * 24 * 3_600_000, 50_000 + 600_000))
    }

    @Test
    fun `after a reboot the wall clock decides alone, elapsed time having restarted`() {
        val pending = armChange(BASE, loosened, 1_000_000, 5_000_000)!!
        val cooldown = 24 * 3_600_000L
        // elapsedRealtime below the armed value can only mean the device restarted.
        assertTrue(hasMatured(pending, 1_000_000 + cooldown, 30_000))
    }

    @Test
    fun `effective settings stay put until the change matures`() {
        val pending = armChange(BASE, loosened, 1_000_000, 50_000)!!
        assertEquals(BASE, effectiveSettings(BASE, pending, 1_000_000 + 3_600_000, 50_000 + 3_600_000))
    }

    @Test
    fun `effective settings switch over once the change matures`() {
        val pending = armChange(BASE, loosened, 1_000_000, 50_000)!!
        val cooldown = 24 * 3_600_000L
        assertEquals(loosened, effectiveSettings(BASE, pending, 1_000_000 + cooldown, 50_000 + cooldown))
    }

    @Test
    fun `with nothing pending the stored settings are the effective ones`() {
        assertEquals(BASE, effectiveSettings(BASE, null, 1_000_000, 50_000))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: FAIL — `Unresolved reference: LockedSettings`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/insta/reelsoff/service/AllowanceLock.kt`:

```kotlin
package com.insta.reelsoff.service

import com.insta.detection.Surface
import kotlinx.serialization.Serializable

/**
 * The settings the lock reasons about: the quota and the surface switches
 * together. Both, because a lock that guarded only the quota would be walked
 * around by switching REELS off.
 *
 * This does not replace `BlockSettings`, which stays what the service reads.
 */
@Serializable
data class LockedSettings(
    val allowance: AllowanceSettings,
    val blockedSurfaces: Set<Surface>,
)

/** A loosening waiting out its cooldown. At most one exists at a time. */
@Serializable
data class PendingChange(
    val proposed: LockedSettings,
    val effectiveAtEpochMillis: Long,
    val armedAtElapsedRealtime: Long,
    /** The cooldown in force when this was armed — see [armChange]. */
    val cooldownMillis: Long,
)

/**
 * The minutes of the day this window covers, as a set.
 *
 * A set of 1440 integers rather than circular-interval arithmetic: windows can
 * straddle midnight, and comparing two of them for containment is where an
 * off-by-one silently turns a tightening into a loosening. 1440 booleans cost
 * nothing on a settings write and are impossible to get subtly wrong.
 */
private fun windowMinutes(settings: AllowanceSettings): Set<Int> =
    (0 until 1440).filterTo(mutableSetOf()) { windowContains(settings, it) }

/**
 * Whether moving from [current] to [proposed] gives the user more room.
 *
 * A change that both tightens and loosens counts wholly as a loosening: the
 * safe reading, and it avoids having to split one edit into two writes of
 * which only half would be delayed.
 *
 * On `enabled`: the quota *grants* time and never removes any, so turning it
 * on is the loosening and turning it off is the tightening. Written the other
 * way round, the lock would be undone in a single write.
 */
fun isLoosening(current: LockedSettings, proposed: LockedSettings): Boolean {
    val c = current.allowance
    val p = proposed.allowance
    if (!c.enabled && p.enabled) return true
    if (p.quotaMillis > c.quotaMillis) return true
    if (p.cooldownMillis < c.cooldownMillis) return true
    if (!windowMinutes(c).containsAll(windowMinutes(p))) return true
    if (!proposed.blockedSurfaces.containsAll(current.blockedSurfaces)) return true
    return false
}

/**
 * Returns the change to hold, or null when it may be applied at once.
 *
 * The cooldown charged is the one **currently in force**, never the proposed
 * one. Otherwise a single write could set the cooldown to zero and take effect
 * immediately, which is the whole lock gone.
 */
fun armChange(
    current: LockedSettings,
    proposed: LockedSettings,
    nowEpochMillis: Long,
    nowElapsedRealtime: Long,
): PendingChange? {
    if (!isLoosening(current, proposed)) return null
    val cooldown = current.allowance.cooldownMillis
    return PendingChange(
        proposed = proposed,
        effectiveAtEpochMillis = nowEpochMillis + cooldown,
        armedAtElapsedRealtime = nowElapsedRealtime,
        cooldownMillis = cooldown,
    )
}

/**
 * Whether a held change may now take effect.
 *
 * Both clocks have to agree, because the wall clock is the user's to move: the
 * settings app winds it forward a week and a wall-clock-only check would ripen
 * every pending loosening on the spot. `elapsedRealtime` cannot be set, only
 * reset — by a reboot, which shows up as a value below the armed one. There is
 * nothing to compare against after that, so the wall clock decides alone; a
 * reboot is a real event and refusing to ever mature would be worse.
 */
fun hasMatured(
    pending: PendingChange,
    nowEpochMillis: Long,
    nowElapsedRealtime: Long,
): Boolean {
    if (nowEpochMillis < pending.effectiveAtEpochMillis) return false
    if (nowElapsedRealtime < pending.armedAtElapsedRealtime) return true
    return nowElapsedRealtime - pending.armedAtElapsedRealtime >= pending.cooldownMillis
}

/**
 * The settings actually in force. Readers derive rather than wait to be told,
 * so a matured change applies even if nothing has written it back yet.
 */
fun effectiveSettings(
    stored: LockedSettings,
    pending: PendingChange?,
    nowEpochMillis: Long,
    nowElapsedRealtime: Long,
): LockedSettings =
    if (pending != null && hasMatured(pending, nowEpochMillis, nowElapsedRealtime)) {
        pending.proposed
    } else {
        stored
    }
```

If `AllowanceSettings` does not compile as `@Serializable` here, add the annotation to it in `Allowance.kt` and import `kotlinx.serialization.Serializable` there — `PendingChange` cannot serialize without it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/insta/reelsoff/service/AllowanceLock.kt app/src/main/kotlin/com/insta/reelsoff/service/Allowance.kt app/src/test/kotlin/com/insta/reelsoff/service/AllowanceLockTest.kt
git commit -m "feat: make every loosening of the settings wait out a cooldown"
```

---

### Task 4: Persistence

**Files:**
- Modify: `app/src/main/kotlin/com/insta/reelsoff/data/SettingsStore.kt`
- Modify: `app/src/androidTest/kotlin/com/insta/reelsoff/data/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: `AllowanceSettings`, `AllowanceState`, `LockedSettings`, `PendingChange` from Tasks 1–3.
- Produces on `SettingsStore`: `allowanceSettings: Flow<AllowanceSettings>`, `allowanceState: Flow<AllowanceState>`, `pendingChange: Flow<PendingChange?>`, `setAllowanceSettings(AllowanceSettings)`, `setAllowanceState(AllowanceState)`, `setPendingChange(PendingChange?)`.

This task also removes `setBlockReels` and `setBlockExplore`, which no production code has called since the move to `setSurfaceBlocked`. The **read** side of the migration in `settings` and `setSurfaceBlocked` stays exactly as it is — deleting it would reset an existing install's switches.

- [ ] **Step 1: Write the failing test**

In `app/src/androidTest/kotlin/com/insta/reelsoff/data/SettingsStoreTest.kt`, first replace the three `store.setBlockExplore(false)` / `store.setBlockReels(false)` calls with `store.setSurfaceBlocked(Surface.EXPLORE, false)` and `store.setSurfaceBlocked(Surface.REELS, false)` respectively, so removing the dead setters does not break the file. Then add:

```kotlin
    @Test
    fun allowanceSettingsRoundTrip() = runTest {
        val store = SettingsStore(context)
        store.clear()
        assertEquals(AllowanceSettings(), store.allowanceSettings.first())

        val wanted = AllowanceSettings(
            enabled = true,
            quotaMillis = 420_000,
            windowStartMinutes = 19 * 60 + 30,
            windowEndMinutes = 20 * 60 + 15,
            cooldownMillis = 12 * 3_600_000,
        )
        store.setAllowanceSettings(wanted)
        assertEquals(wanted, store.allowanceSettings.first())
    }

    @Test
    fun allowanceStateRoundTrip() = runTest {
        val store = SettingsStore(context)
        store.clear()
        assertEquals(AllowanceState(), store.allowanceState.first())

        val wanted = AllowanceState(day = 20_683, consumedMillis = 90_000, passOpenedAtEpochMillis = 1_700_000_000_000)
        store.setAllowanceState(wanted)
        assertEquals(wanted, store.allowanceState.first())
    }

    @Test
    fun pendingChangeRoundTripsAndClears() = runTest {
        val store = SettingsStore(context)
        store.clear()
        assertNull(store.pendingChange.first())

        val wanted = PendingChange(
            proposed = LockedSettings(
                allowance = AllowanceSettings(enabled = true, quotaMillis = 600_000),
                blockedSurfaces = setOf(Surface.REELS, Surface.SHORTS),
            ),
            effectiveAtEpochMillis = 1_700_000_000_000,
            armedAtElapsedRealtime = 50_000,
            cooldownMillis = 24 * 3_600_000,
        )
        store.setPendingChange(wanted)
        assertEquals(wanted, store.pendingChange.first())

        store.setPendingChange(null)
        assertNull(store.pendingChange.first())
    }

    @Test
    fun anUnreadablePendingChangeReadsAsNoneRatherThanThrowing() = runTest {
        val store = SettingsStore(context)
        store.clear()
        store.writeRawPendingChangeForTest("{ not json")
        assertNull(store.pendingChange.first())
    }
```

Add the imports the file needs: `com.insta.detection.Surface`, `com.insta.reelsoff.service.AllowanceSettings`, `com.insta.reelsoff.service.AllowanceState`, `com.insta.reelsoff.service.LockedSettings`, `com.insta.reelsoff.service.PendingChange`, and `kotlin.test.assertNull` (or `org.junit.Assert.assertNull`, matching whatever the file already uses for assertions).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.insta.reelsoff.data.SettingsStoreTest`
Expected: FAIL to compile — `Unresolved reference: allowanceSettings`.

- [ ] **Step 3: Write the implementation**

In `SettingsStore.kt`, add the imports:

```kotlin
import com.insta.reelsoff.service.AllowanceSettings
import com.insta.reelsoff.service.AllowanceState
import com.insta.reelsoff.service.PendingChange
import kotlinx.serialization.json.Json
```

Add a JSON instance and the flows and setters inside the class:

```kotlin
    val allowanceSettings: Flow<AllowanceSettings> = context.dataStore.data.map { preferences ->
        val defaults = AllowanceSettings()
        AllowanceSettings(
            enabled = preferences[ALLOWANCE_ENABLED] ?: defaults.enabled,
            quotaMillis = preferences[ALLOWANCE_QUOTA] ?: defaults.quotaMillis,
            windowStartMinutes = preferences[ALLOWANCE_WINDOW_START] ?: defaults.windowStartMinutes,
            windowEndMinutes = preferences[ALLOWANCE_WINDOW_END] ?: defaults.windowEndMinutes,
            cooldownMillis = preferences[ALLOWANCE_COOLDOWN] ?: defaults.cooldownMillis,
        )
    }

    val allowanceState: Flow<AllowanceState> = context.dataStore.data.map { preferences ->
        AllowanceState(
            day = preferences[ALLOWANCE_DAY] ?: 0,
            consumedMillis = preferences[ALLOWANCE_CONSUMED] ?: 0,
            passOpenedAtEpochMillis = preferences[ALLOWANCE_PASS_OPENED_AT] ?: 0,
        )
    }

    /**
     * Stored as JSON rather than flat keys: it nests a whole [LockedSettings],
     * and a half-written set of flat keys would read back as a change nobody
     * armed. Unparseable content reads as "nothing pending" — the strict
     * answer, since a pending change only ever loosens.
     */
    val pendingChange: Flow<PendingChange?> = context.dataStore.data.map { preferences ->
        val raw = preferences[PENDING_CHANGE] ?: return@map null
        runCatching { json.decodeFromString<PendingChange>(raw) }.getOrNull()
    }

    suspend fun setAllowanceSettings(settings: AllowanceSettings) {
        context.dataStore.edit { preferences ->
            preferences[ALLOWANCE_ENABLED] = settings.enabled
            preferences[ALLOWANCE_QUOTA] = settings.quotaMillis
            preferences[ALLOWANCE_WINDOW_START] = settings.windowStartMinutes
            preferences[ALLOWANCE_WINDOW_END] = settings.windowEndMinutes
            preferences[ALLOWANCE_COOLDOWN] = settings.cooldownMillis
        }
    }

    suspend fun setAllowanceState(state: AllowanceState) {
        context.dataStore.edit { preferences ->
            preferences[ALLOWANCE_DAY] = state.day
            preferences[ALLOWANCE_CONSUMED] = state.consumedMillis
            preferences[ALLOWANCE_PASS_OPENED_AT] = state.passOpenedAtEpochMillis
        }
    }

    suspend fun setPendingChange(change: PendingChange?) {
        context.dataStore.edit { preferences ->
            if (change == null) {
                preferences.remove(PENDING_CHANGE)
            } else {
                preferences[PENDING_CHANGE] = json.encodeToString(change)
            }
        }
    }

    /** Test seam: writes content the parser is meant to reject. */
    internal suspend fun writeRawPendingChangeForTest(raw: String) {
        context.dataStore.edit { it[PENDING_CHANGE] = raw }
    }
```

Add the private `json` property to the class body:

```kotlin
    private val json = Json { ignoreUnknownKeys = true }
```

Add to the `private companion object`:

```kotlin
        val ALLOWANCE_ENABLED = booleanPreferencesKey("allowance_enabled")
        val ALLOWANCE_QUOTA = longPreferencesKey("allowance_quota_millis")
        val ALLOWANCE_WINDOW_START = intPreferencesKey("allowance_window_start_minutes")
        val ALLOWANCE_WINDOW_END = intPreferencesKey("allowance_window_end_minutes")
        val ALLOWANCE_COOLDOWN = longPreferencesKey("allowance_cooldown_millis")
        val ALLOWANCE_DAY = longPreferencesKey("allowance_day")
        val ALLOWANCE_CONSUMED = longPreferencesKey("allowance_consumed_millis")
        val ALLOWANCE_PASS_OPENED_AT = longPreferencesKey("allowance_pass_opened_at")
        val PENDING_CHANGE = stringPreferencesKey("pending_change")
```

Finally delete `setBlockReels` and `setBlockExplore` (the two functions only, lines 106–112 of the current file). `BLOCK_REELS` and `BLOCK_EXPLORE` stay in the companion object — the migration reads still use them.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.insta.reelsoff.data.SettingsStoreTest`
Expected: PASS.

Then restore the device, which the run has just stripped:

```bash
./gradlew :app:installDebug
adb shell appops set com.insta.reelsoff ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services com.insta.reelsoff/com.insta.reelsoff.service.InstagramWatcherService
adb shell settings put secure accessibility_enabled 1
sleep 6 && adb shell dumpsys accessibility | grep -A1 "Bound services"
```

The last line must print a bound service. If it prints nothing, re-run the two `settings put` commands — they fail silently right after an install.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/insta/reelsoff/data/SettingsStore.kt app/src/androidTest/kotlin/com/insta/reelsoff/data/SettingsStoreTest.kt
git commit -m "feat: persist the quota, its state and any pending change"
```

---

### Task 5: The service suspends blocking while a pass is open

**Files:**
- Modify: `app/src/main/kotlin/com/insta/reelsoff/service/InstagramWatcherService.kt`
- Test: `app/src/test/kotlin/com/insta/reelsoff/service/AllowanceGateTest.kt` (create)

**Interfaces:**
- Consumes: `passIsOpen`, `settle`, `effectiveSettings` from Tasks 1–3; the flows from Task 4.
- Produces: `effectiveBlockedSurfaces(LockedSettings, AllowanceState, Long, ZoneId): Set<Surface>` in `Allowance.kt`.

The service must not grow its own copy of this reasoning: the one line that decides gets its own pure function so it can be tested without an Android device.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/insta/reelsoff/service/AllowanceGateTest.kt`:

```kotlin
package com.insta.reelsoff.service

import com.insta.detection.Surface
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(2026, 8, day, hour, minute).atZone(PARIS).toInstant().toEpochMilli()

class AllowanceGateTest {

    private val blocked = setOf(Surface.REELS, Surface.EXPLORE, Surface.SHORTS)
    private val locked = LockedSettings(
        allowance = AllowanceSettings(
            enabled = true,
            quotaMillis = 300_000,
            windowStartMinutes = 20 * 60,
            windowEndMinutes = 21 * 60,
        ),
        blockedSurfaces = blocked,
    )

    @Test
    fun `an open pass suspends every block`() {
        val state = openPass(locked.allowance, AllowanceState(), at(17, 20, 0), PARIS)
        assertEquals(emptySet(), effectiveBlockedSurfaces(locked, state, at(17, 20, 30), PARIS))
    }

    @Test
    fun `a shut pass leaves the switches in force`() {
        assertEquals(blocked, effectiveBlockedSurfaces(locked, AllowanceState(), at(17, 20, 30), PARIS))
    }

    @Test
    fun `an expired pass leaves the switches in force without anyone closing it`() {
        val state = openPass(locked.allowance, AllowanceState(), at(17, 20, 0), PARIS)
        assertEquals(blocked, effectiveBlockedSurfaces(locked, state, at(17, 20, 0) + 300_001, PARIS))
    }

    @Test
    fun `a pass outside its window leaves the switches in force`() {
        val state = openPass(locked.allowance, AllowanceState(), at(17, 20, 55), PARIS)
        assertEquals(blocked, effectiveBlockedSurfaces(locked, state, at(17, 21, 30), PARIS))
    }

    @Test
    fun `a disabled quota leaves the switches in force`() {
        val off = locked.copy(allowance = locked.allowance.copy(enabled = false))
        val state = AllowanceState(day = epochDayOf(at(17, 12), PARIS), passOpenedAtEpochMillis = at(17, 20, 0))
        assertEquals(blocked, effectiveBlockedSurfaces(off, state, at(17, 20, 30), PARIS))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: FAIL — `Unresolved reference: effectiveBlockedSurfaces`.

- [ ] **Step 3: Write the implementation**

Append to `app/src/main/kotlin/com/insta/reelsoff/service/Allowance.kt`:

```kotlin
/**
 * What the blocker should treat as blocked right now.
 *
 * An open pass answers with the empty set, which is `Blocker`'s already-tested
 * "not a blocked surface" path — the quota never enters the blocker itself.
 */
fun effectiveBlockedSurfaces(
    locked: LockedSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): Set<com.insta.detection.Surface> =
    if (passIsOpen(locked.allowance, state, nowEpochMillis, zone)) {
        emptySet()
    } else {
        locked.blockedSurfaces
    }
```

In `InstagramWatcherService.kt`, add the `@Volatile` fields next to the existing `settings` one:

```kotlin
    @Volatile
    private var allowanceSettings = AllowanceSettings()

    @Volatile
    private var allowanceState = AllowanceState()

    @Volatile
    private var pendingChange: PendingChange? = null
```

All three start at their strictest value: quota disabled, no pass, nothing pending. If DataStore never answers, the service blocks exactly as it does today.

Inside `onServiceConnected`, alongside the existing settings collector, add three more collectors following the identical shape — `runCatching { … .retry { … } .collectLatest { … } }.onFailure { … }`. Copy the existing block's structure exactly, including the `retry` with its `delay(1_000)` and its log line, and the `CancellationException` rethrow in `onFailure`:

```kotlin
            scope.launch {
                runCatching {
                    SettingsStore(applicationContext).allowanceSettings
                        .retry { e ->
                            Log.e(TAG, "allowance settings read failed, retrying", e)
                            delay(1_000)
                            true
                        }
                        .collectLatest { allowanceSettings = it }
                }.onFailure {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "allowance settings collection launch failed", it)
                }
            }
```

and the same for `allowanceState` (into `allowanceState`) and `pendingChange` (into `pendingChange`).

In `handle()`, replace the single decision line:

```kotlin
        val decision = blocker.decide(classification, settings.blockedSurfaces)
```

with:

```kotlin
        // The quota can suspend blocking, and a matured pending change can have
        // altered the settings without anything having written it back yet — so
        // the effective values are derived here rather than read. Anything wrong
        // in the stored state lands on the strict side: passIsOpen requires
        // every one of its conditions, and its defaults are "no pass".
        val now = System.currentTimeMillis()
        val effective = effectiveSettings(
            stored = LockedSettings(allowanceSettings, settings.blockedSurfaces),
            pending = pendingChange,
            nowEpochMillis = now,
            nowElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
        )
        val blockedNow = effectiveBlockedSurfaces(
            locked = effective,
            state = allowanceState,
            nowEpochMillis = now,
            zone = ZoneId.systemDefault(),
        )
        val decision = blocker.decide(classification, blockedNow)
```

Add the imports `com.insta.detection.Surface` (if not already present) and `java.time.ZoneId`.

- [ ] **Step 4: Run the tests and build**

Run: `./gradlew :detection:test :app:testDebugUnitTest --rerun-tasks && ./gradlew :app:assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/insta/reelsoff/service/Allowance.kt app/src/main/kotlin/com/insta/reelsoff/service/InstagramWatcherService.kt app/src/test/kotlin/com/insta/reelsoff/service/AllowanceGateTest.kt
git commit -m "feat: let an open pass suspend blocking, and a matured change apply"
```

---

### Task 6: The ViewModel exposes the quota and mediates every write

**Files:**
- Modify: `app/src/main/kotlin/com/insta/reelsoff/ui/HomeViewModel.kt`
- Test: `app/src/test/kotlin/com/insta/reelsoff/ui/AllowanceUiStateTest.kt` (create)

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: `AllowanceUiState` and `allowanceUiState(AllowanceSettings, AllowanceState, PendingChange?, Set<Surface>, Long, Long, ZoneId): AllowanceUiState` in a new file `app/src/main/kotlin/com/insta/reelsoff/ui/AllowanceUiState.kt`; on `HomeViewModel`: `openPass()`, `closePass()`, `proposeSettings(AllowanceSettings)`, `cancelPendingChange()`; `HomeUiState.allowance: AllowanceUiState`.

`HomeViewModel`'s `combine` currently takes seven flows through the vararg overload, indexed positionally with a comment block. This task takes it to ten. **Re-check every index after editing** — the vararg form is untyped, two `Set<String>` flows already sit next to each other, and the compiler cannot catch a swap.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/insta/reelsoff/ui/AllowanceUiStateTest.kt`:

```kotlin
package com.insta.reelsoff.ui

import com.insta.detection.Surface
import com.insta.reelsoff.service.AllowanceSettings
import com.insta.reelsoff.service.AllowanceState
import com.insta.reelsoff.service.LockedSettings
import com.insta.reelsoff.service.PendingChange
import com.insta.reelsoff.service.epochDayOf
import com.insta.reelsoff.service.openPass
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(2026, 8, day, hour, minute).atZone(PARIS).toInstant().toEpochMilli()

class AllowanceUiStateTest {

    private val settings = AllowanceSettings(
        enabled = true,
        quotaMillis = 300_000,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
    )
    private val blocked = setOf(Surface.REELS)

    private fun state(
        allowanceState: AllowanceState = AllowanceState(),
        pending: PendingChange? = null,
        now: Long = at(17, 20, 30),
    ) = allowanceUiState(settings, allowanceState, pending, blocked, now, 50_000, PARIS)

    @Test
    fun `a disabled quota reports itself off and offers nothing`() {
        val ui = allowanceUiState(
            settings.copy(enabled = false), AllowanceState(), null, blocked, at(17, 20, 30), 50_000, PARIS,
        )
        assertFalse(ui.enabled)
        assertFalse(ui.canOpen)
        assertFalse(ui.passRunning)
    }

    @Test
    fun `inside the window with a full quota, the pass can be opened`() {
        val ui = state()
        assertTrue(ui.canOpen)
        assertFalse(ui.passRunning)
        assertTrue(ui.insideWindow)
        assertEquals(300_000, ui.remainingMillis)
    }

    @Test
    fun `outside the window nothing can be opened`() {
        val ui = state(now = at(17, 15, 0))
        assertFalse(ui.canOpen)
        assertFalse(ui.insideWindow)
    }

    @Test
    fun `a running pass reports its remaining time counting down`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val ui = state(allowanceState = opened, now = at(17, 20, 0) + 60_000)
        assertTrue(ui.passRunning)
        assertFalse(ui.canOpen)
        assertEquals(240_000, ui.remainingMillis)
    }

    @Test
    fun `an exhausted quota can no longer be opened`() {
        val spent = AllowanceState(day = epochDayOf(at(17, 12), PARIS), consumedMillis = 300_000)
        val ui = state(allowanceState = spent)
        assertFalse(ui.canOpen)
        assertEquals(0, ui.remainingMillis)
    }

    @Test
    fun `an unmatured pending change is reported with the time it still has to wait`() {
        val pending = PendingChange(
            proposed = LockedSettings(settings.copy(quotaMillis = 600_000), blocked),
            effectiveAtEpochMillis = at(17, 20, 30) + 3_600_000,
            armedAtElapsedRealtime = 50_000,
            cooldownMillis = 24 * 3_600_000,
        )
        val ui = state(pending = pending)
        assertEquals(3_600_000, ui.pendingInMillis)
        // Still the stored quota, not the proposed one.
        assertEquals(300_000, ui.quotaMillis)
    }

    @Test
    fun `no pending change reports none`() {
        assertNull(state().pendingInMillis)
    }

    @Test
    fun `a matured pending change is in force and no longer reported as waiting`() {
        val pending = PendingChange(
            proposed = LockedSettings(settings.copy(quotaMillis = 600_000), blocked),
            effectiveAtEpochMillis = at(17, 20, 30) - 1_000,
            armedAtElapsedRealtime = 0,
            cooldownMillis = 0,
        )
        val ui = state(pending = pending)
        assertNull(ui.pendingInMillis)
        assertEquals(600_000, ui.quotaMillis)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: FAIL — `Unresolved reference: allowanceUiState`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/insta/reelsoff/ui/AllowanceUiState.kt`:

```kotlin
package com.insta.reelsoff.ui

import com.insta.detection.Surface
import com.insta.reelsoff.service.AllowanceSettings
import com.insta.reelsoff.service.AllowanceState
import com.insta.reelsoff.service.LockedSettings
import com.insta.reelsoff.service.PendingChange
import com.insta.reelsoff.service.canOpenPass
import com.insta.reelsoff.service.effectiveSettings
import com.insta.reelsoff.service.minuteOfDay
import com.insta.reelsoff.service.passIsOpen
import com.insta.reelsoff.service.remainingMillis
import com.insta.reelsoff.service.windowContains
import java.time.ZoneId

/**
 * Everything the quota panel draws, already decided. The panel renders; it does
 * not reason about clocks, and it never sees the stored settings when a pending
 * change has matured past them.
 */
data class AllowanceUiState(
    val enabled: Boolean = false,
    val quotaMillis: Long = 0,
    val remainingMillis: Long = 0,
    val windowStartMinutes: Int = 0,
    val windowEndMinutes: Int = 0,
    val cooldownMillis: Long = 0,
    val insideWindow: Boolean = false,
    val canOpen: Boolean = false,
    val passRunning: Boolean = false,
    /** Millis until a held loosening takes effect, or null when none is held. */
    val pendingInMillis: Long? = null,
)

fun allowanceUiState(
    stored: AllowanceSettings,
    state: AllowanceState,
    pending: PendingChange?,
    blockedSurfaces: Set<Surface>,
    nowEpochMillis: Long,
    nowElapsedRealtime: Long,
    zone: ZoneId,
): AllowanceUiState {
    val effective = effectiveSettings(
        stored = LockedSettings(stored, blockedSurfaces),
        pending = pending,
        nowEpochMillis = nowEpochMillis,
        nowElapsedRealtime = nowElapsedRealtime,
    ).allowance
    // Reported only while still held: once it is in force it is no longer news,
    // and `effective` above already reflects it.
    val stillWaiting = pending
        ?.takeIf { effective == stored }
        ?.let { (it.effectiveAtEpochMillis - nowEpochMillis).coerceAtLeast(0) }
    return AllowanceUiState(
        enabled = effective.enabled,
        quotaMillis = effective.quotaMillis,
        remainingMillis = remainingMillis(effective, state, nowEpochMillis, zone),
        windowStartMinutes = effective.windowStartMinutes,
        windowEndMinutes = effective.windowEndMinutes,
        cooldownMillis = effective.cooldownMillis,
        insideWindow = windowContains(effective, minuteOfDay(nowEpochMillis, zone)),
        canOpen = canOpenPass(effective, state, nowEpochMillis, zone),
        passRunning = passIsOpen(effective, state, nowEpochMillis, zone),
        pendingInMillis = stillWaiting,
    )
}
```

Note `effective == stored` as the "still held" test: after maturation `effectiveSettings` returns `pending.proposed`, which differs from `stored` — a pending change is only ever armed when `isLoosening` is true, so the two are never equal at arming time.

In `HomeViewModel.kt`:

1. Add `val allowance: AllowanceUiState = AllowanceUiState()` to `HomeUiState`.
2. Add the three new source flows to the `combine` call — `settingsStore.allowanceSettings`, `settingsStore.allowanceState`, `settingsStore.pendingChange` — and extend the index comment to ten entries:

```kotlin
    //   0 serviceEnabled                  -> Boolean
    //   1 settingsStore.settings          -> BlockSettings
    //   2 events                          -> List<BlockEvent>
    //   3 settingsStore.ruleLoadStatus    -> RuleLoadStatus
    //   4 settingsStore.captureStatus     -> CaptureStatus
    //   5 installedPackages               -> Set<String>
    //   6 settingsStore.declaredPackages  -> Set<String>
    //   7 settingsStore.allowanceSettings -> AllowanceSettings
    //   8 settingsStore.allowanceState    -> AllowanceState
    //   9 settingsStore.pendingChange     -> PendingChange?
```

3. Build the field inside the combiner:

```kotlin
        @Suppress("UNCHECKED_CAST")
        val allowanceSettings = values[7] as AllowanceSettings
        @Suppress("UNCHECKED_CAST")
        val allowanceState = values[8] as AllowanceState
        @Suppress("UNCHECKED_CAST")
        val pending = values[9] as PendingChange?
```

and pass to `HomeUiState`:

```kotlin
            allowance = allowanceUiState(
                stored = allowanceSettings,
                state = allowanceState,
                pending = pending,
                blockedSurfaces = settings.blockedSurfaces,
                nowEpochMillis = System.currentTimeMillis(),
                nowElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
                zone = zone,
            ),
```

4. Add the four actions. Every write settles the state first, so a stale pass never survives an unrelated edit:

```kotlin
    fun openPass() {
        viewModelScope.launch {
            val settings = settingsStore.allowanceSettings.first()
            val current = settingsStore.allowanceState.first()
            val now = System.currentTimeMillis()
            val settled = settle(settings, current, now, zone)
            settingsStore.setAllowanceState(openPass(settings, settled, now, zone))
        }
    }

    fun closePass() {
        viewModelScope.launch {
            val current = settingsStore.allowanceState.first()
            settingsStore.setAllowanceState(closePass(current, System.currentTimeMillis(), zone))
        }
    }

    /**
     * The single door every settings write goes through. A tightening lands at
     * once; a loosening is held for the cooldown currently in force.
     */
    fun proposeSettings(proposed: AllowanceSettings) {
        viewModelScope.launch {
            val current = LockedSettings(
                settingsStore.allowanceSettings.first(),
                settingsStore.settings.first().blockedSurfaces,
            )
            val armed = armChange(
                current = current,
                proposed = current.copy(allowance = proposed),
                nowEpochMillis = System.currentTimeMillis(),
                nowElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
            )
            if (armed == null) {
                settingsStore.setAllowanceSettings(proposed)
                // A tightening supersedes anything held: keeping a loosening
                // armed past it would undo the tightening on its own later.
                settingsStore.setPendingChange(null)
            } else {
                settingsStore.setPendingChange(armed)
            }
        }
    }

    /** Cancelling a held loosening is itself a tightening, so it lands at once. */
    fun cancelPendingChange() {
        viewModelScope.launch { settingsStore.setPendingChange(null) }
    }
```

Import `kotlinx.coroutines.flow.first` and the `service` package's functions. Rename the local `openPass`/`closePass` calls if Kotlin cannot disambiguate them from the methods — qualify as `com.insta.reelsoff.service.openPass(...)`.

5. **`setSurfaceBlocked` must go through the lock too.** Replace its body:

```kotlin
    fun setSurfaceBlocked(surface: Surface, blocked: Boolean) {
        viewModelScope.launch {
            val allowance = settingsStore.allowanceSettings.first()
            val current = LockedSettings(allowance, settingsStore.settings.first().blockedSurfaces)
            val proposed = current.copy(
                blockedSurfaces = if (blocked) {
                    current.blockedSurfaces + surface
                } else {
                    current.blockedSurfaces - surface
                },
            )
            val armed = armChange(
                current, proposed, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(),
            )
            if (armed == null) {
                settingsStore.setSurfaceBlocked(surface, blocked)
                settingsStore.setPendingChange(null)
            } else {
                settingsStore.setPendingChange(armed)
            }
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :detection:test :app:testDebugUnitTest --rerun-tasks && ./gradlew :app:assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/insta/reelsoff/ui/AllowanceUiState.kt app/src/main/kotlin/com/insta/reelsoff/ui/HomeViewModel.kt app/src/test/kotlin/com/insta/reelsoff/ui/AllowanceUiStateTest.kt
git commit -m "feat: route every settings write through the cooldown lock"
```

---

### Task 7: The quota panel

**Files:**
- Create: `app/src/main/kotlin/com/insta/reelsoff/ui/AllowancePanel.kt`
- Modify: `app/src/main/kotlin/com/insta/reelsoff/ui/HomeScreen.kt`
- Modify: `app/src/main/kotlin/com/insta/reelsoff/ui/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/com/insta/reelsoff/ui/AllowanceFormatTest.kt` (create)

**Interfaces:**
- Consumes: `AllowanceUiState` from Task 6, the `HomeViewModel` actions from Task 6.
- Produces: `formatDuration(Long): String`, `formatMinuteOfDay(Int): String` in `AllowancePanel.kt`; `@Composable AllowancePanel(state, onOpen, onClose, onCancelPending)`.

The panel goes in its own file: `HomeScreen.kt` is already 487 lines, and this adds a countdown, a window and a pending-change banner to it.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/insta/reelsoff/ui/AllowanceFormatTest.kt`:

```kotlin
package com.insta.reelsoff.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AllowanceFormatTest {

    @Test
    fun `a duration under a minute reads in seconds`() {
        assertEquals("40 s", formatDuration(40_000))
        assertEquals("0 s", formatDuration(0))
    }

    @Test
    fun `a duration under an hour reads in minutes and seconds`() {
        assertEquals("5 min 00 s", formatDuration(300_000))
        assertEquals("3 min 20 s", formatDuration(200_000))
    }

    @Test
    fun `a duration of an hour or more reads in hours and minutes`() {
        assertEquals("1 h 00", formatDuration(3_600_000))
        assertEquals("21 h 14", formatDuration(21 * 3_600_000L + 14 * 60_000))
    }

    @Test
    fun `a partial second rounds down, so a countdown never overstates`() {
        assertEquals("39 s", formatDuration(39_999))
    }

    @Test
    fun `a minute of day reads as a local wall-clock time`() {
        assertEquals("20 h 00", formatMinuteOfDay(20 * 60))
        assertEquals("00 h 00", formatMinuteOfDay(0))
        assertEquals("09 h 05", formatMinuteOfDay(9 * 60 + 5))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: FAIL — `Unresolved reference: formatDuration`.

- [ ] **Step 3: Write the implementation**

Add to `app/src/main/res/values/strings.xml`:

```xml
    <string name="allowance_title">Quota</string>
    <string name="allowance_off">Quota éteint : les surfaces cochées sont bloquées sans exception.</string>
    <string name="allowance_remaining">%1$s restantes sur %2$s aujourd\'hui</string>
    <string name="allowance_window">Ouvrable de %1$s à %2$s</string>
    <string name="allowance_outside_window">Hors plage — ouvrable de %1$s à %2$s</string>
    <string name="allowance_exhausted">Quota épuisé pour aujourd\'hui</string>
    <string name="allowance_running">Pass ouvert — %1$s</string>
    <string name="allowance_open">Ouvrir</string>
    <string name="allowance_close">Fermer maintenant</string>
    <string name="allowance_needs_service">Le service est inactif : un pass ne changerait rien.</string>
    <string name="allowance_pending">Nouveau réglage actif dans %1$s</string>
    <string name="allowance_cancel_pending">Annuler</string>
    <string name="allowance_lock_hint">Tout assouplissement attend %1$s. Un resserrement s\'applique tout de suite.</string>
```

Create `app/src/main/kotlin/com/insta/reelsoff/ui/AllowancePanel.kt`:

```kotlin
package com.insta.reelsoff.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.insta.reelsoff.R
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * A duration in French, at the coarsest unit that still says something useful:
 * seconds under a minute, minutes and seconds under an hour, hours and minutes
 * above. Always rounds **down**, so a countdown never claims more time than is
 * left.
 */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format(Locale.FRENCH, "%d h %02d", hours, minutes)
        minutes > 0 -> String.format(Locale.FRENCH, "%d min %02d s", minutes, seconds)
        else -> String.format(Locale.FRENCH, "%d s", seconds)
    }
}

/** Minutes since local midnight, as a wall-clock time. */
fun formatMinuteOfDay(minuteOfDay: Int): String =
    String.format(Locale.FRENCH, "%02d h %02d", minuteOfDay / 60, minuteOfDay % 60)

@Composable
fun AllowancePanel(
    state: AllowanceUiState,
    serviceEnabled: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onCancelPending: () -> Unit,
) {
    // The countdown is derived from a local tick rather than pushed by the
    // service, so it keeps running when the service is stopped — and stops on
    // its own when there is nothing left to count down.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.passRunning, state.pendingInMillis != null) {
        while (state.passRunning || state.pendingInMillis != null) {
            delay(1_000)
            tick++
        }
    }

    Column {
        if (!state.enabled) {
            Text(
                text = stringResource(R.string.allowance_off),
                style = MaterialTheme.typography.bodySmall,
                color = EncreDouce,
            )
            return@Column
        }

        val headline = when {
            state.passRunning -> stringResource(R.string.allowance_running, formatDuration(state.remainingMillis))
            state.remainingMillis <= 0 -> stringResource(R.string.allowance_exhausted)
            else -> stringResource(
                R.string.allowance_remaining,
                formatDuration(state.remainingMillis),
                formatDuration(state.quotaMillis),
            )
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            color = if (state.passRunning) Accent else Encre,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (state.insideWindow) R.string.allowance_window else R.string.allowance_outside_window,
                formatMinuteOfDay(state.windowStartMinutes),
                formatMinuteOfDay(state.windowEndMinutes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = EncreDouce,
        )

        if (!serviceEnabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.allowance_needs_service),
                style = MaterialTheme.typography.bodySmall,
                color = EncreDouce,
            )
        }

        Spacer(Modifier.height(14.dp))
        if (state.passRunning) {
            OutlinedButton(onClick = onClose, shape = MaterialTheme.shapes.small) {
                Text(
                    text = stringResource(R.string.allowance_close),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Encre,
                )
            }
        } else {
            OutlinedButton(
                onClick = onOpen,
                enabled = state.canOpen && serviceEnabled,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = stringResource(R.string.allowance_open),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.canOpen && serviceEnabled) Encre else EncreDouce,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            // A zero cooldown is not "waits 0 s" — it is the lock not yet armed,
            // and saying so is what tells the user the gesture that arms it.
            text = if (state.cooldownMillis == 0L) {
                stringResource(R.string.allowance_unlocked)
            } else {
                stringResource(R.string.allowance_lock_hint, formatDuration(state.cooldownMillis))
            },
            style = MaterialTheme.typography.bodySmall,
            color = EncreDouce,
        )

        val pendingIn = state.pendingInMillis
        if (pendingIn != null) {
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.allowance_pending, formatDuration(pendingIn)),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Alerte,
                )
                Spacer(Modifier.width(10.dp))
                TextButton(
                    onClick = onCancelPending,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = stringResource(R.string.allowance_cancel_pending),
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                    )
                }
            }
        }
    }
}
```

The `tick` variable is read by nothing directly — reading `state` alone would never recompose, since the state object only changes when DataStore emits. Add `@Suppress("UNUSED_EXPRESSION")` and a bare `tick` reference at the top of the `Column` body if the compiler warns, or use `key(tick) { … }` around the headline. The simplest working form: put `val ignoredForRecomposition = tick` immediately before `headline` with a comment saying it exists to make the countdown recompose.

In `HomeScreen.kt`, add the parameters and a new `Section` right after `ServiceBlock` and the callouts, before the `today` section:

```kotlin
        Section(title = stringResource(R.string.allowance_title)) {
            AllowancePanel(
                state = state.allowance,
                serviceEnabled = state.serviceEnabled,
                onOpen = onOpenPass,
                onClose = onClosePass,
                onCancelPending = onCancelPendingChange,
            )
        }
```

and add `onOpenPass: () -> Unit`, `onClosePass: () -> Unit`, `onCancelPendingChange: () -> Unit` to the `HomeScreen` signature.

In `MainActivity.kt`, wire the three to the ViewModel methods from Task 6, following the existing `onSurfaceBlockedChanged = viewModel::setSurfaceBlocked` pattern.

This panel reads the quota, the window and the cooldown. The editors that change them are Task 8 — the panel is usable and testable without them, which is why they are a separate task and not a separate release.

- [ ] **Step 4: Run the tests and build**

Run: `./gradlew :detection:test :app:testDebugUnitTest --rerun-tasks && ./gradlew :app:assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/insta/reelsoff/ui/AllowancePanel.kt app/src/main/kotlin/com/insta/reelsoff/ui/HomeScreen.kt app/src/main/kotlin/com/insta/reelsoff/ui/MainActivity.kt app/src/main/res/values/strings.xml app/src/test/kotlin/com/insta/reelsoff/ui/AllowanceFormatTest.kt
git commit -m "feat: a quota panel that opens, closes and counts down a pass"
```

---

### Task 8: The settings editors

**Files:**
- Create: `app/src/main/kotlin/com/insta/reelsoff/ui/AllowanceEditors.kt`
- Modify: `app/src/main/kotlin/com/insta/reelsoff/ui/AllowancePanel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/com/insta/reelsoff/ui/AllowanceEditorsTest.kt` (create)

**Interfaces:**
- Consumes: `AllowanceUiState` (Task 6), `formatDuration` / `formatMinuteOfDay` (Task 7), `HomeViewModel.proposeSettings` (Task 6).
- Produces: `QUOTA_CHOICES: List<Long>`, `COOLDOWN_CHOICES: List<Long>`, `stepMinute(Int, Int): Int` in `AllowanceEditors.kt`; `@Composable AllowanceEditors(state, onPropose)`.

No date/time picker component is used. Material3's `TimePicker` would drag in a dialog host and a token-driven look that fights the ink-on-paper direction; presets and a ±15-minute stepper cover the whole need with plain text buttons.

Every editor calls `onPropose` with a complete `AllowanceSettings`, so a single door — `proposeSettings` — keeps deciding what applies at once and what waits.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/insta/reelsoff/ui/AllowanceEditorsTest.kt`:

```kotlin
package com.insta.reelsoff.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllowanceEditorsTest {

    @Test
    fun `the quota choices are ordered and start at one minute`() {
        assertEquals(listOf(60_000L, 300_000L, 600_000L, 900_000L, 1_800_000L), QUOTA_CHOICES)
    }

    @Test
    fun `the cooldown choices start at none, which is where a fresh install sits`() {
        assertEquals(
            listOf(0L, 3_600_000L, 6 * 3_600_000L, 24 * 3_600_000L, 72 * 3_600_000L),
            COOLDOWN_CHOICES,
        )
    }

    @Test
    fun `every choice formats to something a reader recognises`() {
        assertEquals("1 min 00 s", formatDuration(QUOTA_CHOICES.first()))
        assertEquals("30 min 00 s", formatDuration(QUOTA_CHOICES.last()))
        assertEquals("1 h 00", formatDuration(COOLDOWN_CHOICES[1]))
        assertEquals("72 h 00", formatDuration(COOLDOWN_CHOICES.last()))
    }

    @Test
    fun `stepping a minute forward and back moves by the step`() {
        assertEquals(20 * 60 + 15, stepMinute(20 * 60, 15))
        assertEquals(19 * 60 + 45, stepMinute(20 * 60, -15))
    }

    @Test
    fun `stepping wraps around midnight in both directions`() {
        assertEquals(0, stepMinute(23 * 60 + 45, 15))
        assertEquals(23 * 60 + 45, stepMinute(0, -15))
    }

    @Test
    fun `stepping always lands inside a day`() {
        var minute = 0
        repeat(200) {
            minute = stepMinute(minute, 15)
            assertTrue(minute in 0 until 1440)
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: FAIL — `Unresolved reference: QUOTA_CHOICES`.

- [ ] **Step 3: Write the implementation**

Add to `app/src/main/res/values/strings.xml`:

```xml
    <string name="allowance_enable">Activer le quota</string>
    <string name="allowance_quota_label">Durée par jour</string>
    <string name="allowance_window_label">Plage horaire</string>
    <string name="allowance_cooldown_label">Délai avant tout assouplissement</string>
    <string name="allowance_earlier">Reculer</string>
    <string name="allowance_later">Avancer</string>
    <string name="allowance_window_from">de %1$s</string>
    <string name="allowance_window_to">à %1$s</string>
    <string name="allowance_cooldown_none">aucun</string>
    <string name="allowance_unlocked">Aucun délai : les réglages se changent librement. Choisir un délai verrouille tout assouplissement à venir — et ce choix-là est immédiat.</string>
```

Keep `</resources>` as the file's last line.

Create `app/src/main/kotlin/com/insta/reelsoff/ui/AllowanceEditors.kt`:

```kotlin
package com.insta.reelsoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.insta.reelsoff.R
import com.insta.reelsoff.service.AllowanceSettings

/** Offered daily budgets. Five minutes is the default and the middle choice. */
val QUOTA_CHOICES: List<Long> = listOf(60_000, 300_000, 600_000, 900_000, 1_800_000)

/**
 * Offered cooldowns. Zero comes first because that is where a fresh install
 * sits: the settings have to be arrangeable before the lock is armed, and
 * choosing any nonzero delay is a tightening, so it lands at once and locks
 * everything after it.
 */
val COOLDOWN_CHOICES: List<Long> = listOf(0, 3_600_000, 6 * 3_600_000, 24 * 3_600_000, 72 * 3_600_000)

private const val WINDOW_STEP_MINUTES = 15

/**
 * Moves a minute-of-day by [delta], wrapping at midnight in both directions.
 *
 * Kotlin's `%` keeps the sign of the dividend, so stepping back from 00:00
 * would land on a negative minute and every window comparison downstream would
 * quietly stop matching. The extra `+ 1440` is what prevents that.
 */
fun stepMinute(minuteOfDay: Int, delta: Int): Int = ((minuteOfDay + delta) % 1440 + 1440) % 1440

@Composable
fun AllowanceEditors(
    state: AllowanceUiState,
    onPropose: (AllowanceSettings) -> Unit,
) {
    // Rebuilt from what is actually in force, so an editor never proposes a
    // change relative to a value the lock has already superseded.
    val current = AllowanceSettings(
        enabled = state.enabled,
        quotaMillis = state.quotaMillis,
        windowStartMinutes = state.windowStartMinutes,
        windowEndMinutes = state.windowEndMinutes,
        cooldownMillis = state.cooldownMillis,
    )

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.allowance_enable),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Encre,
            )
            Switch(
                checked = state.enabled,
                onCheckedChange = { onPropose(current.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Papier,
                    checkedTrackColor = Accent,
                    checkedBorderColor = Accent,
                    uncheckedThumbColor = EncreDouce,
                    uncheckedTrackColor = Papier,
                    uncheckedBorderColor = EncreDouce,
                ),
            )
        }

        Spacer(Modifier.height(18.dp))
        ChoiceRow(
            label = stringResource(R.string.allowance_quota_label),
            choices = QUOTA_CHOICES,
            selected = state.quotaMillis,
            onSelect = { onPropose(current.copy(quotaMillis = it)) },
        )

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.allowance_window_label).uppercase(java.util.Locale.FRENCH),
            style = MaterialTheme.typography.labelSmall,
            color = EncreDouce,
        )
        Spacer(Modifier.height(8.dp))
        MinuteStepper(
            prefix = stringResource(R.string.allowance_window_from, formatMinuteOfDay(state.windowStartMinutes)),
            onStep = { onPropose(current.copy(windowStartMinutes = stepMinute(state.windowStartMinutes, it))) },
        )
        Spacer(Modifier.height(6.dp))
        MinuteStepper(
            prefix = stringResource(R.string.allowance_window_to, formatMinuteOfDay(state.windowEndMinutes)),
            onStep = { onPropose(current.copy(windowEndMinutes = stepMinute(state.windowEndMinutes, it))) },
        )

        Spacer(Modifier.height(18.dp))
        ChoiceRow(
            label = stringResource(R.string.allowance_cooldown_label),
            choices = COOLDOWN_CHOICES,
            selected = state.cooldownMillis,
            onSelect = { onPropose(current.copy(cooldownMillis = it)) },
        )
    }
}

/** Presets as plain text, the current one inked and the rest soft. */
@Composable
private fun ChoiceRow(
    label: String,
    choices: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
) {
    Text(
        text = label.uppercase(java.util.Locale.FRENCH),
        style = MaterialTheme.typography.labelSmall,
        color = EncreDouce,
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (choice in choices) {
            TextButton(
                onClick = { onSelect(choice) },
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    // Zero is a cooldown choice, and "0 s" would read as a
                    // duration rather than as the absence of one.
                    text = if (choice == 0L) stringResource(R.string.allowance_cooldown_none) else formatDuration(choice),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (choice == selected) Accent else EncreDouce,
                )
            }
        }
    }
}

@Composable
private fun MinuteStepper(prefix: String, onStep: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = prefix,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Encre,
        )
        TextButton(
            onClick = { onStep(-WINDOW_STEP_MINUTES) },
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text("−", style = MaterialTheme.typography.bodyMedium, color = Accent)
        }
        TextButton(
            onClick = { onStep(WINDOW_STEP_MINUTES) },
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text("+", style = MaterialTheme.typography.bodyMedium, color = Accent)
        }
    }
}
```

In `AllowancePanel.kt`, add an `onPropose: (AllowanceSettings) -> Unit` parameter and render the editors at the foot of the panel, below the lock hint and above the pending banner:

```kotlin
        Spacer(Modifier.height(20.dp))
        AllowanceEditors(state = state, onPropose = onPropose)
```

The `return@Column` early exit for a disabled quota must move **below** the editors block, or the switch that turns the quota on becomes unreachable once it is off. Restructure so the disabled case shows the explanatory line **and** the editors, and only the headline, window line, buttons and lock hint are gated on `state.enabled`.

Thread `onPropose` through `HomeScreen` to `viewModel::proposeSettings` in `MainActivity`, following the existing callback pattern.

- [ ] **Step 4: Run the tests and build**

Run: `./gradlew :detection:test :app:testDebugUnitTest --rerun-tasks && ./gradlew :app:assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/insta/reelsoff/ui/AllowanceEditors.kt app/src/main/kotlin/com/insta/reelsoff/ui/AllowancePanel.kt app/src/main/kotlin/com/insta/reelsoff/ui/HomeScreen.kt app/src/main/kotlin/com/insta/reelsoff/ui/MainActivity.kt app/src/main/res/values/strings.xml app/src/test/kotlin/com/insta/reelsoff/ui/AllowanceEditorsTest.kt
git commit -m "feat: choose the quota, the window and the cooldown"
```

---

### Task 9: Device verification and documentation

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything.
- Produces: nothing in code.

- [ ] **Step 1: Install and restore the service**

```bash
./gradlew :app:installDebug
adb shell appops set com.insta.reelsoff ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services com.insta.reelsoff/com.insta.reelsoff.service.InstagramWatcherService
adb shell settings put secure accessibility_enabled 1
sleep 6 && adb shell dumpsys accessibility | grep -A1 "Bound services"
```

The last command must print a bound service. It fails silently right after an install — re-run the two `settings put` commands and re-read if it prints nothing. **A test pass with the service off looks exactly like a success and measures nothing.**

- [ ] **Step 2: Verify the pass suspends blocking**

A fresh install arrives with the quota **disabled**, a 20:00–21:00 window and **no cooldown**. With no cooldown in force every edit applies at once, so the whole setup is doable in one sitting: in Digue, switch the quota on, step the window until it covers the current hour, and pick a quota of one minute so the exhaustion case can be watched rather than waited out.

Then, with Instagram open:

1. Confirm Reels is blocked as usual — open the Reels tab, land back on the feed.
2. Open Digue, press **Ouvrir**. Confirm the countdown starts.
3. Open the Reels tab. **It must stay open**, and scrolling must work.
4. Wait out the quota. Confirm blocking resumes within a few seconds.
5. Read the episode log to confirm blocks stopped and restarted around the pass:

```bash
adb shell run-as com.insta.reelsoff cat databases/reelsoff.db > /tmp/x.sqlite
sqlite3 /tmp/x.sqlite "select datetime(epochMillis/1000,'unixepoch','localtime'), surface from block_event order by epochMillis desc limit 20;"
```

- [ ] **Step 3: Verify the lock**

1. With no cooldown chosen, confirm a surface switch moves immediately in both directions — the lock is not armed yet.
2. Choose a cooldown of 1 h. Confirm it takes effect **at once**, with no banner: raising it is a tightening.
3. Turn off a surface switch. Confirm the switch does **not** move and a "Nouveau réglage actif dans …" banner appears counting down from about an hour.
4. Press **Annuler**. Confirm the banner goes and the switch is still on.
5. Turn **on** a surface that was off. Confirm it applies at once with no banner.
6. Try to set the cooldown back to "aucun". Confirm it is held behind the banner rather than applied — this is the trap that would otherwise undo the whole lock in one tap.

- [ ] **Step 4: Update the handoff**

In `CLAUDE.md`, add a short section covering: that a quota exists and how the three fine behaviours interact with it; that `enabled = false` is the strict state and why; that the cooldown compares two clocks and what that does and does not defend; and that neither the accessibility toggle nor uninstalling is reachable by any lock. Add the missing settings editors to the follow-ups list.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: record the quota, its lock and what the lock cannot reach"
```

---

## Self-review

**Spec coverage.** Data model → Task 1. Blocking decision and fail-closed → Tasks 1, 5. Pass lifecycle and the three ways it closes → Task 2. Day rollover → Tasks 1, 2. Lock, the three traps, the clock guard → Task 3. Pending-change representation and single-slot replacement → Tasks 3, 4, 6. Persistence → Task 4. Panel → Task 7. Editors for quota, window and cooldown → Task 8. Device verification and limits → Task 9. Tests → every task.

**Divergence from the spec, deliberate.** The spec gives `cooldownMillis` a default of 24 h. This plan defaults it to **zero**, because enabling the quota is itself a loosening: with a nonzero default, a fresh install would arm a 24-hour wait the moment the user switched the feature on, and the feature would do nothing for a day. At zero the settings are arrangeable, and choosing any delay is a tightening that lands at once and locks everything after it. The lock becomes something the user arms deliberately, in one gesture, rather than something that fires on first use. **Fold this back into the spec when the work lands.**

**Type consistency.** `AllowanceSettings`, `AllowanceState`, `LockedSettings`, `PendingChange` are defined once and used with the same field names throughout. Zone is always passed as `ZoneId`, wall clock always as `nowEpochMillis`, monotonic clock always as `nowElapsedRealtime`. `openPass`/`closePass` exist both as free functions (service package) and ViewModel methods — flagged in Task 6 with the qualification to use.
