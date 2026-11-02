import { writeFile } from "node:fs/promises";

// Synthetic data only; use the same Web Streams gzip API documented by State Manager V2.
const payload = {
  defaults: { d: { "txt2img/Prompt": "default", "txt2img/Sampling steps": 20, "txt2img/Unknown": "keep" } },
  entries: { "1700000000123": { saveVersion: "2.0", createdAt: 1700000000123, type: "txt2img", defaults: "d",
    groups: ["history", "favourites"], quickSettings: {}, componentSettings: { "txt2img/Prompt": "web streams fixture" }, preview: null } }
};
const bytes = await new Response(new Blob([JSON.stringify(payload)]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer();
await writeFile(new URL("../../core/src/test/resources/fixtures/state-manager-v2-webstreams.gz", import.meta.url), new Uint8Array(bytes));
