package com.aitddemo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Control scheme: STEERING = the stick gives the screen direction to walk in; DPAD = fixed 4-arrow overlay with the
 * original tank controls (forward/backward relative to the facing, turn in place).
 */
enum class ControlMode { STEERING, DPAD }

/** Options screen: control scheme and Developer Mode. */
@Composable
fun OptionsScreen(
    controlMode: ControlMode,
    onControlModeChange: (ControlMode) -> Unit,
    developerMode: Boolean,
    onDeveloperModeChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    // Secret gesture: 5 rapid taps outside the options toggle Developer Mode (each tap within secretTapWindowMs of the last).
    var secretTapCount by remember { mutableIntStateOf(0) }
    var secretTapLastTimeMs by remember { mutableLongStateOf(0L) }
    val secretTapWindowMs = 600L

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF14080A))) {
        StartPictureImage(StartPicture.MENU)
        GameCanvas(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .offset(x = gx(0), y = gy(60))
                    .size(gx(320), gy(64))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(unit * 1.5f, Color(0xFF8A6D3B))
            )

            // Tap catcher for the secret gesture; composed before the options so those still claim their own taps.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                val now = System.currentTimeMillis()
                                secretTapCount = if (now - secretTapLastTimeMs <= secretTapWindowMs) secretTapCount + 1 else 1
                                secretTapLastTimeMs = now
                                if (secretTapCount >= 5) {
                                    onDeveloperModeChange(!developerMode)
                                    secretTapCount = 0
                                }
                            }
                        )
                    }
            )

            val controlsLabel = "Controls: " + when (controlMode) {
                ControlMode.STEERING -> "Stick"
                ControlMode.DPAD -> "D-Pad"
            }
            val controlsTextX = 160 - bitmapTextWidth(controlsLabel) / 2
            Box(
                modifier = Modifier
                    .offset(x = unit * controlsTextX, y = gy(74))
                    .clickable {
                        onControlModeChange(if (controlMode == ControlMode.STEERING) ControlMode.DPAD else ControlMode.STEERING)
                    }
            ) {
                BitmapText(text = controlsLabel, scale = unit.value, color = Color(0xFFF2E4B8))
            }

            // Developer Mode gates whether the work-in-progress intro/attic flow can be started. Read-only status here.
            val devLabel = "Developer Mode: " + if (developerMode) "Yes" else "No"
            val devTextX = 160 - bitmapTextWidth(devLabel) / 2
            Box(
                modifier = Modifier.offset(x = unit * devTextX, y = gy(94))
            ) {
                BitmapText(text = devLabel, scale = unit.value, color = Color(0xFFF2E4B8))
            }
        }
        BackButton(onClick = onBack)
    }
}
