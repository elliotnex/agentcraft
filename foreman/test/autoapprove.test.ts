// Auto-approve levels: which permission prompts the Foreman answers itself. Rows go through the
// real classifier, so the rule keys tested are the ones agents actually produce.
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { AUTO_APPROVE_LEVELS, autoApproves, classifyToolUse, ruleKeyScope, type AutoApprove, type PolicyContext } from '../src/policy.js';

const wt = path.join(os.tmpdir(), 'ac-policy', 'worktrees', 'demo-app', 'kit-t2');
const outside = path.join(os.tmpdir(), 'ac-policy', 'elsewhere', 'secret.txt').replace(/\\/g, '/');
const worker: PolicyContext = { role: 'worker', cwd: wt, tempDirs: [] };
const lead: PolicyContext = { role: 'lead', cwd: wt, tempDirs: [] };

// the lowest level that approves the call without asking
type Row = [label: string, tool: string, input: Record<string, unknown>, ctx: PolicyContext, lowest: Exclude<AutoApprove, 'off'>];
const bash = (command: string, lowest: Row[4], ctx = worker): Row => [command, 'Bash', { command }, ctx, lowest];

const rows: Row[] = [
  bash('rm -r build', 'worktree'),
  bash('git reset --hard HEAD~1', 'worktree'),
  bash('git clean -fd', 'worktree'),
  bash('git checkout feature-x', 'worktree'),
  bash('find . -name "*.tmp" -delete', 'worktree'),
  bash('npm install lodash', 'network'),
  bash('pnpm add zod', 'network'),
  bash('npm view react', 'network'),
  bash('npx cowsay hi', 'network'),
  bash('curl https://example.com', 'network'),
  bash('rm -r build && npm install', 'network'),
  ['WebFetch', 'WebFetch', { url: 'https://docs.example.com/x' }, worker, 'network'],
  ['WebSearch', 'WebSearch', { query: 'vitest config' }, worker, 'network'],
  bash(`cat ${outside}`, 'all'),
  bash(`curl https://example.com -o ${outside}`, 'all'),
  bash('taskkill /F /IM node.exe', 'all'),
  bash('git branch feature-y', 'all'),
  bash('git config user.name x', 'all'),
  ['Write outside', 'Write', { file_path: outside, content: 'x' }, worker, 'all'],
  bash('npm install', 'all', lead),
];

describe('auto-approve', () => {
  it.each(rows)('%s', (_label, tool, input, ctx, lowest) => {
    const v = classifyToolUse(tool, input, ctx);
    expect(v.action).toBe('ask');
    if (v.action !== 'ask') return;
    const from = AUTO_APPROVE_LEVELS.indexOf(lowest);
    for (const [i, level] of AUTO_APPROVE_LEVELS.entries()) {
      expect(autoApproves(level, v.ruleKeys), `${level} for ${v.ruleKeys.join(', ')}`).toBe(i >= from);
    }
  });

  it('never turns a denial into an allow', () => {
    expect(classifyToolUse('Bash', { command: 'git push origin main' }, worker).action).toBe('deny');
    expect(classifyToolUse('Edit', { file_path: path.join(wt, 'a.ts'), old_string: 'a', new_string: 'b' }, lead).action).toBe('deny');
  });

  it('treats unknown and unverifiable keys as other', () => {
    expect(ruleKeyScope('Bash:exact:abc123')).toBe('other');
    expect(ruleKeyScope('Bash:some-new-kind')).toBe('other');
    expect(ruleKeyScope('mcp__github__create_issue')).toBe('other');
    expect(autoApproves('network', [])).toBe(false);
  });
});
