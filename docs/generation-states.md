# Named generation states and generation history

Use **Named generation states…** in a generation tab to keep a complete supported
settings snapshot for that mode. This is separate from **Settings presets**, whose
tracked fields can selectively overlay a form. A named state restores its saved
settings together, including inactive script and extension controls.

## Save, restore and delete

1. Open txt2img, img2img or Inpaint and edit its settings. Click **Named generation
   states…**; the window heading identifies the mode being saved.
2. Enter a name and click **Save named state**. Saving an existing name asks before
   replacing it. Names are isolated by mode.
3. Select a saved entry to inspect its fields and any unavailable resources.
4. Click **Restore state** and confirm. Shared checkpoint, VAE and clip skip also
   change. Restoration does not start generation.
5. Select an entry and use **Delete** to remove it from that mode's list and disk.
   Deletion asks for confirmation.

The library lives in `<app home>/config/generation-states`, separate from existing
preset collections. Reopening the application reads that library. **Reload** reads
external changes or retries after a storage error. **Restore backup** replaces the
whole named-state library with its preceding saved version, after confirmation;
it is not limited to the selected entry or mode.

## Automatic history

The **History** tab keeps an automatic record of each mode's form at every Generate or Queue click, like the upstream
State Manager's auto-save. Entries are named by time, hold the same complete snapshot as a named state, and show a
100 px thumbnail of the first image the job produced plus the start of the prompt (the thumbnail is missing while the job
runs, or if it failed). Only the newest 50 entries per mode are kept; older ones and their thumbnails are removed.
A state identical to the newest entry is not recorded twice.

- **Record every generation automatically** turns recording on or off (stored as `autosave.off` in the history folder;
  existing entries stay).
- **Delete selected** (multi-select), **Clear history** (this mode only) remove entries after confirmation.
- **Keep as named state** copies the selected entry into the named states under the name typed above the tabs.
- History lives in `<app home>/config/generation-history/` (`library-v1.json`, `thumbs/`), separate from named
  states. A recording failure is reported but never blocks generation.

## Restoring only some categories

Under the tabs, six check boxes (Prompts; Sampling, size, seed, hires; Checkpoint / VAE / clip skip; ControlNet; Script
and extension controls; WD batch and layout) pick which part of a named state or history entry **Restore state** applies.
All checked is a full restore. With fewer checked, only the fields of those categories change and only their resources
are checked, so a missing checkpoint does not block restoring just the prompts. If a full restore is blocked because
a saved choice is missing from the current catalog (for example the default Hires Fix upscaler not being installed),
restore a category subset instead, or install the item.

The field picker and **Restore selected setting** restore just one saved setting. Only resources needed by that
setting are checked. When restoring the WD batch enabled or input setting, the resulting enabled state and input
folder are checked together.

## What is saved

- Prompt and negative prompt, sampler/scheduler, steps, CFG, dimensions, counts,
  seeds, variation settings and retained sampler eta.
- Shared checkpoint/VAE selections and clip skip.
- Mode-specific hires or img2img/inpaint settings, including disabled drafts.
- Script selection and every supported native script/extension control, including
  controls in inactive forms. Empty dynamic model choices remain empty.
- ControlNet units, their enabled states and referenced image paths; img2img WD
  batch enablement, input folder and output folder name.

The current init image and painted mask stay in place: neither their pixels nor
their source paths are restored. Other image paths are references, not archived
copies. A snapshot does not copy models. History, generated images, global
Settings and window layout are outside its scope. The variation-section expanded
state is retained with its controls.

## Missing resources and blocked restoration

Before changing the form, restoration checks the saved fields, available catalog
choices, prompt LoRA references, referenced input files and an enabled WD batch
input folder. Unavailable resources block the whole restoration and appear in
the detail/status area. It does not quietly drop the missing setting. Keep the
referenced files available or restore their catalog entries and retry. Mask
export must finish before a state can be applied.

Checks are repeated after the confirmation dialog because the form or catalog
can change while it is open. Storage errors are reported; reload the library
before trying another write. Init-image and mask preservation does not imply
that a different image is suitable for the saved inpaint settings.

## Scope and evidence

This feature independently implements manual named generation settings from the
[State Manager reference](https://github.com/SenshiSentou/sd-webui-state-manager/tree/2af62ecd5287438bbc309f7e840d34aa5c8e3cb6).
It does not import upstream IndexedDB/text history or claim upstream storage-format compatibility, and has no HTTP API.
Automatic history with thumbnails, category restore and single-setting restore are provided as described above. The MIT
notice is archived; see [the source/license audit](v003-source-license-audit.md).

The actual native Save, Restore, missing-checkpoint refusal, fresh-window reload
and Delete checks passed in `outputs/v003-resumed-states.log`. The Restore check
includes dimensions, eta, disabled FreeU values and preservation of the selected
init image. These are bounded acceptance cases, not every plugin/model
combination. See [the v0.03 evidence record](V003_RELEASE_SCOPE.md) for remaining
release gates.
