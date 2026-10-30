# Public preview validation

## v0.5.2

The public source uses fictional examples. Personal workout PDFs and private test captures are excluded.

- Java 17 / Gradle 8.9 / Android SDK 35: `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` passed.
- Five public automated tests passed. One test checks all seven sample days at five widget sizes in three display modes: 105 combinations.
- Checks cover running/strength/rest import, a dated cross-month table, draft preservation after failure, per-session completion, unknown activities, duplicate rest headings, retained rows, card bounds and transparent unused widget slots.
- README images are rendered from the fictional sample using the real native widget layouts in Robolectric; they are not photographs of physical devices.

The earlier private-fixture validation covered four monthly plans: 1,995 date/size/mode/font combinations and 100 native emulator widget cases. Those personal PDFs are intentionally not redistributed, so that exact historical matrix is not reproducible from this public checkout. The current public test suite is the one listed above.

This is a debug-signed development preview, not a Play Store release. PDF interpretation and OCR can still require corrections. Physical-device launcher behavior, battery restrictions and arbitrary PDF layouts are not guaranteed by these checks.

- Android API 37 emulator: the selectable-text and image-only fictional samples both imported all seven sessions. The native OCR test passed with INTERNET absent from the application manifest; the pre-existing draft and active plan were preserved.
