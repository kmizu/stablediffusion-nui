# Tag autocomplete #24: acceptance inventory

Issue #24 covers index 41. Behaviour reference: the MIT-licensed upstream README at
`4170882f90b47be130a0ff9314f663c230b9153d`; no upstream code or data is copied.
This table tracks the full documented scope, not an acceptance decision. Existing evidence
is in `tag-autocomplete-verification.md`; new work remains unverified until its evidence is recorded.

| Area | Existing implementation / evidence | Remaining work or limit |
|---|---|---|
| Tag CSV, aliases, rank, translations, extra file | Native completion and settings; real MainWindow checks | Large dictionaries not timed; full-prompt translation preview absent |
| Wildcard files | Nested `.txt` names, three Tab modes and line selection; next-folder real MainWindow and SD1.5/SDXL generation checked in `tag24-wildcard-modes-verification.md` | Always-full and selected-branch next-folder have native synthetic checks only; extracted-package GUI unverified |
| Extra networks | LoRA, LyCORIS spelling, embedding, sidecar activation text and previews; real LoRA checked | Actual embedding and LyCORIS UI/model use unverified; embedding v1/v2 filter and model-keyword lists absent; hypernetworks unavailable in host |
| Chants | JSON parsing, term matching and prompt insertion; numeric colour categories use the current main tag CSV's palette | Source-key collision for files sharing a stem is inherited from the format |
| Display and controls | Keyboard, mouse, configurable delay and source-keyed colours; delay checked in real MainWindow with both models in `tag24-completion-delay-verification.md`; optional tag wiki links | Configurable keys and per-model lists absent; extracted-package GUI and physical Japanese IME untested |
| Upstream sources and integrations | Local CSV files, Dynamic Prompts and model catalog supported | Bundled tag lists, Umi AI YAML, and several upstream integrations are not provided; data licence and compatibility need separate review |
| Validation and package | Existing Scala and real SD1.5/SDXL checks documented | Re-run relevant tests and real UI/generation after changes; verify extracted package and record exact evidence and remaining gaps |

Priority: preserve ordinary Enter / click insertion and finished `__name__` semantics, then add
safe wildcard line selection and unselected Tab path completion. Treat file access as bounded,
keep it off the JavaFX thread, preserve declared-root containment, avoid stale results after edits,
and preserve IME behavior. Category colours must not change tag text, sorting or insertion.
The issue stays open until all scope is reviewed and remaining adaptations are explicitly assessed.

## Planned wildcard line selection

- Accepting a file still inserts the normal `__name__` reference first. A second popup may offer
  the file's non-empty, non-comment lines; Escape or a failed/empty read leaves the reference intact.
- Read off the FX thread, use the same ordered wildcard roots as Dynamic Prompts, reject traversal
  and paths outside the real root, and limit bytes and displayed lines. Do not use the existing
  unbounded `DynamicPrompt.Wildcards.lines` directly for this UI operation.
- Bind results to the current text, caret, focus and a monotonically increasing revision. Editing,
  moving the caret, dismissing or choosing another completion invalidates outstanding reads.
- Selecting a line replaces only the inserted reference, preserving any configured comma and the
  surrounding prompt. Tests must cover stale callbacks, root escapes, missing/oversized files,
  nested paths, configured result limits, and keyboard / mouse / IME behavior.
