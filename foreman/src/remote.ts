// GitHub (any git remote) for a hub's projects: fetch, pull, push, publish (gh repo create) and clone.
//
// These are the ONLY network git operations in AgentCraft, and they run only when the user asks
// (the hub settings screen) or turned auto-push on for a repo. They use the user's own git and gh
// logins (credential manager, gh auth) and the user's repo config, unlike every other git call:
// agents never get a transport (gitsafety.ts) and the Foreman's own git (util/git.ts) blocks remotes.
import fs from 'node:fs';
import path from 'node:path';
import { GIT_REDIRECT_VARS } from './gitsafety.js';
import { run, type RunResult } from './util/proc.js';

export class RemoteError extends Error {}

function userEnv(): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...process.env };
  for (const k of Object.keys(env)) if (GIT_REDIRECT_VARS.includes(k.toUpperCase())) delete env[k];
  // never block on a terminal prompt (nobody can answer it); the credential manager may still ask in a window
  env.GIT_TERMINAL_PROMPT = '0';
  env.GH_PROMPT_DISABLED = '1';
  return env;
}

async function exec(cmd: string, cwd: string, args: string[], timeoutMs = 180_000): Promise<RunResult> {
  try {
    return await run(cmd, args, { cwd, env: userEnv(), timeoutMs });
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === 'ENOENT') throw new RemoteError(`${cmd} is not installed (or not on PATH)`);
    throw e;
  }
}

function out(r: RunResult): string {
  return `${r.stdout}\n${r.stderr}`.trim();
}

function check(r: RunResult, what: string): string {
  if (r.timedOut) throw new RemoteError(`${what} timed out`);
  if (r.code !== 0) throw new RemoteError(`${what} failed: ${out(r).split('\n').slice(-4).join(' | ') || `exit ${r.code}`}`);
  return out(r);
}

/** A remote URL without credentials (https://user:token@host/... -> https://host/...). */
export function cleanUrl(url: string): string {
  return url.trim().replace(/^(https?:\/\/)[^@/]+@/, '$1');
}

/** "github.com/owner/name" for display, from https or ssh URLs; the clean URL otherwise. */
export function shortRemote(url: string): string {
  const u = cleanUrl(url);
  const m = /^(?:https?:\/\/|ssh:\/\/git@|git@)([^/:]+)[/:](.+?)(?:\.git)?\/?$/.exec(u);
  return m ? `${m[1]}/${m[2]}` : u;
}

/** True for something `git clone` takes as a remote (https, ssh, git@host:owner/name, file:// mirrors). */
export function isRemoteUrl(s: string): boolean {
  return /^(https?:\/\/|ssh:\/\/|file:\/\/|git@[^:]+:)/.test(s.trim());
}

/** The folder a clone of `url` gets by default: its last path segment without .git. */
export function repoNameFromUrl(url: string): string {
  const last = cleanUrl(url).replace(/\/+$/, '').split(/[/:]/).pop() ?? 'repo';
  return last.replace(/\.git$/, '') || 'repo';
}

export interface RemoteInfo {
  remote?: string;
  upstream?: string;
  ahead?: number;
  behind?: number;
}

/** Local-only look at origin and the branch's upstream (no network; counts are as of the last fetch). */
export async function remoteInfo(repoPath: string, branch: string): Promise<RemoteInfo> {
  const info: RemoteInfo = {};
  const url = await exec('git', repoPath, ['config', '--get', 'remote.origin.url'], 10_000);
  if (url.code === 0 && url.stdout.trim()) info.remote = cleanUrl(url.stdout);
  const up = await exec('git', repoPath, ['rev-parse', '--abbrev-ref', `${branch}@{upstream}`], 10_000);
  if (up.code === 0 && up.stdout.trim()) {
    info.upstream = up.stdout.trim();
    const c = await exec('git', repoPath, ['rev-list', '--left-right', '--count', `${branch}...${info.upstream}`], 10_000);
    const m = /^(\d+)\s+(\d+)/.exec(c.stdout.trim());
    if (c.code === 0 && m) {
      info.ahead = Number(m[1]);
      info.behind = Number(m[2]);
    }
  }
  return info;
}

export async function fetchOrigin(repoPath: string): Promise<string> {
  return check(await exec('git', repoPath, ['fetch', '--prune', 'origin']), 'git fetch');
}

/** Fast-forward the checked-out branch from its upstream (never a merge commit, never a rebase). */
export async function pull(repoPath: string, branch: string): Promise<string> {
  const cur = (await exec('git', repoPath, ['symbolic-ref', '--quiet', '--short', 'HEAD'], 10_000)).stdout.trim();
  if (cur !== branch) throw new RemoteError(`the checkout is on '${cur || 'a detached HEAD'}', not '${branch}': switch back first`);
  return check(await exec('git', repoPath, ['pull', '--ff-only']), 'git pull');
}

/** Push the branch to origin, setting its upstream the first time. */
export async function push(repoPath: string, branch: string): Promise<string> {
  const url = await exec('git', repoPath, ['config', '--get', 'remote.origin.url'], 10_000);
  if (url.code !== 0 || !url.stdout.trim()) throw new RemoteError('this project has no GitHub remote yet: Publish it first');
  return check(await exec('git', repoPath, ['push', '-u', 'origin', branch]), 'git push');
}

/** Create a GitHub repo for a local project and push it (gh repo create --source --push). */
export async function publish(repoPath: string, name: string, visibility: 'private' | 'public'): Promise<string> {
  const url = await exec('git', repoPath, ['config', '--get', 'remote.origin.url'], 10_000);
  if (url.code === 0 && url.stdout.trim()) throw new RemoteError(`it already has a remote: ${shortRemote(url.stdout)}`);
  if (!/^[A-Za-z0-9._-]+$/.test(name)) throw new RemoteError(`'${name}' is not a valid GitHub repository name`);
  return check(
    await exec('gh', repoPath, ['repo', 'create', name, `--${visibility}`, '--source', repoPath, '--remote', 'origin', '--push'], 300_000),
    'gh repo create',
  );
}

/** Clone `url` into `dest` (which must not exist or be empty). */
export async function clone(url: string, dest: string): Promise<string> {
  if (!isRemoteUrl(url)) throw new RemoteError(`not a git URL: ${url}`);
  const abs = path.resolve(dest);
  if (fs.existsSync(abs) && fs.readdirSync(abs).length) throw new RemoteError(`${abs} already exists and is not empty`);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  return check(await exec('git', path.dirname(abs), ['clone', url.trim(), abs], 600_000), 'git clone');
}
