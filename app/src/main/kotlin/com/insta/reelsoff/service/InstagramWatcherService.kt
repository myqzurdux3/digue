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
import com.insta.reelsoff.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
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
            publishCaptureStatus(CaptureStatus(armedAtEpochMillis = sessionStamp))
            Log.i(TAG, "capture session armed")
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
            // RuleSetLoader.load() is designed to never throw, but this callback has no
            // caller-side try/catch the way onAccessibilityEvent() does — an uncaught throw
            // here crashes the service, and Android may then disable it for good, leaving the
            // user believing they are still protected. Belt and braces: fall back to an empty
            // rule set (blocks nothing, but stays alive) rather than let anything escape.
            val loaded = try {
                RuleSetLoader(this).load()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "rule loading failed unexpectedly", e)
                LoadedRules(RuleSet(version = 0, apps = emptyMap()), RuleSource.BUNDLED, "rule loading failed unexpectedly: ${e.message}")
            }
            classifier = ScreenClassifier(loaded.ruleSet)
            ruleSet = loaded.ruleSet
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

            // Collect settings changes on IO scope. A DataStore read can fail with an
            // IOException; retry rather than give up, since completing the flow here
            // would freeze `settings` at whatever it last held — including a stale
            // "enabled" value the UI has since disagreed with (F2). Retries never stop
            // — the service must keep trying to pick up the user's settings for as
            // long as it runs — which also means this flow never completes
            // exceptionally, so a `.catch` after this `retry` would be unreachable
            // dead code (deleted; see below for what serves its fail-closed intent
            // instead). Log from the retry predicate itself, on every attempt, so a
            // permanently broken DataStore stays visible instead of failing silently
            // forever. While retries are ongoing, `settings` simply stays at the
            // `@Volatile` default declared above — `BlockSettings()`, i.e.
            // blockedSurfaces = {REELS, EXPLORE} — which is already the
            // fail-closed value (both surfaces blocked), not a stale permissive one.
            scope.launch {
                runCatching {
                    SettingsStore(applicationContext).settings
                        .retry { e ->
                            Log.e(TAG, "settings read failed, retrying", e)
                            delay(1_000)
                            true
                        }
                        .collectLatest {
                            settings = it
                            applyDeclaredPackages(it.blockedSurfaces)
                        }
                }.onFailure {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "settings collection launch failed", it)
                }
            }

            // The quota's three streams follow the same shape as the settings
            // collector above, and for the same reasons: retry forever rather
            // than complete, since a completed flow would freeze the value at
            // whatever it last held; and never let anything escape, since these
            // run in a coroutine whose failure would take the service with it.
            //
            // Deliberately not folded into one combined flow: each of the three
            // is independently useful, and a single combine would stall all
            // three on whichever one was failing.
            collectAllowance("allowance settings", { SettingsStore(it).allowanceSettings }) {
                allowanceSettings = it
            }
            collectAllowance("allowance state", { SettingsStore(it).allowanceState }) {
                allowanceState = it
            }
            collectAllowance("pending change", { SettingsStore(it).pendingChange }) {
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
     * Mirrors one of the quota's DataStore streams into a `@Volatile` field.
     *
     * Retries forever rather than completing: a completed flow would pin the
     * field at whatever it last held, including a stale value the user has since
     * changed. The field's declared default is the strict one, so a stream that
     * never delivers leaves blocking untouched.
     */
    private fun <T> collectAllowance(
        what: String,
        stream: (Context) -> kotlinx.coroutines.flow.Flow<T>,
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
     * loop. It terminates only because DataStore suppresses writes that would
     * not change the stored value; it is not idempotent by construction. If this
     * ever writes something derived from more than `packages` (e.g. a timestamp),
     * the loop stops terminating.
     */
    private fun publishDeclaredPackages(packages: Set<String>) {
        scope.launch {
            runCatching { SettingsStore(applicationContext).setDeclaredPackages(packages) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "could not publish declared packages", it)
                }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(captureReceiver) }
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
        val snapshot = walker.walk(
            root = AccessibilityNodeLike(root),
            packageName = packageName,
            capturedAtMillis = System.currentTimeMillis(),
        )

        if (captureSession.shouldCapture()) {
            writeCapture(snapshot)
            if (captureStartedWallMillis == 0L) captureStartedWallMillis = System.currentTimeMillis()
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
                epochMillis = System.currentTimeMillis(),
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
     * Presses a node named by the rules, so a surface can be redirected instead of
     * exited. Returns false when nothing usable was found, which is the caller's
     * cue to leave the screen the ordinary way.
     *
     * Only on-screen candidates count. Instagram pre-mounts the neighbouring tab
     * with collapsed or negative bounds, so an off-screen search field is a real
     * possibility, and clicking one would do nothing while looking like success.
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

    private fun writeCapture(snapshot: ScreenSnapshot) {
        val directory = File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        val file = File(directory, "capture-%d-%03d.json".format(sessionStamp, captureIndex++))
        file.writeText(json.encodeToString(snapshot))
        Log.i(TAG, "wrote ${file.absolutePath} (${snapshot.nodes.size} nodes)")
    }

    companion object {
        const val ACTION_START_CAPTURE = "com.insta.reelsoff.START_CAPTURE"

        private const val TAG = "ReelsOff"
    }
}
