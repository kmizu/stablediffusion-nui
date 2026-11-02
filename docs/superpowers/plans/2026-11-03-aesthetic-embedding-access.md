# Aesthetic embedding access implementation plan

> **For agentic workers:** Use superpowers:executing-plans or subagent-driven-development; check tasks against the source and issue before marking them complete.

**Goal:** Let users and MCP agents create and select Aesthetic Gradients embeddings from images, while preserving the existing CLI and generation behavior.

**Architecture:** Reuse the `aesthetic_embedding` worker operation and the existing CLIP encoder. Expose it through an owned, cancellable engine request; build a desktop control and an MCP tool using their existing input and path policies. Publication is staged and atomic, with explicit overwrite and cancellation ownership. Do not treat these additions as completing #2 until its entire documented scope is reviewed and verified.

**Tech stack:** Scala 3, JavaFX, JSON Lines engine, Python/PyTorch, MCP.

**Spec:** GitHub issue #2, `docs/aesthetic-gradients-verification.md`, `docs/port-agent-brief.md`.

## Constraints and review focus

- Do not copy unlicensed upstream code; preserve existing formats and CLI.
- Constrain MCP image and output paths using the existing path policy; protected sessions must not leak plaintext.
- Cancellation, a concurrent file creation, a failed save and explicit replacement must not destroy an existing embedding.
- Avoid leaving decoded temporary inputs, leaked credentials, or a worker job after cancellation.
- Verify both model families on real images and inspect them; distinguish unavailable upstream interoperability and other existing limitations.

## Tasks

- [ ] Add failing publication tests for interrupted, conflicting and failed writes, then implement atomic staged publication and test real saved tensors.
- [ ] Add owned/cancellable engine access and tests of worker request, cancellation and publication ownership.
- [ ] Add MCP list/create tool with explicit schema, path checks, protected-session behavior and temporary cleanup; test external SDK and refusals.
- [ ] Add JavaFX creation controls and selectable existing embeddings; test real UI, failure states, no-overwrite, and refresh.
- [ ] Test full Scala/Python suites, SD1.5 and SDXL end-to-end image creation/use, output inspection and extracted Windows package; document limitations, update ledger only when its acceptance rules are met, review, and integrate.
