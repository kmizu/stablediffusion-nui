// Executes the pinned upstream release's unmodified browser JS with simulated A1111 APIs/UI.
// Supply the downloaded upstream JS and an output path; this does not launch A1111 or access real user data.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import vm from 'node:vm';

const [sourcePath, outputPath] = process.argv.slice(2);
if (!sourcePath || !outputPath) throw Error('usage: node run_state_manager_upstream_fixture.mjs upstream.js output.gz');
const source = readFileSync(sourcePath);
const expected = 'e128d7f6d4c9372aa3a01ff8a8e10ac0175fa5710b393ef6b9a729815a01c080';
if (createHash('sha256').update(source).digest('hex') !== expected) throw Error('unexpected upstream source hash');
const fixedTime = 1700000000123;
class FixedDate extends Date { static now() { return fixedTime; } }
const context = vm.createContext({
  window: { indexedDB: { open: () => ({}) } },
  gradioApp: () => ({}), onUiLoaded: () => {}, onAfterUiUpdate: () => {},
  setTimeout: () => 0, AbortController, Blob, CompressionStream, DecompressionStream,
  TextEncoder, TextDecoder, Uint8Array, Date: FixedDate, console,
});
vm.runInContext(source.toString('utf8'), context, { filename: 'upstream-statemanager.js', timeout: 10000 });
const sm = context.window.stateManager;
const defaults = {
  'txt2img/Prompt/value': 'upstream default', 'txt2img/Negative prompt/value': 'blur',
  'txt2img/Sampling steps/value': 20, 'customscript/seed.py/txt2img/Seed/value': 123,
  'txt2img/Unknown extension/value': 'keep unknown',
};
let saved;
sm.version = '2.0';
sm.api.get = async name => name === 'uidefaults' ? {hash: 'fixture-default', contents: defaults} :
  name === 'quicksettings' ? {settings: {CLIP_stop_at_last_layers: 2}} : {location: 'File'};
sm.api.post = async (name, payload) => {
  if (name !== 'save') throw Error(`unexpected upstream API ${name}`);
  saved = Uint8Array.from(payload.contents.split(',').map(Number));
  return {success: true};
};
await sm.initMemoryStorage({defaults: {}, entries: {}});
for (const [path, value] of Object.entries(sm.memoryStorage.currentDefault.contents)) {
  const current = path === 'txt2img/Prompt' ? 'actual upstream saved prompt' : value;
  sm.componentMap[path] = {entries: [{component: {props: {value: current}}}]};
}
const state = await sm.getCurrentState('txt2img');
sm.saveState(state, 'history');
await sm.updateFileStorage();
if (!saved) throw Error('upstream did not call its save API');
writeFileSync(outputPath, saved);
console.log('UPSTREAM_STATE_FIXTURE_OK');
