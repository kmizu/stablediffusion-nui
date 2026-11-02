## Generator PNG metadata and replay (2026-11-03)

NativeGeneratorSuite exercises AppServices/Generator beyond EngineClient, using
an isolated home with no python directory and the actual native Java worker.
It discovers/loads the configured tiny checkpoint, saves txt2img then img2img,
reads the PNG parameters, and verifies prompt,negative prompt,seed,steps,
denoising strength and resize mode. Replaying parsed parameters with the same
explicit source image produces identical pixels. It passed in8.373s with CUDA
U-Net/VAE offload and16MiB budgets. This establishes the common Generator PNG
save/replay path; it does not imply source pixels are embedded in infotext,
nor verify GUI actions, every plugin, or SD1.5/SDXL quality. Source image must
still be available and supplied when reconstructing img2img from infotext.

## CUDA img2img encoder integration (2026-11-03)

scala-torch now runs VAE encoding as well as decoding on the configured VAE
device. NativeScalaEngineSuite passed using CUDA for U-Net/VAE, both weight
offload flags true and16MiB per session. The64x64 tiny-model txt2img→img2img
test completed in6.354s and checked typed output/seed/progress/keyword prompt.
No Python process is launched. CLIP, posterior sampling and schedulers stay CPU.
The original SD1.5 job was running concurrently, so this is functional evidence,
not a throughput benchmark. Full SD1.5/SDXL and desktop replay gates remain open.

## Native img2img request integration (2026-11-03)

NativeScalaEngineSuite now submits GenerationMode.Img2Img with the actual
Img2ImgParams serializer, using the preceding native txt2img PNG as input.
The Java worker accepts default inpainting fields, denoisingStrength0.5 and
resizeMode2. Tiny-model CPU integration passed: 64x64 output, seed42,
two active steps from four scheduled steps, and model keyword effective prompt.
This uses the same EngineClient/Supervisor and does not launch Python.
The full test passed in29.912s. No production NUI routing change was needed.

scala-torch's worker now implements file decoding, EXIF/RGB conversion,
NUI resize modes0/1/2 and selectable CPU/CUDA VAE encoding for img2img. Mask/inpainting options
remain unsupported and reject when active. This test establishes request and
result transport, not full desktop PNG metadata/replay, SD1.5/SDXL quality,
GPU img2img throughput or support for every extension. Those gates remain open.
Changes stay on feat/scala-torch-native-engine as requested.

## Native CUDA weight offload (2026-11-03)

Set SDFX_SCALATORCH_UNET_OFFLOAD_WEIGHTS=true and
SDFX_SCALATORCH_VAE_OFFLOAD_WEIGHTS=true together with their DEVICE=cuda options
to upload weights per operation instead of retaining every weight on the GPU.
Both default false and accept literal true/false. Activations remain on CUDA;
CLIP/schedulers and stored FP32 model weights remain on CPU. More transfers may
reduce throughput. MAX_BYTES caps session allocations, not total device usage.
Status runtime.unet_offload_weights / vae_offload_weights report the settings.

NativeScalaEngineSuite passed using both offload options and16MiB per session:
64x64 tiny-model generation, previews, VAE replacement/restore, keyword mapping,
and status. The same64x64 job fails VAE decode at2MiB; recovery latents are saved.
The test cleanup now handles recovery directories recursively and preserves the
original exception if cleanup also fails. Separate Java worker tests at10x6 pass
with2MiB per session. These are tiny-model checks, not SD1.5/SDXL acceptance or
proof that every NUI feature works without Python.
# Scala native engine integration

The opt-in `sdfx.engine.NativeScalaEngineSuite` launches scala-torch's
`scalatorch.models.worker.NativeWorkerMain` directly with Java 25. It uses the
real `EngineSupervisor` and `EngineClient`, including NUI's default generation
serialization. It does not launch Python or change the application's default
engine selection.

Verified on Windows with Java 25.0.5, scala-torch commit `6c478ce`, and the pinned
`hf-internal-testing/tiny-stable-diffusion-pipe` checkpoint documented in
scala-torch's reference fixtures: handshake, CPU Float32 model load, SHA-256 model
fingerprint, three DPM++ 2M/Karras steps at 64x64, typed progress and preview events,
PNG dimensions and seed, and model unload. The integration test passed in 17 seconds.
The tiny checkpoint tests execution; it does not establish normal model image quality.

## Running

The desktop and MCP `AppServices` can now explicitly select the native worker.
Set these variables before starting NUI:

```powershell
$env:SDFX_SCALATORCH_CLASSPATH_FILE = 'C:/path/to/worker-classpath.txt'
$env:SDFX_SCALATORCH_MODEL = 'C:/path/to/diffusers-model'
$env:SDFX_SCALATORCH_SCHEDULER = 'C:/path/to/explicit-euler-config.json'
$env:SDFX_SCALATORCH_JAVA = 'C:/path/to/jdk-25/bin/java.exe'
```

The Java override is optional when NUI itself runs on Java 25. This matters for
packaged NUI runtimes that use an older Java version. Classpath entries must be
absolute existing paths. Selecting any of the four launch variables above skips Python bootstrap;
incomplete configuration fails explicitly rather than starting Python. Remove
all four variables to restore the default engine choice. Native dry-run is not
implemented. Set model loading to CPU/Float32 for this initial native backend.
This launch route is experimental: the default remains Python when no native
variables are set, and the remaining unsupported operations below still apply.

### Optional CUDA VAE decoding

With the native launch configured, the worker can use CUDA for VAE decoding:

```powershell
$env:SDFX_SCALATORCH_VAE_DEVICE = 'cuda' # also accepts cuda:0, cuda:1, ...
$env:SDFX_SCALATORCH_VAE_MAX_BYTES = '1073741824' # optional; default 1 GiB
```

Keep model loading at CPU/Float32: CLIP and, by default, U-Net denoising run on CPU.
Only decoding uses resident GPU Float32 weights/activations. Each decode uploads
the VAE weights and releases its CUDA context and allocations when it finishes,
fails, or is cancelled. Cancellation is checked between operations, not inside a
running kernel. GPU errors produce the normal exact-latent VAE recovery artifact;
there is no silent CPU fallback. `system_status.runtime.vae_device` reports the
configured placement; GPU memory telemetry is not yet available.

Set `SDFX_SCALATORCH_VAE_DEVICE=cpu` (or remove it) for CPU decoding. These two
optional variables alone do not select the native engine; the launch variables
above are still required. The same optional variables apply to the integration
test below, which checks that VAE replacement preserves the selected placement.
On 2026-11-03 the integration test passed with CUDA VAE decoding on GTX 1650
SUPER (4 GiB): Java worker handshake, VAE replacement/restore, typed generation,
preview and PNG workflow completed in 14 seconds. CLIP and U-Net remained on CPU;
this does not establish full GPU inference or normal-model quality.

### Optional CUDA U-Net denoising

The native worker also supports CUDA U-Net prediction and classifier-free guidance:

```powershell
$env:SDFX_SCALATORCH_UNET_DEVICE = 'cuda' # also accepts cuda:<index>
$env:SDFX_SCALATORCH_UNET_MAX_BYTES = '1073741824' # optional; default 1 GiB
```

These options are independent of VAE placement. U-Net weights and positive/negative
text contexts upload once per image generation and remain resident across all
sampling steps. CLIP encoding, timestep sin/cos constants and scheduler updates
still use CPU. Each step transfers its latent input and guided prediction; layer
activations stay on GPU. U-Net resources close before VAE decoding starts, including
after cancellation or failure. Models remain loaded in host FP32 storage between
requests, so the load settings are still CPU/Float32; this is not FP16/offload support.

`hello.device` and `system_status.device.active` report the configured U-Net device.
Status also exposes `runtime.unet_device`, `vae_device`, `text_encoder_device` and
`scheduler_device`; mixed execution reports `backend=hybrid`. Hello's `cuda` flag
is true when either U-Net or VAE is configured for CUDA. Availability is checked
when inference opens the CUDA session; failures do not silently switch to CPU.

On 2026-11-03, the real EngineClient test passed with both CUDA options in 2.636s.
The JavaFX desktop also completed the pinned tiny model's 64x64, three-step
DPM++ 2M/Karras generation (seed42, CFG7), displayed `Ready · cuda:0` and saved
the PNG. This is execution evidence for the tiny model, whose output is noise-like;
normal-model quality and performance are not established. The VRAM estimator
request remains unsupported.

First build scala-torch with Java 25. Its `models/Test/javaOptions` contains the
resolved `-Dscalatorch.worker.classpath=...` value. Save only the value after `=`
as UTF-8 text in a local file. This includes the built model jar and runtime
dependencies; do not use sbt's own `java.class.path`. Regenerate after rebuilding.

In the NUI checkout, set:

```powershell
$env:SCALATORCH_WORKER_CLASSPATH_FILE = 'C:/path/to/worker-classpath.txt'
$env:SCALATORCH_TINY_MODEL = 'C:/path/to/tiny-sd-pipe'
sbt 'engine/testOnly sdfx.engine.NativeScalaEngineSuite'
```

Restart an existing sbt server after changing these environment variables. Without
both variables the test is skipped. The model directory needs the tokenizer,
text_encoder, unet and vae Diffusers-format files. The test creates its own explicit
scheduler configuration, uses temporary output files, and cleans up the process
and output after the assertion checks.

## Remaining work

This is integration infrastructure, not a completed engine replacement. The
application still defaults to Python. General catalog operations, production packaging and
normal SD checkpoints, GPU CLIP/scheduler execution, FP16/offload, SDXL, batching, LoRA, img2img,
Hires and the existing extension feature set still need native implementation
and verification. Unsupported active generation settings currently return errors.
Keep changes on `feat/scala-torch-native-engine` until integration is ready for review.

## SDXL EngineClient integration probe (2026-11-03)

`NativeScalaEngineSuite` now has an opt-in tiny SDXL case that starts the same
Java worker through `EngineSupervisor` and `EngineClient`, loads the configured
SDXL model, sends typed NUI txt2img and img2img requests, checks the 64x64 PNG
outputs/seed/effective prompt, then unloads. Set `SCALATORCH_TINY_SDXL_MODEL`
to the local tiny SDXL Diffusers directory alongside the existing worker classpath
variable. The test source compiled with `engine/testOnly ... -z compile`; MUnit
skipped both integration cases because the optional worker/model environment
variables were not set. This is a test hook, not yet evidence of an executed
NUI SDXL generation. NUI GenerationParams still has no custom original-size/crop
fields; worker defaults those SDXL time IDs to requested output dimensions and
zero crop.

## SDXL NUI EngineClient execution (2026-11-03)

Executed `NativeScalaEngineSuite` with `SCALATORCH_WORKER_CLASSPATH_FILE` pointing
to scala-torch's freshly packaged worker jars and
`SCALATORCH_TINY_SDXL_MODEL` pointing to the local pinned tiny SDXL pipeline.
The Java worker hello reported an empty Python executable. Through the typed
NUI `EngineClient`, the test loaded SDXL, generated a64x64 PNG with txt2img,
used that PNG as img2img input, checked dimensions/seed/effective-prompt protocol,
and unloaded. The test passed in30.534s; no Python directory was present in
its temporary output home. One initial run exposed that img2img's optional
`effectivePrompt` is absent for an ordinary request; the assertion now checks
that protocol behavior. This confirms the NUI client serialization/transport
path for tiny SDXL, not GUI interaction, normal SDXL quality, or packaging.

## SDXL size and crop IDs from NUI `extra` (2026-11-03)

Native callers can pass `sdfx_sdxl_original_height`,
`sdfx_sdxl_original_width`, and optional `sdfx_sdxl_crop_top` /
`sdfx_sdxl_crop_left` strings in `GenerationParams.extra`. The worker turns
these into SDXL's six time IDs, appending the requested target height and width.
Both original dimensions are required; invalid integers, dimensions and crops
are rejected. Use only with an SDXL checkpoint. With no overrides, original and
target dimensions match the generated image and crop is zero. The Python engine
continues receiving its existing `extra` map; these names are interpreted by
the native worker.

The NUI EngineClient tiny-SDXL txt2img/img2img integration was rerun with
80x96 original size and crop2,4 and passed in30.581s. The native metadata unit
test checks all six IDs and rejects partial/invalid/non-SDXL overrides.

## Replaceable inference backend boundary (2026-11-03)

NUI engine selection is now centralized in `engine/EngineBackend.scala`. The UI,
Generator, MCP and core protocol continue to talk to `EngineClient`; they do not
import or link PyTorch or scala-torch classes. `EngineBackend.Python` owns the
existing Python launch path, while `EngineBackend.ScalaTorch` owns the Java worker
launch path. Select explicitly with `SDFX_ENGINE_BACKEND=python` or
`SDFX_ENGINE_BACKEND=scala-torch`; Python remains the default. The existing
`SDFX_SCALATORCH_*` opt-in still selects scala-torch for compatibility. An
incomplete native configuration or native dry-run fails without falling back to
Python. This keeps changing inference implementations inside the NUI `engine`
module and the EngineLaunch/JSON-lines boundary.

`ScalaTorchLaunchSuite` passed3 tests covering default and explicit Python choice,
explicit native choice validation, old environment opt-in, and no silent fallback.
Python's PyTorch wheels remain separately pinned in `python/requirements-torch.txt`;
the desktop/application modules do not acquire a PyTorch dependency.

Settings now exposes `Inference backend` (`auto`, `python`, `scala-torch`) and
file/folder pickers for the scala-torch worker classpath, Diffusers model folder,
scheduler JSON and optional Java executable, plus separate CPU/CUDA placement and
weight-offload controls for U-Net and VAE.
Choosing scala-torch skips the first-run Python bootstrap and normalizes native
weight loading to Float32/CPU; the actual U-Net/VAE compute device remains
controlled by the saved device selectors below. Matching `SDFX_SCALATORCH_*`
variables override saved values. Runtime `SDFX_SCALATORCH_*` variables are
forwarded to the worker, so saved CUDA placement and offload settings take effect
in the child JVM. The default Java executable is the current runtime and must be
Java25 or newer for scala-torch.

## SDXL through Generator and PNG replay (2026-11-03)

`NativeGeneratorSuite` now selects the replaceable backend boundary explicitly,
then exercises `AppServices` and `Generator` with the tiny SDXL model. It saves a
txt2img PNG, uses it as img2img input, checks prompt/negative/seed metadata and
output size, and confirms no Python directory is created. The request carries
custom original size/crop in `GenerationParams.extra`. The end-to-end test passed
in30.826s on CPU. This verifies the common desktop generation/save path for tiny
SDXL, while actual JavaFX UI interaction and normal SDXL output quality remain
separate acceptance work.

## SDXL through saved backend settings on CUDA (2026-11-03)

`NativeGeneratorSuite` now builds `AppServices` with the persisted `engineBackend`
and scala-torch paths, without a launch override. The opt-in test ran the real
Java worker with U-Net/VAE CUDA placement and weight offload enabled from saved
settings, without device environment overrides. On the local GeForce GTX 1650 (4GB VRAM),
the worker reported `cuda:0` and completed tiny-SDXL txt2img, img2img and PNG
metadata replay; the test passed in4.2s and confirmed no Python directory was
created. This verifies the settings-to-backend-to-Generator route on this GPU.
It does not establish full-size SDXL quality or fit/performance on 4GB hardware.
