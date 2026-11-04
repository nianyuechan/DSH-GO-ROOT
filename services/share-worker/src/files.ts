// Serving a file from R2: Range (206) for resumed downloads, ETag /
// Last-Modified, and an edge copy (Cache API) for files that never change.
// Callers decide who may read; this only serves.

import type { Env } from './env';

/** Bigger than this isn't put in the edge cache (Cache API limit 512 MB). */
const CACHE_MAX_BYTES = 500 * 1024 * 1024;

/** What an edge copy is stored with, so the Cache API keeps it. */
const CACHED = 'public, max-age=31536000, immutable';

export const COMMON: Record<string, string> = {
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'X-Robots-Tag': 'noindex, nofollow',
  'Strict-Transport-Security': 'max-age=31536000',
};

export interface Ctx {
  waitUntil(promise: Promise<unknown>): void;
}

export function plain(status: number, text: string, extra: Record<string, string> = {}): Response {
  return new Response(text, {
    status,
    headers: { ...COMMON, 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', ...extra },
  });
}

/** Content-Type and Content-Disposition for a release file. */
export function fileHeaders(name: string): Record<string, string> {
  const lower = name.toLowerCase();
  if (lower.endsWith('.apk')) {
    return {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': `attachment; filename="${name}"`,
    };
  }
  if (lower.endsWith('.json')) return { 'Content-Type': 'application/json; charset=utf-8' };
  if (name === 'SHA256SUMS' || lower.endsWith('.txt')) return { 'Content-Type': 'text/plain; charset=utf-8' };
  return { 'Content-Type': 'application/octet-stream', 'Content-Disposition': `attachment; filename="${name}"` };
}

/** `Range: bytes=a-b | a- | -n` (one range); null for none or anything else. */
export function parseRange(header: string | null): R2Range | null {
  const m = header ? /^bytes=(\d*)-(\d*)$/.exec(header.trim()) : null;
  if (!m || (m[1] === '' && m[2] === '')) return null;
  if (m[1] === '') return { suffix: Number(m[2]) };
  const offset = Number(m[1]);
  if (m[2] === '') return { offset };
  const end = Number(m[2]);
  return end < offset ? null : { offset, length: end - offset + 1 };
}

const warming = new Set<string>();

/** Fills the edge cache with the whole file, off the request's path. */
async function warm(env: Env, key: string, cacheKey: string, headers: Headers): Promise<void> {
  if (warming.has(key)) return;
  warming.add(key);
  try {
    const object = await env.BUCKET.get(key);
    if (!object) return;
    const h = new Headers(headers);
    h.set('Content-Length', String(object.size));
    h.set('Cache-Control', CACHED);
    h.delete('X-Cache');
    await caches.default.put(new Request(cacheKey), new Response(object.body, { status: 200, headers: h }));
  } catch {
    // Next full download tries again.
  } finally {
    warming.delete(key);
  }
}

export interface ServeOptions {
  /** R2 key. */
  key: string;
  /** File name for the headers. */
  name: string;
  /** Sent to the client. */
  cacheControl: string;
  /** Keeps an edge copy under this URL (an internal one: callers check access
   *  before every lookup). Only for files that never change. */
  edgeKey?: string;
}

export async function serveObject(request: Request, env: Env, ctx: Ctx, options: ServeOptions): Promise<Response> {
  const { key, name, cacheControl, edgeKey } = options;
  const head = request.method === 'HEAD';
  const edge = edgeKey && typeof caches !== 'undefined' ? caches.default : null;

  if (edge && edgeKey && !head) {
    const lookup = new Headers();
    for (const h of ['Range', 'If-None-Match', 'If-Modified-Since']) {
      const v = request.headers.get(h);
      if (v) lookup.set(h, v);
    }
    const hit = await edge.match(new Request(edgeKey, { headers: lookup }));
    if (hit) {
      const res = new Response(hit.body, hit);
      res.headers.set('Cache-Control', cacheControl);
      res.headers.set('X-Cache', 'HIT');
      return res;
    }
  }

  const headers = new Headers({ ...COMMON, ...fileHeaders(name), 'Cache-Control': cacheControl, 'Accept-Ranges': 'bytes' });
  if (head) {
    const object = await env.BUCKET.head(key);
    if (!object) return plain(404, 'Not found');
    headers.set('Content-Length', String(object.size));
    headers.set('ETag', object.httpEtag);
    headers.set('Last-Modified', object.uploaded.toUTCString());
    return new Response(null, { status: 200, headers });
  }

  const range = parseRange(request.headers.get('Range'));
  let object: R2ObjectBody | null;
  try {
    object = await env.BUCKET.get(key, range ? { range } : undefined);
  } catch (error) {
    if (!range) throw error;
    const meta = await env.BUCKET.head(key);
    if (!meta) return plain(404, 'Not found');
    return plain(416, 'Range not satisfiable', { 'Content-Range': `bytes */${meta.size}` });
  }
  if (!object) return plain(404, 'Not found');
  headers.set('ETag', object.httpEtag);
  headers.set('Last-Modified', object.uploaded.toUTCString());
  headers.set('X-Cache', 'MISS');

  const size = object.size;
  if (range) {
    let start: number;
    let end: number;
    if ('suffix' in range) {
      start = Math.max(0, size - range.suffix);
      end = size - 1;
    } else {
      start = range.offset ?? 0;
      end = range.length !== undefined ? Math.min(size - 1, start + range.length - 1) : size - 1;
    }
    if (start >= size) {
      await object.body.cancel().catch(() => {});
      return plain(416, 'Range not satisfiable', { 'Content-Range': `bytes */${size}` });
    }
    headers.set('Content-Range', `bytes ${start}-${end}/${size}`);
    headers.set('Content-Length', String(end - start + 1));
    return new Response(object.body, { status: 206, headers });
  }

  headers.set('Content-Length', String(size));
  if (edge && edgeKey && size <= CACHE_MAX_BYTES) ctx.waitUntil(warm(env, key, edgeKey, headers));
  return new Response(object.body, { status: 200, headers });
}
