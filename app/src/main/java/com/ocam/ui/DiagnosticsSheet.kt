package com.ocam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * The device's own account of its cameras, in a form that can be pasted into a bug report.
 * Without a phone to plug in, this is how the device tells us what it actually has.
 */
@Composable
fun DiagnosticsSheet(text: String, onCopy: (String) -> Unit, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0C0C0C), RoundedCornerShape(10.dp))
                .padding(14.dp),
        ) {
            Text(
                text = "Camera report",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = text,
                color = Color(0xDDFFFFFF),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(
                    text = "COPY",
                    selected = true,
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        onCopy(text)
                    },
                )
                Pill(text = "CLOSE", selected = false, onClick = onClose)
            }
        }
    }
}
