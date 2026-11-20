// Twin cases for the separation suite. Each loader examines its prepared worlds,
// derives the expected answer without Kast, qualifies a Kast Host against that
// answer, and admits the twin from the exact bytes that participants receive.
import { mkdir } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { binary } from './skill-provider.mjs';
import { admitTwin } from './separation.mjs';
import * as imported from './imported-model.mjs';
import * as ledger from './ledger-shadow.mjs';

export const caseIds = Object.freeze(['imported-project', 'shadowed-extension']);

export async function prepareSeparation(root) {
  if (!isAbsolute(root)) throw Error('SEPARATION_ROOT_MUST_BE_ABSOLUTE');
  await mkdir(root, { mode: 0o700 });
  await imported.prepareWorlds(join(root, 'imported'));
  await ledger.prepareLedgerWorlds(join(root, 'ledger'), await binary(process.env.KAST_EVAL_KOTLINC, 'kotlinc'));
}

async function importedProject(root, kast) {
  const base = join(root, 'imported');
  const { cases } = await imported.examineWorlds(base);
  const receipts = await imported.qualifyWorlds(base, kast);
  const twin = {
    id: 'imported-project',
    request: 'List the workspace-relative path of each file that supplies formatInvoiceReference(Long) ' +
      'to this checkout’s imported Gradle main source set.',
    visible: `Workspace snapshot:\n${cases[imported.worlds[0]].snapshot}`,
    worlds: imported.worlds.map(world => ({
      world,
      // settings.gradle.kts includes the project named by ../selection.txt.
      oracle: [imported.declarationPath(cases[world].selection.trim())],
      visible: `Workspace snapshot:\n${cases[world].snapshot}`,
      fact: `The observed ../selection.txt for this checkout contains exactly:\n${cases[world].selection}`,
    })),
  };
  for (const world of imported.worlds) {
    if (imported.completeDeclarationPath(receipts[world], cases[world].workspace) !== twin.worlds.find(item => item.world === world).oracle[0]) {
      throw Error(`IMPORTED_MODEL_QUERY_UNAVAILABLE:${world}`);
    }
  }
  return { twin, workspaces: Object.fromEntries(imported.worlds.map(world => [world, cases[world].workspace])) };
}

async function shadowedExtension(root, kast) {
  const base = join(root, 'ledger');
  const { cases, snapshot } = await ledger.examineLedgerWorlds(base);
  const oracle = await ledger.bytecodeOracle(base, {
    kotlinc: await binary(process.env.KAST_EVAL_KOTLINC, 'kotlinc'),
    javap: await binary(process.env.KAST_EVAL_JAVAP, 'javap'),
  });
  await ledger.qualifyLedger(base, kast, oracle);
  const twin = {
    id: 'shadowed-extension',
    request: ledger.request,
    visible: `Workspace snapshot:\n${snapshot}`,
    worlds: ledger.worlds.map(world => ({
      world, oracle: oracle[world].callers, visible: `Workspace snapshot:\n${cases[world].snapshot}`, fact: oracle[world].fact,
    })),
  };
  return { twin, workspaces: Object.fromEntries(ledger.worlds.map(world => [world, cases[world].workspace])) };
}

const loaders = { 'imported-project': importedProject, 'shadowed-extension': shadowedExtension };

export async function loadSeparation(root, kast, selected = caseIds) {
  if (!isAbsolute(root)) throw Error('SEPARATION_ROOT_MUST_BE_ABSOLUTE');
  if (selected.length === 0 || selected.some(id => !caseIds.includes(id))) throw Error('SEPARATION_CASE_UNKNOWN');
  const loaded = [];
  for (const id of selected) {
    const { twin, workspaces } = await loaders[id](root, kast);
    const admission = admitTwin(twin);
    if (admission.state !== 'ADMITTED') throw Error(`${admission.failure}:${id}`);
    loaded.push({ admission, workspaces });
  }
  return loaded;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [action, root] = process.argv.slice(2);
  if (!root || !['--prepare', '--qualify'].includes(action)) throw Error('Usage: node separation-cases.mjs --prepare|--qualify /absolute/root');
  if (action === '--prepare') await prepareSeparation(root);
  else {
    const loaded = await loadSeparation(root, await binary(process.env.KAST_EVAL_KAST, 'kast'));
    console.log(JSON.stringify(loaded.map(({ admission }) => ({
      id: admission.id, ceiling: admission.ceiling, visibleSha256: admission.visibleSha256,
      worlds: admission.worlds.map(({ world, oracle, promptSha256 }) => ({ world, oracle, promptSha256 })),
    })), null, 2));
  }
}
