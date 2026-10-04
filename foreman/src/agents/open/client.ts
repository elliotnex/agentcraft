// One chat-completions call to any OpenAI-compatible endpoint: OpenRouter (default; hundreds of
// models behind one key), a local Ollama or LM Studio, or any other provider that speaks the same
// API. Tool calling is the standard `tools` / `tool_calls` shape.

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string | null;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
}

export interface ToolCall {
  id: string;
  type: 'function';
  function: { name: string; arguments: string };
}

export interface ToolSpec {
  type: 'function';
  function: { name: string; description: string; parameters: Record<string, unknown> };
}

export interface Completion {
  message: ChatMessage;
  /** USD for this call when the provider reports it (OpenRouter does), else undefined */
  costUsd?: number;
  tokensIn: number;
  tokensOut: number;
}

export type CompleteFn = (req: { model: string; messages: ChatMessage[]; tools: ToolSpec[]; signal?: AbortSignal }) => Promise<Completion>;

export interface ClientOptions {
  baseUrl: string;
  apiKey?: string;
  /** attribution headers OpenRouter shows on its dashboard */
  appName?: string;
  timeoutMs?: number;
}

/** A CompleteFn bound to an endpoint. Errors carry the provider's message (bad key, unknown model, ...). */
export function openAiClient(opts: ClientOptions): CompleteFn {
  const url = `${opts.baseUrl.replace(/\/+$/, '')}/chat/completions`;
  return async ({ model, messages, tools, signal }) => {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (opts.apiKey) headers.Authorization = `Bearer ${opts.apiKey}`;
    if (opts.appName) {
      headers['X-Title'] = opts.appName;
      headers['HTTP-Referer'] = 'https://github.com/blendi-remade/agentcraft';
    }
    const timeout = AbortSignal.timeout(opts.timeoutMs ?? 180_000);
    const res = await fetch(url, {
      method: 'POST',
      headers,
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
      body: JSON.stringify({ model, messages, tools, tool_choice: 'auto', usage: { include: true } }),
    });
    const text = await res.text();
    let body: Record<string, unknown>;
    try {
      body = JSON.parse(text) as Record<string, unknown>;
    } catch {
      throw new Error(`${res.status} ${res.statusText}: ${text.slice(0, 200)}`);
    }
    if (!res.ok || body.error) {
      const err = body.error as { message?: string } | string | undefined;
      const msg = typeof err === 'string' ? err : err?.message ?? text.slice(0, 200);
      throw new Error(`${res.status}: ${msg}`);
    }
    const choice = (body.choices as Array<{ message: ChatMessage }> | undefined)?.[0];
    if (!choice?.message) throw new Error(`no answer from ${model}`);
    const usage = (body.usage ?? {}) as { prompt_tokens?: number; completion_tokens?: number; cost?: number };
    return {
      message: { role: 'assistant', content: choice.message.content ?? null, ...(choice.message.tool_calls?.length ? { tool_calls: choice.message.tool_calls } : {}) },
      ...(typeof usage.cost === 'number' ? { costUsd: usage.cost } : {}),
      tokensIn: usage.prompt_tokens ?? 0,
      tokensOut: usage.completion_tokens ?? 0,
    };
  };
}
