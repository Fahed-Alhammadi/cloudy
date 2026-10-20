import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';

const env = process.env;
const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));

export const config = {
  port: Number(env.PORT ?? 8080),
  dataDir: env.DATA_DIR ?? '/data',
  databaseUrl: env.DATABASE_URL ?? 'postgres://cloudy@localhost/cloudy?host=/run/cloudy',
  adminUser: env.ADMIN_USER ?? 'admin',
  adminPassword: env.ADMIN_PASSWORD ?? '',
  trashDays: Number(env.TRASH_RETENTION_DAYS ?? 30),
  maxVersions: Number(env.MAX_VERSIONS ?? 20),
  sessionDays: Number(env.SESSION_DAYS ?? 14),
  /** Honor X-Forwarded-For / X-Forwarded-Proto. Enable only when a reverse proxy sets them. */
  trustProxy: /^(1|true|yes)$/i.test(env.TRUST_PROXY ?? ''),
  /** Address users open Cloudy at (e.g. https://cloud.example.com); passkeys are bound to it. Empty = from the request. */
  publicUrl: (env.PUBLIC_URL ?? '').trim().replace(/\/+$/, ''),
  /** GitHub repository whose releases carry the Android APK (for /download/android). */
  appRepo: env.APP_REPO || 'Fahed-Alhammadi/cloudy',
  chunkSize: 8 * 1024 * 1024,
  version: (env.APP_VERSION || pkg.version) as string,
  publicDir: fileURLToPath(new URL('../public/', import.meta.url)),
  schemaFile: fileURLToPath(new URL('./schema.sql', import.meta.url)),
};
