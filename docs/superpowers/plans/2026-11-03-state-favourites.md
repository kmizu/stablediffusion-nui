# State Manager: favourites

Pinned upstream 2af62ec supports adding or removing one or several entries from favourites, deleting selected entries, and clearing history while retaining favourites. It does not support bulk applying multiple states. NUI will keep per-mode favourites independently of the 50-entry history cap, preserve shared thumbnails until no group references them, and expose a Favourites tab with the same single-entry restore controls. Explicit delete removes an entry from both groups. Use a separate native collection in the same revision-checked library, maintaining UUIDs and existing history compatibility.

1. Add failing storage tests for mixed/all selection, history eviction, clear, delete, persistence, thumbnail lifetime and mode isolation; run before implementation.
2. Add bounded favourite collections and transactional single-I/O-thread mutations; errors leave stored history and thumbnails intact.
3. Add native multi-selection favourite toggle, favourite list, existing restore support and Japanese instructions. Test actual JavaFX controls including cancel/confirm and multiple selections.
4. Run Scala tests, real GUI SD1.5 / SDXL and inspect resulting images; record remaining gaps and keep issue open until fully implemented.
