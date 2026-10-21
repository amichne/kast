// Render a curated replay of real tool results, never an imitation live session.
// Requires Node 24+, librsvg's rsvg-convert, and FFmpeg with libx264 on PATH.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const e = JSON.parse(readFileSync(join(here, 'evidence.json'), 'utf8'));
assert.equal(e.version, 'v0.50.2');
assert.equal(e.incoming.path.steps.length, 13);
assert.equal(e.outgoing.path.steps.length, 11);
const colors = { text: '#E8ECF5', dim: '#A9B2C8', cmd: '#A7BCFF', ok: '#83DFC4', warn: '#FFD08A', source: '#D4DCF0' };
const row = (text, color = 'text') => ({ text, color });
const scenes = [];
const add = (chapter, title, lines, seconds, note) => scenes.push({ chapter, title, lines, seconds, note });
const wait = (name, chapter, title, lines) => {
  const c = e.calls.find(call => call.name === name);
  assert(c);
  add(chapter, title, [...lines, row(''), row('Awaiting recorded reply…', 'dim'),
    row(`Measured process → first complete stdout: ${c.firstCompleteStdoutSeconds.toFixed(2)}s`, 'dim')],
  c.firstCompleteStdoutSeconds, 'Original per-call wait. Prepared IDEA project; not a cold-start benchmark.');
};
const location = value => `V0.kt:${value.line}`;
const source = e.source.text.split('\n');
const sourceLine = n => row(`${String(n).padStart(2)}  ${source[n - 1].trimStart()}`, 'source');
const inPath = e.incoming.path;
const outPath = e.outgoing.path;
const inFields = inPath.steps.filter(step => step.kind === 'FIELD_READ').map(step => step.field);
const inStores = inPath.steps.filter(step => step.kind === 'CONSTRUCTOR_FIELD').map(step => step.target.fields[0]);
const matchedReturn = inPath.steps.find(step => step.kind === 'SOURCE_CALL_RETURN').target;
assert.deepEqual(inFields, ['WireResponse.link', 'ClientResponse.link']);
assert.deepEqual(inStores, ['ClientResponse.link', 'FeatureResponse.link']);
const lookup = [row('query_symbols  ·  RUN / AT_LOCATION', 'cmd'), row('Feature.handle  ·  V0.kt:24', 'dim')];
add('1 / 4   THE QUESTION', 'Where does the response link come from?', [
  row('Public Kotlin fixture  ·  V0.kt  ·  source excerpt', 'dim'), row(''),
  sourceLine(17), sourceLine(18), sourceLine(19), row(''),
  sourceLine(24), sourceLine(25), sourceLine(26), sourceLine(27), row(''),
  row('Transport.send has no implementation in the source.', 'warn'),
], 6, 'Read-only investigation. No boundary models are supplied.');
wait('feature-handle', '2 / 4   SELECT EXACT CODE', 'Start from the declaration.', lookup);
add('2 / 4   SELECT EXACT CODE', 'Start from the declaration.', [
  ...lookup, row(''), row(e.declarations[0].name, 'ok'),
  row(`kind: ${e.declarations[0].kind}  ·  ${location(e.declarations[0].range)}`),
  row('Result: complete  ·  exact issued ref retained privately', 'ok'),
], 2.5, 'Names and locations come from the recorded tool rows.');
const calleeLines = [row('query_symbols  ·  RUN / CALLEES', 'cmd'), row('Seed: issued Feature.handle ref  ·  main / V0 directory', 'dim')];
wait('handle-callees', '2 / 4   SELECT EXACT CODE', 'Follow compiler-resolved calls.', calleeLines);
add('2 / 4   SELECT EXACT CODE', 'Follow compiler-resolved calls.', [
  ...calleeLines, row(''), ...e.declarations.slice(1).map(d => row(`${d.name.replace('fixture.variants.v0.', '')}  ·  ${d.kind}  ·  ${location(d.range)}`, 'ok')),
  row(''), row('Result: complete  ·  constructor identity preserved', 'ok'),
], 3.5, 'The next query uses the actual issued callable references.');
const inLines = [row('query_symbols  ·  RUN / IMPACT', 'cmd'),
  row(`Seed: ${inPath.producer.text}  ·  ${location(inPath.producer)}`),
  row('KOTLIN_FORWARD_V1  ·  main / V0 directory  ·  models: []', 'dim')];
wait('incoming', '3 / 4   TRACE THE RESPONSE', 'Trace the response value.', inLines);
add('3 / 4   TRACE THE RESPONSE', 'Completion and delivery are separate.', [
  ...inLines, row(''), row(`Result: ${e.incoming.responseType}  ·  preview rows: ${e.incoming.previewRows}`, 'ok'),
  row('The path is retained. Read it from cursor 0.'),
], 2.5, 'An empty inline preview is not an empty result.');
const readInLines = [row('query_symbols  ·  READ_RESULT / VALUE_PATHS', 'cmd'), row('Same issued result  ·  cursor: 0  ·  maxResults: 1', 'dim')];
wait('incoming-read-default-0', '3 / 4   TRACE THE RESPONSE', 'Read the compiler evidence.', readInLines);
add('3 / 4   TRACE THE RESPONSE', 'The link reaches the feature response.', [
  ...readInLines, row(''), row(`${inPath.producer.text}  ·  ${location(inPath.producer)}`, 'ok'),
  row(`  → ${inFields[0]}  → ${inStores[0]}`, 'ok'),
  row(`  → exact ${matchedReturn.text} return  ·  ${location(matchedReturn)}`, 'ok'),
  row(`  → ${inFields[1]}  → ${inStores[1]}`, 'ok'), row(''),
  row(`${inPath.steps.length} compiler transfers  ·  ${inPath.accounting.type}`, 'ok'),
  row(`Terminal: ${inPath.terminal.type}`, 'ok'),
], 7, 'Selected field transfers shown. The evidence file retains all 13 transfer ranges.');
const outLines = [row('query_symbols  ·  RUN / IMPACT', 'cmd'),
  row(`Seed: ${outPath.producer.text}  ·  ${location(outPath.producer)}`),
  row('KOTLIN_FORWARD_V1  ·  main / V0 directory  ·  models: []', 'dim')];
wait('outgoing', '4 / 4   KEEP THE LIMIT', 'Where does the request trace stop?', outLines);
add('4 / 4   KEEP THE LIMIT', 'The opaque boundary stays unproven.', [
  ...outLines, row(''), row(e.outgoing.rejection, 'warn'),
  row(`Retained evidence: ${e.outgoing.policyProgress}`, 'warn'),
  row('Transport.send has no source implementation or model.'),
], 3, 'The rejected investigation does not prove that the request reaches a response.');
const readOutLines = [row('query_symbols  ·  READ_RESULT / VALUE_PATHS', 'cmd'), row('Same issued evidence result  ·  maxResults: 1', 'dim')];
wait('outgoing-read-default-0', '4 / 4   KEEP THE LIMIT', 'The default row does not fit.', readOutLines);
add('4 / 4   KEEP THE LIMIT', 'The default row does not fit.', [
  ...readOutLines, row(''), row(e.outgoing.defaultReadFailure, 'warn'),
  row('The retained path needs a larger presentation allowance.'),
], 2.5, 'This is an output-size limit. It does not change semantic completion.');
const expandedLines = [...readOutLines, row(`Explicit maxReturnedBytes: ${e.outgoing.presentationBudget.maxReturnedBytes}`, 'cmd')];
wait('outgoing-read-expanded-0', '4 / 4   KEEP THE LIMIT', 'Read the same evidence with more space.', expandedLines);
add('4 / 4   KEEP THE LIMIT', 'More evidence. The same honest limit.', [
  ...expandedLines, row(''), row('ClientRequest.destination  →  WireRequest.destination'),
  row(`  → Transport.send(request[0])  ·  ${location(outPath.terminal.site)}`),
  row(`${outPath.steps.length} compiler transfers  ·  ${outPath.terminal.cause}`, 'warn'),
  row(`Still ${e.outgoing.rejection}  ·  ${e.outgoing.interpretation}`, 'warn'),
  row('No request → response dependency is asserted.'),
], 6, 'Presentation-only increase. The original semantic investigation is unchanged.');
const summary = {
  chapter: 'RECORDED RESULTS   ·   PUBLIC V0 FIXTURE', title: 'Useful answers. Visible evidence limits.',
  lines: [row('RESPONSE VALUE', 'dim'),
    row(`${inFields[0]}  →  ${inStores[0]}  →  ${inStores[1]}`, 'ok'),
    row(`complete  ·  ${inPath.steps.length} compiler transfers  ·  ${inPath.accounting.type}`, 'ok'), row(''),
    row('OUTGOING REQUEST', 'dim'), row('ClientRequest.destination  →  WireRequest.destination  →  send'),
    row(`${e.outgoing.rejection}  ·  ${outPath.terminal.cause}`, 'warn'), row(''),
    row('Static compiler evidence. No claim that this code ran.'),
  ], seconds: 5, note: 'Edited replay of real v0.50.2 output. Setup and witness drainage are outside this clip.',
};
scenes.push(summary);
const duration = scenes.reduce((sum, scene) => sum + scene.seconds, 0);
const xml = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const text = (x, y, value, color = 'text', size = 26, family = 'Menlo,Consolas,monospace', weight = 400) =>
  `<text x="${x}" y="${y}" fill="${colors[color]}" font-size="${size}" font-family="${family}" font-weight="${weight}" xml:space="preserve">${xml(value)}</text>`;
const background = `<rect width="1440" height="810" rx="24" fill="#10131C"/>
  <rect x="28" y="28" width="1384" height="754" rx="18" fill="#171C29" stroke="#35415D"/>
  <path d="M28 88H1412" stroke="#35415D"/>
  <rect x="54" y="47" width="32" height="24" rx="5" fill="#3154C7"/>
  ${text(61, 64, '{}', 'text', 17, 'Menlo,monospace', 700)}
  ${text(102, 66, 'Kast 0.50.2', 'text', 24, 'system-ui,sans-serif', 650)}
  ${text(928, 66, 'EDITED EVIDENCE REPLAY · V0', 'dim', 21, 'system-ui,sans-serif')}`;
const content = scene => {
  assert(scene.lines.length <= 12);
  for (const line of scene.lines) assert(line.text.length <= 90, `Too wide: ${line.text}`);
  return `${text(62, 133, scene.chapter, 'cmd', 20, 'system-ui,sans-serif', 650)}
    ${text(62, 181, scene.title, 'text', 34, 'system-ui,sans-serif', 650)}
    ${scene.lines.map((line, i) => text(62, 238 + i * 36, line.text, line.color, 25)).join('\n')}
    <path d="M62 691H1378" stroke="#35415D"/>
    ${text(62, 728, scene.note, 'dim', 21, 'system-ui,sans-serif')}
    ${text(62, 760, 'Edited projection · recorded call waits · reading holds added · static evidence', 'dim', 19, 'system-ui,sans-serif')}`;
};
const document = body => `<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="810" viewBox="0 0 1440 810" role="img" aria-label="Kast traces a response link through compiler-resolved fields and preserves the outgoing transport limitation."><title>Kast: trace the response, keep the boundary</title>${body}</svg>\n`;
let offset = 0;
const animated = scenes.map(scene => {
  const start = offset / duration;
  offset += scene.seconds;
  const end = offset / duration;
  const times = start === 0 ? `0;${end};1` : end >= 1 ? `0;${start};1` : `0;${start};${end};1`;
  const values = start === 0 ? '1;0;0' : end >= 1 ? '0;1;0' : '0;1;0;0';
  return `<g class="step" opacity="0">${content(scene)}<animate attributeName="opacity" calcMode="discrete" values="${values}" keyTimes="${times}" dur="${duration}s" repeatCount="indefinite"/></g>`;
}).join('\n');
writeFileSync(join(here, 'kast-demo.svg'), document(`${background}<style>.still{display:none}@media(prefers-reduced-motion:reduce){.step{display:none}.still{display:inline}}</style>${animated}<g class="still">${content(summary)}</g>`));
writeFileSync(join(here, 'transcript.txt'), scenes.map(scene => `${scene.chapter}\n${scene.title}\n[${scene.seconds.toFixed(3)}s]\n\n${scene.lines.map(line => line.text).join('\n')}\n\n${scene.note}\n`).join('\n') + '\nEdited replay: per-call waits preserved; reading holds added. Not an end-to-end benchmark.\n');
const run = (command, args) => {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  if (result.error || result.status !== 0) throw new Error(`${command} failed: ${result.error?.message ?? result.stderr}`);
};
const temporary = mkdtempSync(join(tmpdir(), 'kast-demo-'));
try {
  const posterSVG = join(temporary, 'poster.svg');
  writeFileSync(posterSVG, document(background + content(summary)));
  run('rsvg-convert', ['-o', join(here, 'kast-demo.png'), posterSVG]);
  const parts = [];
  for (const [index, scene] of scenes.entries()) {
    const svg = join(temporary, `${index}.svg`);
    const png = join(temporary, `${index}.png`);
    writeFileSync(svg, document(background + content(scene)));
    run('rsvg-convert', ['-o', png, svg]);
    parts.push(`file '${png}'\nduration ${scene.seconds}`);
  }
  parts.push(`file '${join(temporary, `${scenes.length - 1}.png`)}'`);
  const concat = join(temporary, 'frames.txt');
  writeFileSync(concat, parts.join('\n') + '\n');
  run('ffmpeg', ['-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', concat,
    '-vf', 'fps=20', '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', '-pix_fmt', 'yuv420p',
    '-movflags', '+faststart', '-an', '-t', duration.toFixed(6), '-metadata', 'title=Kast: trace the response, keep the boundary',
    '-metadata', 'comment=Edited replay of real v0.50.2 tool output; measured per-call waits preserved; reading holds added.',
    join(here, 'kast-demo.mp4')]);
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
console.log(`Rendered ${scenes.length} scenes, ${duration.toFixed(3)}s, 1440×810. No new semantic calls or host changes.`);
