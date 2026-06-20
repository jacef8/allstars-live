package com.libertyclerk.allstarslive

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libertyclerk.allstarslive.ingest.SrtIngestScreen
import com.libertyclerk.allstarslive.ui.theme.AllStarsLiveTheme

/** Minimal in-memory navigation; no nav library needed for the spike. */
private enum class Screen { HOME, INGEST }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The scorer is handheld for a whole game on top of live video — keep the screen on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            AllStarsLiveTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var screen by remember { mutableStateOf(Screen.HOME) }
                    when (screen) {
                        Screen.HOME -> HomeScreen(onOpenIngest = { screen = Screen.INGEST })
                        Screen.INGEST -> SrtIngestScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpenIngest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("All-Stars Live", fontSize = 34.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Liberty County AAA All-Stars — broadcast scorer",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "M0 skeleton • next: M1 ingest spike",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onOpenIngest, modifier = Modifier.padding(top = 32.dp)) {
            Text("Open SRT ingest spike (M1)")
        }
    }
}
