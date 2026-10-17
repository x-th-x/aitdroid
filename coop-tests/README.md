# Co-op tests that run on a plain JVM

The Android project has no unit test setup (no JUnit), so these small programs run on a plain JVM with the Kotlin compiler. They need no device.

| File | What it tests | Needs |
|---|---|---|
| `SnapshotTests.kt` | the snapshot buffer and the smoother (`RemoteSnapshots.kt`): smoothness under jitter, a stall and its catch-up, reordering, headings, rooms | `app/src/main/java/com/aitddemo/coop/RemoteSnapshots.kt` |
| `SenderTests.kt` | the sending rule of the local hero (`LocalStateSender.kt`): rate, nothing while standing still, the counter | `app/src/main/java/com/aitddemo/coop/LocalStateSender.kt` |
| `PeaceTest.kt` | (outdated: the attic is no longer peaceful) on the game engine (PC test harness of the project author, not in this repository): the co-op attic was peaceful for six minutes and the stairs are closed | the harness with the original game files |

How the first two were run (compile the test and the one source file with the Kotlin compiler, then run the class; `SnapshotTests.kt` has `main` as `TgSnapKt`, `SenderTests.kt` as `TgSenderKt`
when compiled under their original names): `kotlinc SnapshotTests.kt RemoteSnapshots.kt -d out && java -cp out SnapshotTestsKt`.
The last run printed `ALL OK` for both (15 and 9 checks).
