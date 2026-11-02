# State Manager V2 import

Upstream 2af62ec stores gzip bytes for both shared history.txt and browser IndexedDB. The decompressed object contains defaults by hash and entries by createdAt; each entry stores changed component settings and quick settings. Reconstruct an effective field map from its referenced defaults overridden by componentSettings. Preserve raw JSON and unmapped values; never silently claim full A1111 extension equivalence. Reject malformed/duplicate JSON and oversized compressed or expanded data.

1. Test synthetic source-derived gzip payloads, default merging, explicit override, exact large integer seeds, unrecognized settings, truncated archives, missing defaults and size limits.
2. Implement a bounded parser and explicit native mappings. Preserve all unknown raw data so it can be inspected; errors must not mutate the native history.
3. Build an explicit user-reviewed import flow with safe merge, collision/idempotence handling, group semantics, resource preflight and clear reporting of unsupported fields. Ensure import never silently discards history on the 50-entry cap.
4. Verify native GUI and real SD1.5/SDXL generation. Keep issue open until IndexedDB extraction, complex extension formats and other unimplemented upstream scope are addressed.
