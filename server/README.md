# AITDroid co-op server (prototype)

A small WebSocket server for the co-op prototype of the Android port: two players, one session, **Carnby** (player 1) and **Emily** (player 2) in the same room.
It runs the rules of the session (who is in it, which hero belongs to whom, how the game is started, what happens at a disconnect) and relays the state of
each hero to the other player. It does **not** simulate the game: every phone moves its own hero and the server passes the result on. It is not an anti-cheat.

Node.js 20 or newer, TypeScript, the `ws` library. Status: written and tested locally (see "Tests"); **not deployed to Render**.

## Layout

| File | What it is |
|---|---|
| `src/protocol.ts` | the message types and the validation of everything a client sends |
| `src/sessions.ts` | the session logic without any sockets (testable) |
| `src/server.ts` | HTTP (`/health`) + WebSocket handling, rate limit, heartbeat |
| `src/config.ts` | settings from environment variables |
| `src/index.ts` | start up and shut down (SIGTERM) |
| `test/` | automated tests (`node:test`) |
| `../render.yaml` | the Render blueprint |

## Run it

```
cd server
npm install
npm run dev        # builds and starts on port 8080 (or PORT)
npm test           # builds and runs the 39 automated tests
```

### Which URL does the Android app use?

* **Production (Render):** `wss://<service-name>.onrender.com` — secure WebSocket on the standard HTTPS port (no port number). Render terminates TLS, the server itself speaks plain WebSocket behind it.
* **Local development:** `ws://<IP of your computer in the Wi-Fi>:8080`, for example `ws://192.168.1.20:8080`. Both phones and the computer must be in the same network, and the firewall of the
  computer must let port 8080 in.
  * `localhost` / `127.0.0.1` is **the phone itself**, not your computer. It does not work from a real device.
  * The Android emulator reaches the computer at `ws://10.0.2.2:8080`.
  * Plain `ws://` is only for local tests (the app has to allow cleartext traffic for it); the production URL is always `wss://`.

Check that the server is up: open `http://<host>:8080/health` (or `https://<service>.onrender.com/health`); it answers `{"status":"ok",...}`.

### Settings (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | port to listen on (Render sets it) |
| `HOST` | 0.0.0.0 | address to listen on |
| `ALLOWED_AREAS` | `0/0` | places a client may report, `stage/room` list; `0/0` is the attic. The first entry is the start area |
| `MAX_SPEED` | 20000 | fastest plausible movement in engine units per second (a sanity check, see below) |
| `SILENT_AFTER_MS` | 5000 | a running player who sends no state for this long is reported as silent |
| `IDLE_SESSION_MS` | 1800000 | waiting/ready sessions nobody touches are removed after this |
| `INTERRUPTED_KEEP_MS` | 120000 | an interrupted session is removed after this |
| `MAX_MESSAGES_PER_SECOND` | 60 | per connection; triple that and the connection is cut |
| `MAX_SAVE_BASE64` | 400000 | largest save point (base64 characters) |

## Sessions

* A player creates a session and gets a short name like `Raven-27` (a word and two digits, unique among the active sessions) and a unique internal id (UUID). The name is for typing, not a secret.
* The creator is **slot 1 = Carnby**. A session has exactly **two** slots. The second player joins by the name (case and spaces do not matter) and is **slot 2 = Emily**. The server assigns the heroes;
  the client cannot choose, and cannot report the state of the other hero (`wrong_hero`).
* Status: `waiting` (one player) → `ready` (two players, choosing the start) → `running` → `interrupted`. Only `waiting` sessions can be joined and are listed.
  A running or interrupted session never takes a new player.
* One connection is in at most one session.

### Starting a game

Nothing starts by itself. Each player sends `select_start`; if the two choices differ, `session.mismatch` says why and `set_ready` is refused. A changed choice cancels everybody's readiness.
When both are ready and the choices match, the server sends `start` to both with the same content.

* **New game** (`mode: "new"`): both clients start the attic from the beginning.
* **Shared save point** (`mode: "save"`): the saves of the app are local to each phone, so two phones could never load "the same" one by themselves. The host (slot 1) sends the bytes of the save
  (`upload_save`, base64, with an id); the guest picks that offered id; the `start` message carries the bytes to both, and both load these bytes. A new upload cancels all save choices.

### Leaving and failing

| Case | What the server does |
|---|---|
| host leaves before the start | the session is closed, the guest gets `closed` |
| guest leaves before the start | the session goes back to `waiting`, the choices are reset, somebody else can join |
| a player disconnects while the game runs | the session is `interrupted`, the other player gets `peer` (`disconnected`) and `session`; nobody can join; states are refused; it is removed when both are gone or after `INTERRUPTED_KEEP_MS` |
| a player sends no states | after `SILENT_AFTER_MS` the other gets `peer` (`silent`), and `peer` (`active`) when states come back |
| third connection joins | `session_full`; a running session: `session_not_joinable` |
| server restarts | all sessions are gone (nothing is stored); old session names give `unknown_session`. A game is never "restored" |
| malformed, unknown or binary frames | an `error` message; the connection stays |
| too many messages | `rate_limited`; far too many: the connection is cut |

## Protocol

Text frames with one JSON object each, `{"type": ..., ...}`. After connecting the server sends `welcome`.

### Client → server

```json
{"type":"list_sessions"}
{"type":"create_session"}
{"type":"join_session","name":"Raven-27"}
{"type":"leave_session"}
{"type":"select_start","mode":"new"}
{"type":"select_start","mode":"save","saveId":"slot1-1a2b3c"}
{"type":"upload_save","saveId":"slot1-1a2b3c","label":"Slot 1 - 10.10.2026 14:23","data":"<base64>"}
{"type":"set_ready","ready":true}
{"type":"state","seq":42,"t":123456,"hero":"carnby","stage":0,"room":0,"x":3231,"y":0,"z":-1548,"beta":512,"anim":254}
{"type":"ping","t":123456}
```

`state` carries the engine's own values for the hero the sender controls: **room-relative coordinates** of the room `stage/room` (the game's `Actor.renderX/Y/Z`, 1000 units ≈ 1 m),
`beta` the heading in 1/1024 turns, `anim` the animation number of the hero's animation list (-1 = none), `seq` a counter that grows with every state, `t` the sender's own clock in milliseconds
(only differences are used).

### Server → client

```json
{"type":"welcome","protocol":1,"serverTime":1760000000000,"areas":[{"stage":0,"room":0}]}
{"type":"sessions","sessions":[{"name":"Raven-27","status":"waiting","players":1}]}
{"type":"joined","sessionId":"<uuid>","name":"Raven-27","slot":2,"hero":"emily"}
{"type":"session","sessionId":"<uuid>","name":"Raven-27","status":"ready","players":[{"slot":1,"hero":"carnby","connected":true,"selection":{"mode":"new"},"ready":false},
  {"slot":2,"hero":"emily","connected":true,"selection":null,"ready":false}],"selectionsMatch":false,"mismatch":null,"offeredSave":null}
{"type":"start","sessionId":"<uuid>","mode":"new","saveId":null,"saveData":null,"area":{"stage":0,"room":0},"serverTime":1760000000000}
{"type":"remote_state","seq":42,"t":123456,"serverTime":1760000000123,"hero":"carnby","stage":0,"room":0,"x":3231,"y":0,"z":-1548,"beta":512,"anim":254}
{"type":"peer","slot":1,"hero":"carnby","state":"disconnected"}
{"type":"closed","reason":"the host left the session"}
{"type":"error","code":"wrong_hero","message":"you control emily, not carnby","requestType":"state"}
{"type":"pong","t":123456,"serverTime":1760000000123}
```

Error codes: `bad_message`, `unknown_type`, `rate_limited`, `not_in_session`, `already_in_session`, `unknown_session`, `session_full`, `session_not_joinable`, `wrong_state`, `not_host`,
`wrong_hero`, `area_not_allowed`, `implausible_state`, `save_too_large`, `save_missing`, `selection_mismatch`.

### What the server checks in a `state`

The session must be running; the hero must be the sender's own; stage/room must be in `ALLOWED_AREAS` (a client cannot send an arbitrary room); every field must be an integer in range; a `seq` that
is not newer than the last one is dropped silently (old or repeated); the position must not have moved more than `MAX_SPEED` × the time since the last state (+3000 units of slack). This is
a plausibility check against garbage and mistakes, **not** a protection against a determined cheater.

### How the client is meant to interpolate (implemented in the Android layer, stage 4)

States arrive with `serverTime` (the server's clock at receipt) and the sender's `seq`. The client keeps a small buffer of them, ordered by `seq`, and shows the remote hero about
120 ms behind the newest state (configurable): it looks for the two states around `now − delay` and blends position and heading between them, so network jitter is absorbed by the buffer instead
of showing as jumps. If no newer state arrives, the hero stays at the last state (a short hold, no unlimited extrapolation); when states come back, the shown position moves to them smoothly.

## Tests

Automated (`npm test`, 39 tests, all passing on the last run): validation of every message type and of malformed input; session creation, naming, joining, the two slots, a third player rejected,
running sessions not joinable; the start agreement (mismatched choices block, matching choices start, shared save point bytes reach both); state relay in both directions, wrong hero refused,
unsupported room refused, jumps refused, stale states dropped, silence detection; every disconnect case in the table above including the server restart; and a real WebSocket run with two
clients (create, join, third rejected, start, simultaneous states, impersonation refused, disconnect), malformed/binary frames and the rate limit.

Needs two real Android devices (or emulators) and is **not** covered by the automated tests: the lobby screens, the shared start of the game itself, how smooth the remote hero looks, and the
behaviour on a real mobile network.

## Deploy to Render (not done yet)

1. Push the branch with `server/` and `render.yaml` to GitHub (a private repo works: install Render's GitHub app for it).
2. In the Render dashboard: **New → Blueprint**, pick the repository and the branch; Render reads `render.yaml` (a web service `aitdroid-coop-server`, root directory `server`,
   build `npm ci && npm run build`, start `npm start`, health check `/health`). Alternatively create a **Web Service** by hand with the same values.
3. Wait for the first deploy to finish; the URL is `https://aitdroid-coop-server.onrender.com` (or the name Render gives).
4. Check `https://<service>.onrender.com/health` in a browser.
5. In the app use `wss://<service>.onrender.com` as the server address.
6. Note on the free plan: the service sleeps after about 15 minutes without traffic; the first connection after that waits roughly a minute while it wakes up. The sessions are in memory, so a
   restart or a new deploy ends all sessions (on purpose).

## Known limitations

* Only the attic (`0/0`). Changing the room is not synchronized; the prototype does not support it.
* The server relays; it does not simulate. Clients are trusted for their own hero's movement, within the plausibility checks above.
* No reconnection: a lost connection in a running game interrupts it for good.
* Sessions live in memory only.
* No accounts, no matchmaking, no TLS inside the process (Render provides it).

## Not implemented (out of scope for the prototype)

Combat, monsters, items, doors, puzzles, story events, joining a running game, lag compensation, accounts.
