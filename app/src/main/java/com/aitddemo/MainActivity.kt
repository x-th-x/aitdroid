package com.aitddemo

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private enum class Screen { LOGO, TITLE, MENU, SELECT, MUSIC, INTRO, ATTIC, OPTIONS }

private val SCREEN_BACKGROUND = Color(0xFF14080A)
private val TEXT_COLOR = Color(0xFFF2E4B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Developer shortcuts (adb): `--ez devAutoAttic true`, `--ez devAutoIntro true`, `--ez devAutoMusic true`
        // jump straight into that screen; `--es devControlMode dpad` starts with D-Pad controls.
        val devAutoAttic = intent?.getBooleanExtra("devAutoAttic", false) ?: false
        val devAutoIntro = intent?.getBooleanExtra("devAutoIntro", false) ?: false
        val devAutoMusic = intent?.getBooleanExtra("devAutoMusic", false) ?: false
        val devControlMode = intent?.getStringExtra("devControlMode")
        setContent { DemoApp(devAutoAttic = devAutoAttic, devAutoIntro = devAutoIntro, devAutoMusic = devAutoMusic, devControlMode = devControlMode) }
    }
}

@Composable
private fun DemoApp(devAutoAttic: Boolean = false, devAutoIntro: Boolean = false, devAutoMusic: Boolean = false, devControlMode: String? = null) {
    var screen by remember {
        mutableStateOf(
            when {
                devAutoAttic -> Screen.ATTIC
                devAutoIntro -> Screen.INTRO
                devAutoMusic -> Screen.MUSIC
                else -> Screen.LOGO
            }
        )
    }
    val context = LocalContext.current

    var controlMode by remember {
        mutableStateOf(if (devControlMode == "dpad") ControlMode.DPAD else ControlMode.STEERING)
    }

    // Developer Mode: off by default. Needed to start the (work-in-progress) intro/attic flow from the character screen.
    var developerMode by remember { mutableStateOf(devAutoAttic) }

    LaunchedEffect(screen) {
        if (screen == Screen.LOGO) {
            delay(1500)
            if (screen == Screen.LOGO) screen = Screen.TITLE
        }
    }

    // System back from the character screen returns to the main menu instead of closing the app.
    BackHandler(enabled = screen == Screen.SELECT) {
        screen = Screen.MENU
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = screen == Screen.LOGO || screen == Screen.TITLE) {
                screen = if (screen == Screen.LOGO) Screen.TITLE else Screen.MENU
            },
        contentAlignment = Alignment.Center
    ) {
        when (screen) {
            Screen.LOGO -> TitleText("AITDroid", scale = 5f)
            Screen.TITLE -> TitleText("AITDroid", scale = 5f, subtitle = "Tap to continue")
            Screen.MENU -> MenuScreen(
                onSelect = { index ->
                    when (index) {
                        0 -> screen = Screen.SELECT
                        1 -> Toast.makeText(context, "Not implemented yet", Toast.LENGTH_SHORT).show()
                        2 -> screen = Screen.OPTIONS
                        3 -> screen = Screen.MUSIC
                        4 -> (context as? Activity)?.finish()
                    }
                }
            )
            Screen.SELECT -> SelectScreen(
                onBack = { screen = Screen.MENU },
                // Carnby leads into the intro/attic flow, but only while Developer Mode is on (that gameplay is still
                // work in progress); otherwise a placeholder message is shown.
                onAttemptStart = { name ->
                    if (name == "Edward Carnby" && developerMode) {
                        screen = Screen.INTRO
                    } else {
                        Toast.makeText(context, "Would start as $name (demo only)", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            Screen.INTRO -> IntroScreen(onFinished = { screen = Screen.ATTIC })
            Screen.MUSIC -> MusicViewerScreen(onExit = { screen = Screen.MENU })
            Screen.ATTIC -> AtticScreen(onExit = { screen = Screen.MENU }, controlMode = controlMode, developerMode = developerMode)
            Screen.OPTIONS -> OptionsScreen(
                controlMode = controlMode,
                onControlModeChange = { controlMode = it },
                developerMode = developerMode,
                onDeveloperModeChange = { developerMode = it },
                onBack = { screen = Screen.MENU }
            )
        }
    }
}

@Composable
private fun TitleText(title: String, scale: Float, subtitle: String? = null) {
    Box(modifier = Modifier.fillMaxSize().background(SCREEN_BACKGROUND), contentAlignment = Alignment.Center) {
        BitmapText(text = title, scale = scale, color = TEXT_COLOR)
        if (subtitle != null) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                BitmapText(text = subtitle, scale = 1.6f, color = Color(0xFF9C8B6E))
            }
        }
    }
}

@Composable
private fun SelectScreen(onBack: () -> Unit, onAttemptStart: (String) -> Unit) {
    BackHandler { onBack() }
    Box(modifier = Modifier.fillMaxSize().background(SCREEN_BACKGROUND)) {
        Row(modifier = Modifier.fillMaxSize()) {
            HeroHalf("Emily Hartwood", Modifier.weight(1f)) { onAttemptStart("Emily Hartwood") }
            HeroHalf("Edward Carnby", Modifier.weight(1f)) { onAttemptStart("Edward Carnby") }
        }
        BackButton(onClick = onBack)
    }
}

@Composable
private fun HeroHalf(name: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(24.dp)
            .border(2.dp, Color(0xFF8A6D3B))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BitmapText(text = name, scale = 2.2f, color = TEXT_COLOR)
    }
}
