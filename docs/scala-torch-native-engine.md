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
application still defaults to Python. Catalog operations, production launch and
packaging, normal SD checkpoints, GPU inference, SDXL, batching, LoRA, img2img,
Hires and the existing extension feature set still need native implementation
and verification. Unsupported active generation settings currently return errors.
Keep changes on `feat/scala-torch-native-engine` until integration is ready for review.
