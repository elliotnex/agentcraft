// The models an endpoint offers: OpenRouter's catalog (public; with prices, context length and
// whether a model can call tools) or any OpenAI-compatible /models list (ids only, e.g. Ollama).
import type { ModelInfo } from '../../protocol.js';

/** USD per token as OpenRouter writes it ("0.00000025") -> USD per million tokens; negative/unknown -> undefined. */
export function perMillion(v: unknown): number | undefined {
  const n = typeof v === 'string' ? Number(v) : typeof v === 'number' ? v : NaN;
  if (!Number.isFinite(n) || n < 0) return undefined;
  return Math.round(n * 1e6 * 10000) / 10000;
}

/** Parse a /models response (OpenRouter's rich shape or the plain OpenAI one). Tool-capable models first. */
export function parseModels(body: unknown): ModelInfo[] {
  const data = (body as { data?: unknown })?.data;
  if (!Array.isArray(data)) return [];
  const out: ModelInfo[] = [];
  for (const raw of data as Array<Record<string, unknown>>) {
    if (typeof raw?.id !== 'string' || !raw.id) continue;
    const m: ModelInfo = { id: raw.id, name: typeof raw.name === 'string' && raw.name ? raw.name : raw.id };
    const pricing = raw.pricing as Record<string, unknown> | undefined;
    if (pricing) {
      const p = perMillion(pricing.prompt);
      const c = perMillion(pricing.completion);
      if (p !== undefined) m.promptUsdPerM = p;
      if (c !== undefined) m.completionUsdPerM = c;
    }
    if (typeof raw.context_length === 'number') m.contextLength = raw.context_length;
    if (Array.isArray(raw.supported_parameters)) m.tools = raw.supported_parameters.includes('tools');
    out.push(m);
  }
  // stable: keeps the provider's order (OpenRouter: newest first) within each group
  return out.sort((a, b) => Number(b.tools !== false) - Number(a.tools !== false));
}

export async function fetchModels(baseUrl: string, apiKey?: string, timeoutMs = 20_000): Promise<ModelInfo[]> {
  const res = await fetch(`${baseUrl.replace(/\/+$/, '')}/models`, {
    headers: apiKey ? { Authorization: `Bearer ${apiKey}` } : {},
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} from ${baseUrl}/models`);
  return parseModels(await res.json());
}

/** USD for a call from token counts and a model's price (undefined when the price is unknown). */
export function estimateCost(m: ModelInfo | undefined, tokensIn: number, tokensOut: number): number | undefined {
  if (!m || m.promptUsdPerM === undefined || m.completionUsdPerM === undefined) return undefined;
  return (tokensIn * m.promptUsdPerM + tokensOut * m.completionUsdPerM) / 1e6;
}
