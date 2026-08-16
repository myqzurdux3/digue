package com.insta.reelsoff.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.insta.detection.ScreenClassifier
import com.insta.detection.ScreenSnapshot
import com.insta.detection.Surface
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
    private lateinit var classifier: ScreenClassifier

    private var captureIndex = 0
    private var sessionStamp = 0L

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            sessionStamp = System.currentTimeMillis()
            captureIndex = 0
            captureSession.start()
            Log.i(TAG, "capture session started")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(
            this,
            captureReceiver,
            IntentFilter(ACTION_START_CAPTURE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val loaded = RuleSetLoader(this).load()
        classifier = ScreenClassifier(loaded.ruleSet)
        Log.i(TAG, "rules loaded from ${loaded.source}${loaded.error?.let { " ($it)" } ?: ""}")
        Log.i(TAG, "service connected")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(captureReceiver) }
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
        if (!captureSession.isActive() && !throttle.shouldProcess()) return

        // Frequently null during screen transitions. Nothing to do but skip.
        val root = rootInActiveWindow ?: return
        val snapshot = walker.walk(
            root = AccessibilityNodeLike(root),
            packageName = INSTAGRAM_PACKAGE,
            capturedAtMillis = System.currentTimeMillis(),
        )

        if (captureSession.shouldCapture()) writeCapture(snapshot)

        val classification = classifier.classify(snapshot)
        val decision = blocker.decide(classification, BLOCKED_SURFACES)

        when (decision.action) {
            BlockAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            BlockAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            BlockAction.NONE -> Unit
        }

        if (decision.recordEpisode) {
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

        // Task 10 replaces this constant with a user-configurable setting.
        private val BLOCKED_SURFACES = setOf(Surface.REELS, Surface.EXPLORE)
    }
}
