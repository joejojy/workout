# Privacy

Workout reads selected PDFs on your device. It stores the imported original PDF, extracted text, plan data, widget preferences and completion history in the app's storage. Date-migration backups can also be retained there. Only files you choose through Android's picker are imported; broad storage access is not requested.

PDF extraction and the bundled Latin-script OCR model run locally. The app has no accounts or cloud backend. INTERNET permission is explicitly removed from the merged application manifest, including permissions contributed by libraries. The OCR library contributes a network-state permission and an internal receiver permission; neither grants network transmission. Android application backup is disabled in the manifest.

Importing a replacement creates a draft. Activation replaces the stored active plan and original PDF. Discarding a draft removes that draft; completion history and date-migration backups may remain. Android's app storage controls can clear all local app data. Keep your own copy of your original PDF.

This describes the GitHub preview build, not a hosted service. If you share an issue, do not upload private workout plans or health information. Use a fictional example to reproduce problems. The repository's examples are fictional.
