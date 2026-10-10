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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aitddemo.original.GameFiles
import kotlinx.coroutines.delay

private enum class Screen { LOGO_STATIC, LOGO_3D, TITLE, MENU, SELECT, LOAD, MODELS, MUSIC, INTRO, ATTIC, OPTIONS }

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
        // `--es devItems 12,15` puts those world objects into the inventory when the attic starts (for testing)
        val devItems = intent?.getStringExtra("devItems")?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        // `--es devPos 7000,4000,0` places the player (x, z, heading) when the attic starts (for testing)
        val devPos = intent?.getStringExtra("devPos")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }
        setContent { StartGate(devAutoAttic, devAutoIntro, devAutoMusic, devControlMode, devItems, devPos) }
    }
}

/** The start sequence, the menus and the font need files from the original game: read them first, show the error screen when they are missing. */
@Composable
private fun StartGate(devAutoAttic: Boolean, devAutoIntro: Boolean, devAutoMusic: Boolean, devControlMode: String?, devItems: List<Int>, devPos: List<Int>?) {
    val context = LocalContext.current
    val loader = rememberOriginalFiles(missing = { StartOriginalReader.missingFiles(it) }, cacheKey = "start") { files: GameFiles -> StartOriginalReader.read(files) }
    when (val s = loader.state) {
        is OriginalState.Loading -> Box(Modifier.fillMaxSize().background(Color.Black))
        is OriginalState.Failed -> OriginalFilesErrorScreen(s.message, loader.chooseFolder, onExit = { (context as? Activity)?.finish() })
        is OriginalState.Ready -> {
            if (StartAssets.original !== s.data) StartAssets.install(s.data)
            if (StartAssets.original != null) DemoApp(devAutoAttic, devAutoIntro, devAutoMusic, devControlMode, devItems, devPos)
        }
    }
}

@Composable
private fun DemoApp(devAutoAttic: Boolean, devAutoIntro: Boolean, devAutoMusic: Boolean, devControlMode: String?, devItems: List<Int>, devPos: List<Int>?) {
    var screen by remember {
        mutableStateOf(
            when {
                devAutoAttic -> Screen.ATTIC
                devAutoIntro -> Screen.INTRO
                devAutoMusic -> Screen.MUSIC
                else -> Screen.LOGO_STATIC
            }
        )
    }
    val context = LocalContext.current

    var controlMode by remember {
        mutableStateOf(if (devControlMode == "dpad") ControlMode.DPAD else ControlMode.STEERING)
    }

    var loadSlot by remember { mutableStateOf<Int?>(null) } // a saved game to continue from when the attic opens
    // Developer Mode: off by default. Shows the world position and camera in the attic.
    var developerMode by remember { mutableStateOf(devAutoAttic) }

    fun advance() {
        screen = when (screen) {
            Screen.LOGO_STATIC -> Screen.LOGO_3D
            Screen.LOGO_3D -> Screen.TITLE
            Screen.TITLE -> Screen.MENU
            else -> screen
        }
    }

    // boot sequence of the original: Infogrames logo, the spinning Tatou with thunder, then the title picture
    LaunchedEffect(screen) {
        when (screen) {
            Screen.LOGO_STATIC -> { delay(1800); advance() }
            Screen.LOGO_3D -> { StartAssets.playThunder(); delay(6000); advance() }
            else -> {}
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
            .clickable(enabled = screen == Screen.LOGO_STATIC || screen == Screen.LOGO_3D || screen == Screen.TITLE) { advance() },
        contentAlignment = Alignment.Center
    ) {
        when (screen) {
            Screen.LOGO_STATIC -> StartPictureImage(StartPicture.LOGO)
            Screen.LOGO_3D -> TatouSpinner(modifier = Modifier.fillMaxSize())
            Screen.TITLE -> StartPictureImage(StartPicture.TITLE)
            Screen.MENU -> MenuScreen(
                onSelect = { index ->
                    when (index) {
                        0 -> screen = Screen.SELECT
                        1 -> if (SaveStore.hasAny(context)) screen = Screen.LOAD else Toast.makeText(context, "There is no saved game yet", Toast.LENGTH_SHORT).show()
                        2 -> screen = Screen.OPTIONS
                        3 -> screen = Screen.MODELS
                        4 -> screen = Screen.MUSIC
                        5 -> (context as? Activity)?.finish()
                    }
                }
            )
            Screen.SELECT -> SelectScreen(
                onBack = { screen = Screen.MENU },
                // Carnby starts the intro and the attic; Emily's game is not available yet.
                onAttemptStart = { name ->
                    if (name == "Edward Carnby") {
                        loadSlot = null
                        screen = Screen.INTRO
                    } else {
                        Toast.makeText(context, "Would start as $name (demo only)", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            Screen.LOAD -> LoadScreen(
                onBack = { screen = Screen.MENU },
                onPick = { slot -> loadSlot = slot; screen = Screen.ATTIC },
            )
            Screen.INTRO -> IntroScreen(onFinished = { loadSlot = null; screen = Screen.ATTIC })
            Screen.MODELS -> ModelsScreen(onExit = { screen = Screen.MENU })
            Screen.MUSIC -> MusicViewerScreen(onExit = { screen = Screen.MENU })
            Screen.ATTIC -> AtticScreen(onExit = { screen = Screen.MENU }, controlMode = controlMode, developerMode = developerMode, devItems = devItems, devPos = devPos, loadSlot = loadSlot)
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
private fun SelectScreen(onBack: () -> Unit, onAttemptStart: (String) -> Unit) {
    // One picture holds both characters: Emily at her desk on the left, Carnby at the bar on the right. After the choice the screen splits:
    // the character's body (rotating) on the left, the first-person letter on the right.
    var selected by remember { mutableStateOf<Int?>(null) }

    // the original plays a page-turning sound when the text appears
    LaunchedEffect(selected) {
        if (selected != null) StartAssets.playPageTurn()
    }

    BackHandler(enabled = selected != null) { selected = null }
    BackHandler(enabled = selected == null) { onBack() }

    val picked = selected
    if (picked == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            StartPictureImage(StartPicture.SELECT)
            Row(modifier = Modifier.fillMaxSize()) {
                HeroHalf(onClick = { selected = 0 })
                HeroHalf(onClick = { selected = 1 })
            }
            BackButton(onClick = onBack)
        }
    } else {
        CharacterResultScreen(
            isEmily = picked == 0,
            onBack = { selected = null },
            onStartIntro = { onAttemptStart(if (picked == 0) "Emily Hartwood" else "Edward Carnby") }
        )
    }
}

@Composable
private fun RowScope.HeroHalf(onClick: () -> Unit) {
    Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick))
}

@Composable
private fun CharacterResultScreen(isEmily: Boolean, onBack: () -> Unit, onStartIntro: () -> Unit) {
    val original = StartAssets.original ?: return
    val introText = if (isEmily) original.storyEmily else original.storyCarnby
    val loader = rememberOriginalFiles(missing = { ModelLibraryReader.missingFiles(it) }, cacheKey = "models") { files: GameFiles -> ModelLibraryReader.read(files) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // left: the character's body, drawn from LISTBODY.PAK / LISTBOD2.PAK (Carnby as he looks with the lamp, body 11); tap to start
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black)
                    .border(2.dp, Color(0xFF8A6D3B))
                    .clickable(onClick = onStartIntro)
            ) {
                val library = (loader.state as? OriginalState.Ready)?.data
                val entry = library?.entries?.firstOrNull { if (isEmily) it.pak == 1 && it.raw == 13 else it.pak == 0 && it.raw == 12 }
                if (library != null && entry != null) {
                    val mesh = remember(entry) { library.mesh(entry) }
                    ModelSpinner(vertices = mesh.vertices, triangles = mesh.triangles, rotationSpeed = PREVIEW_ROTATION_SPEED, modifier = Modifier.fillMaxSize())
                }
            }
            // right: the character's half of the picture (not stretched), with the letter on top
            Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onStartIntro)) {
                CharacterPictureHalf(original.select.let { StartAssets.picture(it) }, showLeftHalf = isEmily, modifier = Modifier.fillMaxSize())
                IntroText(introText)
            }
        }
        BackButton(onClick = onBack)
    }
}

@Composable
private fun CharacterPictureHalf(picture: ImageBitmap, showLeftHalf: Boolean, modifier: Modifier = Modifier) {
    val halfWidth = picture.width / 2
    val painter = remember(showLeftHalf) {
        BitmapPainter(
            image = picture,
            srcOffset = IntOffset(if (showLeftHalf) 0 else halfWidth, 0),
            srcSize = IntSize(halfWidth, picture.height)
        )
    }
    Image(painter = painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.background(Color.Black))
}

@Composable
private fun IntroText(text: String) {
    // leave room at the bottom so the text does not overlap the "< Back" button; the block scrolls
    BitmapParagraphs(
        text = text,
        scale = 1.6f,
        color = Color(0xFFF0E6C8),
        maxWidthUnits = 200,
        lineSpacing = 3f,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(start = 34.dp, top = 14.dp, end = 8.dp, bottom = 50.dp)
            .verticalScroll(rememberScrollState())
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(10.dp)
    )
}

/** The list of saved games of the main menu. */
@Composable
private fun LoadScreen(onBack: () -> Unit, onPick: (Int) -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color(0xFF14080A))) {
        StartPictureImage(StartPicture.MENU)
        GameCanvas(Modifier.fillMaxSize()) {
            Box(Modifier.offset(x = gx(0), y = gy(60)).size(gx(320), gy(112)).background(Color.Black.copy(alpha = 0.5f)).border(unit * 1.5f, Color(0xFF8A6D3B)))
            for (slot in 0 until SaveStore.SLOTS) {
                val label = SaveStore.label(context, slot)
                val has = SaveStore.savedAt(context, slot) != null
                Box(Modifier.offset(x = unit * (160 - bitmapTextWidth(label) / 2), y = gy(84 + slot * 20)).clickable(enabled = has) { onPick(slot) }) {
                    BitmapText(text = label, scale = unit.value, color = if (has) Color(0xFFF2E4B8) else Color(0xFF9C8B6E))
                }
            }
        }
        BackButton(onClick = onBack)
    }
}
