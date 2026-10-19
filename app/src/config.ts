import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';

const env = process.env;
const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));

export const config = {
  port: Number(env.PORT ?? 8080),
  dataDir: env.DATA_DIR ?? '/data',
  databaseUrl: env.DATABASE_URL ?? 'postgres://filecloud@localhost/filecloud?host=/run/filecloud',
  adminUser: env.ADMIN_USER ?? 'admin',
  adminPassword: env.ADMIN_PASSWORD ?? '',
  trashDays: Number(env.TRASH_RETENTION_DAYS ?? 30),
  maxVersions: Number(env.MAX_VERSIONS ?? 20),
  sessionDays: Number(env.SESSION_DAYS ?? 14),
  /** Honor X-Forwarded-For / X-Forwarded-Proto. Enable only when a reverse proxy sets them. */
  trustProxy: /^(1|true|yes)$/i.test(env.TRUST_PROXY ?? ''),
  chunkSize: 8 * 1024 * 1024,
  version: (env.APP_VERSION || pkg.version) as string,
  /** GitHub "owner/repo" whose releases are checked for newer server versions (optional). */
  updateRepo: env.UPDATE_REPO ?? '',
  publicDir: fileURLToPath(new URL('../public/', import.meta.url)),
  schemaFile: fileURLToPath(new URL('./schema.sql', import.meta.url)),
};
