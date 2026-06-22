package com.libertyclerk.allstarslive.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Start game stream" sheet — name + who-can-watch, then creates the broadcast.
 * App-level (raised from either the Video tab or the Game page) so there's one
 * dialog and one [Broadcast] flow no matter where the operator taps Go Live.
 */
@Composable
fun GoLiveDialog(
    initialTitle: String,
    onStart: (title: String, privacy: String) -> Unit,
    onCancel: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle.ifBlank { "All-Stars Live" }) }
    var privacy by remember { mutableStateOf("unlisted") }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC05080C)).clickable(onClick = onCancel).imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .padding(16.dp)
                .background(Color(0xFF141A22), RoundedCornerShape(16.dp))
                .padding(20.dp)
                .pointerInput(Unit) { detectTapGestures { } },   // swallow taps so the card doesn't close
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Start game stream", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Stream name (shown on YouTube)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Who can watch", color = Color(0xFF9AA0A6), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrivacyChip("Public", "public", privacy) { privacy = "public" }
                PrivacyChip("Unlisted", "unlisted", privacy) { privacy = "unlisted" }
                PrivacyChip("Private", "private", privacy) { privacy = "private" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onCancel) { Text("Cancel", color = Color(0xFF9AA0A6)) }
                Button(
                    onClick = { onStart(title, privacy) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B5C), contentColor = Color.White),
                ) { Text("Go Live", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun PrivacyChip(label: String, value: String, selected: String, onClick: () -> Unit) {
    val on = value == selected
    Text(
        label,
        color = if (on) Color(0xFF05080C) else Color(0xFFE8EAED),
        fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .background(if (on) Color(0xFFA3E635) else Color(0xFF222B36), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}
