## Tiled CUDA GEMM and im2col convolution (2026-11-03)

The inference path now uses a 16x16 shared-memory GEMM tile and lowers each
convolution's input patches to im2col in bounded chunks (up to 128 MiB), then
writes grouped/batched products into NCHW output slices. The exact generic
`conv2d` path remains available. The first kernel draft exposed a PTX shared
address-space conversion bug at CUDA synchronization; using the shared symbol's
native address fixed it. See the [PTX ISA shared-address rules](https://docs.nvidia.com/cuda/parallel-thread-execution/index.html).

On the local GTX 1650 SUPER, direct native SD1.5 generation completed at
512x512, 20 steps, DPM++ 2M/Karras, CFG 7.5, seed 42 in 422.64 seconds. It
saved `.superpowers/im2col-chunk-sd15-512x20-20261103-2000/native-82970952-c650-4353-b5a0-5b7a963b672d.png`
(SHA256 `2346f06bb713d6db469e346500efc376d2c319152c047c7e85e63d3265a2e2e2`).
That is about 42% shorter than the earlier 12m09s warp-convolution run. During
the sampled run the GPU was around 95%, VRAM around 860 MiB and worker RSS
around 10.8 GiB; the host-RAM figure was a sample, not a measured peak.

The rebuilt Windows package also completed a real 512x512, 20-step MCP request
through its bundled JRE and worker classpath, with all `SDFX_*` environment
variables removed from the launcher. It exited 0 and saved
`.superpowers/im2col-package-defaultattention/build-430f835c42674bd69553cf913d687b5c/zip-extracted/SDNUI/outputs/txt2img/2026-11-03/00000-4260.png`
(SHA256 `84fba2b6801206ae529a35c3ee83f06218d852262766e914612d0770dad9c3f9`).
The ZIP SHA256 is `43bd5f421765215804dc3a7ac91ae9636e0ac8d8a0ce6c19b47e499d22ed8354`.
The package includes the worker JRE and classpath manifest but has no
`python.exe` or Python virtual environment. The worker's default attention
score limit was raised to 134,217,728 elements after the packaged 512px request
hit the previous 16,777,216 limit; the same larger limit was already used by
the successful direct run. This verifies the packaged native route, not full
Python feature parity; grouped/batched convolution and SDXL remain unverified.

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
NUI resize modes0/1/2 and selectable CPU/CUDA VAE encoding for img2img. Four- and
nine-channel masked img2img, including full-resolution mask crops, are implemented.
Nine-channel routing is verified with widened tiny weights; an official
Diffusers inpaint checkpoint now also completes the native NUI masked workflow,
though this single smoke image does not establish broad quality parity. This test establishes request and
result transport, not full desktop PNG metadata/replay, SD1.5/SDXL quality,
GPU img2img throughput or support for every extension. Those gates remain open.
Changes stay on feat/scala-torch-native-engine as requested.

## Native batch generation (2026-11-03)

The native worker accepts NUI `batch_size` (1..16) together with `n_iter`
(1..100), producing their product in the standard seed order. It generates
images sequentially, so peak activation memory remains close to batch size 1;
this adds request compatibility and capacity without claiming parallel batch
throughput. Progress events report the total image count and requested logical
batch size. The native worker suite verified four outputs for batch_size 2 ×
n_iter 2, seed/subseed values 200..203 and 9..12, and matching progress fields.
The no-Python `AppServices`/`Generator` integration also passed with a tiny
SD1.5 checkpoint: batch_size 2 × n_iter 2 produced four PNGs with seeds 80..83
and retained both infotext fields. This does not establish GUI batch controls,
parallel batching, or normal-checkpoint quality/performance.

## Full-resolution masked img2img (2026-11-03)

The native worker now follows NUI's mask-bounded crop flow: blur and bound the
mask, apply configured padding, expand the crop to the requested aspect ratio,
resize the crop to the model canvas, then paste the generated region back and
composite through the full-resolution mask. The returned PNG and image result
dimensions follow the original canvas. scala-torch's worker suite passed a
100x60 synthetic canvas through a 64x64 crop and verified untouched pixels stay
unchanged. The no-Python NUI `Generator` integration also passed with an 80x48
source and 64x64 model request, returning an 80x48 PNG with unmasked pixels
preserved. Mask blur currently uses scala-torch's separable box approximation;
pixel-exact parity with Pillow's Gaussian blur is not established. Exact Pillow
parity for blurred-color fill mode 0, trained nine-channel checkpoint quality,
and real-model throughput remain open.

## Native masked content fills (2026-11-03)

Four-channel masked img2img now supports fill mode 0 (blurred surrounding color
with a separable box-blur approximation), mode 1 (original pixels), mode 2
(seeded pixel noise), and mode 3 (RGB 127 gray). Noise bytes match CPython's
`random.Random(seed).randbytes(...)`, including partial-word output. The Scala
worker suite checks Python golden vectors and masked/unmasked pixels; the NUI
`Generator` path passed full-resolution mode 2, while the worker path passed
modes 0 and 3. Mode 0 approximates Pillow's Gaussian blur with separable box
filters; pixel-exact parity is not established. Fill values are applied to model
input; original pixels remain the source for preservation and final mask
composition.

## Native nine-channel inpainting (2026-11-03)

The shared CPU/CUDA image pipeline accepts inpaint U-Nets with
`2 * latentChannels + 1` input channels. Each step concatenates the scheduler
latent, downsampled repaint mask and masked-image latent; four-channel U-Nets
retain their existing per-step latent preservation. NUI `Generator` integration
and the CUDA pipeline test passed with a synthetic tiny checkpoint widened from
4 to 9 input channels. This verifies model loading and condition routing, not
trained inpaint quality: no dedicated nine-channel checkpoint is present in the
local test fixtures.

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

## Runtime dependency boundary

The Python/PyTorch implementation is isolated in the `pythonEngine` SBT
project: it owns Python discovery, virtual-environment bootstrap, the
PyTorch/diffusers requirements and `PythonBackendProvider`. The native adapter
is isolated in `scalaTorchEngine`; it owns scala-torch worker launch and
`ScalaTorchBackendProvider`. Shared `engine` exposes only the generic backend
SPI and JSON Lines process protocol. `ui` and `mcp` compose the provider
modules, while generation continues through `EngineClient`.

The UI's compile-time reference to `pythonEngine` is limited to the optional
first-run Python setup flow. PyTorch APIs, requirements and process settings
stay in `pythonEngine`; replacing that implementation does not require adding
PyTorch to shared `engine`, `core`, plugins or MCP request handling. Likewise,
NUI does not link scala-torch's model/tensor jars: `scalaTorchEngine` passes the
external worker classpath manifest to a separate Java process. Keep tensor and
model implementation in scala-torch's `models` module; do not add those jars
to NUI's sbt dependencies or assembly, or move tensor/model types into shared
NUI modules.

Backend-specific values may be persisted by `core` and edited by `ui`, but
those modules treat them as generic configuration fields. `EngineLaunch` and
the JSON Lines protocol are the seam: replacing either backend should require
changes in its provider module and launch configuration, while generation UI,
plugins and MCP continue using `EngineClient`.

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
packaged NUI runtimes that use an older Java version. Classpath entries may be
absolute paths or paths relative to the classpath file. scala-torch can create a
relocatable worker runtime directory with `sbt 'models/workerDistribution'`; set
`SDFX_SCALATORCH_CLASSPATH_FILE` to its `worker-classpath.txt`, or place it at
`<app-home>/scala-torch/worker-classpath.txt` for automatic discovery. When no
scheduler path is configured, NUI uses the selected model's
`scheduler/scheduler_config.json`. A bundled Java runtime at
`<app-home>/scala-torch/jre/bin` is also discovered automatically. Selecting any of the four launch variables above skips Python bootstrap;
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
normal-model quality and performance are not established. Native VRAM profile,
estimate and calibration requests are implemented in scala-torch. The tiny
checkpoint could not complete the minimum 256px calibration cases, so usable
measurements from a normal checkpoint remain unverified.

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
normal SD checkpoints, GPU CLIP/scheduler execution, FP16/offload, batching,
nonstandard LoRA families and full NUI LoRA request-path acceptance, remaining
img2img modes, Hires and the existing extension feature set still need native
implementation and verification. Unsupported active generation settings currently return errors.
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

## Dependency boundary and native basic inpainting (2026-11-03)

The `engine` module owns the provider SPI, child-process protocol and
`EngineClient`; it contains no Python interpreter, PyTorch installation or
backend implementation. `pythonEngine` owns Python discovery, venv/bootstrap,
PyTorch requirements, Python process environment and its `PythonBackendProvider`.
`scalaTorchEngine` owns the scala-torch worker launcher and its provider. UI and
MCP include these provider modules in the runtime package and select them through
the SPI; generation still uses the shared `EngineClient` protocol. Replacing the
Python/PyTorch implementation can therefore be done in `pythonEngine` without
adding those dependencies to `engine`, plugins, or MCP request handling. Backend
settings remain plain values in core configuration; no inference library types
cross the common boundary.

## Native latent Hires Fix (2026-11-03)

The NUI `engine` adapter forwards latent Hires parameters through the existing
JSON-lines request; the two-pass latent implementation runs in scala-torch's
worker process. No PyTorch/scala-torch dependency is added to NUI's SBT graph or
assembly. `core` persists only generic scalar/path settings and `ui` edits those
values; neither imports backend classes. Replacing the inference library remains
an `engine` adapter/worker-classpath change behind `EngineClient`.

Tiny SD1 Generator integration passed without creating a Python environment:
64x64 base generation followed by 128x128 latent Hires and final-size PNG save.
This was rerun on the pinned Narsil tiny checkpoint through NUI `AppServices`,
`Generator` and the relative-path worker distribution; the CPU child JVM loaded
the model and passed `NativeHiresGeneratorSuite` in 42.895s, including the
assertions that the handshake reports no Python and no Python directory is
created. The suite now also generates txt2img Hires, then sends that PNG back
through `Generator` as unmasked img2img with latent Hires enabled; the second
request retained its Hires parameters and returned a 128x128 final PNG. The
updated suite passed in 75.849s, again with no Python directory.
The native worker covers Latent bilinear, AA bilinear, bicubic, AA bicubic,
nearest and nearest-exact for txt2img and unmasked img2img. Unmasked img2img
returns denoised first-pass latents for the same interpolation and second pass,
without a VAE decode/encode round trip. A direct Java worker CUDA smoke with the
pinned 9 MB Narsil tiny checkpoint requested 64x64 img2img with two base steps
at 0.5 strength (one active step), then 96x96 latent Hires at two second-pass
steps, and saved one 96x96 PNG in 2.35s. Masked inpainting Hires, pixel/model upscalers, per-pass
prompt/sampler/CFG/LoRA overrides and Hires-specific tweak hooks remain
unsupported. NUI `AppServices`/`Generator` routing is now verified for both
txt2img and unmasked img2img Hires on the tiny CPU checkpoint. Direct worker
CUDA smoke also passed for img2img Hires. Normal-model quality and complete NUI
Hires parity remain unverified.

## Native standard linear LoRA boundary (2026-11-03)

LoRA names, resolved file paths and per-encoder/U-Net multipliers remain ordinary
`GenerationRequest` data. The native worker consumes those JSON fields inside the
scala-torch process; no LoRA tensor or model types are added to NUI `core`, UI,
plugins or MCP. This keeps backend replacement confined to `engine` and the
worker protocol. The current native slice accepts bounded safetensors with
standard 2D linear factors and rejects unsupported adapter families and
per-block/step options explicitly. Tiny SD1.5 worker coverage passes, including
zero-scale no-op, nonzero output change, deterministic replay and base-weight
restoration. A tiny SDXL worker accepts the typed adapter request, and the second
encoder's alias changes its live CLIP linear output. The opt-in NUI
`NativeGeneratorSuite` now also passes a synthetic standard LoRA through
`Generator` and the Java worker (70.868s): zero-scale output matches baseline,
nonzero output changes pixels, replay is deterministic, and the following
adapter-free request returns to baseline. The test uses only NUI request types
and a temporary worker classpath file; no scala-torch types enter NUI's SBT
compile graph. This verifies the Generator/worker route, not JavaFX interaction
or normal-checkpoint LoRA quality.

## Native textual inversion formats (2026-11-03)

`Generator` sends embedding paths as ordinary protocol data; the NUI `engine`
module does not import embedding tensor or model types. The scala-torch worker
reads active `.safetensors` vectors or the restricted current-format `.pt`
tensor subset inside its own process and extends a loaded CLIP tokenizer/encoder.
The `.pt` reader accepts plain float tensors, `emb_params`,
`string_to_param["*"]`, and SDXL `clip_l`/`clip_g` mappings, with NUI's scalar
`name`/`step` metadata; it rejects unknown pickle globals, old non-ZIP archives
and unsupported tensor views.
Loaded tokens persist until model unload, matching the Python worker's lifecycle.
`NativeGeneratorSuite` passes both catalog formats through `Generator` and
confirms both change output; it also verifies safetensors persistence after its
catalog path is omitted and a Python-free engine home. Tiny SD1 fixtures verify
routing, not trained embeddings or full-size model quality. The SDXL
`NativeGeneratorSuite` also
passes on CUDA with a paired CLIP-L/CLIP-G embedding, confirms persistence after
catalog removal, and verifies the existing masked replay path in 6.274s on the
local GTX 1650 SUPER (4 GiB). Tiny fixtures validate routing and tensor shapes,
not trained embedding quality.

The opt-in `NativeGeneratorSuite` SDXL Generator test now sends a local mask
through the real `AppServices` and `Generator` path, with U-Net/VAE CUDA placement
and weight offload loaded from saved settings. On the local GTX 1650 (4 GiB), it
passed in 4.251s: pixels in the black half of the mask exactly match the source,
pixels in the white half include repaint changes, the output is64x64, PNG prompt
metadata remains readable, and the temporary home contains no Python directory.
This is the Python-free NUI integration path, without JavaFX interaction.

Native masked img2img currently covers four-channel Diffusers U-Nets. White mask
pixels mean repaint; black pixels preserve the resized source exactly. Euler and
DPM++ use latent preservation at each scheduler step and final pixel compositing.
Mask inversion and bounded blur are supported; the worker uses three separable
box-blur passes to approximate Gaussian blur, so edge values are not promised to
match Pillow byte-for-byte. Nine-channel U-Nets now receive the scheduler latent,
latent mask and masked-image latent through the same CPU/CUDA pipeline; their
four-channel latent-preservation blend is not applied. A widened tiny checkpoint
passed the no-Python NUI `Generator` path and CUDA pipeline test. This synthetic
fixture verifies channel routing, not trained inpaint quality. Exact Pillow
parity for blurred-color fill mode 0 and plugin inpaint integrations remain open.
No dedicated trained nine-channel checkpoint is available in the local fixtures.
Normal-size model quality and the rest of NUI's
Python feature set are still migration work.

## Real SD1.5 CUDA smoke result (2026-11-03)

The opt-in `NativeRealCheckpointSuite` runs the local Diffusers SD1.5 checkpoint
through `AppServices` and `Generator` with saved CUDA placement and U-Net/VAE
weight offload. At 256x256, it completed 12-step txt2img plus masked img2img
(denoising strength 0.7) in 473.5s on the local GTX 1650 SUPER (4 GiB). The worker
reported `cuda:0` and an empty Python executable. The test verified the source
pixels on the black half, repaint changes on the white half, and no Python
directory. It exports `sd15-source.png` and `sd15-masked-inpaint.png` to the
caller-specified sample directory for visual inspection.

The generated images are visibly poor and do not pass a quality gate; the test
proves execution and mask ownership only. A separate 512x512, 16-step SD1.5 run
with FP32 weight offload was stopped after about 35 minutes without completing.
It did not hit the configured CUDA allocation limit or exceed about 1 GiB VRAM,
so the measured problem was throughput rather than VRAM capacity. Normal-size
quality, a practical high-resolution offload path, and performance with weights
resident on a 6 GiB GPU remain open.

## Full-size NUI masked SD1.5 execution (2026-11-03)

`NativeRealInpaintSuite` takes the known 512x512 native SD1.5 red-apple output
as input and sends masked img2img through NUI `AppServices` and `Generator`.
It uses `DPM++ 2M`, `Karras`, 20 scheduled steps, strength 0.5 (10 active steps),
CFG7.5, saved CUDA U-Net/VAE placement and weight offload. With
`SDFX_SCALATORCH_MAX_ATTENTION_SCORE_ELEMENTS=134217728`, it passed on the local
GTX 1650 SUPER (4 GiB) in 685.5s. The worker reported `cuda:0`; the test verified
exact source pixels across the black half, changed pixels across the white half,
PNG prompt/seed/strength metadata, and no Python directory. The output and its
source are saved in `.superpowers/native-sd15-nui-inpaint/` for review.

The result is visually coherent but remained a red apple despite a green-apple
prompt. This proves the full-size NUI request, CUDA worker, mask ownership and
PNG path; semantic color editing at this strength is not accepted. It also
shows that 512px masked generation on this 4 GiB setup is slow with FP32 weight
offload. These measurements do not establish speed on a 6 GiB GPU without
offload.

The same test also ran at strength 1.0 (20 active steps) and passed in
1232.3 seconds on the same GPU. The black half stayed pixel-identical and the
white half changed, but the apple remained red. Increasing strength alone did
not solve the semantic color edit. The generated image and source are saved in
`.superpowers/native-sd15-nui-inpaint-strength1/` for comparison.

## Native img2img Hires Fix

The scala-torch worker now accepts NUI's separate `img2img_hires_fix` hook for
img2img/inpaint requests. It resizes the completed first-pass image with the
built-in `Lanczos`, `Nearest`, or `None` (Lanczos alias) resampler, or the
CUDA-backed `R-ESRGAN 4x+` RRDBNet x4 model, then resizes to the requested target
and runs the existing image-source pipeline. R-ESRGAN requires its supported
`.pth`, `.pt`, or `.safetensors` checkpoint in `upscaler_paths` and a CUDA U-Net device; tiled
inference uses 128px core tiles with 32px overlap. Tiny synthetic nine-channel
inpaint coverage verifies the whole-image second pass and built-in resamplers;
the `R-ESRGAN 4x+` label resolves to an installed `RealESRGAN_x4plus` checkpoint
inside the engine adapter. The opt-in `NativeImg2ImgHiresGeneratorSuite` passes
both the built-in CPU path and the NUI Generator → child-JVM CUDA path using the
official `RealESRGAN_x4plus.pth`; the latter returned a 128x128 PNG in 36.47s on
the local GTX 1650 SUPER (4 GiB). A separate scala-torch smoke ran a synthetic
16x16 RGB image to 64x64 in 1.73 seconds. These checks cover real weight loading,
the CUDA kernel path, request routing and output dimensions; seams on larger
images and perceptual quality remain unverified.
Other ESRGAN-family models, LDSR, and Waifu2x remain unsupported. Refinement
LoRA switching and ControlNet replay also remain unsupported. The adapter and
child-JVM integration stay in NUI's `engine` module; no scala-torch model/tensor
JAR is added to NUI's SBT graph or assembly.

## Native-first Windows package smoke

`scripts/package_windows.ps1` can include the relocatable worker distribution,
link a Java 25 child runtime, and set `-PreferScalaTorch` so first launch skips
Python bootstrap. The resulting Windows app-image and ZIP were built locally
with the Java 25 x64 JDK. The app-image was started with the bundled JavaFX and
Java worker, loaded the pinned Apache-2.0
`Narsil/tiny-stable-diffusion-torch` model (`42c2377ff725c83cfb48afc85eec636d202f3c3c`),
and generated a 64x64 PNG at two Euler steps on the GTX 1650 SUPER. No Python
virtual environment was created. The displayed image and PNG metadata confirm
the NUI → packaged worker → CUDA generation route; this tiny model produces
noise-like results and says nothing about normal checkpoint quality. The
native worker answers VRAM profile requests from CUDA device and model identity,
validates imported profile fingerprints, fits estimates from compatible
completed measurements, and runs the UI's serial calibration sweep. Calibration
records a 15ms sampled device-wide CUDA memory peak; it is an approximation of
incremental reserved memory. The tiny model's minimum 256px sweep produced no
completed measurements because its attention/VAE limits reject those sizes.
Normal SD1.5/SDXL calibration, image quality and throughput remain unverified.
## Replaceable Scala module boundary (2026-11-03)

NUI's `engine` project now exposes only a generic `EngineBackendProvider` SPI.
Scala-Torch-specific discovery, Java worker launch construction and settings
translation live in the separate `scalaTorchEngine` SBT project. The shared
`engine` project has no compile dependency on that module, and the desktop and
MCP projects now include it only in their runtime classpaths. The fat jar still
ships the provider through ServiceLoader, but UI/MCP compile against only the
generic SPI. Settings-to-`SDFX_SCALATORCH_*` translation is performed inside
`ScalaTorchBackendProvider`, keeping backend-specific launch configuration
within the replaceable module. Removing or replacing the module leaves the
Python backend and shared EngineClient protocol intact. The worker runtime
classpath remains an external manifest supplied at launch, so scala-torch's
FFM/CUDA jars are not folded into the NUI fat jar.

AppServices integration suites that select this provider now live under the
`scalaTorchEngine` project's test sources as well. This keeps the ordinary
`engine` project's test classpath independent of the optional provider while
testing the persisted-settings-to-worker route through the implementation module.

The fat-jar MCP stdio route completed an actual CUDA `txt2img` call from a
separate home with no `python/` directory. It loaded the pinned tiny SD1 model
from saved settings, reported `scala-torch-0.1.0 device=cuda:0`, returned a
64x64 PNG at seed 42, and exited 0 after stdin closed. The artifact is
`.superpowers/native-mcp-no-python/outputs/txt2img/2026-11-03/00000-42.png`.
The image is noise-like because the checkpoint is a tiny random-weight smoke
fixture; this verifies MCP-to-Generator-to-CUDA-worker plumbing, not quality.

## Extracted Windows package acceptance (2026-11-03)

`scripts/package_windows.ps1 -PreferScalaTorch` built a Windows x64 app image,
bundled worker distribution and worker Java 25 runtime. The resulting
299,537,019-byte ZIP (`a1d0603b2050db5cd144a3a8c25391f667e61dc5f3afc8554772f9b116b81a53`)
was extracted to `.superpowers/native-package-win/extracted/SDNUI`. That
extracted copy contains the relative worker manifest and bundled JRE, with no
`python.exe`; Python source files are still included for the optional Python
backend. With classpath and Java override environment variables unset, MCP
stdio loaded the worker using the extracted package's own
`scala-torch/jre/bin/java.exe`, generated a 64x64 CUDA PNG at seed 44, and
exited 0. The jpackage `SDNUI.exe` also launched the extracted test app image,
loaded the same model and reached `Ready · cuda:0`; its screenshot is
`.superpowers/native-package-win/build-e8f81302e1094d029e3716aa5e09ddd7/image/SDNUI/packaged-startup.png`.
Generated image: `.superpowers/native-package-win/extracted/SDNUI/outputs/txt2img/2026-11-03/00000-44.png`.
This verifies relocated package startup and MCP generation with a tiny fixture,
not normal-model quality or the complete NUI feature surface.

The moved `NativeHiresGeneratorSuite` passed through `scalaTorchEngine` on the
tiny SD1 fixture in 76.345s, including txt2img Hires and img2img Hires with a
no-Python home. `NativeImg2ImgHiresGeneratorSuite` passed its Lanczos refinement
path in 50.617s; its official RRDB checkpoint case was skipped because no
checkpoint path was supplied. The assembled JavaFX application was also
launched with `--home` pointing to an empty directory containing no `python/`
folder. It reached the main window with the engine status `Ready · cpu`, and
saved screenshots under `.superpowers/native-ui-no-python-run/`. This verifies
JavaFX startup and native engine handshake without Python; GUI-driven image
generation and the packaged Java 25 worker distribution remain unverified.

## CUDA VAE tiling smoke (2026-11-03)

The direct Java worker accepted `vae_tiling=true` on the local CUDA device with
U-Net/VAE weight offload. A 256x256 tiny-model img2img request completed in
10.07s and returned a 256x256 PNG with no worker warnings and no Python runtime
in the handshake. The 128px VAE sample size produces 64px latent tiles, so this
request exercises a 3x3 tile grid during both source encoding and final decode.
This checks execution and stitching dimensions; the tiny checkpoint output is
noise-like and does not establish visual quality or full NUI GUI wiring.

## Default PNDM config and refreshed Python-free package (2026-11-03)

The local Diffusers SD1.5 model stores a `PNDMScheduler` config. The native
worker now reads its shared beta settings for the sampler selected by NUI and
validates PNDM-specific boolean metadata; other scheduler classes remain
rejected. `EulerConfigSuite` passed all six cases, including the actual PNDM
field shape.

After rebuilding the worker distribution and Windows package, the extracted ZIP
(SHA256 `2ea2b814c4c0a812aea0e38604e6e96f96375ad8316cbb522c6d4bb6456a90da`)
contained no `python.exe`. Packaged MCP stdio returned 26 tools and logged
`engine ready: scala-torch-0.1.0` using the model's default
`scheduler/scheduler_config.json`, with no Python directory in its empty home.
Packaged `SDNUI.exe` also reached `Ready · cuda:0` from another empty home with
the scheduler override unset. Screenshot:
`.superpowers/native-package-win/extracted/pndm-fix/packaged-pndm-ui.png`.
The same extracted package then completed an MCP `txt2img` request against the
local real SD1.5 model with its default PNDM scheduler config: 256x256, four
steps, CFG 7.5, seed 4242, DPM++ 2M/Karras, fp32, CUDA U-Net/VAE and weight
offload. It wrote
`.superpowers/native-package-win/extracted/pndm-fix/mcp-real-model-pndm/outputs/txt2img/2026-11-03/00000-4242.png`
(SHA256 `942508c0ad6c86decff3669ad5ffb9c2625d3c4de4186e2dc419315a6813d950`);
the isolated app home has no `python/` directory. I inspected the image: it is
an abstract, low-step smoke result, so this confirms the real-model package
route and CUDA execution, not image quality or full feature parity.

The freshly assembled NUI jar (SHA256
`8f55d61e13cf5452d69bd07e5e455905862b8e006832f3015c1522776e870903`) was then
run directly over MCP stdio with the bundled scala-torch worker distribution.
With the model's parent folder configured as the checkpoint directory,
`list_models` exposed `sd15`, `set_model` loaded it, and `txt2img` generated a
256x256 PNG at four steps, DPM++ 2M/Karras, CFG 7.5, seed 4244. MCP returned the
saved path and infotext; the process exited 0 and this isolated home has no
`python/` directory. Image:
`.superpowers/fresh-assembly-sd15-set-model/outputs/txt2img/2026-11-03/00000-4244.png`
(SHA256 `70fe6fe17ec245b48810b35499e4f9a02adcf4cd510b1952bfd051bf6d71fe7c`).
The inspected image is an abstract low-step smoke result, so the evidence is
for the current fat jar's ServiceLoader, model selection, worker launch and
CUDA generation path, not visual quality or feature parity.

A follow-up 512x512, 20-step request on the same host did not return within a
15-minute MCP drain and produced no PNG. During the run, the worker stayed in
FFM CUDA `conv2d`, GPU use varied from 69% to 100%, VRAM stayed below 1 GiB,
and worker host RAM reached about 14 GiB. This makes higher-resolution,
normal-step SD1.5 generation performance a concrete remaining blocker on the
local GTX 1650 SUPER; the short smoke above only establishes the route.

A second 512x512, 20-step NUI MCP run used the rebuilt worker's FP32 FMA
inference convolution path, explicitly supplied the current worker classpath,
and targeted the same local SD1.5 model. It also reached the 15-minute drain
limit without returning an image; the MCP process exited 0 after draining, but
the generation request had no result and only a native temporary file remained.
During the run, `jcmd` showed the worker's generation thread waiting in
`CudaDiffusionRuntime.conv`. At 12m04s the GPU was at 94%, VRAM at 858 MiB and
worker RSS at 12.54 GiB. Thus the FP32 FMA path and correct worker launch do
not resolve the throughput problem. That FMA kernel still assigned one output
value to one thread and serially walked every input channel and kernel position.

The inference convolution was then changed to assign one output to a warp and
split its input-channel/kernel reduction across 32 lanes. A butterfly shuffle
reduces the lane sums, and lane zero stores the output; the exact generic CUDA
convolution remains unchanged. The launch uses four warps per block and does
not allocate an im2col buffer. During the first short NUI run, the driver JIT
rejected the new PTX because `%ctaid.x` was passed directly to `mad.lo`; the
CUDA JIT error log now includes the ptxas diagnostic, and copying the special
register into a normal register fixed the PTX.

With the rebuilt worker distribution, the fat-jar MCP route completed a real
local SD1.5 request at 512x512, 20 steps, DPM++ 2M/Karras, CFG 7.5, seed 4249,
fp32, CUDA U-Net/VAE and weight offload. It exited 0 after 12m09s and saved
`.superpowers/warpconv-sd15-512x20-20261103-160648/outputs/txt2img/2026-11-03/00000-4249.png`
(SHA256 `2e2212329d3c4bbba1db62ad832a6bdd5b9ca62a6d08fd773c2a92b689cf4cac`).
The image is a coherent red barn and field, rather than the earlier four-step
abstract smoke output. During sampled points, GPU utilization ranged from 60%
to 97%, VRAM from 836 to 912 MiB and worker RSS reached 12.37 GiB. This crosses
the previous 15-minute timeout on the GTX 1650 SUPER and remains slow with high
host-memory use; it is a concrete real-model CUDA milestone, not broad
performance or feature-parity acceptance. The isolated app home has no
`python/` directory.

A follow-up tried nesting the filter-row/column loops inside each channel lane
to remove the three integer divisions per reduction term. The updated worker
passed the 256px smoke/JIT path, but the matching 512x512, 20-step MCP request
again reached its 15-minute drain limit without a result or PNG. This version
was discarded in favor of the warp-reduction kernel above, which has the
verified 12m09s output. Reducing integer divisions alone did not improve
end-to-end generation; layer uploads, kernel launch count and the full
diffusion call path still need profiling before further changes.
## Backend dependency boundary

The generic `engine` module owns the backend provider SPI and setup-task API.
`pythonEngine` owns Python discovery, PyTorch/diffusers bootstrap and Python
launch; `scalaTorchEngine` owns the native worker launch. The UI uses the
generic setup SPI and declares both implementations as runtime dependencies,
so UI compilation does not depend on the Python/PyTorch backend. MCP includes
Python at runtime and in test scope for its Python fixtures, while its normal
compile surface stays on the generic engine API. Replacing the inference
backend therefore means replacing its provider module, not changing UI code.

## Native-only Windows distribution (2026-11-03)

The SBT build accepts `SDFX_NATIVE_ONLY=true` when starting its server and
omits `pythonEngine` from the root aggregate and MCP/UI runtime graph; shut down any existing SBT
server before setting the variable, then shut it down after assembly. The ordinary assembly still
contains both provider modules. Settings choices are populated from the generic
provider SPI, so the native-only UI offers scala-torch without Python setup or
custom-code controls. The Windows packager's `-NativeOnly` option verifies that
the jar service descriptor contains only
`sdfx.engine.scalatorch.ScalaTorchBackendProvider` and no Python provider
classes in `sdfx/pythonengine`, Python-named classes, and the Python provider,
then omits the `python/` source and requirements payload.

The native-only jar assembled successfully with SHA256
`f9f4092046d8af7323d40bae5196a1a06e950294b7528cc6334d91366e08b02d`; provider
inspection found exactly the scala-torch service. The normal jar was also
assembled and inspected; it still contains both providers and Python engine
classes. The resulting 287,391,595-byte Windows ZIP has SHA256
`d48a4a598a6bc75fb7f5535a6ce0473403e2f08939b88230dd811ee2b9510a5f`. Its 828
archive entries contain no Python path or requirements file, and include the
worker manifest and bundled Java 25 executable.

I extracted that ZIP and launched `SDNUI.exe --mcp` with Python-related
environment variables removed and `PATH` restricted to Windows system folders.
MCP discovered 26 tools and the local `sd15` checkpoint, loaded the model, then
completed 256x256, four-step DPM++ 2M/Karras generation at CFG 7.5, seed 4261.
The app exited 0 and logged `engine ready: scala-torch-0.1.0 device=cuda:0`;
its worker command used the extracted package's `scala-torch/jre/bin/java.exe`.
The saved image is
`.superpowers/native-only-distribution/package-final/build-653eb9ece0c44bb38a9afa374c410172/zip-extracted/SDNUI/outputs/txt2img/2026-11-03/00000-4262.png`
(SHA256 `a375b6b851976100fc2d2cf90f2b9f9f2327ffe3805e3be6e02ff24e549b9cf8`).
This four-step image is an abstract smoke result. It verifies the extracted
native-only package route and CUDA execution, not normal-step image quality or
complete Python feature parity.
# SD1 single-file checkpoints

The scala-torch backend can load conventional SD1 `.safetensors` checkpoints and
the ZIP-based PyTorch `.ckpt` layout used by the official SD1.5 pruned EMA-only
checkpoint. Other pickle layouts or model variants may be rejected; the reader
does not execute pickle globals or training objects.

PyTorch archives normally include ZIP CRC32 checksums, which the native reader
verifies. For an archive intentionally saved with PyTorch CRC32 disabled, set
`SDFX_SCALATORCH_ALLOW_MISSING_CRC=1` before launching NUI. This compatibility
mode accepts missing checksums only when every archive entry has a zero CRC;
it still checks recorded CRCs in other archives. The all-zero case cannot be
checked for corruption using CRC, so use it only for a file whose origin and
integrity have been verified separately. This setting applies to PyTorch model,
ControlNet, LoRA, textual inversion and supported upscaler/archive operations.

An extracted native-only ZIP (`SHA-256 02200a4d1420e6eeff110e2a2b44acaf6f32267b3b3e45eea773e481fb39d737`)
was tested through NUI AppServices using its bundled Java and worker with the
setting enabled. A copy of the official SD1.5 checkpoint with zeroed ZIP CRC
fields generated on the 4 GB GPU, and VAE replacement/reset reproduced the
original output pixels. The generated 256x256 image matched the original
checkpoint's result pixel-for-pixel. This test uses the service harness, not
manual interaction with the package's graphical controls.
The file does not contain the CLIP tokenizer: provide `vocab.json` and
`merges.txt` in `<app-home>/scala-torch/tokenizer/`, or in a `tokenizer/`
directory next to the checkpoint. `SDFX_SCALATORCH_TOKENIZER` explicitly selects
another tokenizer directory. A single-file model uses the SD1 scheduler defaults
unless `SDFX_SCALATORCH_SCHEDULER` is set. SDXL and SD2 single-file checkpoints
are not supported by this loader. SD1 four-channel and nine-channel inpainting
configurations are supported subject to the checkpoint structure checks.

For a Windows app image, pass `-ScalaTorchTokenizerDirectory <directory>` to
`scripts/package_windows.ps1` alongside the worker distribution and JDK options
to include the two tokenizer files under `scala-torch/tokenizer`. The packager
copies only those two files; use assets you are entitled to distribute.

With the public SD1.5 checkpoint, NUI's AppServices and Generator completed a
64x64 CUDA generation in a temporary home without Python. A direct worker run
produced a 256x256 image in 12 steps; broader feature parity remains work in
progress.

The official SD1.5 pruned EMA-only `.ckpt` also passed a 256x256, eight-step
CUDA generation through NUI AppServices and Generator on a 4 GB GTX 1650, with
U-Net and VAE weight offloading and no Python directory in the application
home. Checkpoint loading additionally consumes substantial system memory; this
test does not establish a minimum system RAM requirement.

The extracted native-only Windows package also generated the same 256x256 image
through its JavaFX window using `--autogen`, the packaged Java and worker, and
the official SD1.5 `.ckpt`. The saved screen shows the selected checkpoint,
CUDA device, generation settings and completed image. This is an automated GUI
smoke test; interactive controls and other model layouts were not exercised.

With the extracted package's worker and bundled Java, NUI AppServices also
switched from the official `.ckpt` to an SD1 `.safetensors` model, generated,
rejected a damaged `.ckpt`, and reproduced the same pixels on the still-active
model before switching back to the original checkpoint. On this 4 GB GPU test,
one working-set observation during the switch was approximately 11.6 GiB for
the worker; it is not a measured peak or minimum system-memory requirement.

The refreshed native-only package with selective `.ckpt` VAE loading was
extracted and checked against the worker build by SHA-256. The extracted tree
contained no Python executable, `.py` or `.pyc` files. NUI AppServices using the
extracted worker and bundled Java generated with the official SD1.5 checkpoint,
switched to an external VAE, restored the checkpoint VAE, and reproduced the
original image pixels on the 4 GB GPU. This acceptance uses the NUI service test
harness; the refreshed package's graphical VAE controls were not exercised.

## Full SDXL memory and smoke test

The native worker holds model parameters in host Float32 memory. Full SDXL can
exceed Java's usual 25% heap limit even when its source files are FP16. The
scala-torch provider launches its child JVM with `-XX:MaxRAMPercentage=50.0`;
this changes only the native worker's heap limit, not the Python backend. An
explicit `-Xmx` supplied through normal JVM options can set a different limit.
This does not reduce the model's actual host-memory requirement. If the heap is
insufficient, the worker reports `resource_exhausted` for the request.

On the local 64 GiB host and 4 GiB CUDA card, the official SDXL base model
loaded through NUI `AppServices` and `Generator` and produced a 64x64,
one-step PNG without Python. Before the heap adjustment, the same model ran out
of heap during loading. An actual 8 GiB worker heap also returned the expected
`resource_exhausted` error. A separate direct-worker test using the official
SDXL offset LoRA completed 256x256 at eight steps in 223 seconds; its image was
strongly distorted. A second run with the same prompt, seed and settings but no
LoRA completed in 198 seconds and was also strongly distorted. An independent
CPU reference at those same settings produced almost the same distorted image.
At 512x512 and 16 steps, the native worker instead produced a recognizable apple
on a table in 580 seconds, closely matching the CPU reference (average RGB
difference 0.030 out of 255). This is a direct worker run, not a packaged NUI
acceptance test; broader sizes, models and NUI workflows still need validation.

An updated native-only Windows ZIP was subsequently extracted and launched with
Python-related environment variables removed and PATH restricted to Windows
system directories. Its bundled JVM and scala-torch worker ran the same full
SDXL 512x512/16-step request through MCP; it returned an image and metadata,
and every RGB pixel matched the earlier direct worker result. The ZIP contained
828 entries and no Python payload; its SHA-256 was
`548341182b23a0b93adb68eb20d6ca96f2e3728a0d2e8327115d9b10abbf0e61`.
The output PNG was `sdxl-native-package-output/build-8fd3719e2d004360a02eb90f2c97685d/extracted/SDNUI/outputs/txt2img/2026-11-03/00000-42.png`
(SHA-256 `b66ce2447971e4af504cd8a96857f655fee9d4aada95812b7d93e6d4d85a3523`).
This does not establish complete application feature parity.

After scala-torch's CUDA upload and workspace-budget fixes, the native worker
distribution and native-only ZIP were rebuilt. The updated ZIP SHA-256 is
`6c67201f77e015d88cf37e6005a757f180e0664ef110ecf16e003d11dcef0440`;
it also has 828 entries and no Python payload. Its extracted MCP process used
the bundled runtime and completed a full-SDXL 64x64, one-step CUDA smoke test.
The 512x512 acceptance run above used the previous worker build.

The larger request was subsequently repeated with the updated ZIP, producing the
same PNG byte-for-byte. One pre-change and one post-change run measured from
settings-file creation to saved image took 601.4s and 525.9s respectively,
including startup and model loading; this is not a controlled benchmark.

Standalone NUI upscale now works through the native worker before a diffusion
model is loaded. None, Lanczos, Nearest and `R-ESRGAN 4x+` are supported; the
official Real-ESRGAN checkpoint was tested through NUI Generator on CUDA at
2x and 5x. Other model upscalers remain unsupported. Fractional nearest-neighbor
resizing uses pixel-center sampling.
The native-only ZIP containing standalone upscale and the model-name fix has
SHA-256 `bae14829eb56278695ae0ee690721ea367612c1b1885d6cbbae21f206d62e0de`.
Its extracted worker completed a fractional Nearest request without Python;
its extracted NUI also completed a full-SDXL 64x64 one-step MCP smoke. NUI
Generator separately exercised the official Real-ESRGAN model at 2x and 5x.

Standalone upscale now also returns NUI hardware-info metadata when enabled,
including elapsed time and the optional remark. On the local CUDA machine,
NUI Generator wrote the GPU name, memory sizes, CPU identifier and remark into
the output PNG for both built-in and Real-ESRGAN paths. Hardware probing falls
back to `unknown` when the required system information cannot be obtained.
The rebuilt native-only ZIP including this change has SHA-256
`cf020e8b59c3d9f1ba7854eda3bf33228b05854804a08a4c058f3cfb5e855d25`.
It contains no Python payload; its extracted worker passed an upscale request
with hardware-info enabled, and the extracted NUI completed an SDXL 64x64
one-step MCP smoke using its bundled Java runtime.

Hardware-info is now supported for native txt2img and img2img as well. Each
output receives its own hardware profile, elapsed time and optional remark;
the native worker runs batch items sequentially, so times are per item. Tiny
SDXL tests cover batches, img2img, disabled metadata and CUDA device access.
NUI Generator tests verify the fields in saved PNGs. A rebuilt native-only ZIP
with SHA-256 `7883d97397b53fef0875b366213b7f11539d958603d137cbd3c330defc121076`
was extracted and ran official SDXL at 64x64/one step through MCP with hardware
metadata enabled. Both the MCP response and PNG contained the GPU information,
timing and remark. This smoke is not a full-size or full-parity acceptance test.

## Euler ancestral sampler

The native worker now exposes `Euler a` with seeded stochastic updates for
txt2img, img2img, latent Hires and img2img Hires Fix. Uniform is the default.
As in the currently bundled Python/Diffusers scheduler, selecting Karras for
Euler a falls back to the checkpoint timestep spacing and produces a warning;
it does not execute a Karras schedule. `sample` prediction is explicitly
unsupported for this sampler. Seeds are repeatable in scala-torch but use a
different random generator from PyTorch and therefore do not promise identical
images across backends.

Step calculations match independent Diffusers fixtures, and small SDXL tests
cover CPU/CUDA, replay, image input and Hires. NUI Generator exercised txt2img
and img2img without Python. A native-only Windows package (SHA-256
`18e6b0d38d6f745b56617eb75bc0115cc66c74cdff18da07600f7f6295a89169`)
also ran official SDXL via MCP with Euler a, Uniform, 64x64 and two steps;
hardware metadata and the sampler name were present. This is a small execution
smoke, not a visual-quality or complete-parity test.

## Euler Uniform schedule correction

NUI's Euler Uniform uses log-interpolated training sigmas and overrides a
checkpoint's `timestep_spacing`. The native worker previously reused the
checkpoint spacing and linearly interpolated sigmas; for a leading-spacing
checkpoint, a one-step request could begin at timestep 1 instead of 999.
The worker now follows NUI's Uniform schedule for image and latent-return
passes; the generic scala-torch Diffusers-style Euler scheduler remains intact.
Fixtures from NUI/Diffusers verify Uniform and Karras at 1, 3, 4 and 9 steps.

The native-only ZIP SHA-256 is
`2239a1432ce291ea0cb8cbb94cf8de4896e16c08fa38336905ff0ad88ec215a4`.
After extraction, NUI MCP completed an official SDXL 64x64, one-step Euler
Uniform request with the bundled worker and Java, with Python removed from the
environment. Against an independent NUI/Diffusers CPU run using the same
initial noise, the saved RGB image differed by at most 1/255, averaging
0.0031/255. This is a numerical execution smoke, not a quality assessment.

## Native TAESD previews

The `taesd` preview method uses local Diffusers-format TAESD decoder weights.
Set `SDFX_SCALATORCH_TAESD_DIR` for SD1 and `SDFX_SCALATORCH_TAESDXL_DIR` for
SDXL to directories containing `diffusion_pytorch_model.safetensors` before
launching NUI. The verified models are MIT-licensed `madebyollin/taesd` at
`614f76814bbe30edbe2e627ace1c2234c81a2c0e` and `madebyollin/taesdxl` at
`b20258aaef75ef61e659c1e0f14f251cf0ad153e`. Models are not downloaded or
bundled automatically. The decoder uses the configured VAE device; CUDA uses
the VAE's bounded allocation budget. Missing, invalid, or unavailable weights
produce a warning and use the approximate preview instead. CPU decoding can be
slow. This preview decoder does not implement the separate VAE Tools feature.

The native worker's SD1.5 and SDXL previews were tested through NUI
AppServices on a 4 GB GTX 1650; the SD1.5 run received three previews. The
native-only ZIP with SHA-256
`190438e64ec9aafe3822a48cf814cf548619c720d1aa709d0d16ef8d4a50720a`
was extracted and repeated the SD1.5 service test using its bundled Java and
worker; the final image matched the pre-TAESD output pixel-for-pixel. The ZIP
does not bundle TAESD weights. The current package test did not operate the
graphical controls manually.

## Heun through NUI EngineClient (2026-11-04)

`HeunScheduler` performs the predictor/corrector update in sigma space and uses
a single Euler evaluation at terminal sigma zero. The native worker advertises
`Heun` and canonicalizes the NUI spelling `heun`. The integration test requests
Heun for tiny SD1.5 txt2img, validates the 64x64 PNG, progress and preview, then
reuses the image for img2img. The same suite sends tiny SDXL txt2img/img2img
requests through the Python-free worker.

Ran `engine/testOnly sdfx.engine.NativeScalaEngineSuite` with the freshly built
scala-torch worker distribution and local `tiny-sd-pipe` / `tiny-sdxl-pipe`
fixtures. Both CPU cases passed (SD1.5 Heun in 59.258s; SDXL DPM++ 2M in
30.34s; 93s total). Both worker hello messages had an empty Python executable.
Repeated with both U-Net and VAE set to CUDA on the 4 GiB GTX 1650 SUPER and
Heun selected for both model families; both cases passed in 17s, and the suite
verified the worker reported `cuda:0`. An initial run failed before model
loading because its model discovery root included sibling fixtures; narrowing
the requested catalog root to the selected model directory fixed test
isolation.

This verifies tiny-model CPU and CUDA protocol execution, not practical image
quality on ordinary checkpoints or full NUI/Python feature parity.

## Heun on full checkpoints with 4 GiB CUDA offload (2026-11-04)

Added opt-in `NativeRealCheckpointSuite` for a normal Diffusers SD1.5 or SDXL
checkpoint. Set `SCALATORCH_REAL_SD15_MODEL` or `SCALATORCH_REAL_SDXL_MODEL`
(one at a time), plus `SCALATORCH_WORKER_CLASSPATH_FILE`. Run with U-Net and VAE
on CUDA and both weight-offload options enabled. Through NUI `EngineClient`, the
test loads the model and generates a 64x64, two-step Heun PNG; it verifies the
worker reports an empty Python executable, `cuda:0`, both offload flags and the
image dimensions.

On the 4 GiB GTX 1650 SUPER, full SD1.5 passed in 29.133s and full SDXL passed
in 86.624s. The SDXL U-Net safetensors file is 4.78 GiB on disk. The worker
reached about 16.8 GiB resident host memory during SDXL inference, so this path
still needs substantial system RAM. The test launch helper now mirrors
production `ScalaTorchLaunch` with `-XX:MaxRAMPercentage=50.0`; its earlier
default heap limit caused an SDXL `resource_exhausted` failure before this
alignment. These tests prove low-resolution inference on the local checkpoint
fixtures, not full-size quality or general performance.

The same opt-in test then generated SD1.5 at 512x512 with two Heun steps in
77.125s using CUDA U-Net/VAE and weight offload. This confirms the 4 GiB path
works at the target image dimensions, but two steps are only an execution and
performance smoke, not a meaningful image-quality check. `SCALATORCH_REAL_WIDTH`,
`SCALATORCH_REAL_HEIGHT`, and `SCALATORCH_REAL_STEPS` set the dimensions and
step count; defaults remain 64x64 and two steps.

## Wider CUDA convolution tile through NUI (2026-11-04)

Scala-Torch's FP32 fast convolution now dispatches a separate shared-memory
GEMM kernel with a 16x32 output tile and 32-wide reduction tile. The generic
matrix multiply remains unchanged. CUDA convolution tests passed 5/5, including
grouped workspace chunks, non-divisible M/N/K tails, and comparison with the
CPU reference. The last partial workspace chunk exposed that im2col packs rows
using the chunk's actual width; the new kernel now receives that width as its
input leading dimension.

Rebuilt `models/workerDistribution` and reran the full SD1.5 NUI EngineClient
path at 512x512, four Heun steps, CUDA U-Net/VAE and weight offload. It passed
in 149.062s, compared with the prior 147.0s run under the same setup. A GPU
sample during inference showed 1,349 MiB / 4,096 MiB and 96% utilization. This
is within run-to-run variation and does not establish a speedup; FP32 offload
throughput remains an open optimization target. No image-quality claim is made
for a four-step run.

## FP32 dense CUDA attention through NUI (2026-11-04)

Scala-Torch now uses a dedicated FP32 score, softmax and value-accumulation path
for non-causal dense attention. Causal attention keeps its existing path. The
CUDA dense and causal attention suites passed 6/6, including CPU-reference
comparisons and NaN/workspace checks.

Rebuilt `models/workerDistribution` and ran the NUI Python-free
`NativeRealCheckpointSuite` against full SD1.5 with CUDA U-Net/VAE and weight
offload on the 4 GiB GTX 1650 SUPER. Its 256x256, 12-step txt2img plus masked
img2img completed in 149.883s; the suite verified CUDA placement, preserved
masked pixels, changed unmasked pixels, and absence of a Python directory. The
earlier run of this same smoke is documented at 473.5s. The CUDA profile for
the new run recorded 768+512 calls each to FP32 attention scores and prepare,
for 1.787+1.993s and 1.205+1.328s respectively. Upload and allocation took
42.475s and 28.341s across the two generations, the largest remaining profiled
cost. This is functional/performance evidence, not an image-quality claim.

The native-only development GUI was launched from the source worktree with the
rebuilt worker classpath. Windows reported a responsive `Stable Diffusion NUI`
window. The launch command used `SDFX_NATIVE_ONLY=true`.

## CUDA offload weight cache and 512px run (2026-11-05)

Scala-Torch's `CudaDiffusionRuntime` now retains the first offloaded weights
that fit within `min(session.maxBytes / 4, 256 MiB)`. This first-fit policy is
specific to the model's repeated ordered traversal: an initial LRU version
retained weights but thrashed on each sequential pass and did not reduce upload
calls. When a weight does not fit the remaining cache, it stays transient.
Runtime close, cancellation and operation failure release cache allocations.

CUDA offload cache tests passed 2/2. Tiny-fixture `CudaOffloadSuite` passed
2/2, including cancellation cleanup, parameter snapshot behavior and resident
output parity. CUDA attention, spatial transformer and LoRA regression suites
passed 11 tests; five optional fixture cases were skipped.

The full SD1.5 256x256, 12-step txt2img plus masked img2img NUI Service test
passed in 146.536s with Python absent, CUDA U-Net/VAE and weight offload. The
previous same test after FP32 attention but before caching took 149.883s; this
small difference is not a standalone cache speed claim. Upload call counts fell
from 16,502 to 9,050 for txt2img and from 11,002 to 6,142 for masked img2img.
The U-Net cache reported 7,452 and 4,860 hits respectively, retaining 128 MiB.
The source and edit PNGs are in
`outputs/native-offload-firstfit-profile-20261104/`; the low-step outputs are
visibly poor and do not pass a quality gate.

The Python-free NUI EngineClient passed full SD1.5 at 512x512 and four Heun
steps in 113.607s with a 128 MiB cache and 112.609s with a 256 MiB cache on the
4 GiB GTX 1650 SUPER. Both runs used CUDA U-Net/VAE and weight offload; the
worker reported `cuda:0` and an empty Python path. The 256 MiB profile recorded
5,044 U-Net cache hits representing 3,328 MiB, 4,583 upload calls and 23.522s
in upload/allocation; it retained the cache without exceeding the session
budget. A size-priority cache alternative was slower at 115.104s with 156 hits
and 9,471 uploads, so first-fit remains selected. A prior 148.369s run predates
both FP32 dense attention and caching; that difference reflects both changes.
Four-step output is execution/performance evidence, not an image-quality check.

The same 512x512, four-step EngineClient run was repeated with upload phases
instrumented separately. It completed in 111.83s. U-Net upload/allocation took
22.313s over 4,583 calls; CPU-side host staging took 13.497s and HtoD calls took
6.909s. Stream synchronization and device allocation were each below the
profile's top-twelve reporting cutoff. This points to staging and repeated
transfer volume as the next optimization targets; the run does not yet test a
larger retained-weight budget.

Scala-Torch now reuses a session-confined native staging buffer and grows it
only when a larger upload arrives. The FFM staging-buffer suite passed 1/1 and
the CUDA layout/upload suite passed 3/3. After rebuilding the worker
distribution, the same 512x512, four-step EngineClient generation passed in
103.148s. U-Net upload/allocation fell to 14.049s across the same 4,583 calls;
host staging was 6.665s (6.616s copying, 0.047s staging allocation), HtoD was
6.915s and stream synchronization was 0.217s. This is an 8.7s wall-time
improvement over the 111.83s profiled run; HtoD time stayed essentially flat.
Four steps remain an execution/performance check, not an image-quality gate.
The staging buffer retains its largest native host allocation until the CUDA
session closes, so long-running sessions with an unusually large single upload
may hold more host RAM between operations.

CUDA worker startup now derives each unspecified CUDA session budget from half
of the device's currently free VRAM, capped at 2 GiB. An explicit
`SDFX_SCALATORCH_UNET_MAX_BYTES` or `SDFX_SCALATORCH_VAE_MAX_BYTES` still wins.
The first-fit U-Net offload cache remains bounded to one quarter of that
session budget, with its absolute cap raised to 512 MiB. The 4 GiB GTX 1650
SUPER run retained 410.3 MiB, had 6,201 cache hits (5,334.1 MiB) and 3,426
upload calls, and completed in 102.533s without OOM. Compared with the 1 GiB
session run, this cut 1,157 HtoD calls and 2,006 MiB of upload volume; upload
time fell from 14.049s to 13.272s, while total time moved from 103.148s to
102.533s, within run-to-run variation. Treat the transfer reduction as
verified; the small total-time difference is not a standalone speed claim.

After the measurements, the current source worktree was relaunched with
`SDFX_NATIVE_ONLY=true` and CUDA profiling enabled. Windows reports a
responsive `Stable Diffusion NUI` window (PID 46016); its classpath contains
`sdfx-scala-torch-engine` and no `sdfx-python-engine`.

## Packaged Windows native-only smoke (2026-11-05)

Built the current native-only assembly and Windows image, then launched the
packaged `SDNUI.exe`. The package contains no Python payload or Python engine
provider and includes the scala-torch worker distribution plus a bundled Java
25 runtime. ZIP SHA-256:
`7db6b3c5a4f9ac8eb187402b2f28c07f34f1f25aafac82be285f48797a2c5fcc`.

With the packaged runtime, `PATH=C:\Windows\System32;C:\Windows`, and Python
environment variables removed, the packaged MCP server initialized, catalogued
the SD1.5 Diffusers model, loaded it, and started scala-torch on `cuda:0`. It
generated 512x512 images at four Euler steps (seed 4242) and twelve Euler steps
(seed 4243); both jobs completed without OOM. The 12-step output is a coherent,
recognizable fox-in-forest image at
`%LOCALAPPDATA%/Temp/sdnui-native-only-current-20261105-011905/mcp-home/outputs/txt2img/2026-11-05/00001-4243.png`.
This confirms the packaged inference path on the 4 GiB GTX 1650 SUPER without
Python. It is one SD1.5 workflow, not broad feature parity or a comprehensive
quality benchmark.

The accepted product runtime dependency is the JDK/JRE; Python and native
PyTorch are not required. The tested Windows package bundles Java 25. Rebuild
from the final worktree before release; this temporary image is only a smoke
artifact.

## CUDA 768px Hires and VAE tile fallback (2026-11-05)

A packaged NUI MCP run at 512x512 with a 1.5x Latent Hires pass reached the 768x768 second-pass latent, then failed during whole-frame VAE decode with `CUDA session allocation budget exceeded`. Replaying the captured `Shape(1,4,96,96)` latent with the full SD1.5 VAE reproduced that failure at a 1.8 GB CUDA session budget. The same real VAE decoded it successfully through existing overlapping tile decode at a 1.3 GB budget, producing finite `Shape(1,3,768,768)` output.

Scala-Torch now automatically uses tiled VAE decode when latent height or width exceeds one VAE tile (`sample_size / decoder scale`). The explicit `vae_tiling` setting still forces tiling for smaller images. Regression coverage is in `VaeDecodeTilingSuite`; `models/testOnly scalatorch.models.VaeDecodeTilingSuite` passed 1/1 after first failing because the decision helper did not yet exist. The CUDA U-Net attention work-limit adjustment for high-resolution score matrices remains in `NativeWorkerMain` and `NativeWorkerSuite` passed 4/4 in the isolated SBT snapshot.

Rebuilt `models/workerDistribution` from the current Scala-Torch source and used those jars in a native-only Windows NUI image. Packaged NUI MCP started the scala-torch worker on `cuda:0`, loaded full SD1.5, and completed 512x512 base (4 Euler steps) plus 768x768 Hires (2 steps), seed 4244, with `vae_tiling=false` in settings. PNG is `%LOCALAPPDATA%/Temp/sdnui-native-only-auto-vae-tiling-20261105/mcp-home/outputs/txt2img/2026-11-05/00002-4244.png`; PNG IHDR confirms 768x768 and the image was visually inspected. The packaged MCP/job run used the Scala-Torch backend; no Python engine or Python payload is present in the native-only app image.

This verifies the SD1.5 1.5x Hires case on the 4 GiB GTX 1650 SUPER, not arbitrary Hires scales, image aspect ratios, or complete Python-feature parity. The smoke image was assembled in a temporary package folder; rebuild from the current worktree for release.

A longer quality pass then completed through the same packaged MCP worker: 512x512 base at 12 Euler steps plus 768x768 Latent Hires at 8 steps, seed 4245, with `vae_tiling=false`. It finished with `hires step 8/8`, saved `00003-4245.png`, and PNG IHDR is 768x768. Visual inspection shows a coherent stylized fox and forest with no obvious tile seam. This is a useful quality sample; it is not a general model-quality benchmark.

### RealESRGAN `upscale_extras` through packaged NUI (2026-11-05)

The packaged NUI model catalog supplies `RealESRGAN_x4plus`; scala-torch used
the implementation key `R-ESRGAN 4x+`. Added normalization for the catalog
name and `.pth` filename form in `NativeStandaloneUpscale`, preserving the
catalog key for model-path lookup. Regression suite: 3/3 passing.

Verified the actual packaged MCP `upscale_extras` path on the native-only image:
768x768 input, RealESRGAN x4 model, requested scale 1.5, CUDA worker on
`cuda:0`, completed with 1152x1152 PNG at
`%LOCALAPPDATA%/Temp/sdnui-native-only-auto-vae-tiling-20261105/mcp-home/outputs/extras/2026-11-05/00000-4245.png`.
No Python runtime/payload was involved. Visual review found no obvious tile
seams. On the 4 GiB GTX 1650 SUPER this request took about 23 minutes (roughly
1.5 GiB VRAM, GPU near saturation), so it demonstrates functional bounded
inference, not interactive performance. MCP progress remained at 0% while the
worker computed. The temporary image was not packaged as a ZIP or release.

### RealESRGAN inference performance follow-up (2026-11-05)

`CudaRealEsrganX4Plus` now defaults its RRDB convolutions to the tested
`CudaSession.conv2dFast` FP32 FMA/im2col/GEMM path; direct convolution remains
selectable for comparison. The opt-in hardware `CudaRealEsrganX4PlusSuite`
compared complete RRDB output with the official `.pth` weights and a deterministic
32x32 input: 1/1 passed, max channel error <=1/255.

On the same packaged NUI `upscale_extras` request as the previous test (768px
input, x4 model, requested 1.5x output, CUDA, 4 GiB GTX 1650 SUPER), the optimized
path completed in 206.4 seconds versus about 23 minutes with direct convolution
(about 6.7x faster). Both outputs are 1152x1152. Across all channel samples,
max absolute difference was 1/255 and mean absolute difference was 0.000111;
visual review found no obvious seams. Observed VRAM was about 1.7 GiB. The core
tile remains 128px; 256px exceeded the 1 GiB CUDA session budget. Optimized PNG:
`%LOCALAPPDATA%/Temp/sdnui-native-only-realesrgan-tile256-20261105/mcp-home/outputs/extras/2026-11-05/00002-4245.png`.
This is one model, image and GPU, not a broad performance benchmark or release
package.

### RealESRGAN Anime 6B support (2026-11-05)

The native RRDB loader and CUDA inference now support both the 23-block
RealESRGAN x4plus model and the official six-block Anime 6B model. The loader
detects the block count from checkpoint tensor names and validates the matching
weight shapes. NUI's `RealESRGAN_x4plus_anime_6B` choice resolves in standalone
Extras and img2img Hires Fix paths.

The official Anime 6B `.pth` checkpoint passed the six-block loader test. A
fresh Scala-Torch worker distribution then ran the native-only NUI MCP
`upscale_extras` plugin with the Anime 6B choice on CUDA `cuda:0`. A 64x64
source completed at requested scale 1.5 and produced a 96x96 image. Total
packaged-app startup plus job time was 8.4 seconds. This verifies a small image
smoke path; full-size image quality and performance remain unmeasured.

The registered NUI `img2img_hires_fix` AlwaysOn path was also exercised through
the packaged MCP server. A 256x256, 12-step SD1.5 txt2img request used
`RealESRGAN_x4plus_anime_6B` at 1.5x, then ran eight refinement steps at 0.25
denoise and saved a 384x384 image. MCP reported `img2img_hires step 2/2`; the
selected upscaler/config appeared in infotext. CUDA ran on the 4 GiB GTX 1650
SUPER (`cuda:0`), total app startup plus generation was 107.6 seconds, and
observed GPU use during the run was 1962/4096 MiB. Output SHA-256:
`E9E127B7B389FE9AA54058983CDBBD1D8CDE7D05FE54504F8329FB7084AE2A38`.
This verifies one low-resolution NUI HiresFix workflow, not broad image-quality
parity or other upscaler architectures.

The same HiresFix route was exercised after a masked img2img/inpaint request.
The 4-channel SD1.5 Diffusers model processed a 384x384 source and white
elliptical mask (8 Euler steps, 0.75 denoise, mask blur 4), then ran Anime 6B
at 1.25x and four refinement steps at 0.2 denoise. Packaged MCP completed with
`img2img_hires step 1/1` and saved a 480x480 PNG. Startup plus job took 115.7
seconds; in-run GPU samples were 1846-2050/4096 MiB at 87-95% utilization (not
a peak measurement). Output SHA-256:
`484C3E3C93F767D7FE594A7C2E27971229D1344831716D9ED9A6605A79ACF4B6`.
This does not yet cover nine-channel inpaint checkpoints, other mask shapes, aspect ratios or masked latent Hires.
The full-resolution crop/paste variant was then run with the same NUI request,
`inpaint_full_res=true` and padding 32. Infotext retained `Inpaint area: Only
masked` and `Masked area padding: 32`. The 4-channel SD1.5 inpaint plus Anime 6B
1.25x upscale/refinement completed on CUDA in 115.9 seconds including packaged
app startup and produced a 480x480 PNG. SHA-256:
`EA47DD04E4B90A052CEC97D896FE831DE5F84C4379E0A398C62D8247792F1F64`.
This covers one full-resolution mask/crop/paste configuration; other mask
shapes, sizes and masked latent Hires remain unverified. Official nine-channel
inpaint is covered below.

## Official nine-channel Diffusers inpaint checkpoint (2026-11-05)

`NativeDiffusionBackend` now accepts Diffusers' `StableDiffusionInpaintPipeline`
model-index class name. A focused regression test first failed with
`unsupported diffusion model family`, then passed after this minimal change.
The test uses a nine-channel fixture and exercises the normal worker load path.

For an end-to-end run, the official `stable-diffusion-v1-5/stable-diffusion-inpainting`
FP16 U-Net was downloaded to a temporary directory; existing SD1.5 text
encoder, tokenizer, VAE and scheduler folders were linked into that directory.
The rebuilt Scala-Torch worker distribution was selected in native-only NUI;
no Python process was involved. MCP completed a 384x384 masked img2img request with full-resolution crop/paste
(padding 32), then completed pixel HiresFix at 1.25x and four refinement steps
at 0.2 denoise. The job reported `img2img_hires step 1/1` and saved 480x480.
Total packaged startup plus generation was 132.8 seconds; concurrent VRAM
samples were about 1.9 GiB of 4 GiB, not a measured peak. SHA-256:
`9F5149E1D89E6D08233AA809F45E505C977CF95B865BC03A0BE468E38AA5D205`.

This verifies one official nine-channel checkpoint and request configuration.
It does not establish output quality parity across prompts, mask geometry,
aspect ratios or higher step counts.

### Cold async dynamic-choice validation (2026-11-05)

The first async generation request can arrive before the engine has started and
refreshed its model catalog. `AlwaysOnInputs` previously rejected any supplied
dynamic choice against that empty list. It now defers the membership check only
when the list is empty; once populated, unknown values are still rejected. A
focused test covers both states and passed 5/5 in `AlwaysOnFilePolicySuite`.

Rebuilt and packaged native-only NUI, then launched a fresh MCP home whose first
catalog-dependent call was `img2img_async` with explicit `upscaler: Lanczos`.
The call was accepted as `waiting_for_engine`; after startup, the official
nine-channel inpaint request completed with full-resolution mask crop/paste and
HiresFix (`img2img_hires step 1/1`) at 1.25x, saving 480x480. No Python process
was involved. Packaged startup plus generation took 79.4 seconds. Output
SHA-256: `B37EB312B9BF3E2011E819A1784011104E19E437BE1F2C5ED4C6ABED78F3BA23`.
The whole MCP module suite passed: 101 passed, one skipped, zero failures.

### Native-only SD1.5 512px CUDA profile (2026-11-05)

Launched the extracted native-only package using `SDNUI.exe --mcp` in a fresh
MCP home and generated a real SD1.5 512x512 image with Euler, four steps, seed
4281. The request completed in 70.2 seconds including package and engine
startup. Output SHA-256
`58F2B3EF5419CBAB24EEF396AF040B84AF4D0A78B21519DDE33FA92678073173`:
`C:\Users\Mizushima\AppData\Local\Temp\sdnui-async-dynamic-upscaler-fix-20261105\mcp-home-profile-sd15-512\outputs\txt2img\2026-11-05\00000-4281.png`.

The CUDA profile reported 410.3 MiB retained in the U-Net offload cache,
2,872.2 MiB cache hits and 23,358.3 MiB uploaded over 2,149 calls. Upload and
allocation took 7.998s; FP32 attention score kernels took 7.992s. These profile
times are aggregated and can overlap. This is a low-step runtime smoke test,
not a normal-step speed or image-quality parity result. Profile attention and
host-to-device transfer separately before changing cache limits.

### Native-only SD1.5 512px, 20-step generation (2026-11-05)

Packaged NUI MCP generated from the real SD1.5 model on the 4 GiB GTX 1650
SUPER: 512x512, Euler, 20 steps, CFG 7, seed 4282. The request completed all
steps in 247.1 seconds including app and engine startup. Output SHA-256
`EA62D1F9B050AE16A380C5A7F0387984E18E772119540CEDE2A301C877798D83`:
`C:\Users\Mizushima\AppData\Local\Temp\sdnui-async-dynamic-upscaler-fix-20261105\mcp-home-profile-sd15-512-20step\outputs\txt2img\2026-11-05\00000-4282.png`.
The single output depicts the requested red barn and green field; this is not a
multi-prompt quality or parity benchmark.

CUDA profile totals were 39.910s for attention scores (2,680 calls), 16.871s
for softmax preparation, 7.685s for attention output, and 39.377s for offloaded
weight upload/allocation (8,899 calls, 115,150.3 MiB uploaded). The cache held
410.3 MiB and recorded 16,002.2 MiB of hits. Profile totals overlap overall
execution and are not additive wall time. Attention and weight transfers remain
major optimization targets.

## Experimental fused CUDA attention evaluation (2026-11-05)

Added an experimental fused dense-attention PTX kernel based on the
FlashAttention IO-aware goal of avoiding global score materialization. It uses
one 256-thread CTA per query row, bounded shared-memory scores for up to 4096
keys and FP32 max-subtracted softmax. It is not the paper's online-softmax
recurrence ([Dao et al., 2022](https://arxiv.org/abs/2205.14135)). Longer key
sequences fall back to the existing sliced kernels.

The CUDA dense-attention suite passed 4/4 with opt-in disabled and enabled. It
covers GQA, unequal widths, NaN propagation, 4096/4097 key dispatch, fallback
for value width 257, CPU parity and allocation cleanup. The feature requires
`SCALATORCH_CUDA_FUSED_ATTENTION=1` and remains off by default. Packaged
native-only SD1.5 512x512, Euler 20-step generation completed in 252.8s
including startup, versus 247.1s for the earlier legacy path. The fused profile
reported 71.453s for 1,280 U-Net calls and 0.850s for VAE attention; legacy
score, softmax and output profiles totaled 64.466s. Profile counters overlap
wall time, and this is one run comparison, so it does not prove a wall-time
regression; it does fail to show the intended speedup.

Output SHA-256:
`2246434F82AE5252FDBC12FF98C0FA13335F7AB264852F3C98118FB179E2A0FA` at
`C:\Users\Mizushima\AppData\Local\Temp\sdnui-fused-attention-20261105\mcp-home-packaged-sd15-20step\outputs\txt2img\2026-11-05\00000-4283.png`.
Sampled VRAM reached 1,962/4,096 MiB; the samples can miss brief peaks.
Rebuilt default-off native-only NUI packaged and completed a 512x512 four-step
smoke in 70.7s, with `attention_scores_fp32` in the profile. ZIP SHA-256
`1dc1f0fdf766facfdb207679989df3112beb7d10e5738731c39ef17d6fca3d48`.
Warp-level reduction and multi-query tiling remain needed before considering
this kernel for default dispatch.

The same native-only app image was verified with `scala_torch_java` explicitly
set to its bundled `scala-torch/jre/bin/java.exe`. The packaged worker generated
512x512 SD1.5 with Euler, four steps, seed 4285 on `cuda:0` in 71.0s including
startup. Its profile used the default legacy `attention_scores_fp32` path.
Output SHA-256 `CC59F610AFFBCD5CDDFA070196B4BC5C2D06873C518A0EBF379BB87AE1E02DEE`.
The app image contained zero Python executables, `.py` or `.pyc` files.

Final native-only package after adding the fused value-width fallback guard has
ZIP SHA-256
`6D8245C5C78B93E2C3086D16967B8BD162C9C05EBC184FAB4A3751AAA896DE90` and zero
Python executables, `.py` or `.pyc` files in its app image. With the bundled
`scala-torch/jre/bin/java.exe` and worker jars, NUI MCP generated SD1.5 at
512x512, Euler four steps, seed 4286 in 71.1s including startup on `cuda:0`.
The profile confirmed default legacy attention dispatch. Output SHA-256
`BB1E44DB8F5202214A83E894A095779564F6632A0CCE4F7B28650F8DF1AE23E9`.

### Warp-shuffle attention experiment (2026-11-05)

Tried one 32-thread warp per query row with `shfl.sync.bfly` for max/sum
reductions. NVIDIA documents `shfl.sync` for `sm_30` and newer, compatible
with Scala-Torch's `sm_50` PTX floor ([PTX ISA](https://docs.nvidia.com/cuda/parallel-thread-execution/)).
The opt-in CUDA attention suite passed 4/4; packaged native-only SD1.5 at
512x512, Euler 20 steps completed in 265.5s including startup. Profile time
was 82.319s for 1,280 fused calls. The preceding 256-thread CTA variant took
252.8s overall and 71.453s attention time. Since warp lanes process eight
times more keys serially, this version was reverted. Source remains on the
256-thread experimental path, disabled by default. Next investigate a
multi-query-per-CTA tile for K/V reuse while preserving query parallelism.
Seed 4287 output was 512x512, SHA-256
`776438C4D3EFA573197B15FC69E2A9D6BF1CC6D037318FC60954DDE66AD6C141`.
Experimental native-only ZIP SHA-256:
`1D514EDA5D28146582B10C4DFDD5415A6288448C306FBF73A4D57FEECB6E6786`.
Device-wide VRAM samples reached 2,040/4,096 MiB during the warp run; brief peaks may have been missed.
