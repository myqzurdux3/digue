package com.insta.reelsoff.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insta.reelsoff.service.InstagramWatcherService

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent bars over the paper background, with dark system icons: the
        // theme is locked to light, so the default `auto` style would flip the icons
        // to white the moment the phone is in dark mode and make them invisible.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            DigueTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    // Collected separately because it re-emits every second, and
                    // uiState must not: that one rebuilds the 14-day chart.
                    val allowance by viewModel.allowance.collectAsStateWithLifecycle()
                    HomeScreen(
                        state = state,
                        allowance = allowance,
                        modifier = Modifier.safeDrawingPadding(),
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onDeleteCaptures = viewModel::deleteAllCaptures,
                        onStartCapture = {
                            sendBroadcast(
                                Intent(InstagramWatcherService.ACTION_START_CAPTURE)
                                    .setPackage(packageName),
                            )
                        },
                        onSurfaceBlockedChanged = { surface, blocked ->
                            viewModel.setSurfaceBlocked(surface, blocked)
                        },
                        onOpenPass = viewModel::openPass,
                        onClosePass = viewModel::closePass,
                        onCancelPendingChange = viewModel::cancelPendingChange,
                        onProposeAllowance = viewModel::proposeAllowanceSettings,
                        onReloadRules = {
                            sendBroadcast(
                                Intent(InstagramWatcherService.ACTION_RELOAD_RULES)
                                    .setPackage(packageName),
                            )
                        },
                    )
                }
            }
        }
    }

    /** The accessibility toggle lives in system settings, so re-read on return. */
    override fun onResume() {
        super.onResume()
        viewModel.refreshServiceStatus()
        viewModel.refreshInstalledPackages()
        viewModel.refreshCaptures()
        // A held change can mature while this screen is closed — which is the
        // usual case for a delay measured in hours. Writing it back here keeps
        // the store from drifting behind the values already in force.
        viewModel.commitAnyMaturedChange()
    }
}
