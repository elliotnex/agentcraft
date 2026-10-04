// Scout's chat mode: no repo needed; a read-only view of every hub on this machine (their state,
// feeds, agent logs, spend and code); a message to Scout carries the chat on; no tool can change
// anything.
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { OpenBackend } from '../src/agents/open/index.js';
import type { ChatMessage, CompleteFn, ToolCall } from '../src/agents/open/client.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

let h: Harness;
let home: string;
let repoPath: string;
const seen: ChatMessage[][] = [];

let callNo = 0;
const call = (name: string, args: Record<string, unknown>): ToolCall => ({ id: `c${++callNo}`, type: 'function', function: { name, arguments: JSON.stringify(args) } });
const tools = (...calls: ToolCall[]): ChatMessage => ({ role: 'assistant', content: null, tool_calls: calls });
const results = (m: ChatMessage[]) => m.filter((x) => x.role === 'tool').map((x) => x.content ?? '');

const fake: CompleteFn = async ({ messages }) => {
  seen.push([...messages]);
  const users = messages.filter((x) => x.role === 'user').map((x) => x.content ?? '');
  const last = users.at(-1)!;
  const reply = (message: ChatMessage) => ({ message, costUsd: 0.001, tokensIn: 100, tokensOut: 20 });
  // model calls since the latest user message
  const step = messages.slice(messages.map((x) => x.role).lastIndexOf('user')).filter((x) => x.role === 'assistant').length;
  if (last.includes('How is the town doing')) {
    const script = [
      tools(call('list_hubs', {})),
      tools(call('hub_overview', { hub: 'web' }), call('usage', {})),
      tools(call('hub_tasks', { hub: 'webv3', status: 'doing' }), call('task_detail', { hub: 'webv3', task: 't2' }), call('hub_feed', { hub: 'webv3' }), call('agent_log', { hub: 'webv3', agent: 'kit' })),
      tools(call('read_file', { hub: 'webv3', path: 'README.md', limit: 3 }), call('read_file', { hub: 'webv3', path: '../escape.txt' }), call('write_file', { path: 'x.txt', content: 'x' }), call('run_command', { command: 'ls' })),
    ];
    if (step < script.length) return reply(script[step]!);
    return reply(tools(call('answer', { text: 'Kit is busy on t2 in webv3.' })));
  }
  if (last.includes('And the spend')) return reply({ role: 'assistant', content: 'About $1.25 so far.' });
  return reply(tools(call('answer', { text: 'ok' })));
};

/** A second hub ("webv3", hub id "web" in config.json) that this Foreman only reads. */
function otherHub(): void {
  const dir = path.join(home, 'webv3');
  fs.mkdirSync(path.join(dir, 'logs'), { recursive: true });
  const now = Date.now();
  const state = {
    version: 1,
    createdAt: now,
    agents: [
      { id: 'marlow', name: 'Marlow', role: 'lead', color: '#ffffff', skin: 'marlow', state: 'idle', activity: '', station: 'desk', paused: false, active: true },
      { id: 'kit', name: 'Kit', role: 'worker', title: 'Backend tinkerer', color: '#ffffff', skin: 'kit', state: 'editing', activity: 'editing src/api.ts', station: 'desk', taskId: 't2', paused: false, active: true },
    ],
    tasks: [
      { id: 't1', title: 'Set up the API', status: 'done', assignee: 'kit', deps: [], priority: 0, ci: 'pass', createdBy: 'marlow', createdAt: now - 7200_000, updatedAt: now - 3600_000 },
      { id: 't2', title: 'Add rate limiting', description: 'Token bucket per client.', status: 'doing', assignee: 'kit', deps: ['t1'], priority: 0, ci: 'none', worktree: 'kit-t2', createdBy: 'marlow', createdAt: now - 600_000, updatedAt: now - 60_000 },
    ],
    decisions: [{ id: 'd1', agentId: 'kit', kind: 'permission', question: 'Kit wants to run npm install', options: ['Allow', 'Deny'], status: 'open', taskId: 't2', createdAt: now - 30_000 }],
    repos: [{ id: 'webapp', name: 'webapp', path: repoPath, branch: 'main', dirty: false, worktrees: [], ci: 'none' }],
    goals: [{ id: 'g1', text: 'Ship the public API', progress: 0.5, status: 'active', createdAt: now - 7200_000, updatedAt: now - 60_000 }],
    feed: [{ ts: now - 60_000, kind: 'task', text: 'Kit started t2', agentId: 'kit' }],
    messages: [],
    counters: {},
    sessions: {
      'marlow:g1': { turns: 10, costUsd: 0.75, model: 'opus', updatedAt: now - 3600_000 },
      'kit:t2': { turns: 8, costUsd: 0.5, model: 'sonnet', updatedAt: now - 60_000 },
    },
    worktreeMeta: {},
    permissionRules: {},
    backend: { claude: {} },
  };
  fs.writeFileSync(path.join(dir, 'state.json'), JSON.stringify(state));
  fs.writeFileSync(path.join(dir, 'foreman.json'), JSON.stringify({ pid: 999999, port: 7999, host: '127.0.0.1', backend: 'claude', profile: 'webv3', version: '0.1.0', startedAt: new Date(now).toISOString() }));
  fs.writeFileSync(path.join(dir, 'logs', 'kit.jsonl'), `${JSON.stringify({ ts: now - 50_000, kind: 'tool', text: 'reading src/api.ts' })}\n`);
  fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ hubs: { profiles: { web: 'webv3' } } }));
}

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  otherHub();
  // the freelancer with no repo of its own
  h = makeForeman(home, ['--backend', 'open', '--auto-approve', 'off']);
  await h.fm.start(new OpenBackend(h.fm, h.cfg.open, fake, { OPENROUTER_API_KEY: 'test-key' }, async () => []));
});

afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

describe('open backend: chat', () => {
  it('still wants a repo for a task or a question', async () => {
    await expect(h.fm.submitGoal('Fix the bug')).rejects.toThrow(/no repo connected/);
    await expect(h.fm.submitGoal('Where is X?', undefined, { mode: 'ask' })).rejects.toThrow(/no repo connected/);
  });

  it('chats without a repo, looking at every hub read-only', async () => {
    const goal = await h.fm.submitGoal('How is the town doing?', undefined, { mode: 'chat' });
    expect(goal.repoId).toBeUndefined();
    const t = h.fm.tasks.forGoal(goal.id)[0]!;
    await until(() => h.fm.tasks.get(t.id)!.status === 'done', 30_000);
    expect(h.fm.tasks.get(t.id)).toMatchObject({ title: 'Chat: How is the town doing?', summary: 'Kit is busy on t2 in webv3.' });
    expect(h.fm.tasks.get(t.id)!.worktree).toBeUndefined();
    expect(h.events.some((e) => e.type === 'agent.say' && e.agentId === 'scout' && e.text.includes('Kit is busy'))).toBe(true);

    const convo = seen.at(-1)!;
    expect(convo[0]!.content).toContain('read-only');
    const out = results(convo);
    const [hubs, overview, usage, doing, detail, feed, log, readme, escape, write, run] = out;
    // list_hubs: this hub first, then the other one, by its hub id too
    expect(hubs).toMatch(/^- open \[this is you\]/);
    expect(hubs).toContain('webv3 (hub web): claude backend, stopped');
    expect(hubs).toContain('1 open decisions');
    expect(hubs).toContain('spend $1.25');
    // the hub id resolves to its profile
    expect(overview).toContain('# webv3 (hub web)');
    expect(overview).toContain('kit (Backend tinkerer): editing - editing src/api.ts [t2]');
    expect(overview).toContain('d1 permission from kit [t2]: Kit wants to run npm install');
    expect(overview).toContain('Ship the public API');
    expect(usage).toContain('Total across hubs: $1.25');
    expect(usage).toContain('by model: opus $0.7500, sonnet $0.5000');
    expect(usage).toContain('marlow:g1: $0.7500, 10 turns, opus');
    expect(doing).toContain('t2 [doing] Add rate limiting (kit)');
    expect(doing).not.toContain('t1');
    expect(detail).toContain('Token bucket per client.');
    expect(detail).toContain('cost $0.5000');
    expect(feed).toContain('Kit started t2');
    expect(log).toContain('reading src/api.ts');
    // code: readable, but only inside the repo; nothing can be written or run
    expect(readme).toMatch(/^1\t/);
    expect(escape).toContain('outside the repository');
    expect(write).toContain('no tool named write_file');
    expect(run).toContain('no tool named run_command');
    expect(fs.existsSync(path.join(repoPath, 'x.txt'))).toBe(false);
    expect(h.fm.decisions.list()).toHaveLength(0);
  });

  it('carries the chat on when the user talks to Scout, with earlier tool output trimmed', async () => {
    const chat = h.fm.tasks.list().find((t) => t.title.startsWith('Chat: '))!;
    const before = h.fm.tasks.list().length;
    await h.fm.handle({ type: 'user.message', to: 'scout', text: 'And the spend?' } as never, () => {});
    await until(() => h.fm.tasks.get(chat.id)!.summary === 'About $1.25 so far.', 30_000);
    expect(h.fm.tasks.list()).toHaveLength(before);
    const convo = seen.at(-1)!;
    expect(convo.some((m) => m.role === 'user' && m.content?.includes('How is the town doing'))).toBe(true);
    expect(convo.at(-1)!.content).toBe('Alex: And the spend?');
    expect(results(convo).every((r) => r.length <= 700)).toBe(true);
  });

  it('starts a new chat when the last one is cancelled', async () => {
    const old = h.fm.tasks.list().find((t) => t.title.startsWith('Chat: '))!;
    h.fm.backend!.onTaskAction(old, 'cancel');
    await h.fm.handle({ type: 'user.message', to: 'scout', text: 'Hello again' } as never, () => {});
    await until(() => h.fm.tasks.list().some((t) => t.title === 'Chat: Hello again' && t.status === 'done'), 30_000);
  });
});
