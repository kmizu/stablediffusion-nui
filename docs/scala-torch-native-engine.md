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
