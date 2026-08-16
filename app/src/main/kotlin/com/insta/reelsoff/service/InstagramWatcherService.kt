package com.insta.reelsoff.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.insta.detection.RuleSet
import com.insta.detection.ScreenClassifier
import com.insta.detection.ScreenSnapshot
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
                LoadedRules(RuleSet(version = 0, surfaces = emptyMap()), RuleSource.BUNDLED, "rule loading failed unexpectedly: ${e.message}")
            }
            classifier = ScreenClassifier(loaded.ruleSet)
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
            // blockReels = true, blockExplore = true — which is already the
            // fail-closed value (both surfaces blocked), not a stale permissive one.
            scope.launch {
                runCatching {
                    SettingsStore(applicationContext).settings
                        .retry { e ->
                            Log.e(TAG, "settings read failed, retrying", e)
                            delay(1_000)
                            true
                        }
                        .collectLatest { settings = it }
                }.onFailure {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "settings collection launch failed", it)
                }
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
                classifier = ScreenClassifier(RuleSet(version = 0, surfaces = emptyMap()))
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
        if (event?.packageName != INSTAGRAM_PACKAGE) return
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
            packageName = INSTAGRAM_PACKAGE,
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
        val decision = blocker.decide(classification, settings.blockedSurfaces)

        when (decision.action) {
            BlockAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            BlockAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
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

    private fun writeCapture(snapshot: ScreenSnapshot) {
        val directory = File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        val file = File(directory, "capture-%d-%03d.json".format(sessionStamp, captureIndex++))
        file.writeText(json.encodeToString(snapshot))
        Log.i(TAG, "wrote ${file.absolutePath} (${snapshot.nodes.size} nodes)")
    }

    companion object {
        const val ACTION_START_CAPTURE = "com.insta.reelsoff.START_CAPTURE"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val TAG = "ReelsOff"
    }
}
