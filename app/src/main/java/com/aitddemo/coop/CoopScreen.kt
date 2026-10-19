package com.aitddemo.coop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aitddemo.BackButton
import com.aitddemo.BitmapText
import com.aitddemo.GameCanvas
import com.aitddemo.SaveStore
import com.aitddemo.bitmapTextWidth

private val DIM = Color(0xFF9C8B6E)
private val BRIGHT = Color(0xFFF2E4B8)
private val WARN = Color(0xFFE0705A)
private val GOLD = Color(0xFF8A6D3B)

private class Row(val text: String, val color: Color = DIM, val onClick: (() -> Unit)? = null)

private fun shortUrl(url: String) = if (url.length > 34) url.take(33) + "…" else url

/**
 * The co-op lobby in the style of the main menu: connect to the server, create a session or join one (by the list or by its name), and in the session see the two players
 * with their heroes, choose how to start and say that you are ready. The game starts when the server has confirmed the shared start ([CoopModel.start]); [onStarted] is then called once.
 */
@Composable
fun CoopScreen(model: CoopModel, onBack: () -> Unit, onStarted: (StartInfo) -> Unit = {}) {
    BackHandler { onBack() }
    var editing by remember { mutableStateOf<String?>(null) }
    var joinName by remember { mutableStateOf("") }
    var pickingSave by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val start = model.start
    LaunchedEffect(start) { if (start != null) onStarted(start) }
    LaunchedEffect(Unit) { model.wake() } // a sleeping server starts now, while the player looks at the lobby

    // the host offers one of his saved games: the bytes of the file go to the server, which hands them to both phones at the start
    val offerSlot = { slot: Int ->
        val bytes = SaveStore.readBytes(context, slot)
        if (bytes != null) {
            val saveId = "slot${slot + 1}-${SaveStore.savedAt(context, slot) ?: 0}"
            model.uploadSave(saveId, SaveStore.label(context, slot), android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            model.selectSave(saveId)
        }
        pickingSave = false
    }
    val rows = if (pickingSave && model.me != null) slotRows(context, offerSlot) { pickingSave = false } else buildRows(model, joinName, onEdit = { editing = it }, onPickSave = { pickingSave = true })

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        GameCanvas(Modifier.fillMaxSize()) {
            // many lines (a lobby with a save point and notes) are set closer, so that all of them fit on the 200 lines of the picture
            val boxTop = if (rows.size > 9) 6 else 22
            val step = if (rows.size > 9) 12 else 14
            Box(Modifier.offset(x = gx(0), y = gy(boxTop)).size(gx(320), gy(14 + rows.size * step)).background(Color.Black.copy(alpha = 0.5f)).border(unit * 1.5f, GOLD))
            rows.forEachIndexed { i, row ->
                val y = boxTop + 8 + i * step
                val m = Modifier.offset(x = gx(10), y = gy(y)).size(gx(300), gy(step))
                Box(if (row.onClick != null) m.clickable { row.onClick.invoke() } else m) {
                    Box(Modifier.offset(x = unit * (150 - bitmapTextWidth(row.text).coerceAtMost(296) / 2))) {
                        BitmapText(text = row.text, scale = unit.value, color = row.color)
                    }
                }
            }
        }
        BackButton(onClick = onBack)

        editing?.let { which ->
            EditOverlay(
                title = if (which == "url") "Server address" else "Session name",
                initial = if (which == "url") model.url else joinName,
                onDone = { value ->
                    if (value != null) { if (which == "url") model.changeUrl(value) else joinName = value.trim() }
                    editing = null
                },
            )
        }
    }
}

private fun buildRows(model: CoopModel, joinName: String, onEdit: (String) -> Unit, onPickSave: () -> Unit): List<Row> {
    val rows = ArrayList<Row>()
    val me = model.me
    val session = model.session
    rows.add(Row("Co-op", BRIGHT))
    when {
        model.connection != CoopModel.Connection.CONNECTED -> {
            rows.add(Row("Server: " + shortUrl(model.url), DIM) { onEdit("url") })
            if (model.connection == CoopModel.Connection.CONNECTING) rows.add(Row("Connecting...", BRIGHT))
            else rows.add(Row("Connect", BRIGHT) { model.connect() })
            if (model.waking) rows.add(Row("Waking up the server (up to a minute)", DIM))
        }
        me == null || session == null -> {
            rows.add(Row("Connected", DIM))
            rows.add(Row("Create a session", BRIGHT) { model.createSession() })
            rows.add(Row("Show open sessions", BRIGHT) { model.listSessions() })
            for (s in model.sessions.take(4)) rows.add(Row("Join " + s.name, BRIGHT) { model.join(s.name) })
            rows.add(Row(if (joinName.isEmpty()) "Name of a session: (tap to enter)" else "Name of a session: $joinName", DIM) { onEdit("name") })
            if (joinName.isNotEmpty()) rows.add(Row("Join \"$joinName\"", BRIGHT) { model.join(joinName) })
            rows.add(Row("Disconnect", DIM) { model.disconnect() })
        }
        else -> lobbyRows(model, me, session, rows, onPickSave)
    }
    model.peerNote?.let { rows.add(Row(it, WARN)) }
    model.error?.let { rows.add(Row(it.take(52), WARN) { model.clearError() }) }
    return rows
}

/** The saved games of the app, to choose the one that the host offers. */
private fun slotRows(context: android.content.Context, onPick: (Int) -> Unit, onCancel: () -> Unit): List<Row> {
    val rows = ArrayList<Row>()
    rows.add(Row("Offer a save point", BRIGHT))
    for (slot in 0 until SaveStore.SLOTS) {
        val has = SaveStore.savedAt(context, slot) != null
        rows.add(Row(SaveStore.label(context, slot), if (has) BRIGHT else DIM) { if (has) onPick(slot) })
    }
    rows.add(Row("Cancel", DIM) { onCancel() })
    return rows
}

private fun lobbyRows(model: CoopModel, me: ServerEvent.Joined, session: SessionInfo, rows: ArrayList<Row>, onPickSave: () -> Unit) {
    val status = when (session.status) {
        "waiting" -> "waiting for the second player"
        "ready" -> "both players are here"
        "running" -> "the game is running"
        "interrupted" -> "interrupted: a player left"
        else -> session.status
    }
    rows.add(Row("Session " + session.name, BRIGHT))
    rows.add(Row(status, DIM))
    for (slot in 1..2) {
        val p = session.players.firstOrNull { it.slot == slot }
        val hero = if (slot == 1) Hero.CARNBY else Hero.EMILY
        val you = if (slot == me.slot) " (you)" else ""
        val text = if (p == null) "Player $slot: ${hero.title} - waiting" else "Player $slot: ${hero.title}$you - " + when {
            !p.connected -> "disconnected"
            session.status == "running" -> "playing"
            p.ready -> "ready"
            p.selectionMode == null -> "has not chosen"
            else -> "chose " + (if (p.selectionMode == "new") "a new game" else "a save point")
        }
        rows.add(Row(text, if (slot == me.slot) BRIGHT else DIM))
    }
    if (session.status == "waiting" || session.status == "ready") {
        val mine = session.players.firstOrNull { it.slot == me.slot }
        val chose = mine?.selectionMode == "new"
        rows.add(Row((if (chose) "[x] " else "[ ] ") + "Start a new game", BRIGHT) { model.selectNew() })
        // a save point: the host offers one of his own, the guest takes the one that is offered (both phones load the same bytes)
        val offered = session.offeredSave
        val choseSave = mine?.selectionMode == "save"
        if (me.slot == 1) {
            rows.add(Row((if (choseSave) "[x] " else "[ ] ") + (if (offered != null) "Start from: " + offered.label.take(30) else "Start from a save point"), BRIGHT) { if (offered != null && !choseSave) model.selectSave(offered.saveId) else onPickSave() })
            if (offered != null && choseSave) rows.add(Row("Offer another save point", DIM) { onPickSave() })
        } else if (offered != null) {
            rows.add(Row((if (choseSave) "[x] " else "[ ] ") + "Start from the host's save: " + offered.label.take(24), BRIGHT) { model.selectSave(offered.saveId) })
        } else {
            rows.add(Row("(the host has not offered a save point)", DIM))
        }
        session.mismatch?.let { rows.add(Row(it, WARN)) }
        if (session.status == "ready") {
            val ready = mine?.ready == true
            rows.add(
                Row(
                    when {
                        ready -> "Waiting for the other player (tap to cancel)"
                        mine?.selectionMode == null -> "Choose how to start first"
                        else -> "I am ready"
                    },
                    BRIGHT,
                ) { if (mine?.selectionMode != null) model.setReady(!ready) },
            )
        }
    } else if (session.status == "interrupted") {
        rows.add(Row("The game cannot go on. Leave and start again.", WARN))
    }
    rows.add(Row("Leave the session", DIM) { model.leave() })
}

@Composable
private fun EditOverlay(title: String, initial: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler { onDone(null) }
    // at the top of the screen, so that the keyboard (which covers the lower half in landscape) leaves the field and the OK button in sight
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(enabled = false) {}, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.padding(top = 8.dp).fillMaxWidth(0.8f).border(2.dp, GOLD).padding(12.dp)) {
            BitmapText(text = title, scale = 2f, color = DIM)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = BRIGHT, fontSize = 22.sp),
                    cursorBrush = SolidColor(BRIGHT),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp).focusRequester(focus),
                )
                Box(Modifier.clickable { onDone(text) }.padding(horizontal = 12.dp, vertical = 6.dp)) { BitmapText(text = "OK", scale = 2.4f, color = BRIGHT) }
            }
        }
    }
}
