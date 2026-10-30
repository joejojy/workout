# Architecture

- `Plan.kt`: shared sessions, schedule bounds, date-specific/recurring lookup and JSON persistence.
- `CalendarMapping.kt`: explicit dates and inferred monthly/undated calendars.
- `PdfImportReader.kt`: per-page PDFBox extraction and bounded, on-device OCR. Low-text pages use OCR; an unrecognized text schedule can trigger a whole-document OCR retry.
- `PlanParser.kt`: detailed weekday sections and weekly running tables.
- `FlexiblePlanParser.kt`: dated weekly schedules, wrapped weekday cells and reusable strength libraries. Ambiguous activity types are preserved as Other. Optional notes are not silently converted into dated sessions.
- `PlanStore.kt`: draft/active original PDFs, text, plans, completion history and migration backups. Imports run on the UI's worker; the active plan is not changed until activation.
- `MainActivity.kt` and `PdfReviewActivity.kt`: local import, source comparison, corrections, daily/weekly views and completion.
- `WidgetLayout.kt`: native TextView measurements, density preference, whole rows, scroll decisions, upcoming context and content-sized black card in a transparent host.
- `WorkoutWidget.kt`: Android RemoteViews delivery, resize handling, refreshes and midnight scheduling. Android 12+ uses collection items; older supported versions use the protected rows service.
- `WidgetScreens.kt`: size tester, sample-day shortcuts and per-widget display settings.

There is no web service or AI API key in the app. Training generation occurs outside Workout; the app imports the user's existing document.
