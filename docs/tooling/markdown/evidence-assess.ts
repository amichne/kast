import {assessEvidence, assessmentAccepted, examinePageAgreement, pageAgreementAccepted} from './evidence-native.ts';

const args = process.argv.slice(2);
const rootIndex = args.indexOf('--root');
const scopeIndex = args.indexOf('--scope');
const root = rootIndex < 0 ? undefined : args[rootIndex + 1];
const scope = scopeIndex < 0 ? undefined : args[scopeIndex + 1];
const checkPages = args.includes('--check-pages');
const expected = checkPages ? 5 : 4;
if (root === undefined || (scope !== 'public' && scope !== 'canonical') || args.length !== expected) {
  process.stdout.write(JSON.stringify({status: 'failed', stage: 'input', error: {
    name: 'ArgumentsError', message: 'Use --root <absolute-repository-root> --scope public|canonical [--check-pages].',
  }}) + '\n');
  process.exitCode = 2;
} else if (checkPages) {
  const result = await examinePageAgreement({root, scope});
  process.stdout.write(JSON.stringify(result, null, 2) + '\n');
  process.exitCode = pageAgreementAccepted(result) ? 0 : 2;
} else {
  const result = await assessEvidence({root, scope});
  process.stdout.write(JSON.stringify(result, null, 2) + '\n');
  process.exitCode = result.status === 'failed' ? 2 : assessmentAccepted(result) ? 0 : 1;
}
