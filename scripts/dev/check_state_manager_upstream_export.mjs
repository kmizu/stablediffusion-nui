// Validate an exported gzip through the pinned upstream decompressor and memory-storage initialization.
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import vm from 'node:vm';
const [sourcePath, exportPath] = process.argv.slice(2);
if (!sourcePath || !exportPath) throw Error('usage: node check_state_manager_upstream_export.mjs upstream.js exported.gz');
const source = readFileSync(sourcePath);
if (createHash('sha256').update(source).digest('hex') !== 'e128d7f6d4c9372aa3a01ff8a8e10ac0175fa5710b393ef6b9a729815a01c080') throw Error('unexpected upstream source');
const context = vm.createContext({window: {indexedDB: {open: () => ({})}}, gradioApp: () => ({}),
  onUiLoaded: () => {}, onAfterUiUpdate: () => {}, setTimeout: () => 0,
  AbortController, Blob, CompressionStream, DecompressionStream, TextEncoder, TextDecoder, Uint8Array, console});
vm.runInContext(source.toString('utf8'), context, {filename: 'upstream-statemanager.js', timeout: 10000});
const sm = context.window.stateManager;
const stored = JSON.parse(await sm.utils.decompress(readFileSync(exportPath)));
sm.api.get = async () => ({hash: 'current-default', contents: {}});
await sm.initMemoryStorage(stored);
const entry = sm.memoryStorage.entries.data['1700000000123'];
if (!entry || entry.componentSettings['txt2img/Prompt'] !== 'actual upstream saved prompt' ||
    sm.memoryStorage.savedDefaults[entry.defaults]['customscript/seed.py/txt2img/Seed'] !== 123 ||
    sm.memoryStorage.savedDefaults[entry.defaults]['txt2img/Unknown extension'] !== 'keep unknown') {
  throw Error('upstream did not recover expected original data');
}
console.log('UPSTREAM_EXPORT_READ_OK');
