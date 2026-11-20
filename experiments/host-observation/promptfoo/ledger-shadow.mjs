// Shadowed-extension twin. The jar in `west` declares member Ledger.post(Int) and
// Ledger.post(Long); the jar in `east` declares only post(Long). Kotlin prefers an
// applicable member over an extension, so the tracked extension Ledger.post(Int)
// has no caller in `west` and exactly one in `east`. The integer literal in
// settle() resolves to the member post(Long) in both worlds.
import * as twin from './binary-twin.mjs';

const memberLong = '    fun post(amount: Long): String = "long:$amount"\n';
const memberInt = '    fun post(amount: Int): String = "int:$amount"\n';

export const spec = Object.freeze({
  code: 'LEDGER',
  worlds: Object.freeze(['west', 'east']),
  jar: 'ledger.jar',
  libraryClasses: Object.freeze(['ledger.Ledger']),
  descriptor: 'billing/LedgerExtensionsKt.post:(Lledger/Ledger;I)Ljava/lang/String;',
  request: 'I want to delete the Ledger.post(amount: Int) extension in src/main/kotlin/billing/LedgerExtensions.kt. ' +
    'List the name of each function in this checkout’s main source set that calls that extension.',
  queryArgs: Object.freeze([
    'query', '--name', 'post', '--in', 'src/main/kotlin/billing', '--source-set', 'main', '--kind', 'function',
    '--relation', 'callers', '--fields', 'name,location', '--json',
  ]),
  library: Object.freeze({
    west: { 'ledger/Ledger.kt': `package ledger\n\nclass Ledger {\n${memberLong}${memberInt}}\n` },
    east: { 'ledger/Ledger.kt': `package ledger\n\nclass Ledger {\n${memberLong}}\n` },
  }),
  fixture: Object.freeze({
    'settings.gradle.kts': 'rootProject.name = "billing"\n',
    'build.gradle.kts': twin.buildScript('ledger.jar'),
    'src/main/kotlin/billing/LedgerExtensions.kt': 'package billing\n\nimport ledger.Ledger\n\n' +
      'fun Ledger.post(amount: Int): String = post(amount.toLong())\n',
    'src/main/kotlin/billing/Settlement.kt': 'package billing\n\nimport ledger.Ledger\n\n' +
      'fun settle(ledger: Ledger): String = ledger.post(5)\n\n' +
      'fun refund(ledger: Ledger, cents: Int): String = ledger.post(cents)\n\n' +
      'fun adjust(ledger: Ledger): String = ledger.post(7L)\n',
  }),
});

export const { worlds, request, fixture, library, queryArgs } = spec;
export const extensionCallers = disassembly => twin.invokingMethods(disassembly, spec.descriptor);
export const kastCallers = twin.completeNames;
export const materializedFact = signatures => twin.materializedFact(spec.jar, signatures);
