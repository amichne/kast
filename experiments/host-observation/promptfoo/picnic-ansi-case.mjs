import { fileURLToPath } from 'node:url';

const sourceDirectory = 'src/main/kotlin/com/jakewharton/picnic';
const sources = [
  'IntCounts.kt', 'dsl.kt', 'model.kt', 'textBorder.kt', 'textLayout.kt', 'textRender.kt', 'textSurface.kt',
].map(file => sourceDirectory + '/' + file);

export const picnicAnsiCase = {
  id: 'picnic-ansi',
  kind: 'edit',
  source: {
    repository: 'https://github.com/JakeWharton/picnic',
    revision: 'cd569782e2503ddd4f95fb47017d12f9e9484210',
    paths: sources.map(path => path.replace('src/main/kotlin/', 'picnic/src/main/java/')),
    adaptation: 'Relocated seven production files; removed standalone @JvmDefault annotations; current fixture build.',
  },
  fixture: fileURLToPath(new URL('./developer-tasks/picnic-ansi', import.meta.url)),
  warmup: { name: 'renderText', kind: 'function' },
  prompt: [
    'Fix Picnic table rendering for cell text with ANSI colors and styles.',
    'Treat numeric ANSI SGR escape sequences, such as ESC[31;1;4m and ESC[0m, as zero columns. Preserve their exact bytes in renderText output.',
    'Handle adjacent escapes, resets, and escapes in the middle of a line.',
    'Keep neighboring cells and borders aligned on multiline text. Align each line by its visible width and preserve supplementary Unicode code points.',
    'Keep the public DSL and renderText signatures compatible. Edit the Kotlin sources in this project.',
  ].join('\n'),
  allowedChangedPaths: sources,
  allowedNewSourceDirs: [sourceDirectory],
  oracle: {
    main: fileURLToPath(new URL('./developer-tasks/oracles/PicnicAnsi.kt', import.meta.url)),
    mainClass: 'oracle.PicnicAnsiKt',
    candidateSources: sources,
    expectedStdout: 'PASS',
  },
};
