package com.creditinform.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.creditinform.app.monitoring.AlarmController

class AlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenPresentation()
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun configureLockScreenPresentation() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun render(alertIntent: Intent) {
        val title = alertIntent.getStringExtra(EXTRA_TITLE).orEmpty()
        val message = alertIntent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        val detail = alertIntent.getStringExtra(EXTRA_DETAIL).orEmpty()
        val prominentText = alertIntent.getStringExtra(EXTRA_PROMINENT_TEXT).orEmpty()

        setContent {
            CreditInformTheme {
                AlertScreen(
                    title = title,
                    message = message,
                    detail = detail,
                    prominentText = prominentText,
                    onDismiss = {
                        AlarmController.dismissAll(this)
                        finishAndRemoveTask()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_PROMINENT_TEXT = "prominent_text"
    }
}

@Composable
private fun AlertScreen(
    title: String,
    message: String,
    detail: String,
    prominentText: String,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = true) { }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.error) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(20.dp))
            Text(
                message,
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            if (prominentText.isNotBlank()) {
                Spacer(Modifier.height(32.dp))
                Text(
                    prominentText,
                    color = Color.White,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    detail,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(48.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("我已知晓，关闭提醒")
            }
        }
    }
}
