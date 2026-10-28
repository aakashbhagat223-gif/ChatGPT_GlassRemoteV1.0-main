package com.glassremote.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun GlassAutomationPanel(
    onSendText: (String) -> Unit,
    onWatchJio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .14f), Color.White.copy(alpha = .05f))))
            .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SMART CONTROLS", color = Color.White)
        OutlinedTextField(
            value = text, onValueChange = { if (it.length <= 500) text = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Text to send to TV") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { onSendText(text); text = "" } })
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { if (text.isNotBlank()) { onSendText(text); text = "" } }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Send Text") }
            OutlinedButton(onClick = {
                val copied = clipboard.getText()?.text.orEmpty()
                if (copied.isNotBlank()) onSendText(copied)
            }, modifier = Modifier.weight(1f)) { Text("Paste & Send") }
        }
        OutlinedButton(onClick = onWatchJio, modifier = Modifier.fillMaxWidth()) { Text("Watch Jio · HDMI 2") }
        }
    }
}
