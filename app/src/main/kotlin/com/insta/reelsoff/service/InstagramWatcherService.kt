package com.insta.reelsoff.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.insta.detection.RuleSet
import com.insta.detection.ScreenClassifier
import com.insta.detection.ScreenSnapshot
import com.insta.detection.Surface
import com.insta.reelsoff.data.AppDatabase
import com.insta.reelsoff.data.BlockEvent
import com.insta.reelsoff.data.BlockSettings
import com.insta.reelsoff.data.CaptureStatus
import com.insta.reelsoff.data.PassEvent
import com.insta.reelsoff.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId

class InstagramWatcherService : AccessibilityService() {

    private val clock: Clock = SystemClock
    private val walker = TreeWalker()
    private val throttle = EventThrottle(clock)
    private val captureSession = CaptureSession(clock)
    private val json = Json { prettyPrint = true }
    private val blocker = Blocker(clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var classifier: ScreenClassifier

    @Volatile
    private var settings = BlockSettings()

    // All three start at their strictest value — quota disabled, no pass, nothing
    // pending — so a DataStore that never answers leaves blocking exactly as it
    // is today rather than opening a hole.
    @Volatile
    private var allowanceSettings = AllowanceSettings()

    @Volatile
    private var allowanceState = AllowanceState()

    @Volatile
    private var pendingChange: PendingChange? = null

    @Volatile
    private var ruleSet: RuleSet = RuleSet(version = 0, apps = emptyMap())

    /**
     * What [publishDeclaredPackages] last wrote successfully, so it can close the
     * loop it feeds itself. Only ever touched under [publishLock].
     */
    private var lastPublishedPackages: Set<String>? = null

    /** Serialises the compare-and-write in [publishDeclaredPackages]. */
    private val publishLock = Mutex()

    private var captureIndex = 0
    private var sessionStamp = 0L

    /**
     * Wall-clock instant the capture window opened, or 0 while still armed.
     * Kept separately from [CaptureSession.startedAtMillis] because that one runs
     * on elapsed real time, which the UI cannot compare against its own clock.
     */
    private var captureStartedWallMillis = 0L

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            sessionStamp = System.currentTimeMillis()
            captureIndex = 0
            captureStartedWallMillis = 0L
            captureSession.arm()
            purgeOldCaptures()
            publishCaptureStatus(CaptureStatus(armedAtEpochMillis = sessionStamp))
            Log.i(TAG, "capture session armed")
        }
    }

    /**
     * Re-reads the rules on demand.
     *
     * Without this the load status was only ever written in
     * [onServiceConnected], so a user who repaired a hand-edited rules.json kept
     * staring at the failure banner until the service happened to reconnect —
     * and the service kept running on the fallback rules the whole time. The
     * banner was telling the truth; there was simply no way to act on it.
     *
     * Refreshing the *status* alone would have been worse than useless: it would
     * have cleared the banner while the service still ran on the fallback.
     */
    private val reloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadRules()
        }
    }

    /**
     * Mirrors capture progress to the UI. Failing to publish must never take the
     * service down — the capture itself is unaffected, only its display.
     */
    private fun publishCaptureStatus(status: CaptureStatus) {
        scope.launch {
            runCatching { SettingsStore(applicationContext).setCaptureStatus(status) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "could not publish capture status", it)
                }
        }
    }

    /**
     * Wholly wrapped (F6): registerReceiver used to sit above the try block whose
     * comment claimed the whole method never throws. If it had thrown, `classifier`
     * (a `lateinit`) would never get assigned, and every later accessibility event
     * would throw UninitializedPropertyAccessException into onAccessibilityEvent's
     * own catch — logging forever, blocking nothing, with a healthy-looking service.
     * Now the entire body is covered, and the catch guarantees classifier ends up
     * initialized either way.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            ContextCompat.registerReceiver(
                this,
                captureReceiver,
                IntentFilter(ACTION_START_CAPTURE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            ContextCompat.registerReceiver(
                this,
                reloadReceiver,
                IntentFilter(ACTION_RELOAD_RULES),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            reloadRules()

            // Four independent collectors, all through the same helper — see
            // collectSetting for the retry-forever rule and why the fields it
            // writes are safe to leave at their defaults meanwhile.
            //
            // For `settings` in particular, that default is `BlockSettings()`,
            // i.e. blockedSurfaces = {REELS, EXPLORE}: already the fail-closed
            // value (both surfaces blocked), not a stale permissive one (F2).
            //
            // Deliberately not folded into one combined flow: each is
            // independently useful, and a single combine would stall all four on
            // whichever one was failing.
            collectSetting("settings", { SettingsStore(it).settings }) {
                settings = it
                applyDeclaredPackages(it.blockedSurfaces)
            }
            collectSetting("allowance settings", { SettingsStore(it).allowanceSettings }) {
                allowanceSettings = it
            }
            collectSetting("allowance state", { SettingsStore(it).allowanceState }) {
                allowanceState = it
            }
            collectSetting("pending change", { SettingsStore(it).pendingChange }) {
                pendingChange = it
            }

            Log.i(TAG, "service connected")
        } catch (e: Throwable) {
            // Unlike the scope.launch blocks above, this catch guards a lifecycle
            // callback body, not a coroutine — there is no suspending code here for
            // CancellationException to correctly propagate through, and rethrowing it
            // would let an exception escape onServiceConnected, risking Android
            // disabling the service for good while the user believes it is still
            // protected. So, unlike those blocks, this one does not rethrow.
            Log.e(TAG, "onServiceConnected failed unexpectedly", e)
            if (!::classifier.isInitialized) {
                classifier = ScreenClassifier(RuleSet(version = 0, apps = emptyMap()))
            }
            // Land on the unmatchable sentinel rather than whatever packageNames
            // was declared (or left un-narrowed) before this failure — see F1.
            applyDeclaredPackages(emptySet())
        }
    }

    /**
     * Loads the rules and publishes what happened, at connection time and again
     * whenever the user asks for a reload.
     *
     * RuleSetLoader.load() is designed never to throw, but the callers here have
     * no try/catch of their own the way onAccessibilityEvent() does — an
     * uncaught throw crashes the service, and Android may then disable it for
     * good, leaving the user believing they are still protected. Belt and
     * braces: fall back to an empty rule set (blocks nothing, but stays alive)
     * rather than let anything escape.
     */
    private fun reloadRules() {
        val loaded = try {
            RuleSetLoader(this).load()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.e(TAG, "rule loading failed unexpectedly", e)
            LoadedRules(
                RuleSet(version = 0, apps = emptyMap()),
                RuleSource.BUNDLED,
                "rule loading failed unexpectedly: ${e.message}",
            )
        }
        classifier = ScreenClassifier(loaded.ruleSet)
        ruleSet = loaded.ruleSet
        // The declared packages come from the rule set, so a reload that changes
        // which apps carry surfaces has to redeclare them too.
        applyDeclaredPackages(settings.blockedSurfaces)
        Log.i(TAG, "rules loaded from ${loaded.source}${loaded.error?.let { " ($it)" } ?: ""}")

        // Mirror the load outcome into DataStore (F1): the home screen has no other
        // way to learn the service fell back to bundled rules, or that even those
        // failed to parse — both of which mean the app can be blocking nothing while
        // showing "Service actif".
        scope.launch {
            runCatching {
                SettingsStore(applicationContext).setRuleLoadStatus(loaded.source.name, loaded.error)
            }.onFailure {
                if (it is CancellationException) throw it
                Log.e(TAG, "could not persist rule load status", it)
            }
        }
    }

    /**
     * Mirrors one DataStore stream into a `@Volatile` field.
     *
     * Retries forever rather than completing: a DataStore read can fail with an
     * IOException, and a completed flow would pin the field at whatever it last
     * held — including a stale value the user has since changed. Retrying without
     * end also means the flow never completes exceptionally, so a `.catch` after
     * the `retry` would be unreachable. Every field this writes declares the
     * strict value as its default, so a stream that never delivers leaves
     * blocking exactly as it is rather than opening a hole.
     *
     * Logs from the retry predicate itself, on every attempt, so a permanently
     * broken DataStore stays visible instead of failing silently forever.
     *
     * Nothing escapes: this runs in a coroutine whose failure would take the
     * service down with it, and Android may answer that by disabling the service
     * for good.
     */
    private fun <T> collectSetting(
        what: String,
        stream: (Context) -> Flow<T>,
        assign: (T) -> Unit,
    ) {
        scope.launch {
            runCatching {
                stream(applicationContext)
                    .retry { e ->
                        Log.e(TAG, "$what read failed, retrying", e)
                        delay(1_000)
                        true
                    }
                    .collectLatest { assign(it) }
            }.onFailure {
                if (it is CancellationException) throw it
                Log.e(TAG, "$what collection launch failed", it)
            }
        }
    }

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
                // See packageNamesFor: a null/empty packageNames means "every
                // app" to Android, so an empty selection must be expressed as
                // a package that cannot match.
                packageNames = packageNamesFor(packages)
            }
            Log.i(TAG, "declared packages: ${packages.size}")
            // Only reached once the assignment above has actually succeeded, and
            // publishes exactly what was assigned (not the sentinel array) — if
            // the assignment throws, this line never runs and the home screen
            // keeps showing whatever was last actually declared, rather than
            // claiming success for a change that never took.
            publishDeclaredPackages(packages)
        }.onFailure { Log.e(TAG, "could not narrow declared packages", it) }
    }

    /**
     * Mirrors the packages just declared to the UI. Failing to publish must never
     * take the service down — the declaration itself already succeeded, only its
     * display is affected.
     *
     * This writes to the same DataStore the settings collector above reads
     * (`SettingsStore(...).settings`), so every publish re-emits settings and
     * re-runs applyDeclaredPackages, which calls back in here — a self-feeding
     * loop.
     *
     * The guard below is what stops it, and it is deliberately in this code
     * rather than borrowed from the storage layer. It used to terminate only
     * because DataStore happens to suppress a write that would not change the
     * stored value: a property of a library, not of this app, and one that a
     * single added field — a timestamp, say — would quietly cost us. Now the loop
     * closes here, where the reason it closes is visible.
     *
     * The value is recorded only once the write has actually landed, which is
     * what keeps the guard from swallowing a retry: a publish that failed leaves
     * the field alone, so the next settings emission tries again.
     *
     * Compare and write happen **inside one mutex**, not around the launch. Read
     * on the calling thread and written on IO, the check would have raced: two
     * quick surface toggles produce publishes A then B, `scope.launch` does not
     * order them, and B landing before A leaves the store holding A while the
     * field says B — after which the guard suppresses every correction and the
     * "Applications observées" line stays wrong for good. Serialising makes the
     * last publish started the last one stored.
     */
    private fun publishDeclaredPackages(packages: Set<String>) {
        scope.launch {
            publishLock.withLock {
                if (packages == lastPublishedPackages) return@withLock
                runCatching {
                    SettingsStore(applicationContext).setDeclaredPackages(packages)
                    lastPublishedPackages = packages
                }.onFailure {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "could not publish declared packages", it)
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(captureReceiver) }
        runCatching { unregisterReceiver(reloadReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Everything is wrapped: an exception escaping this callback crashes the
     * service, and Android may then disable it for good — leaving the user
     * believing they are still protected.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handle(event)
        } catch (e: Throwable) {
            Log.e(TAG, "event handling failed", e)
        }
    }

    override fun onInterrupt() = Unit

    private fun handle(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        // Belt-and-braces: packageNames narrows what Android delivers, but should
        // that ever widen (see F1), this keeps captures and tree walks scoped to
        // apps the loaded rule set actually knows about.
        if (packageName !in ruleSet.apps) return
        // Always consult the throttle (F8): it used to be short-circuited away
        // whenever a capture session was active, which meant a content-changed
        // event walked the tree unthrottled — on the main thread — for the whole
        // 60-second capture window. Snapshots are still only written every 3s by
        // captureSession.shouldCapture() below, so the extra walks bought nothing
        // but jank.
        if (!throttle.shouldProcess()) return

        // Frequently null during screen transitions. Nothing to do but skip.
        val root = rootInActiveWindow ?: return
        decide(root, packageName)
    }

    /**
     * The whole decision for one event, split out of [handle] only to keep that
     * one about the guards and this one about the work.
     *
     * Note what is deliberately absent: nothing here recycles an
     * `AccessibilityNodeInfo`. `recycle()` is a no-op from API 33, `minSdk` is 26,
     * and the versions in between are the ones this project has no device for —
     * so recycling would be code that never runs where it can be observed and only
     * runs where it cannot. Recycling a node the platform's own cache still holds
     * throws later, from inside the framework, on an unrelated call; caught by the
     * guard around `onAccessibilityEvent`, it would leave a bound service that
     * blocks nothing. See the follow-up note in CLAUDE.md.
     */
    private fun decide(root: AccessibilityNodeInfo, packageName: String) {
        // One reading of the wall clock for the whole event. Three separate calls
        // used to serve the snapshot, the quota and the logged episode, so the row
        // written to the history was not stamped with the instant that decided it.
        val now = System.currentTimeMillis()

        val snapshot = walker.walk(
            root = AccessibilityNodeLike(root),
            packageName = packageName,
            capturedAtMillis = now,
        )

        if (captureSession.shouldCapture()) {
            writeCapture(snapshot)
            if (captureStartedWallMillis == 0L) captureStartedWallMillis = now
            publishCaptureStatus(
                CaptureStatus(
                    armedAtEpochMillis = sessionStamp,
                    startedAtEpochMillis = captureStartedWallMillis,
                    count = captureSession.capturedCount,
                ),
            )
        }

        val classification = classifier.classify(snapshot)

        // The quota can suspend blocking, and a matured pending change can have
        // altered the settings without anything having written it back yet — so
        // both are derived here rather than read. Everything unreadable lands on
        // the strict side: passIsOpen needs every one of its conditions, and the
        // fields above default to "no quota, no pass, nothing pending".
        val effective = effectiveSettings(
            stored = LockedSettings(allowanceSettings, settings.blockedSurfaces),
            pending = pendingChange,
            nowEpochMillis = now,
            nowElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
        )
        val zone = ZoneId.systemDefault()
        // A pass that runs out while the user is scrolling is noticed by nobody
        // else: the screen may be closed, and the pure functions only ever derive.
        // The service is the component that is always running, so it is the one
        // that banks the time and records the pass.
        val state = recordAnyClosedPass(effective.allowance, allowanceState, now, zone)
        val blockedNow = effectiveBlockedSurfaces(
            locked = effective,
            state = state,
            nowEpochMillis = now,
            zone = zone,
        )
        val decision = blocker.decide(classification, blockedNow)

        when (decision.action) {
            BlockAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            BlockAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            // Falls back to leaving the screen when the node is gone: a redirect
            // that silently does nothing would leave the user on the very surface
            // the rule exists to take them off.
            BlockAction.CLICK ->
                if (!clickNode(root, decision.clickViewId)) performGlobalAction(GLOBAL_ACTION_BACK)
            BlockAction.NONE -> Unit
        }

        if (decision.recordEpisode) {
            val event = BlockEvent(
                epochMillis = now,
                surface = classification.surface.name,
                ruleTier = decision.tier?.name ?: "UNKNOWN",
            )
            // Off the main thread: onAccessibilityEvent runs on it, and a disk
            // write in the hot path would show up as jank in Instagram itself.
            scope.launch {
                runCatching { AppDatabase.get(applicationContext).blockEventDao().insert(event) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.e(TAG, "could not record episode", it)
                    }
            }
            Log.i(TAG, "blocked ${classification.surface} via ${decision.tier}")
        }
    }

    /**
     * Banks a pass that has just ended and writes it to the history, returning the
     * state to reason with — settled if it closed one, untouched otherwise.
     *
     * Returns synchronously and persists in the background, so the blocking
     * decision below never waits on a disk write: `onAccessibilityEvent` runs on
     * the main thread, and the returned state is already correct whether or not
     * the write lands.
     *
     * The write feeds the same DataStore this service collects, so the field is
     * updated by that collector rather than here. It terminates by idempotence,
     * not by luck: settling an already-shut pass returns it unchanged, so the
     * second pass through produces no write.
     */
    private fun recordAnyClosedPass(
        settings: AllowanceSettings,
        state: AllowanceState,
        nowEpochMillis: Long,
        zone: ZoneId,
    ): AllowanceState {
        val closure = closureOf(settings, state, nowEpochMillis, zone) ?: return state
        scope.launch {
            runCatching {
                SettingsStore(applicationContext).setAllowanceState(closure.state)
                // Same arithmetic as the UI's "Fermer maintenant", by construction:
                // both go through closureFrom.
                // Zero-length closures are not recorded: a pass carried over from
                // an earlier day has no duration that belongs to today.
                if (closure.durationMillis > 0) {
                    AppDatabase.get(applicationContext).passEventDao().insert(
                        PassEvent(
                            epochMillis = nowEpochMillis,
                            durationMillis = closure.durationMillis,
                        ),
                    )
                }
            }.onFailure {
                if (it is CancellationException) throw it
                Log.e(TAG, "could not record a closed pass", it)
            }
        }
        return closure.state
    }

    /**
     * Presses a node named by the rules, so a surface can be redirected instead of
     * exited. Returns false when nothing usable was found, which is the caller's
     * cue to leave the screen the ordinary way.
     *
     * Only on-screen candidates count. Instagram pre-mounts the neighbouring tab
     * with collapsed or negative bounds, so an off-screen search field is a real
     * possibility, and clicking one would do nothing while looking like success.
     *
     */
    private fun clickNode(root: AccessibilityNodeInfo, viewId: String?): Boolean {
        if (viewId == null) return false
        val target = root.findAccessibilityNodeInfosByViewId(viewId)
            .orEmpty()
            .firstOrNull { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                !bounds.isEmpty
            }
            ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Deletes the snapshots left by earlier sessions, when a new one is armed.
     *
     * These files hold real personal data — the project has measured it: contact
     * names and previews of private conversations arrive inside
     * `contentDescription`, which this app does read. They sit in app-specific
     * external storage, which the user's own file manager can open, and nothing
     * used to remove them, so every capture ever taken accumulated there until
     * the app was uninstalled.
     *
     * External storage is kept rather than `filesDir` on purpose: the project's
     * own recipe pulls these files off the device without `run-as`, and moving
     * them would break it. Clearing them at each arming does not make the
     * exposure zero — it bounds it to one session instead of to the app's whole
     * history, which is the part that was actually indefensible. The home screen
     * offers to clear the rest on demand.
     *
     * Which files go is decided by [deleteCaptures], shared with that button.
     */
    private fun purgeOldCaptures() {
        val current = sessionStamp
        scope.launch {
            runCatching {
                val gone = deleteCaptures(applicationContext, before = current)
                if (gone > 0) Log.i(TAG, "purged $gone captures from earlier sessions")
            }.onFailure {
                if (it is CancellationException) throw it
                Log.e(TAG, "could not purge earlier captures", it)
            }
        }
    }

    /**
     * Writes one snapshot, off the main thread.
     *
     * Encoding a several-hundred-node tree to indented JSON and writing it is
     * hundreds of kilobytes of work, and this used to happen inline — on the main
     * thread, inside `onAccessibilityEvent`, every three seconds for a whole
     * minute. It is exactly what the episode insert refuses to do a few lines
     * above, for exactly the same reason: it shows up as jank inside the app being
     * watched.
     *
     * The file name is claimed here, synchronously, so the numbering stays
     * sequential however the writes interleave.
     */
    private fun writeCapture(snapshot: ScreenSnapshot) {
        val name = "capture-%d-%03d.json".format(sessionStamp, captureIndex++)
        scope.launch {
            runCatching {
                val file = File(captureDirectory(applicationContext), name)
                file.writeText(json.encodeToString(snapshot))
                Log.i(TAG, "wrote ${file.absolutePath} (${snapshot.nodes.size} nodes)")
            }.onFailure {
                if (it is CancellationException) throw it
                Log.e(TAG, "could not write capture", it)
            }
        }
    }

    companion object {
        const val ACTION_START_CAPTURE = "com.insta.reelsoff.START_CAPTURE"
        const val ACTION_RELOAD_RULES = "com.insta.reelsoff.RELOAD_RULES"

        private const val TAG = "ReelsOff"

    }
}
