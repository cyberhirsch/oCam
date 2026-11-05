package com.ocam.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ocam.qr.QrContent

/**
 * What a code says. Nothing happens until it has been read and a button pressed: a code is an
 * address nobody can read by looking at it, so the whole of it is on screen first.
 */
@Composable
fun QrCard(code: String, onMessage: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val content = QrContent.of(code)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 44.dp)
            .background(Color(0xE6000000), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = "QR · ${content.kind.label}", color = Color(0x99FFFFFF), fontSize = 10.sp)
        Text(
            text = content.text,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (content.openable) {
                FlatButton(
                    label = "OPEN",
                    active = true,
                    onClick = { if (!open(context, content.text)) onMessage("No app here can open that") },
                )
            }
            FlatButton(
                label = "COPY",
                active = false,
                onClick = {
                    clipboard.setText(AnnotatedString(content.text))
                    onMessage("Copied")
                },
            )
            FlatButton(label = "CLOSE", active = false, onClick = onDismiss)
        }
    }
}

/** Browsable only: what a web page could link to, and nothing that reaches inside an app. */
private fun open(context: Context, text: String): Boolean {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(text)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    return runCatching { context.startActivity(view) }.isSuccess
}
