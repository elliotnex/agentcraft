// The open backend (Scout, any OpenAI-compatible model) with a scripted model: a task runs in its own
// worktree and ends in a merge decision; a question answers in the feed without a worktree; tools stay
// inside the repository; shell commands go through the permission policy; requested changes continue
// the same conversation; the model can be switched per goal or with /model.
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { OpenBackend } from '../src/agents/open/index.js';
import type { ChatMessage, CompleteFn, ToolCall } from '../src/agents/open/client.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

let h: Harness;
let home: string;
let repoPath: string;
let backend: OpenBackend;
const seen: Array<{ model: string; messages: ChatMessage[] }> = [];

let callNo = 0;
const call = (name: string, args: Record<string, unknown>): ToolCall => ({ id: `c${++callNo}`, type: 'function', function: { name, arguments: JSON.stringify(args) } });
const tools = (...calls: ToolCall[]): ChatMessage => ({ role: 'assistant', content: null, tool_calls: calls });
const lastTool = (m: ChatMessage[]) => [...m].reverse().find((x) => x.role === 'tool')?.content ?? '';
const userText = (m: ChatMessage[]) => m.filter((x) => x.role === 'user').map((x) => x.content).join('\n');

/** The scripted model: picks a script by the request text, a step by how far the conversation got. */
const fake: CompleteFn = async ({ model, messages }) => {
  seen.push({ model, messages: [...messages] });
  const req = userText(messages);
  const steps = messages.filter((x) => x.role === 'assistant').length;
  const reply = (message: ChatMessage) => ({ message, costUsd: 0.001, tokensIn: 100, tokensOut: 20 });

  if (req.includes('Document the --version flag')) {
    const followups = messages.filter((x) => x.role === 'user' && x.content?.includes('requested changes')).length;
    if (followups && !messages.some((x) => x.role === 'tool' && x.content?.includes('Edited README.md') && messages.indexOf(x) > messages.findIndex((y) => y.content?.includes('requested changes')))) {
      return reply(tools(call('edit_file', { path: 'README.md', old_text: '`notes --version` prints the version.', new_text: '`notes --version` prints the version and exits.' })));
    }
    if (followups) return reply(tools(call('finish', { summary: 'Said that it exits.' })));
    if (steps === 0) return reply(tools(call('read_file', { path: 'README.md', limit: 5 })));
    if (steps === 1) return reply({ role: 'assistant', content: 'Adding a line to the README.', tool_calls: [call('write_file', { path: 'NOTES.md', content: '`notes --version` prints the version.\n' })] });
    if (steps === 2) return reply(tools(call('edit_file', { path: 'NOTES.md', old_text: 'prints the version.', new_text: 'prints the version.\n' }), call('run_command', { command: 'git status --short' })));
    if (steps === 3) return reply(tools(call('edit_file', { path: 'README.md', old_text: fs.readFileSync(path.join(repoPath, 'README.md'), 'utf8').split('\n')[0]!, new_text: `${fs.readFileSync(path.join(repoPath, 'README.md'), 'utf8').split('\n')[0]!}\n\n\`notes --version\` prints the version.` })));
    return reply(tools(call('finish', { summary: 'Documented --version in the README.' })));
  }
  if (req.includes('Which file parses tags')) {
    if (steps === 0) return reply(tools(call('search', { pattern: 'tag' })));
    return reply(tools(call('answer', { text: 'Tags are parsed in src/ (see the search results).' })));
  }
  if (req.includes('Escape the repo')) {
    if (steps === 0) return reply(tools(call('write_file', { path: '../escaped.txt', content: 'x' }), call('read_file', { path: '.git/config' })));
    return reply(tools(call('finish', { summary: 'Tried.' })));
  }
  if (req.includes('Fetch something')) {
    if (steps === 0) return reply(tools(call('run_command', { command: 'curl https://example.com' })));
    return reply(tools(call('finish', { summary: lastTool(messages).includes('denied') ? 'Was denied.' : 'Fetched.' })));
  }
  if (req.includes('Chatty')) return reply({ role: 'assistant', content: 'I would do it like this, but I have no tools.' });
  return reply(tools(call('finish', { summary: 'ok' })));
};

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  h = makeForeman(home, ['--backend', 'open', '--repo', repoPath, '--ci', 'git --version', '--auto-approve', 'off']);
  backend = new OpenBackend(h.fm, h.cfg.open, fake, { OPENROUTER_API_KEY: 'test-key' });
  await h.fm.start(backend);
});

afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

const waitDecision = async (kind: string, taskId: string) => {
  await until(() => h.fm.decisions.open().some((d) => d.kind === kind && d.taskId === taskId), 30_000);
  return h.fm.decisions.open().find((d) => d.kind === kind && d.taskId === taskId)!;
};

describe('open backend', () => {
  it('has Scout alone on the roster, ready at the terminal', () => {
    expect(h.fm.agents().map((a) => a.id)).toEqual(['scout']);
    expect(h.fm.agent('scout')).toMatchObject({ active: true, state: 'idle', station: 'terminal' });
    expect(h.fm.status).toMatchObject({ backend: 'open', auth: 'ok', model: 'openai/gpt-5-mini', account: 'OpenRouter' });
  });

  it('runs a task in a worktree and ends in a merge decision; merging lands it', async () => {
    const goal = await h.fm.submitGoal('Document the --version flag', undefined, { model: 'anthropic/claude-sonnet-4.5' });
    const t = h.fm.tasks.forGoal(goal.id)[0]!;
    const d = await waitDecision('merge', t.id);
    expect(d.agentId).toBe('scout');
    expect(d.context).toContain('model: anthropic/claude-sonnet-4.5');
    expect(h.fm.status.model).toBe('anthropic/claude-sonnet-4.5');
    expect(seen.at(-1)!.model).toBe('anthropic/claude-sonnet-4.5');
    expect(h.fm.status.costUsd).toBeGreaterThan(0);
    const task = h.fm.tasks.get(t.id)!;
    expect(task.status).toBe('review');
    expect(task.branch).toMatch(/^agentcraft\/scout\//);
    // the work happened in the worktree, not the user's checkout
    expect(fs.existsSync(path.join(repoPath, 'NOTES.md'))).toBe(false);
    // the safe git status ran without asking
    expect(h.fm.decisions.list().some((x) => x.kind === 'permission' && x.taskId === t.id)).toBe(false);

    // requested changes continue the same conversation
    await h.fm.answerDecision(d.id, 'Request changes', 'Say that it exits too.');
    await until(() => h.fm.decisions.open().some((x) => x.kind === 'merge' && x.taskId === t.id && x.id !== d.id), 30_000);
    const followup = seen.at(-1)!.messages;
    expect(followup.some((m) => m.content?.includes('Say that it exits too.'))).toBe(true);
    expect(followup.some((m) => m.content?.includes('Document the --version flag'))).toBe(true);

    const d2 = h.fm.decisions.open().find((x) => x.kind === 'merge' && x.taskId === t.id)!;
    await h.fm.answerDecision(d2.id, 'Merge');
    await until(() => h.fm.tasks.get(t.id)!.status === 'done', 30_000);
    expect(fs.readFileSync(path.join(repoPath, 'README.md'), 'utf8')).toContain('prints the version and exits.');
    expect(fs.readFileSync(path.join(repoPath, 'NOTES.md'), 'utf8')).toContain('--version');
  });

  it('answers a question in the feed without a worktree', async () => {
    const goal = await h.fm.submitGoal('? Which file parses tags');
    const t = h.fm.tasks.forGoal(goal.id)[0]!;
    await until(() => h.fm.tasks.get(t.id)!.status === 'done', 30_000);
    const task = h.fm.tasks.get(t.id)!;
    expect(task.title).toBe('Q: Which file parses tags');
    expect(task.worktree).toBeUndefined();
    expect(task.summary).toContain('Tags are parsed');
    expect(h.events.some((e) => e.type === 'agent.say' && e.agentId === 'scout' && e.text.includes('Tags are parsed'))).toBe(true);
    // the question saw only read tools
    const q = seen.find((s) => userText(s.messages).includes('Which file parses tags'))!;
    expect(q.messages[0]!.content).toContain('cannot change files');
  });

  it('keeps the tools inside the repository', async () => {
    const goal = await h.fm.submitGoal('Escape the repo');
    const t = h.fm.tasks.forGoal(goal.id)[0]!;
    await waitDecision('merge', t.id);
    const results = seen.at(-1)!.messages.filter((m) => m.role === 'tool').map((m) => m.content);
    expect(results.some((r) => r?.includes('outside the repository'))).toBe(true);
    expect(results.some((r) => r?.includes('.git folder is off limits'))).toBe(true);
    const wt = h.fm.repos.findWorktree(h.fm.tasks.get(t.id)!.repoId!, h.fm.tasks.get(t.id)!.worktree!)!;
    expect(fs.existsSync(path.join(path.dirname(wt.path), 'escaped.txt'))).toBe(false);
    await h.fm.answerDecision(h.fm.decisions.open().find((x) => x.taskId === t.id)!.id, 'Reject');
  });

  it('asks before a network command, and a denial reaches the model', async () => {
    const goal = await h.fm.submitGoal('Fetch something');
    const t = h.fm.tasks.forGoal(goal.id)[0]!;
    const p = await waitDecision('permission', t.id);
    expect(p.agentId).toBe('scout');
    expect(p.question).toContain('curl https://example.com');
    expect(h.fm.agent('scout')!.state).toBe('waiting_user');
    await h.fm.answerDecision(p.id, 'Deny');
    const m = await waitDecision('merge', t.id);
    expect(m.context).toContain('Was denied.');
    await h.fm.answerDecision(m.id, 'Reject');
  });

  it('switches models with /model and reports a model that never uses its tools', async () => {
    backend.onUserMessage('scout', '/model meta-llama/llama-3-8b');
    expect(h.fm.status.model).toBe('meta-llama/llama-3-8b');
    const goal = await h.fm.submitGoal('Chatty');
    const t = h.fm.tasks.forGoal(goal.id)[0]!;
    await until(() => h.fm.tasks.get(t.id)!.status === 'review' || h.fm.tasks.get(t.id)!.status === 'blocked', 30_000);
    // after reminders, its plain reply is taken as the summary
    expect(seen.at(-1)!.model).toBe('meta-llama/llama-3-8b');
    await waitDecision('merge', t.id);
  });

  it('says so when the OpenRouter key is missing', async () => {
    const h2 = makeForeman(tempDir(), ['--backend', 'open', '--repo', repoPath]);
    await h2.fm.start(new OpenBackend(h2.fm, h2.cfg.open, fake, {}));
    expect(h2.fm.status.auth).toBe('failed');
    expect(h2.fm.status.message).toContain('OPENROUTER_API_KEY');
    await h2.fm.close();
    rmrf(h2.home);
  });
});
