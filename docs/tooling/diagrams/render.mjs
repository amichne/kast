import { readFile, readdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { renderMermaidSVG } from 'beautiful-mermaid';

const directory = new URL('../../public/images/architecture/', import.meta.url);
for (const file of (await readdir(directory)).filter((name) => name.endsWith('.mmd')).sort()) {
  const source = await readFile(new URL(file, directory), 'utf8');
  for (const [theme, bg, fg] of [['light', '#FBFBF9', '#20231F'], ['dark', '#171816', '#F0F1EB']]) {
    let svg = renderMermaidSVG(source, {
      bg, fg, accent: theme === 'light' ? '#3154C7' : '#8FA7FF',
      muted: theme === 'light' ? '#52594E' : '#B3B9AC',
      font: 'system-ui, sans-serif', padding: 20, nodeSpacing: 24, layerSpacing: 32,
    });
    // Standalone SVGs use local system fonts, with no external CSS request.
    svg = svg.replace(/^\s*@import url\([^\n]+\);\s*$/gm, '');
    if (/<(?:script|foreignObject|animate|image)\b|@import|(?:href|src)=["']https?:|url\(\s*["']?https?:/i.test(svg)) {
      throw new Error(`Unsafe SVG in ${file}`);
    }
    const output = new URL(file.replace('.mmd', `.${theme}.svg`), directory);
    await writeFile(output, svg + '\n');
    console.log(fileURLToPath(output));
  }
}
