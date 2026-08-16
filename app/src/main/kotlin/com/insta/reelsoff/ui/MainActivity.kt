package com.insta.reelsoff.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insta.reelsoff.service.InstagramWatcherService

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    HomeScreen(
                        state = state,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onStartCapture = {
                            sendBroadcast(
                                Intent(InstagramWatcherService.ACTION_START_CAPTURE)
                                    .setPackage(packageName),
                            )
                        },
                        onBlockReelsChanged = viewModel::setBlockReels,
                        onBlockExploreChanged = viewModel::setBlockExplore,
                    )
                }
            }
        }
    }

    /** The accessibility toggle lives in system settings, so re-read on return. */
    override fun onResume() {
        super.onResume()
        viewModel.refreshServiceStatus()
    }
}
