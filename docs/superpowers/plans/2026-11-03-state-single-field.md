# State Manager: single-field restore

Issue #23 remains open: add an independently selectable saved field to the existing named-state and history inspector. A field restore uses the existing admission, resource preflight and transactional current-form apply, rechecking after confirmation. It must not mutate any other field or require unavailable resources from the rest of the snapshot. Mode, record identity and chosen key are captured before the confirmation; mode/selection changes during that dialog invalidate the action.

1. Extend the real MainWindow offline check to select and restore the saved prompt while a different missing checkpoint blocks full restoration; ensure width stays untouched. Run it before the implementation and confirm failure due to the missing control.
2. Add a field picker and restore action to `NamedGenerationStatesPane`, repopulating it whenever the selected snapshot changes. Share the existing safe restore pipeline, while retaining category-based restoration.
3. Run the real MainWindow check, focused named-state/history suites, and real model checks for both SD1.5 and SDXL. Inspect result images and screenshot; document bounded evidence and limits. Keep #23 open until the rest of its scope is implemented.
