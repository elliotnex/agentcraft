// The open backend: Scout, a freelancer on any OpenAI-compatible model (OpenRouter by default, or a
// local Ollama / LM Studio). One job at a time. A task runs in its own worktree and ends in the
// usual merge decision; a question ("ask") reads the main checkout and answers in the feed. Shell
// commands go through the same permission policy as the Claude team.
import type { Backend, Foreman } from '../../foreman.js';
import type { OpenConfig } from '../../config.js';
import { OPENROUTER_URL } from '../../config.js';
import { SCOUT } from '../../cast.js';
import { autoApproves, classifyBash, describeRuleKey } from '../../policy.js';
import { MERGE_OPTIONS, PERMISSION_OPTIONS, type Decision, type Goal, type GoalMode, type ModelInfo, type Task } from '../../protocol.js';
import { estimateCost, fetchModels } from './models.js';
import { firstLine, truncate } from '../../util/text.js';
import { userName } from '../../user.js';
import { openAiClient, type ChatMessage, type CompleteFn } from './client.js';
import { editFile, listFiles, readFile, runCommand, search, toolsFor, ToolError, writeFile } from './tools.js';

const ID = SCOUT.id;

interface OpenState {
  costUsd: number;
  model?: string;
  /** task id -> model it runs on (a retry / requested change keeps it) */
  taskModels: Record<string, string>;
  ciFixes: Record<string, number>;
}

interface Job {
  taskId: string;
  mode: GoalMode;
  /** follow-up for an existing conversation (requested changes, CI failure) */
  followup?: string;
}

export class OpenBackend implements Backend {
  readonly name = 'open' as const;
  private readonly complete: CompleteFn;
  private readonly jobs: Job[] = [];
  private running?: { taskId: string; abort: AbortController };
  /** conversations by task, for requested changes (lost on restart: a follow-up then starts fresh) */
  private readonly chats = new Map<string, ChatMessage[]>();
  private stopping = false;
  private catalog?: { at: number; models: ModelInfo[] };
  private catalogLoading?: Promise<ModelInfo[]>;
  private readonly fetchCatalog: () => Promise<ModelInfo[]>;

  constructor(
    private readonly fm: Foreman,
    private readonly cfg: OpenConfig,
    complete?: CompleteFn,
    private readonly env: NodeJS.ProcessEnv = process.env,
    fetchCatalog?: () => Promise<ModelInfo[]>,
  ) {
    this.fetchCatalog = fetchCatalog ?? (() => fetchModels(cfg.baseUrl, this.apiKey()));
    this.complete = complete ?? openAiClient({ baseUrl: cfg.baseUrl, ...(this.apiKey() ? { apiKey: this.apiKey()! } : {}), appName: 'AgentCraft' });
  }

  private apiKey(): string | undefined {
    return this.cfg.apiKeyEnv ? this.env[this.cfg.apiKeyEnv]?.trim() || undefined : undefined;
  }

  private get st(): OpenState {
    const b = this.fm.store.data.backend;
    const s = (b.open ??= { costUsd: 0, taskModels: {}, ciFixes: {} }) as OpenState;
    s.taskModels ??= {};
    s.ciFixes ??= {};
    s.costUsd ??= 0;
    return s;
  }

  model(): string {
    return this.st.model ?? this.cfg.model;
  }

  private provider(): string {
    if (this.cfg.baseUrl === OPENROUTER_URL) return 'OpenRouter';
    if (/localhost:11434|127\.0\.0\.1:11434/.test(this.cfg.baseUrl)) return 'Ollama';
    if (/localhost:1234|127\.0\.0\.1:1234/.test(this.cfg.baseUrl)) return 'LM Studio';
    return this.cfg.baseUrl.replace(/^https?:\/\//, '');
  }

  private publishStatus(): void {
    this.fm.setStatus({ model: this.model(), autoApprove: this.cfg.autoApprove, costUsd: Math.round(this.st.costUsd * 10000) / 10000 });
  }

  async start(): Promise<void> {
    if (this.cfg.baseUrl === OPENROUTER_URL && !this.apiKey()) {
      this.fm.setStatus({ auth: 'failed', account: this.provider(), model: this.model(), message: `No OpenRouter key: set ${this.cfg.apiKeyEnv} and restart the freelancer` });
    } else {
      this.fm.setStatus({ auth: 'ok', account: this.provider(), message: `Freelancer on ${this.model()} (${this.provider()})` });
    }
    this.publishStatus();
    // a job cut short by a restart: put it back on the board for a retry
    for (const t of this.fm.tasks.list()) {
      if (t.assignee === ID && t.status === 'doing') this.fm.tasks.setStatus(t.id, 'blocked', { force: true, reason: 'interrupted by a restart: retry it' });
    }
    this.fm.setAgent(ID, { active: true, paused: false, state: 'idle', station: 'terminal', activity: `ready (${shortModel(this.model())})`, taskId: null, worktree: null });
    // prices for cost estimates (when a provider does not report spend); the terminal asks for it anyway
    void this.listModels().catch((e: Error) => this.fm.log.info(`model list: ${e.message}`));
  }

  /** The endpoint's models (cached ten minutes; a failed fetch keeps the last list). */
  async listModels(refresh = false): Promise<ModelInfo[]> {
    if (!refresh && this.catalog && Date.now() - this.catalog.at < 10 * 60_000) return this.catalog.models;
    this.catalogLoading ??= this.fetchCatalog()
      .then((models) => {
        this.catalog = { at: Date.now(), models };
        return models;
      })
      .catch((e: Error) => {
        if (this.catalog) return this.catalog.models;
        throw e;
      })
      .finally(() => (this.catalogLoading = undefined));
    return this.catalogLoading;
  }

  private modelInfo(id: string): ModelInfo | undefined {
    return this.catalog?.models.find((m) => m.id === id);
  }

  async stop(): Promise<void> {
    this.stopping = true;
    this.jobs.length = 0;
    this.running?.abort.abort();
  }

  /** Change the default model (a goal naming one does this too). */
  setModel(model: string): void {
    const m = model.trim();
    if (!m || m === this.model()) return;
    this.st.model = m;
    this.fm.store.markDirty();
    this.fm.setStatus({ message: `Freelancer on ${m} (${this.provider()})` });
    this.publishStatus();
    this.fm.bus.feed('message', `Scout now runs on ${m}`, { agentId: ID });
    if (!this.running) this.fm.setAgent(ID, { activity: `ready (${shortModel(m)})` });
  }

  async submitGoal(goal: Goal): Promise<void> {
    if (goal.model) this.setModel(goal.model);
    let text = goal.text;
    let mode: GoalMode = goal.mode ?? 'task';
    if (!goal.mode && /^\s*\?/.test(text)) {
      mode = 'ask';
      text = text.replace(/^\s*\?\s*/, '');
    }
    const t = this.fm.tasks.create({
      title: `${mode === 'ask' ? 'Q: ' : ''}${firstLine(text, 72)}`,
      description: text,
      assignee: ID,
      createdBy: 'user',
      ...(goal.repoId ? { repoId: goal.repoId } : {}),
      goalId: goal.id,
    });
    this.st.taskModels[t.id] = this.model();
    this.fm.tasks.update(t.id, { model: this.model() });
    this.fm.store.markDirty();
    this.fm.setGoal(goal.id, { status: 'active' });
    this.enqueue({ taskId: t.id, mode });
  }

  onUserMessage(_to: string, text: string): void {
    const m = /^\s*\/model\s+(\S+)/.exec(text);
    if (m) {
      this.setModel(m[1]!);
      return;
    }
    // anything else said to Scout is a question about the code
    void this.fm.submitGoal(text, undefined, { mode: 'ask' }).catch((e: Error) => this.fm.bus.send(ID, 'user', `I can't take that: ${e.message}`));
  }

  onDecisionSettled(d: Decision): void {
    if (d.kind !== 'merge' || !d.taskId) return;
    const t = this.fm.tasks.get(d.taskId);
    if (!t) return;
    const opt = d.answer?.option;
    if (opt === 'Request changes') {
      this.enqueue({ taskId: t.id, mode: 'task', followup: `${userName()} reviewed your work and requested changes:\n${d.answer?.text || '(no details given)'}\n\nMake the changes, check them, then call finish again.` });
    } else {
      this.chats.delete(t.id);
      if (!this.running) this.idle(opt === 'Merge' ? `${t.id} merged` : `${t.id} ${opt === 'Reject' ? 'rejected' : 'closed'}`);
    }
  }

  onTaskAction(task: Task, action: 'reassign' | 'cancel' | 'retry' | 'prioritize'): void {
    if (action === 'cancel') {
      this.drop(task.id);
      void this.windDown(task, 'cancelled');
    } else if (action === 'retry') {
      if (this.running?.taskId === task.id || this.jobs.some((j) => j.taskId === task.id)) return;
      const ask = task.title.startsWith('Q: ');
      this.fm.tasks.setStatus(task.id, 'todo', { force: true });
      this.enqueue({ taskId: task.id, mode: ask ? 'ask' : 'task', ...(this.chats.has(task.id) ? { followup: 'You were interrupted. Carry on from where you were, then call finish.' } : {}) });
    }
  }

  onAgentAction(agentId: string, action: 'pause' | 'resume' | 'stop' | 'spawn'): void {
    if (agentId !== ID) return;
    if (action === 'pause' || action === 'stop') {
      const cur = this.running?.taskId;
      this.jobs.length = 0;
      this.running?.abort.abort();
      if (cur) this.fm.tasks.setStatus(cur, 'blocked', { force: true, reason: `${action === 'pause' ? 'paused' : 'stopped'} by ${userName()}: retry to continue` });
    }
    if (action === 'resume' || action === 'spawn') this.fm.setAgent(ID, { active: true, paused: false });
  }

  // ---- jobs ---------------------------------------------------------------------------------

  private enqueue(job: Job): void {
    this.jobs.push(job);
    void this.pump();
  }

  private drop(taskId: string): void {
    for (let i = this.jobs.length - 1; i >= 0; i--) if (this.jobs[i]!.taskId === taskId) this.jobs.splice(i, 1);
    if (this.running?.taskId === taskId) this.running.abort.abort();
  }

  private async pump(): Promise<void> {
    if (this.running || this.stopping) return;
    const job = this.jobs.shift();
    if (!job) return;
    const abort = new AbortController();
    this.running = { taskId: job.taskId, abort };
    try {
      await this.run(job, abort.signal);
    } catch (e) {
      const t = this.fm.tasks.get(job.taskId);
      if (abort.signal.aborted) {
        if (t) this.fm.agentLog(ID, 'error', `${t.id} stopped`);
      } else {
        const msg = (e as Error).message;
        this.fm.log.warn(`scout ${job.taskId}: ${msg}`);
        this.fm.agentLog(ID, 'error', msg);
        if (t && t.status !== 'done' && t.status !== 'cancelled') this.fm.tasks.setStatus(t.id, 'blocked', { force: true, reason: truncate(msg, 200) });
        this.fm.bus.send(ID, 'user', `I hit a problem on ${job.taskId}: ${truncate(msg, 300)}`);
        this.fm.setAgent(ID, { state: 'error', station: 'terminal', activity: truncate(msg, 48) });
      }
    } finally {
      this.running = undefined;
      this.fm.store.markDirty();
      if (this.jobs.length) void this.pump();
      else if (this.fm.agent(ID)?.state !== 'error' && this.fm.agent(ID)?.state !== 'waiting_user') this.idle();
    }
  }

  private idle(activity?: string): void {
    this.fm.setAgent(ID, { state: 'idle', station: 'terminal', activity: activity ?? `ready (${shortModel(this.model())})`, taskId: null, worktree: null });
  }

  private async run(job: Job, signal: AbortSignal): Promise<void> {
    const t = this.fm.tasks.require(job.taskId);
    const repo = this.fm.repos.require(t.repoId ?? this.fm.repos.defaultRepo()?.id ?? '');
    const model = this.st.taskModels[t.id] ?? this.model();
    let cwd = repo.path;
    if (job.mode === 'task') {
      let wt = t.worktree ? this.fm.repos.findWorktree(repo.id, t.worktree) : undefined;
      if (!wt || wt.status !== 'active') {
        wt = await this.fm.repos.createWorktree(repo.id, ID, t);
        this.fm.tasks.update(t.id, { branch: wt.branch, worktree: wt.id });
      }
      cwd = wt.path;
      this.fm.setAgent(ID, { taskId: t.id, repoId: repo.id, worktree: wt.id });
    } else {
      this.fm.setAgent(ID, { taskId: t.id, repoId: repo.id });
    }
    if (t.status !== 'doing') this.fm.tasks.setStatus(t.id, 'doing', { force: true });
    this.fm.setAgent(ID, { state: 'thinking', station: 'terminal', activity: `${t.id} on ${shortModel(model)}` });
    this.fm.agentLog(ID, 'text', `${job.followup ? 'Back on' : 'Starting'} ${t.id} with ${model}`);

    let chat = this.chats.get(t.id);
    if (!chat || !job.followup) {
      chat = [
        { role: 'system', content: systemPrompt(job.mode, repo.branch) },
        { role: 'user', content: job.mode === 'ask' ? `Question from ${userName()}:\n${t.description ?? t.title}` : `Task from ${userName()}:\n${t.description ?? t.title}${job.followup && t.summary ? `\n\nEarlier you reported: ${t.summary}` : ''}` },
      ];
      this.chats.set(t.id, chat);
    }
    if (job.followup) chat.push({ role: 'user', content: job.followup });

    const tools = toolsFor(job.mode);
    let nudges = 0;
    for (let step = 0; step < this.cfg.maxSteps; step++) {
      if (signal.aborted) throw new Error('stopped');
      this.fm.setAgent(ID, { state: 'thinking', station: 'terminal' });
      const res = await this.complete({ model, messages: chat, tools, signal });
      const cost = res.costUsd ?? estimateCost(this.modelInfo(model), res.tokensIn, res.tokensOut);
      if (cost) {
        this.st.costUsd += cost;
        this.fm.tasks.update(t.id, { costUsd: (this.fm.tasks.get(t.id)?.costUsd ?? 0) + cost });
        this.publishStatus();
      }
      const msg = res.message;
      chat.push(msg);
      if (msg.content?.trim()) this.fm.agentLog(ID, 'text', msg.content.trim());
      const calls = msg.tool_calls ?? [];
      if (!calls.length) {
        // a plain reply: for a question that is the answer; a task gets one reminder to use the tools
        if (job.mode === 'ask' && msg.content?.trim()) return this.answered(t, msg.content.trim());
        if (nudges++ < 2) {
          chat.push({ role: 'user', content: job.mode === 'ask' ? 'Call the answer tool with your answer.' : 'Use the tools to do the work. When the change is done and checked, call finish with a summary.' });
          continue;
        }
        if (job.mode === 'task' && msg.content?.trim()) return this.finished(t, msg.content.trim(), signal);
        throw new Error(`${shortModel(model)} stopped without using its tools (it may not support tool calling)`);
      }
      for (const call of calls) {
        if (signal.aborted) throw new Error('stopped');
        let args: Record<string, unknown> = {};
        try {
          args = call.function.arguments ? (JSON.parse(call.function.arguments) as Record<string, unknown>) : {};
        } catch {
          chat.push({ role: 'tool', tool_call_id: call.id, content: 'Error: the arguments were not valid JSON. Try again.' });
          continue;
        }
        const name = call.function.name;
        if (name === 'finish' && job.mode === 'task') {
          chat.push({ role: 'tool', tool_call_id: call.id, content: 'ok' });
          return this.finished(t, String(args.summary ?? 'Done.'), signal);
        }
        if (name === 'answer' && job.mode === 'ask') {
          chat.push({ role: 'tool', tool_call_id: call.id, content: 'ok' });
          return this.answered(t, String(args.text ?? ''));
        }
        const out = await this.tool(name, args, cwd, job.mode, signal);
        chat.push({ role: 'tool', tool_call_id: call.id, content: out });
      }
    }
    throw new Error(`ran out of steps (${this.cfg.maxSteps}) before finishing; retry to give it more`);
  }

  private async tool(name: string, args: Record<string, unknown>, cwd: string, mode: GoalMode, signal: AbortSignal): Promise<string> {
    const show = describe(name, args);
    try {
      if (!toolsFor(mode).some((s) => s.function.name === name)) throw new ToolError(`no tool named ${name}`);
      switch (name) {
        case 'list_files':
          this.fm.setAgent(ID, { state: 'reading', activity: show });
          return listFiles(cwd, args.path, args.depth);
        case 'read_file':
          this.fm.setAgent(ID, { state: 'reading', activity: show });
          this.fm.agentLog(ID, 'tool', show);
          return readFile(cwd, args.path, args.offset, args.limit);
        case 'search':
          this.fm.setAgent(ID, { state: 'reading', activity: show });
          this.fm.agentLog(ID, 'tool', show);
          return await search(cwd, args.pattern, args.path);
        case 'write_file':
        case 'edit_file': {
          this.fm.setAgent(ID, { state: 'editing', station: 'desk', activity: show });
          const r = name === 'write_file' ? writeFile(cwd, args.path, args.content) : editFile(cwd, args.path, args.old_text, args.new_text);
          this.fm.agentLog(ID, 'tool', r);
          const repoId = this.fm.agent(ID)?.repoId;
          if (repoId) this.fm.repos.scheduleRefresh(repoId);
          return r;
        }
        case 'run_command': {
          const command = String(args.command ?? '').trim();
          if (!command) throw new ToolError('command is required');
          const ok = await this.permit(command, cwd, signal);
          if (ok !== true) return ok;
          this.fm.setAgent(ID, { state: command.match(/\b(test|jest|vitest|pytest|cargo test|go test)\b/) ? 'testing' : 'running', station: 'terminal', activity: truncate(command, 48) });
          this.fm.agentLog(ID, 'tool', `$ ${command}`);
          const r = await runCommand(cwd, ID, command);
          this.fm.agentLog(ID, r.startsWith('exit 0') ? 'result' : 'error', truncate(r, 600));
          return r;
        }
        default:
          throw new ToolError(`no tool named ${name}`);
      }
    } catch (e) {
      const msg = e instanceof ToolError ? e.message : `failed: ${(e as Error).message}`;
      this.fm.agentLog(ID, 'error', `${show}: ${msg}`);
      return `Error: ${msg}`;
    }
  }

  /** true, or the tool result explaining why not. Same policy and decision flow as the Claude team. */
  private async permit(command: string, cwd: string, signal: AbortSignal): Promise<true | string> {
    const rules = this.fm.store.data.permissionRules[ID] ?? [];
    const v = classifyBash(command, { role: 'worker', cwd, alwaysAllow: rules });
    if (v.action === 'allow') return true;
    if (v.action === 'deny') {
      this.fm.agentLog(ID, 'error', `blocked: ${command} (${v.reason})`);
      return `Error: not allowed: ${v.reason}`;
    }
    if (autoApproves(this.cfg.autoApprove, v.ruleKeys)) {
      this.fm.agentLog(ID, 'result', `auto-approved (${this.cfg.autoApprove}): ${command}`);
      return true;
    }
    const prev = this.fm.agent(ID);
    const d = this.fm.createDecision({
      agentId: ID,
      kind: 'permission',
      tool: 'Bash',
      question: `Scout wants to run ${truncate(command, 160)}`,
      options: [...PERMISSION_OPTIONS],
      context: `${v.reason}\ncwd: ${cwd}\n"${PERMISSION_OPTIONS[1]}" covers: ${[...new Set(v.ruleKeys.map(describeRuleKey))].join('; ')}`,
      ...(prev?.taskId ? { taskId: prev.taskId } : {}),
    });
    this.fm.setAgent(ID, { state: 'waiting_user', station: 'terminal', activity: 'asking permission' });
    this.fm.agentLog(ID, 'tool', `permission? ${command}`);
    const onAbort = () => this.fm.decisions.cancel(d.id, 'stopped');
    signal.addEventListener('abort', onAbort, { once: true });
    const res = await this.fm.decisions.wait(d.id);
    signal.removeEventListener('abort', onAbort);
    if (signal.aborted) throw new Error('stopped');
    this.fm.setAgent(ID, { state: 'thinking', station: 'terminal', activity: prev?.activity ?? '' });
    const opt = res.answer?.option;
    if (res.status === 'answered' && (opt === PERMISSION_OPTIONS[0] || opt === PERMISSION_OPTIONS[1])) {
      if (opt === PERMISSION_OPTIONS[1]) {
        const list = (this.fm.store.data.permissionRules[ID] ??= []);
        for (const k of v.ruleKeys) if (!list.includes(k)) list.push(k);
        this.fm.store.markDirty();
      }
      return true;
    }
    this.fm.agentLog(ID, 'error', `${userName()} denied: ${command}`);
    return `Error: ${userName()} denied this${res.answer?.text ? `: ${res.answer.text}` : ''}. Find another way.`;
  }

  private answered(t: Task, text: string): void {
    const answer = text.trim() || '(no answer)';
    this.fm.tasks.setStatus(t.id, 'done', { force: true, summary: truncate(answer, 1000) });
    this.fm.bus.send(ID, 'user', answer);
    this.chats.delete(t.id);
    this.idle(`answered ${t.id}`);
  }

  private async finished(t: Task, summary: string, signal: AbortSignal): Promise<void> {
    if (!t.repoId || !t.worktree) throw new Error(`${t.id} has no worktree`);
    this.fm.tasks.setStatus(t.id, 'review', { force: true, summary: truncate(summary, 1000) });
    this.fm.agentLog(ID, 'result', `finished ${t.id}: ${truncate(summary, 300)}`);
    const wtPath = this.fm.repos.requireWorktree(t.repoId, t.worktree).path;
    const command = this.cfg.ciCommand ?? this.fm.repos.detectTestCommand(wtPath);
    if (command) {
      this.fm.setAgent(ID, { state: 'testing', station: 'terminal', activity: `CI for ${t.id}` });
      this.fm.tasks.update(t.id, { ci: 'running' });
      const ci = await this.fm.repos.runTests(t.repoId, t.worktree, command);
      this.fm.tasks.update(t.id, { ci: ci.pass ? 'pass' : 'fail' });
      this.fm.agentLog(ID, ci.pass ? 'result' : 'error', `CI ${ci.pass ? 'passed' : 'FAILED'} (${ci.command})`);
      if (!ci.pass && (this.st.ciFixes[t.id] ?? 0) < 1 && !signal.aborted) {
        this.st.ciFixes[t.id] = (this.st.ciFixes[t.id] ?? 0) + 1;
        // one round to fix it, in the same conversation
        this.jobs.unshift({ taskId: t.id, mode: 'task', followup: `The tests failed (${ci.command}):\n${ci.output.split('\n').slice(-40).join('\n')}\n\nFix them, then call finish again.` });
        return;
      }
    }
    await this.fm.repos.refresh(t.repoId);
    const wt = this.fm.repos.requireWorktree(t.repoId, t.worktree);
    const cur = this.fm.tasks.require(t.id);
    this.fm.createDecision({
      agentId: ID,
      kind: 'merge',
      question: `Merge ${t.id} "${t.title}" (${wt.branch}) into ${wt.base}?`,
      options: [...MERGE_OPTIONS],
      context: `${summary}\n${wt.files} files, +${wt.additions} -${wt.deletions} | tests: ${cur.ci} | model: ${this.st.taskModels[t.id] ?? this.model()}${cur.costUsd ? ` | cost: $${cur.costUsd.toFixed(4)}` : ''}`,
      taskId: t.id,
      repoId: t.repoId,
      worktree: wt.id,
    });
    this.fm.bus.feed('task', `Scout finished ${t.id}: ready for your review`, { agentId: ID });
    this.fm.setAgent(ID, { state: 'idle', station: 'terminal', activity: `awaiting your review of ${t.id}` });
  }

  private async windDown(t: Task, how: 'cancelled'): Promise<void> {
    if (t.repoId && t.worktree) {
      const wt = this.fm.repos.findWorktree(t.repoId, t.worktree);
      if (wt?.status === 'active') await this.fm.repos.abandon(t.repoId, wt.id, `agentcraft: ${t.id} (${how})`).catch((e: Error) => this.fm.log.warn(`abandon: ${e.message}`));
    }
    this.chats.delete(t.id);
    if (!this.running) this.idle(`${t.id} ${how}`);
  }
}

const shortModel = (m: string) => m.replace(/^[^/]+\//, '');

function describe(name: string, a: Record<string, unknown>): string {
  const p = typeof a.path === 'string' ? a.path : '';
  switch (name) {
    case 'list_files':
      return `listing ${p || '.'}`;
    case 'read_file':
      return `reading ${p}`;
    case 'search':
      return `searching ${String(a.pattern ?? '')}`;
    case 'write_file':
      return `writing ${p}`;
    case 'edit_file':
      return `editing ${p}`;
    case 'run_command':
      return `$ ${String(a.command ?? '')}`;
    default:
      return name;
  }
}

function systemPrompt(mode: GoalMode, branch: string): string {
  const who = userName();
  const common = `You are Scout, a freelance software engineer working for ${who} inside AgentCraft. You work alone on one request at a time, using only the tools you are given. Paths are relative to the repository root. Be concise in your messages.`;
  if (mode === 'ask') {
    return `${common}

${who} asked a question about this repository (branch ${branch}). Look at the code with list_files, search and read_file until you can answer accurately, then call answer with your answer: direct, specific, citing files and line numbers where useful. You cannot change files.`;
  }
  return `${common}

You are in your own git worktree, a fresh branch off ${branch}, so you can change files freely. Steps:
1. Look around first (list_files, search, read_file) so your change fits the code's style.
2. Make the change with edit_file (exact text replacement; read the file first) or write_file.
3. Check it: run the tests or a build with run_command if the project has them.
4. Call finish with a short summary of what you changed and how you checked it.
Do not commit or push: when you finish, ${who} reviews the diff and decides whether to merge. Keep the change focused on the request.`;
}
