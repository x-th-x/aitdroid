# AITDroid

AITDroid is an Android port / engine re-implementation of *Alone in the Dark* (1992). It does not ship any game content:
the app reads **your own, legally owned copy** of the original game files at runtime, using the Android folder picker.

**No original game data is included in this repository** (no models, bitmaps, fonts, sounds, music, palettes or texts).
Only parser and engine code is provided. This project is not affiliated with, endorsed by, or connected to Infogrames
or Atari. *Alone in the Dark* and all related assets remain the property of their respective owners.

## Status

Work in progress.

- Attic scene (stage 0, room 0): camera pictures, furniture and player body read from the original files, fixed cameras with zone based camera switching, background masks, wall collision
- Intro cutscene (stage 7): parsed from the original files, played with the original timing
- Music viewer: plays the original AdLib tracks through a software OPL2 emulation
- Player actor: original animations, tank style controls and root motion (stick or D-pad), footstep samples
- Life script interpreter: early work in progress (not yet driving the game)
- Menus use plain system fonts and solid colours (the original art is not embedded)

## Game files

Copy the original game files (for example `ITD_RESS.PAK`, `ETAGE00.PAK`, `CAMERA00.PAK`, `LISTBODY.PAK`, `LISTANIM.PAK`,
`LISTMUS.PAK`, `LISTSAMP.PAK`, `OBJETS.ITD`, ... from the CD/DOS version) to your phone's storage. When a screen needs
them, AITDroid asks you to pick the folder that contains them. If files are missing, the screen lists which ones.

## Build

Requirements: Android Studio (or JDK 17+ and the Android SDK, platform 34), Gradle wrapper included.

1. Create `local.properties` in the project root with `sdk.dir=<path to your Android SDK>` (the file is gitignored).
2. Run `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
3. Install `app/build/outputs/apk/debug/app-debug.apk` on a device (minSdk 24).

Developer shortcuts (adb): `--ez devAutoAttic true`, `--ez devAutoIntro true`, `--ez devAutoMusic true`,
`--es devControlMode dpad`.
