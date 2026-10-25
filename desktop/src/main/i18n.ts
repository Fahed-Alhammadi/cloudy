// Translations, as on the web: English text in the code — t('Sync now') — looked up in i18n/<lang>.json
// ({"English": "translation"}); missing entries stay English. The language is the account's (Settings → Profile on
// the web), else the computer's. Keep LANGUAGES in sync with server/src/i18n.ts and server/public/js/i18n.js.
import { readFileSync } from 'node:fs';
import path from 'node:path';

export const LANGUAGES = ['en', 'ar', 'de', 'fr', 'es'];
const RTL = new Set(['ar']);

let lang = 'en';
let dict: Record<string, string> = {};

export function t(text: string, vars?: Record<string, string | number>): string {
  let out = dict[text] ?? text;
  if (vars) out = out.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
  return out;
}

/** Picks the first supported language of `preferred` (account language first, then the system's). */
export function setLanguage(dir: string, ...preferred: (string | null | undefined)[]): void {
  lang = preferred.map((l) => String(l ?? '').split(/[-_]/)[0].toLowerCase()).find((l) => LANGUAGES.includes(l)) ?? 'en';
  try {
    dict = lang === 'en' ? {} : JSON.parse(readFileSync(path.join(dir, `${lang}.json`), 'utf8'));
  } catch {
    dict = {};
  }
}

export const language = () => lang;
export const isRtl = () => RTL.has(lang);
export const strings = () => dict;
