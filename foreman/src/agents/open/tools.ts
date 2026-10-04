// The freelancer's tools: a small, sturdy set any tool-calling model can drive. Files stay inside
// the task's worktree; commands go through the same permission policy as the Claude team (allow /
// ask the user / deny) and run with git's network transports disabled (gitsafety).
import fs from 'node:fs';
import path from 'node:path';
import { withGitSafety } from '../../gitsafety.js';
import { run, runShell } from '../../util/proc.js';
import { headLines, tailLines } from '../../util/text.js';
import { isInsideOrEqual } from '../../util/fsx.js';
import { agentGitIdentity } from '../../util/git.js';
import type { ToolSpec } from './client.js';

export type Mode = 'task' | 'ask';

const fn = (name: string, description: string, properties: Record<string, unknown>, required: string[]): ToolSpec => ({
  type: 'function',
  function: { name, description, parameters: { type: 'object', properties, required } },
});

const READ_TOOLS: ToolSpec[] = [
  fn('list_files', 'List files and folders under a path of the repository (relative; default the root).', {
    path: { type: 'string', description: 'folder, relative to the repository root' },
    depth: { type: 'integer', description: 'how many levels deep (1-4, default 2)' },
  }, []),
  fn('read_file', 'Read a text file of the repository, optionally a range of lines.', {
    path: { type: 'string' },
    offset: { type: 'integer', description: 'first line, 1-based' },
    limit: { type: 'integer', description: 'number of lines (default 400)' },
  }, ['path']),
  fn('search', 'Search the repository for a regular expression (git grep); returns matching lines with file and line number.', {
    pattern: { type: 'string' },
    path: { type: 'string', description: 'limit to this folder' },
  }, ['pattern']),
];

const WRITE_TOOLS: ToolSpec[] = [
  fn('write_file', 'Create or overwrite a file with the given content.', { path: { type: 'string' }, content: { type: 'string' } }, ['path', 'content']),
  fn('edit_file', 'Replace an exact piece of text in a file (old_text must occur exactly once).', {
    path: { type: 'string' },
    old_text: { type: 'string' },
    new_text: { type: 'string' },
  }, ['path', 'old_text', 'new_text']),
  fn('run_command', 'Run a shell command in the repository (tests, builds, git status...). Network access and commands outside the repository may need the user\'s OK; git push is never allowed.', {
    command: { type: 'string' },
  }, ['command']),
  fn('finish', 'You are done: summarise what you changed and how you checked it. The user reviews the diff and decides whether to merge.', {
    summary: { type: 'string' },
  }, ['summary']),
];

const ANSWER_TOOL = fn('answer', 'Give the user your answer (markdown allowed). Ends the session.', { text: { type: 'string' } }, ['text']);

export function toolsFor(mode: Mode): ToolSpec[] {
  return mode === 'task' ? [...READ_TOOLS, ...WRITE_TOOLS] : [...READ_TOOLS, ANSWER_TOOL];
}

export class ToolError extends Error {}

/** Resolve a repository-relative path; refuses anything outside the root (absolute, `..`, links). */
export function resolveInside(root: string, rel: unknown): string {
  if (typeof rel !== 'string' || !rel.trim()) throw new ToolError('path is required');
  const p = path.resolve(root, rel.trim());
  if (!isInsideOrEqual(p, root)) throw new ToolError(`${rel} is outside the repository`);
  if (fs.existsSync(p)) {
    const real = fs.realpathSync(p);
    if (!isInsideOrEqual(real, fs.realpathSync(root))) throw new ToolError(`${rel} leads outside the repository`);
  }
  const parts = path.relative(root, p).split(path.sep);
  if (parts[0] === '.git') throw new ToolError('the .git folder is off limits');
  return p;
}

export function listFiles(root: string, rel: unknown, depth: unknown): string {
  const start = rel ? resolveInside(root, rel) : root;
  const max = Math.max(1, Math.min(4, Number(depth) || 2));
  const out: string[] = [];
  const walk = (dir: string, level: number) => {
    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    entries.sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name));
    for (const e of entries) {
      if (e.name === '.git' || e.name === 'node_modules' || out.length > 400) continue;
      const full = path.join(dir, e.name);
      out.push(`${'  '.repeat(level)}${e.name}${e.isDirectory() ? '/' : ''}`);
      if (e.isDirectory() && level + 1 < max) walk(full, level + 1);
    }
  };
  walk(start, 0);
  return out.length ? out.join('\n') : '(empty)';
}

export function readFile(root: string, rel: unknown, offset: unknown, limit: unknown): string {
  const p = resolveInside(root, rel);
  if (!fs.existsSync(p) || !fs.statSync(p).isFile()) throw new ToolError(`no file ${String(rel)}`);
  const lines = fs.readFileSync(p, 'utf8').split(/\r?\n/);
  const from = Math.max(1, Number(offset) || 1);
  const n = Math.max(1, Math.min(2000, Number(limit) || 400));
  const slice = lines.slice(from - 1, from - 1 + n).map((l, i) => `${from + i}\t${l}`);
  const more = from - 1 + n < lines.length ? `\n... (${lines.length} lines; read more with offset ${from + n})` : '';
  return slice.join('\n') + more;
}

export function writeFile(root: string, rel: unknown, content: unknown): string {
  const p = resolveInside(root, rel);
  if (typeof content !== 'string') throw new ToolError('content is required');
  fs.mkdirSync(path.dirname(p), { recursive: true });
  const existed = fs.existsSync(p);
  fs.writeFileSync(p, content);
  return `${existed ? 'Overwrote' : 'Created'} ${path.relative(root, p).replace(/\\/g, '/')} (${content.split('\n').length} lines)`;
}

export function editFile(root: string, rel: unknown, oldText: unknown, newText: unknown): string {
  const p = resolveInside(root, rel);
  if (typeof oldText !== 'string' || typeof newText !== 'string' || !oldText) throw new ToolError('old_text and new_text are required');
  if (!fs.existsSync(p)) throw new ToolError(`no file ${String(rel)}`);
  const text = fs.readFileSync(p, 'utf8');
  // models often lose \r: match on normalised line endings, write back in the file's own style
  const crlf = text.includes('\r\n');
  const norm = (s: string) => s.replace(/\r\n/g, '\n');
  const body = norm(text);
  const want = norm(oldText);
  const count = body.split(want).length - 1;
  if (count === 0) throw new ToolError('old_text was not found (read the file again and copy the exact text)');
  if (count > 1) throw new ToolError(`old_text occurs ${count} times; include more surrounding lines so it is unique`);
  const next = body.replace(want, norm(newText));
  fs.writeFileSync(p, crlf ? next.replace(/\n/g, '\r\n') : next);
  return `Edited ${path.relative(root, p).replace(/\\/g, '/')}`;
}

export async function search(root: string, pattern: unknown, rel: unknown): Promise<string> {
  if (typeof pattern !== 'string' || !pattern) throw new ToolError('pattern is required');
  const args = ['grep', '-n', '-I', '-E', '--max-count=20', '-e', pattern];
  if (rel) args.push('--', path.relative(root, resolveInside(root, rel)) || '.');
  const res = await run('git', args, { cwd: root, timeoutMs: 20_000, env: withGitSafety(process.env) });
  if (res.code === 1) return 'no matches';
  if (res.code !== 0) throw new ToolError(res.stderr.trim() || 'search failed');
  return headLines(res.stdout, 120, 8000);
}

let bashPath: string | null | undefined;
/** Git Bash on Windows (models write bash), else the system shell. */
function bash(): string | null {
  if (bashPath !== undefined) return bashPath;
  const candidates = process.platform === 'win32'
    ? [process.env.AGENTCRAFT_BASH, 'C:\\Program Files\\Git\\bin\\bash.exe', 'C:\\Program Files (x86)\\Git\\bin\\bash.exe']
    : [process.env.AGENTCRAFT_BASH, '/bin/bash', '/usr/bin/bash'];
  bashPath = candidates.find((c): c is string => !!c && fs.existsSync(c)) ?? null;
  return bashPath;
}

export async function runCommand(root: string, agentId: string, command: string, timeoutMs = 180_000): Promise<string> {
  const env = withGitSafety(process.env, { ...(agentGitIdentity(agentId) as Record<string, string>), CI: '1', FORCE_COLOR: '0', NO_COLOR: '1' },
    { ceiling: path.dirname(path.resolve(root)) });
  const sh = bash();
  const res = sh ? await run(sh, ['-c', command], { cwd: root, env, timeoutMs }) : await runShell(command, { cwd: root, env, timeoutMs });
  const out = `${res.stdout}${res.stderr ? `${res.stdout ? '\n' : ''}${res.stderr}` : ''}`.trim();
  return `exit ${res.code}${res.timedOut ? ' (timed out)' : ''}\n${tailLines(out, 80, 6000) || '(no output)'}`;
}
