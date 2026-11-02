# Selected ControlNet preprocessor batch: partial #18

The Extras card can choose multiple image files, show and remove list entries, then process them in
the order returned by the OS chooser using the existing output-folder field. The folder batch and
selected-file batch share processing, error reporting, cancellation and protection-session checks.
Repeated names are suffixed and not overwritten. Selected files may be saved beside the inputs;
the folder batch still rejects the input directory as its output directory. The upstream extension
is unlicensed at pinned `857262300ae8b849cfa0ed2c2802e93bf160ebd0`; this is an independent native
implementation of the README's Extras preprocessing and file-list batch behaviour, not copied code.

- Focused tests (`outputs/codex-cn-warning-path-green.log`) cover ordering, individual failures,
  cancellation, session races, no overwrite, injected transfer failure and cleanup failure. A
  controlled fake-worker MainWindow test changes the protection session while processing; no output
  is published, and a subsequent run with an actual scrambled input produces an image readable only
  with its password (`outputs/codex-cn-final-focused.log`). The OS file chooser was not operated: tests inject paths into
  the actual list before using the real button.
- After rebasing onto `85bf927`, ten focused tests, the protected-input MainWindow fake-worker
  check and `ui/assembly` passed (`outputs/codex-cn-selected-rebase.log`). JAR SHA-256:
  `36dcbd696365bcbf7bd9d56359948f78bd236d824a1d382c0844632e46cc6271`.
- The real MainWindow with the real engine processed existing SD1.5 and SDXL generated images using
  canny (`outputs/codex-cn-selected-real2.log` and `outputs/cn-selected-real2/report.json`). A selected
  list with two valid images and one corrupt image produced two PNGs and reported one failure;
  folder rerun kept existing results, cancellation stopped after 9 of 60 files and a later single run
  succeeded. Canny output was inspected: the SD1.5 person has clear outlines, while the SDXL input's
  black/red mesh becomes dense edges. The Japanese UI was inspected in the fake-worker run.
- Before publishing a batch item, the worker-produced staging PNG is copied to a temporary file in
  the output folder and renamed to a non-existing final name while holding the session monitor and
  checking the captured revision. If the session changed before publication, only the job's staging
  file is cleaned up. A protected session produces an already-scrambled staging PNG; in an unprotected
  session the output-folder temporary file is plaintext and inherits the folder's normal permissions,
  so other local processes may read it during copying. A slow output filesystem can delay session
  lock/unlock while the copy runs. The tests use actual files but do not establish cross-volume network
  filesystem semantics. A cleanup failure is reported separately and the final status retains its count
  and the first path/reason only; other details may only be in temporary notifications.
- The `controlnet_aux==0.0.10` package is Apache-2.0, while the `lllyasviel/Annotators` model card
  says `other` and gives no terms. A CLI dry-run reported `mlsd_large_512_fp32.pth` 6.3 MB and
  `ControlNetHED.pth` 29.4 MB. The package and weights were not installed or downloaded during this
  work, and their model-specific terms and detector operation remain unverified. The extracted package,
  manual native file chooser, protected input with the real engine, and cleanup warning display under injected
  GUI cleanup failure remain unverified. Issue #18 remains open.
