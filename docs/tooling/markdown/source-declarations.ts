import {readFile} from 'node:fs/promises';
import {extname, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import ts from 'typescript';

type Declaration = {name: string; startLine: number; endLine: number; executableTest: boolean};
type Scan =
  | {type: 'accepted'; names: string[]; declarations: Declaration[]}
  | {type: 'rejected'; reason: 'INVALID_SOURCE' | 'UNREADABLE_SOURCE'};

/** Syntactic evidence only: no module resolution or runtime execution is implied. */
export function scan(path: string, content: string): Scan {
  const extension = extname(path).toLowerCase();
  const kind = extension === '.tsx' ? ts.ScriptKind.TSX :
    extension === '.jsx' ? ts.ScriptKind.JSX :
    extension === '.js' || extension === '.mjs' ? ts.ScriptKind.JS : ts.ScriptKind.TS;
  const fileName = resolve('/', path);
  const source = ts.createSourceFile(fileName, content, ts.ScriptTarget.Latest, true, kind);
  // An in-memory compiler host keeps the syntax rule independent of filesystem,
  // dependency resolution, and runtime module loading. No assertion over a
  // private TypeScript property is needed to obtain public syntax diagnostics.
  const options: ts.CompilerOptions = {noResolve: true, noLib: true, noEmit: true, allowJs: true};
  const host: ts.CompilerHost = {
    getSourceFile: name => name === fileName ? source : undefined,
    getDefaultLibFileName: () => '', writeFile: () => {},
    getCurrentDirectory: () => '/', getDirectories: () => [],
    fileExists: name => name === fileName,
    readFile: name => name === fileName ? content : undefined,
    getCanonicalFileName: name => name, useCaseSensitiveFileNames: () => true,
    getNewLine: () => '\n',
  };
  if (ts.createProgram([fileName], options, host).getSyntacticDiagnostics(source).length !== 0) {
    return {type: 'rejected', reason: 'INVALID_SOURCE'};
  }

  const declarations: Declaration[] = [];
  function add(name: string, node: ts.Node, executableTest: boolean): void {
    if (!name) return;
    declarations.push({
      name,
      startLine: source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1,
      endLine: source.getLineAndCharacterOfPosition(node.end - 1).line + 1,
      executableTest,
    });
  }
  function binding(name: ts.BindingName): void {
    if (ts.isIdentifier(name)) add(name.text, name, false);
    else for (const element of name.elements) if (ts.isBindingElement(element)) binding(element.name);
  }
  function visit(node: ts.Node): void {
    if (ts.isVariableDeclaration(node) || ts.isParameter(node) || ts.isBindingElement(node)) {
      // Nested binding elements are handled by the enclosing binding pattern.
      if (!ts.isBindingElement(node)) binding(node.name);
    } else if (
      ts.isFunctionDeclaration(node) || ts.isFunctionExpression(node) || ts.isClassDeclaration(node) ||
      ts.isClassExpression(node) || ts.isInterfaceDeclaration(node) || ts.isTypeAliasDeclaration(node) ||
      ts.isEnumDeclaration(node) || ts.isEnumMember(node) || ts.isModuleDeclaration(node) ||
      ts.isMethodDeclaration(node) || ts.isMethodSignature(node) || ts.isPropertyDeclaration(node) ||
      ts.isPropertySignature(node) || ts.isGetAccessorDeclaration(node) || ts.isSetAccessorDeclaration(node) ||
      ts.isTypeParameterDeclaration(node)
    ) {
      if (node.name && ts.isIdentifier(node.name)) add(node.name.text, node.name, false);
    }
    if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) &&
        (node.expression.text === 'test' || node.expression.text === 'it')) {
      const name = node.arguments[0];
      const callback = node.arguments[1];
      if (name && ts.isStringLiteral(name) && callback &&
          (ts.isArrowFunction(callback) || ts.isFunctionExpression(callback)) && callback.body) {
        add(name.text, name, true);
      }
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  return {type: 'accepted', names: [...new Set(declarations.map(item => item.name))].sort(), declarations};
}

async function main(paths: string[]): Promise<void> {
  const results = await Promise.all(paths.map(async path => {
    let content: string;
    try {
      content = await readFile(path, 'utf8');
    } catch (error) {
      if (error instanceof Error && 'code' in error && typeof error.code === 'string' &&
          ['ENOENT', 'EACCES', 'EPERM', 'ENOTDIR', 'EISDIR', 'EMFILE', 'ENFILE', 'ELOOP', 'EIO'].includes(error.code)) {
        return {path, result: {type: 'rejected', reason: 'UNREADABLE_SOURCE'}};
      }
      throw error;
    }
    return {path, result: scan(path, content)};
  }));
  process.stdout.write(JSON.stringify(results) + '\n');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await main(process.argv.slice(2));
}
