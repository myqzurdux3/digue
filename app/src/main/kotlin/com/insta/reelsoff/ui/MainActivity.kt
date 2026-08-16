package com.insta.reelsoff.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.insta.reelsoff.R
import com.insta.reelsoff.service.InstagramWatcherService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CaptureScreen()
                }
            }
        }
    }
}

@Composable
private fun CaptureScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text(stringResource(R.string.open_accessibility_settings))
        }

        Text(stringResource(R.string.capture_hint), style = MaterialTheme.typography.bodyMedium)

        Button(onClick = {
            context.sendBroadcast(
                Intent(InstagramWatcherService.ACTION_START_CAPTURE)
                    .setPackage(context.packageName),
            )
        }) {
            Text(stringResource(R.string.start_capture))
        }
    }
}
