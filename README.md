# AITDroid

AITDroid is an Android port / engine re-implementation of *Alone in the Dark* (1992). It does not ship any game content:
the app reads **your own, legally owned copy** of the original game files at runtime, using the Android folder picker.

**No original game data is included in this repository** (no models, bitmaps, fonts, sounds, music, palettes or texts).
Only parser and engine code is provided. This project is not affiliated with, endorsed by, or connected to Infogrames
or Atari. *Alone in the Dark* and all related assets remain the property of their respective owners.

## Status

Work in progress. The game logic of the original is re-implemented: the life scripts (LISTLIFE.PAK) run on the world objects (OBJETS.ITD), the engine moves, animates
and collides the actors and draws them through the original cameras over the original pictures.

- Floors: attic, floor 1, 2 and 3 (stages 0 to 3) are playable; floors 4 to 6 (stages 4 to 6) run but are not yet reviewed in detail
- Rooms and cameras (ETAGEnn.PAK, CAMERAnn.PAK): camera zones and the original camera choice, background masks (each room a camera sees has its own), objects of neighbouring rooms
- Intro: played by the game engine from the original scripts of stage 7 (car, frog, driver, his walk through the house up to the attic; the script's GAME_OVER ends it)
- Characters: Edward Carnby and Emily Hartwood (her own bodies and animations, LISTBOD2.PAK and LISTANI2.PAK)
- Music (AdLib tracks through a software OPL2 emulation) and sound with the priorities of the original (PRIORITY.ITD)
- Saved games, inventory, fights, items, books
- Main menu with a floor selection (floor, room, items, waiting times) for testing
- Menus use plain system fonts and solid colours (the original art is not embedded)

Known gaps: floors 4 to 6 are not reviewed in detail; the frame rate of effects is an estimate; the pitch of sounds is unknown (the drivers are external programs).

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
`--es devControlMode dpad`, `--es devVars 990=<stage*100+room>,991=1` (start in a room, skip the waiting times).
