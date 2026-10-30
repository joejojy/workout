# Contributing

Open an issue with your Android version, launcher, widget dimensions/display mode and steps to reproduce. For import bugs, describe the layout and attach a fictional or redacted example you have permission to share. Avoid health data and personal plans.

Fork the repository, create a branch and open a pull request. Run `./gradlew testDebugUnitTest lintDebug assembleDebug` (or `gradlew.bat` on Windows). For OCR changes, also run `connectedDebugAndroidTest` on Android. Add a regression that checks the resulting workout information, not just whether import returns successfully.

Keep app and widget text readable at small sizes and large fonts. Do not guess unclear reps, distances or dates. Preserve source text and the original PDF for review. Avoid introducing network processing without a separate product/privacy discussion.

Never commit local.properties, signing keys, passwords, personal PDFs, captured app data or private test fixtures. PDF files are ignored except for the explicitly allowed fictional examples.

An optional GitHub Actions configuration is provided in `docs/android-ci-template.yml`. To enable it, copy it to `.github/workflows/android.yml` using GitHub or credentials with workflow permission. It is a template, not an enabled CI workflow in this repository.
