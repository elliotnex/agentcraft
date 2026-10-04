// Hub settings, Foreman side: user-triggered remote operations against a local bare repo standing in
// for GitHub (push sets the upstream, ahead/behind follow commits and fetches, pull fast-forwards,
// clone registers), the default repo, removing a repo (refused while work is in flight) and the
// helpers that tidy remote URLs.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { Outbound } from '../src/protocol.js';
import { cleanUrl, isRemoteUrl, repoNameFromUrl, shortRemote } from '../src/remote.js';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

let h: Harness;
let home: string;
let repoPath: string;
let bare: string;
let work: string;

const git = (cwd: string, ...args: string[]) =>
  execFileSync('git', ['-c', 'user.name=T', '-c', 'user.email=t@t', ...args], { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

async function send(msg: Record<string, unknown>): Promise<{ ok: boolean; error?: string; result?: Record<string, unknown> }> {
  const out: Outbound[] = [];
  await h.fm.handle({ v: 1, id: `m${Math.random()}`, ...msg } as never, (m) => out.push(m));
  return out.find((m) => m.type === 'ack') as never;
}

beforeAll(async () => {
  home = tempDir();
  work = tempDir('ac-remote-');
  repoPath = await demoRepo();
  bare = path.join(work, 'origin.git');
  execFileSync('git', ['init', '--bare', '-b', 'main', bare]);
  git(repoPath, 'remote', 'add', 'origin', bare);
  h = makeForeman(home);
  await h.fm.repos.add(repoPath);
});

afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(work);
  rmrf(path.dirname(repoPath));
});

describe('hub settings: GitHub (a local bare origin)', () => {
  it('sees the remote; push sets the upstream and clears "to push"', async () => {
    const id = h.fm.repos.list()[0]!.id;
    expect(h.fm.repos.get(id)!.remote).toBe(bare);
    expect(h.fm.repos.get(id)!.upstream).toBeUndefined();
    const ack = await send({ type: 'repo.git', repoId: id, action: 'push' });
    expect(ack.ok).toBe(true);
    const r = h.fm.repos.get(id)!;
    expect(r.upstream).toBe('origin/main');
    expect([r.ahead, r.behind]).toEqual([0, 0]);
    expect(git(bare, 'rev-parse', 'main')).toBe(git(repoPath, 'rev-parse', 'main'));
  });

  it('counts local commits to push, and upstream commits to pull after a fetch; pull fast-forwards', async () => {
    const id = h.fm.repos.list()[0]!.id;
    fs.writeFileSync(path.join(repoPath, 'LOCAL.md'), 'local\n');
    git(repoPath, 'add', '-A');
    git(repoPath, 'commit', '-m', 'local');
    await h.fm.repos.pollStatus(id);
    expect(h.fm.repos.get(id)!.ahead).toBe(1);
    expect((await send({ type: 'repo.git', repoId: id, action: 'push' })).ok).toBe(true);
    // someone else pushes
    const other = path.join(work, 'other');
    execFileSync('git', ['clone', bare, other], { stdio: 'ignore' });
    fs.writeFileSync(path.join(other, 'REMOTE.md'), 'remote\n');
    git(other, 'add', '-A');
    git(other, 'commit', '-m', 'remote');
    git(other, 'push', 'origin', 'main');
    expect((await send({ type: 'repo.git', repoId: id, action: 'fetch' })).ok).toBe(true);
    expect(h.fm.repos.get(id)!.behind).toBe(1);
    const pulled = await send({ type: 'repo.git', repoId: id, action: 'pull' });
    expect(pulled.ok).toBe(true);
    expect(h.fm.repos.get(id)!.behind).toBe(0);
    expect(fs.existsSync(path.join(repoPath, 'REMOTE.md'))).toBe(true);
  });

  it('refuses to publish a project that already has a remote, and push without one', async () => {
    const id = h.fm.repos.list()[0]!.id;
    const pub = await send({ type: 'repo.git', repoId: id, action: 'publish' });
    expect(pub.ok).toBe(false);
    expect(pub.error).toContain('already has a remote');
    const lone = path.join(work, 'lone');
    fs.mkdirSync(lone);
    git(lone, 'init', '-b', 'main');
    fs.writeFileSync(path.join(lone, 'a.txt'), 'a\n');
    git(lone, 'add', '-A');
    git(lone, 'commit', '-m', 'a');
    const r = await h.fm.repos.add(lone);
    const push = await send({ type: 'repo.git', repoId: r.id, action: 'push' });
    expect(push.ok).toBe(false);
    expect(push.error).toContain('Publish it first');
  });

  it('clones a URL into a folder and registers it', async () => {
    const dest = path.join(work, 'cloned-site');
    const ack = await send({ type: 'repo.clone', url: pathToFileURL(bare).href, path: dest });
    expect(ack.ok).toBe(true);
    const r = h.fm.repos.get(ack.result!.repoId as string)!;
    expect(r.path.toLowerCase()).toBe(path.resolve(dest).toLowerCase());
    expect(r.upstream).toBe('origin/main');
    expect(fs.existsSync(path.join(dest, 'REMOTE.md'))).toBe(true);
    expect((await send({ type: 'repo.clone', url: pathToFileURL(bare).href, path: dest })).error).toContain('not empty');
  });
});

describe('hub settings: projects', () => {
  it('makes a repo the default for new goals', async () => {
    const [first] = h.fm.repos.list();
    expect(h.fm.repos.defaultRepo()!.id).not.toBe(first!.id); // most recently added wins by default
    expect((await send({ type: 'repo.default', repoId: first!.id })).ok).toBe(true);
    expect(h.fm.repos.defaultRepo()!.id).toBe(first!.id);
    expect(h.fm.repos.list().filter((r) => r.isDefault)).toHaveLength(1);
  });

  it('turns auto-push on and off', async () => {
    const id = h.fm.repos.list()[0]!.id;
    await send({ type: 'repo.settings', repoId: id, autoPush: true });
    expect(h.fm.repos.get(id)!.autoPush).toBe(true);
    await send({ type: 'repo.settings', repoId: id, autoPush: false });
    expect(h.fm.repos.get(id)!.autoPush).toBeUndefined();
  });

  it('with auto-push on, an approved merge goes to origin', async () => {
    const id = h.fm.repos.list()[0]!.id;
    await send({ type: 'repo.settings', repoId: id, autoPush: true });
    const t = h.fm.tasks.create({ title: 'auto push me', createdBy: 'user', repoId: id, assignee: 'kit' });
    const wt = await h.fm.repos.createWorktree(id, 'kit', t);
    h.fm.tasks.update(t.id, { worktree: wt.id, branch: wt.branch });
    h.fm.tasks.setStatus(t.id, 'review', { force: true });
    fs.writeFileSync(path.join(wt.path, 'AUTO.md'), 'pushed by itself\n');
    const d = h.fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Merge?', options: ['Merge', 'Request changes', 'Reject'], taskId: t.id, repoId: id, worktree: wt.id });
    await h.fm.answerDecision(d.id, 'Merge');
    const until = Date.now() + 20_000;
    while (git(bare, 'rev-parse', 'main') !== git(repoPath, 'rev-parse', 'main') && Date.now() < until) await new Promise((r) => setTimeout(r, 100));
    expect(git(bare, 'rev-parse', 'main')).toBe(git(repoPath, 'rev-parse', 'main'));
    expect(git(bare, 'show', 'main:AUTO.md')).toContain('pushed by itself');
    await send({ type: 'repo.settings', repoId: id, autoPush: false });
  });

  it('removes a repo, but not while a task of it is in progress', async () => {
    const victim = h.fm.repos.list().find((r) => r.name === 'lone')!;
    const t = h.fm.tasks.create({ title: 'busy', createdBy: 'user', repoId: victim.id, assignee: 'kit' });
    h.fm.tasks.setStatus(t.id, 'doing', { force: true });
    const refused = await send({ type: 'repo.remove', repoId: victim.id });
    expect(refused.ok).toBe(false);
    expect(refused.error).toContain('in progress');
    h.fm.tasks.setStatus(t.id, 'cancelled', { force: true });
    const seen: Outbound[] = [];
    const off = h.fm.subscribe((m) => seen.push(m));
    expect((await send({ type: 'repo.remove', repoId: victim.id })).ok).toBe(true);
    off();
    expect(h.fm.repos.get(victim.id)).toBeUndefined();
    expect(seen.some((m) => m.type === 'repo.removed' && m.repoId === victim.id)).toBe(true);
    expect(fs.existsSync(victim.path)).toBe(true); // the files stay
  });
});

describe('remote URL helpers', () => {
  it('strips credentials, shortens, recognises and names', () => {
    expect(cleanUrl('https://user:ghp_secret@github.com/o/r.git')).toBe('https://github.com/o/r.git');
    expect(shortRemote('https://github.com/elliotnex/webv3.git')).toBe('github.com/elliotnex/webv3');
    expect(shortRemote('git@github.com:elliotnex/webv3.git')).toBe('github.com/elliotnex/webv3');
    expect(isRemoteUrl('https://github.com/o/r')).toBe(true);
    expect(isRemoteUrl('git@github.com:o/r.git')).toBe(true);
    expect(isRemoteUrl('C:\\Projects\\r')).toBe(false);
    expect(repoNameFromUrl('https://github.com/o/my-site.git')).toBe('my-site');
  });
});
