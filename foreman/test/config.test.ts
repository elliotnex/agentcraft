import { afterEach, describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';
import { rmrf, tempDir } from './helpers.js';

let home: string | undefined;
afterEach(() => {
  if (home) rmrf(home);
  home = undefined;
});

function load(args: string[]) {
  home = tempDir();
  return loadConfig(['--home', home, ...args], {});
}

describe('loadConfig argument checking', () => {
  it('accepts the documented claude flags', () => {
    const cfg = load(['--backend', 'claude', '--workers', 'juniper,kit', '--model', 'sonnet', '--effort', 'low', '--no-notify', '--max-budget', '2']);
    expect(cfg.claude.workers).toEqual(['juniper', 'kit']);
    expect(cfg.claude.leadModel).toBe('sonnet');
    expect(cfg.claude.workerModel).toBe('sonnet');
    expect(cfg.claude.effort).toBe('low');
    expect(cfg.claude.leadEffort).toBe('low');
    expect(cfg.notify).toBe(false);
  });

  it('accepts the sim flags launch.ps1 passes', () => {
    const cfg = load(['--backend', 'sim', '--profile', 'x', '--port', '41000', '--reset', '--showcase', 'late', '--speed', '2', '--autostart']);
    expect(cfg.sim.showcaseAt).toBe('showcase-late');
    expect(cfg.sim.speed).toBe(2);
  });

  // regression: PowerShell `-File launch.ps1 -ForemanArgs '--workers,juniper,kit,--model,sonnet'` hands
  // the Foreman ONE argument; it used to be ignored silently and the team started on opus/medium/3 workers
  it('refuses an argument that PowerShell joined with commas', () => {
    expect(() => load(['--backend', 'claude', '--workers,juniper,kit,--model,sonnet,--effort,low'])).toThrow(/unknown option "--workers,juniper,kit,--model,sonnet,--effort,low"/);
  });

  it('refuses mistyped flags and stray positionals', () => {
    expect(() => load(['--wokers', 'kit'])).toThrow(/unknown option "--wokers"/);
    expect(() => load(['--backend', 'sim', 'oops', 'extra'])).toThrow(/unexpected argument "oops"/);
  });

  it('refuses an unknown effort instead of falling back to medium', () => {
    expect(() => load(['--effort', 'lo'])).toThrow(/unknown effort "lo"/);
  });
});

describe('user name', () => {
  it('comes from --user-name, then AGENTCRAFT_USER_NAME, then config.json, else the OS account', async () => {
    const fs = await import('node:fs');
    const path = await import('node:path');
    const { defaultUserName } = await import('../src/user.js');
    expect(load(['--user-name', 'Sam']).userName).toBe('Sam');
    home = tempDir();
    expect(loadConfig(['--home', home], { AGENTCRAFT_USER_NAME: 'Robin' }).userName).toBe('Robin');
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ userName: 'Kai' }));
    expect(loadConfig(['--home', home], {}).userName).toBe('Kai');
    rmrf(home);
    const d = load([]).userName;
    expect(d).toBe(defaultUserName());
    expect(d.length).toBeGreaterThan(0);
  });

  it('is sent to the mod in foreman.status and used in prompts', async () => {
    const { makeForeman } = await import('./helpers.js');
    const { leadSystemPrompt } = await import('../src/agents/claude/prompts.js');
    home = tempDir();
    const h = makeForeman(home, ['--user-name', 'Sam']);
    try {
      expect(h.fm.status.userName).toBe('Sam');
      expect(h.fm.nameOf('user')).toBe('Sam');
      expect(leadSystemPrompt(h.fm, ['kit'])).toContain('The user is Sam.');
    } finally {
      await h.fm.close();
    }
  });
});

describe('account label', () => {
  it('comes from --account-label, then AGENTCRAFT_ACCOUNT_LABEL, then config.json; false or empty hides it', async () => {
    const fs = await import('node:fs');
    const path = await import('node:path');
    expect(load([]).claude.accountLabel).toBeUndefined();
    expect(load(['--account-label', 'Studio']).claude.accountLabel).toBe('Studio');
    expect(load(['--no-account-label']).claude.accountLabel).toBe(false);
    home = tempDir();
    expect(loadConfig(['--home', home], { AGENTCRAFT_ACCOUNT_LABEL: 'Env label' }).claude.accountLabel).toBe('Env label');
    expect(loadConfig(['--home', home], { AGENTCRAFT_ACCOUNT_LABEL: '' }).claude.accountLabel).toBe(false);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { accountLabel: false } }));
    expect(loadConfig(['--home', home], {}).claude.accountLabel).toBe(false);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { accountLabel: '  Max  ' } }));
    expect(loadConfig(['--home', home], {}).claude.accountLabel).toBe('Max');
  });
});

describe('auto-approve', () => {
  it('defaults to network; --auto-approve <level>, AGENTCRAFT_AUTO_APPROVE or config.json; --no-auto-approve is off', async () => {
    const fs = await import('node:fs');
    const path = await import('node:path');
    expect(load([]).claude.autoApprove).toBe('network');
    expect(load(['--auto-approve', 'worktree']).claude.autoApprove).toBe('worktree');
    expect(load(['--no-auto-approve']).claude.autoApprove).toBe('off');
    expect(() => load(['--auto-approve', 'everything'])).toThrow(/unknown auto-approve level "everything"/);
    home = tempDir();
    expect(loadConfig(['--home', home], { AGENTCRAFT_AUTO_APPROVE: 'all' }).claude.autoApprove).toBe('all');
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { autoApprove: 'off' } }));
    expect(loadConfig(['--home', home], {}).claude.autoApprove).toBe('off');
  });
});
