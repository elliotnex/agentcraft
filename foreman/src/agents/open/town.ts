// The town: a read-only view of every hub on this machine, for Scout's chat mode. Each hub runs a
// Foreman on its own profile (<home>/<profile>/state.json, written atomically), so reading those
// files never touches another hub's work; this Foreman's own profile is read live from its store.
// Hub ids that map to a profile come from <home>/config.json "hubs.profiles" (see HubProfiles.java).
import fs from 'node:fs';
import path from 'node:path';
import type { StateData } from '../../store.js';
import type { RunInfo } from '../../runfile.js';
import type { LogEntry, Task } from '../../protocol.js';
import { readJson } from '../../util/fsx.js';
import { truncate } from '../../util/text.js';
import { ToolError } from './tools.js';

const PROFILE_RE = /^[a-zA-Z0-9_-]+$/;

export class Town {
  private cache = new Map<string, { mtimeMs: number; data: StateData }>();

  constructor(
    readonly home: string,
    readonly self: string,
    private readonly own: () => StateData,
    private readonly now: () => number = Date.now,
  ) {}

  /** Every profile with a state.json, this one first. */
  profiles(): string[] {
    let names: string[] = [];
    try {
      names = fs.readdirSync(this.home, { withFileTypes: true })
        .filter((e) => e.isDirectory() && PROFILE_RE.test(e.name) && fs.existsSync(path.join(this.home, e.name, 'state.json')))
        .map((e) => e.name);
    } catch {
      /* no home yet */
    }
    return [this.self, ...names.filter((n) => n !== this.self).sort()];
  }

  /** hub id -> profile, from config.json "hubs.profiles". */
  private mapping(): Record<string, string> {
    try {
      const cfg = readJson<{ hubs?: { profiles?: Record<string, unknown> } }>(path.join(this.home, 'config.json'));
      const out: Record<string, string> = {};
      for (const [k, v] of Object.entries(cfg?.hubs?.profiles ?? {})) if (typeof v === 'string') out[k] = v;
      return out;
    } catch {
      return {};
    }
  }

  /** The hub ids that run `profile` (a hub id is its profile unless config.json maps it elsewhere). */
  hubIds(profile: string): string[] {
    const m = this.mapping();
    return Object.entries(m).filter(([, p]) => p === profile).map(([h]) => h);
  }

  /** A profile from a profile name or a hub id (case-insensitive). */
  resolve(hub: unknown): string {
    if (typeof hub !== 'string' || !hub.trim()) throw new ToolError('hub is required (see list_hubs)');
    const want = hub.trim().toLowerCase();
    const profiles = this.profiles();
    const direct = profiles.find((p) => p.toLowerCase() === want);
    if (direct) return direct;
    const mapped = Object.entries(this.mapping()).find(([h]) => h.toLowerCase() === want)?.[1];
    if (mapped && profiles.includes(mapped)) return mapped;
    throw new ToolError(`no hub "${hub}" (hubs: ${profiles.join(', ')})`);
  }

  state(profile: string): StateData {
    if (profile === this.self) return this.own();
    const file = path.join(this.home, profile, 'state.json');
    let mtimeMs: number;
    try {
      mtimeMs = fs.statSync(file).mtimeMs;
    } catch {
      throw new ToolError(`hub ${profile} has no state`);
    }
    const hit = this.cache.get(profile);
    if (hit && hit.mtimeMs === mtimeMs) return hit.data;
    let data: StateData | undefined;
    try {
      data = readJson<StateData>(file);
    } catch (e) {
      throw new ToolError(`could not read hub ${profile}: ${(e as Error).message}`);
    }
    if (!data) throw new ToolError(`hub ${profile} has no state`);
    this.cache.set(profile, { mtimeMs, data });
    return data;
  }

  private run(profile: string): (RunInfo & { alive: boolean }) | undefined {
    try {
      const r = readJson<RunInfo>(path.join(this.home, profile, 'foreman.json'));
      if (!r || typeof r.pid !== 'number') return undefined;
      return { ...r, alive: profile === this.self || pidAlive(r.pid) };
    } catch {
      return undefined;
    }
  }

  private ago(ts: number | undefined): string {
    if (!ts) return 'never';
    const s = Math.max(0, Math.round((this.now() - ts) / 1000));
    if (s < 90) return `${s}s ago`;
    if (s < 90 * 60) return `${Math.round(s / 60)}m ago`;
    if (s < 36 * 3600) return `${Math.round(s / 3600)}h ago`;
    return `${Math.round(s / 86400)}d ago`;
  }

  private label(profile: string): string {
    const ids = this.hubIds(profile).filter((h) => h !== profile);
    return `${profile}${ids.length ? ` (hub ${ids.join(', ')})` : ''}${profile === this.self ? ' [this is you]' : ''}`;
  }

  // ---- tools ----------------------------------------------------------------------------------

  listHubs(): string {
    const out: string[] = [];
    for (const p of this.profiles()) {
      let s: StateData;
      try {
        s = this.state(p);
      } catch (e) {
        out.push(`- ${this.label(p)}: unreadable (${(e as Error).message})`);
        continue;
      }
      const r = this.run(p);
      const busy = s.agents.filter((a) => a.active && a.state !== 'idle');
      const last = Math.max(0, ...s.feed.map((f) => f.ts));
      out.push(`- ${this.label(p)}: ${r ? `${r.backend} backend, ${r.alive ? 'running' : 'stopped'}` : 'never started'}`
        + ` | agents ${s.agents.filter((a) => a.active).length} on shift, ${busy.length} busy`
        + ` | tasks ${taskCounts(s.tasks) || 'none'}`
        + ` | ${s.decisions.filter((d) => d.status === 'open').length} open decisions`
        + ` | repos: ${s.repos.map((x) => x.name).join(', ') || 'none'}`
        + ` | spend ${usd(spend(s))}`
        + ` | last activity ${this.ago(last || undefined)}`);
    }
    return out.join('\n') || 'no hubs';
  }

  overview(hub: unknown): string {
    const p = this.resolve(hub);
    const s = this.state(p);
    const r = this.run(p);
    const lines = [`# ${this.label(p)}`, r ? `${r.backend} backend, ${r.alive ? 'running' : 'stopped'}, started ${r.startedAt}` : 'never started', `spend ${usd(spend(s))}`];
    lines.push('', '## Goals (newest last)');
    for (const g of s.goals.slice(-6)) lines.push(`- ${g.id} [${g.status}${g.mode ? `, ${g.mode}` : ''}] ${truncate(g.text, 160)} (${Math.round(g.progress * 100)}%, ${this.ago(g.updatedAt)})`);
    if (!s.goals.length) lines.push('none');
    lines.push('', '## Agents');
    for (const a of s.agents) {
      lines.push(`- ${a.id} (${a.title ?? a.role}): ${a.active ? (a.paused ? 'paused' : a.state) : 'off shift'}${a.activity ? ` - ${a.activity}` : ''}${a.taskId ? ` [${a.taskId}]` : ''}`);
    }
    lines.push('', `## Tasks: ${taskCounts(s.tasks) || 'none'}`);
    for (const t of s.tasks.filter((x) => x.status === 'doing' || x.status === 'review' || x.status === 'blocked')) lines.push(taskLine(t, this.ago(t.updatedAt)));
    const open = s.decisions.filter((d) => d.status === 'open');
    lines.push('', `## Open decisions: ${open.length}`);
    for (const d of open) lines.push(`- ${d.id} ${d.kind} from ${d.agentId}${d.taskId ? ` [${d.taskId}]` : ''}: ${truncate(d.question, 160)} (${this.ago(d.createdAt)})`);
    lines.push('', '## Repos');
    for (const x of s.repos) {
      const wts = x.worktrees.filter((w) => w.status === 'active');
      lines.push(`- ${x.id} "${x.name}" ${x.path} on ${x.branch}${x.head ? `@${x.head}` : ''}${x.dirty ? ' (dirty)' : ''}${x.remote ? ` remote ${x.remote}` : ''}`);
      for (const w of wts) lines.push(`  - worktree ${w.id}: ${w.agentId}${w.taskId ? ` ${w.taskId}` : ''} on ${w.branch}, ${w.files} files +${w.additions} -${w.deletions}`);
    }
    if (!s.repos.length) lines.push('none');
    return lines.join('\n');
  }

  tasks(hub: unknown, status: unknown, limit: unknown): string {
    const s = this.state(this.resolve(hub));
    const want = typeof status === 'string' && status.trim() ? status.trim().toLowerCase() : undefined;
    const n = clamp(limit, 1, 200, 40);
    const list = s.tasks.filter((t) => !want || t.status === want).sort((a, b) => b.updatedAt - a.updatedAt).slice(0, n);
    return list.map((t) => taskLine(t, this.ago(t.updatedAt))).join('\n') || 'no tasks';
  }

  task(hub: unknown, id: unknown): string {
    const p = this.resolve(hub);
    const s = this.state(p);
    const t = s.tasks.find((x) => x.id === String(id ?? '').trim());
    if (!t) throw new ToolError(`no task "${String(id)}" in ${p}`);
    const lines = [
      `${t.id} [${t.status}] ${t.title}`,
      `assignee ${t.assignee ?? '-'}, created by ${t.createdBy} ${this.ago(t.createdAt)}, updated ${this.ago(t.updatedAt)}`,
      `goal ${t.goalId ?? '-'}, repo ${t.repoId ?? '-'}, branch ${t.branch ?? '-'}, worktree ${t.worktree ?? '-'}, ci ${t.ci}${t.deps.length ? `, after ${t.deps.join(', ')}` : ''}`,
    ];
    const cost = taskCost(s, t);
    if (cost) lines.push(`cost ${usd(cost)}${t.model ? ` on ${t.model}` : ''}`);
    if (t.blockedReason) lines.push(`blocked: ${t.blockedReason}`);
    if (t.description) lines.push('', 'Description:', truncate(t.description, 3000));
    if (t.summary) lines.push('', 'Summary:', truncate(t.summary, 3000));
    const ds = s.decisions.filter((d) => d.taskId === t.id);
    if (ds.length) {
      lines.push('', 'Decisions:');
      for (const d of ds) lines.push(`- ${d.id} ${d.kind} [${d.status}${d.answer?.option ? `: ${d.answer.option}` : ''}${d.answer?.text ? ` "${truncate(d.answer.text, 200)}"` : ''}] ${truncate(d.question, 200)}`);
    }
    return lines.join('\n');
  }

  feed(hub: unknown, limit: unknown): string {
    const s = this.state(this.resolve(hub));
    const items = s.feed.slice(-clamp(limit, 1, 200, 40));
    return items.map((f) => `${this.ago(f.ts)} [${f.kind}]${f.agentId ? ` ${f.agentId}${f.to ? ` -> ${f.to}` : ''}:` : ''} ${truncate(f.text, 400)}`).join('\n') || 'no activity';
  }

  log(hub: unknown, agent: unknown, limit: unknown): string {
    const p = this.resolve(hub);
    const id = String(agent ?? '').trim().toLowerCase();
    if (!PROFILE_RE.test(id)) throw new ToolError('agent is required (an agent id, e.g. "kit")');
    const file = path.join(this.home, p, 'logs', `${id}.jsonl`);
    if (!fs.existsSync(file)) throw new ToolError(`no log for ${id} in ${p}`);
    const n = clamp(limit, 1, 200, 40);
    const lines = readTail(file, 256 * 1024).slice(-n);
    const out: string[] = [];
    for (const l of lines) {
      try {
        const e = JSON.parse(l) as LogEntry;
        out.push(`${this.ago(e.ts)} [${e.kind}] ${truncate(e.text, 500)}`);
      } catch {
        /* a partial line */
      }
    }
    return out.join('\n') || 'empty log';
  }

  usage(hub: unknown): string {
    const profiles = hub ? [this.resolve(hub)] : this.profiles();
    const out: string[] = [];
    let total = 0;
    for (const p of profiles) {
      let s: StateData;
      try {
        s = this.state(p);
      } catch {
        continue;
      }
      const sum = spend(s);
      total += sum;
      out.push(`## ${this.label(p)}: ${usd(sum)}`);
      // the Claude team: one session per agent and task (or the lead and goal)
      const sessions = Object.entries(s.sessions ?? {}).filter(([, x]) => x.costUsd > 0 || x.turns > 0);
      if (sessions.length) {
        const byModel = new Map<string, number>();
        const byAgent = new Map<string, number>();
        for (const [key, x] of sessions) {
          byModel.set(x.model ?? '?', (byModel.get(x.model ?? '?') ?? 0) + x.costUsd);
          const agent = key.split(':')[0]!;
          byAgent.set(agent, (byAgent.get(agent) ?? 0) + x.costUsd);
        }
        out.push(`by model: ${[...byModel].sort((a, b) => b[1] - a[1]).map(([m, c]) => `${m} ${usd(c)}`).join(', ')}`);
        out.push(`by agent: ${[...byAgent].sort((a, b) => b[1] - a[1]).map(([m, c]) => `${m} ${usd(c)}`).join(', ')}`);
        out.push('top sessions (agent:task or agent:goal):');
        for (const [key, x] of sessions.sort((a, b) => b[1].costUsd - a[1].costUsd).slice(0, 10)) out.push(`- ${key}: ${usd(x.costUsd)}, ${x.turns} turns, ${x.model ?? '?'}, ${this.ago(x.updatedAt)}`);
      }
      // the freelancer: spend per task and model
      const priced = s.tasks.filter((t) => t.costUsd);
      if (priced.length) {
        const byModel = new Map<string, number>();
        for (const t of priced) byModel.set(t.model ?? '?', (byModel.get(t.model ?? '?') ?? 0) + t.costUsd!);
        out.push(`by model: ${[...byModel].sort((a, b) => b[1] - a[1]).map(([m, c]) => `${m} ${usd(c)}`).join(', ')}`);
        out.push('top tasks:');
        for (const t of priced.sort((a, b) => b.costUsd! - a.costUsd!).slice(0, 10)) out.push(`- ${t.id} ${truncate(t.title, 80)}: ${usd(t.costUsd!)}${t.model ? ` on ${t.model}` : ''}`);
      }
    }
    if (!hub) out.unshift(`Total across hubs: ${usd(total)}`);
    return out.join('\n');
  }

  /** The folder of a hub's repo (its main checkout, or one of its worktrees) for the read tools. */
  repoRoot(hub: unknown, repo: unknown, worktree: unknown): string {
    const p = this.resolve(hub);
    const s = this.state(p);
    const want = String(repo ?? '').trim().toLowerCase();
    const r = want
      ? s.repos.find((x) => x.id.toLowerCase() === want || x.name.toLowerCase() === want)
      : s.repos.length === 1 ? s.repos[0] : undefined;
    if (!r) throw new ToolError(want ? `no repo "${String(repo)}" in ${p} (repos: ${s.repos.map((x) => x.id).join(', ') || 'none'})` : `say which repo of ${p} (repos: ${s.repos.map((x) => x.id).join(', ') || 'none'})`);
    let root = r.path;
    if (typeof worktree === 'string' && worktree.trim()) {
      const w = r.worktrees.find((x) => x.id === worktree.trim());
      if (!w) throw new ToolError(`no worktree "${worktree}" in ${r.id} (active: ${r.worktrees.filter((x) => x.status === 'active').map((x) => x.id).join(', ') || 'none'})`);
      root = w.path;
    }
    if (!fs.existsSync(root)) throw new ToolError(`${root} is gone`);
    return root;
  }
}

function pidAlive(pid: number): boolean {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === 'EPERM';
  }
}

function readTail(file: string, maxBytes: number): string[] {
  const size = fs.statSync(file).size;
  const len = Math.min(size, maxBytes);
  if (!len) return [];
  const fd = fs.openSync(file, 'r');
  try {
    const buf = Buffer.alloc(len);
    fs.readSync(fd, buf, 0, len, size - len);
    let text = buf.toString('utf8');
    if (len < size) text = text.slice(text.indexOf('\n') + 1);
    return text.split('\n').filter(Boolean);
  } finally {
    fs.closeSync(fd);
  }
}

function clamp(v: unknown, min: number, max: number, dflt: number): number {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? Math.max(min, Math.min(max, Math.round(n))) : dflt;
}

const usd = (n: number) => `$${n < 1 ? n.toFixed(4) : n.toFixed(2)}`;

/** What a profile has spent: Claude sessions plus the freelancer's provider-reported spend. */
export function spend(s: StateData): number {
  const sessions = Object.values(s.sessions ?? {}).reduce((sum, x) => sum + (x.costUsd || 0), 0);
  const open = (s.backend?.open as { costUsd?: number } | undefined)?.costUsd;
  return sessions + (typeof open === 'number' ? open : s.tasks.reduce((sum, t) => sum + (t.costUsd ?? 0), 0));
}

function taskCost(s: StateData, t: Task): number {
  if (t.costUsd) return t.costUsd;
  return Object.entries(s.sessions ?? {}).filter(([k]) => k.endsWith(`:${t.id}`)).reduce((sum, [, x]) => sum + (x.costUsd || 0), 0);
}

function taskCounts(tasks: Task[]): string {
  const c = new Map<string, number>();
  for (const t of tasks) c.set(t.status, (c.get(t.status) ?? 0) + 1);
  return [...c].map(([k, n]) => `${k} ${n}`).join(', ');
}

function taskLine(t: Task, ago: string): string {
  return `- ${t.id} [${t.status}] ${truncate(t.title, 120)}${t.assignee ? ` (${t.assignee})` : ''}${t.blockedReason ? ` blocked: ${truncate(t.blockedReason, 120)}` : ''} - ${ago}`;
}
