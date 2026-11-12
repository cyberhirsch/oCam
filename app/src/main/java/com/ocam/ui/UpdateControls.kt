package com.ocam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ocam.CameraUiState
import com.ocam.CameraViewModel
import com.ocam.update.UpdateState

/**
 * An update offered over the frame. Not a dialog: a picture may be about to happen, and this has
 * to stay out of the way of the shutter. It stands aside for a run or a timer altogether.
 */
@Composable
fun UpdateStrip(state: CameraUiState, viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val update = state.update
    val offered = update is UpdateState.Available || update is UpdateState.Downloading ||
        update is UpdateState.Ready || update is UpdateState.Installing || update is UpdateState.Failed
    if (!offered || state.updateDismissed || state.sequence != null || state.countdown != null) return

    Column(
        modifier = modifier
            .widthIn(max = 280.dp)
            .background(Color(0xCC000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = updateText(update), color = Color.White, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UpdateAction(state, viewModel)
            if (update !is UpdateState.Installing && update !is UpdateState.Downloading) {
                FlatButton(
                    label = if (update is UpdateState.Failed) "OK" else "LATER",
                    active = false,
                    onClick = viewModel::dismissUpdate,
                )
            }
        }
    }
}

/** The same thing in settings, where a check can also be asked for. */
@Composable
fun UpdateSection(state: CameraUiState, viewModel: CameraViewModel) {
    val update = state.update
    Text(
        text = "Updates",
        color = Color.White,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
    )
    Text(
        text = updateText(update),
        color = Color(0x88FFFFFF),
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    when (update) {
        UpdateState.Idle, UpdateState.UpToDate, is UpdateState.Failed, UpdateState.Checking ->
            FlatButton(
                label = "CHECK",
                active = false,
                enabled = update != UpdateState.Checking,
                onClick = viewModel::checkForUpdate,
            )
        else -> UpdateAction(state, viewModel)
    }
}

/** The one thing that can be done next, when there is one. */
@Composable
private fun UpdateAction(state: CameraUiState, viewModel: CameraViewModel) {
    when (state.update) {
        is UpdateState.Available ->
            FlatButton(label = "UPDATE", active = true, onClick = viewModel::downloadUpdate)
        is UpdateState.Downloading ->
            FlatButton(label = "CANCEL", active = false, onClick = viewModel::cancelUpdateDownload)
        is UpdateState.Ready ->
            FlatButton(
                label = "INSTALL",
                active = true,
                // Installing closes the app, so a shot or a merge in progress holds it back.
                enabled = state.safeToInstall,
                onClick = viewModel::installUpdate,
            )
        else -> Unit
    }
}

private fun updateText(update: UpdateState): String = when (update) {
    UpdateState.Idle -> "Asks GitHub once a day, and downloads nothing until told to."
    UpdateState.Checking -> "Asking GitHub."
    UpdateState.UpToDate -> "This is the newest build."
    is UpdateState.Available ->
        "oCam ${update.offer.build.label} is out, ${update.offer.sizeBytes / (1024 * 1024)} MB."
    is UpdateState.Downloading -> "Downloading oCam ${update.offer.build.label}, ${update.percent}%."
    is UpdateState.Ready ->
        "oCam ${update.offer.build.label} is checked and ready. Installing closes the app; open it again afterwards."
    is UpdateState.Installing -> "Installing oCam ${update.ready.offer.build.label}."
    is UpdateState.Failed -> update.message
}
