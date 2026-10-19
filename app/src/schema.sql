-- FileCloud schema. Applied idempotently at every start.

CREATE TABLE IF NOT EXISTS settings (
  key text PRIMARY KEY,
  value text NOT NULL
);

CREATE TABLE IF NOT EXISTS users (
  id text PRIMARY KEY,
  display_name text NOT NULL DEFAULT '',
  email text NOT NULL DEFAULT '',
  password_hash text NOT NULL,
  is_admin boolean NOT NULL DEFAULT false,
  disabled boolean NOT NULL DEFAULT false,
  quota_bytes bigint,                      -- NULL = unlimited
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS groups (
  id text PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS group_members (
  group_id text NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (group_id, user_id)
);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash text PRIMARY KEY,
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Per-device passwords for WebDAV / CalDAV / CardDAV clients.
CREATE TABLE IF NOT EXISTS app_passwords (
  id bigserial PRIMARY KEY,
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name text NOT NULL,
  token_hash text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz
);

-- The file tree. File contents live in
-- immutable blobs on disk (DATA_DIR/blobs/xx/<uuid>), so rename/move never touch disk.
-- Folder size/etag are propagated to ancestors on every change.
-- Trash = whole subtree stamped with the same deleted_at; trash_path keeps the origin.
CREATE TABLE IF NOT EXISTS nodes (
  id bigserial PRIMARY KEY,
  owner text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  parent_id bigint REFERENCES nodes(id) ON DELETE CASCADE,
  name text NOT NULL,
  is_dir boolean NOT NULL,
  size bigint NOT NULL DEFAULT 0,
  mime text NOT NULL DEFAULT 'application/octet-stream',
  blob text,
  etag text NOT NULL DEFAULT md5(random()::text),
  mtime timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  trashed_root boolean NOT NULL DEFAULT false,
  trash_path text
);
CREATE UNIQUE INDEX IF NOT EXISTS nodes_live_name ON nodes (parent_id, name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS nodes_root ON nodes (owner) WHERE parent_id IS NULL;
CREATE INDEX IF NOT EXISTS nodes_parent ON nodes (parent_id);
CREATE INDEX IF NOT EXISTS nodes_owner_live ON nodes (owner, mtime DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS nodes_trash ON nodes (owner) WHERE trashed_root;

CREATE TABLE IF NOT EXISTS versions (
  id bigserial PRIMARY KEY,
  node_id bigint NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
  blob text NOT NULL,
  size bigint NOT NULL,
  mtime timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS versions_node ON versions (node_id, created_at DESC);

CREATE TABLE IF NOT EXISTS favorites (
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  node_id bigint NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, node_id)
);

-- User, group and public link shares.
CREATE TABLE IF NOT EXISTS shares (
  id bigserial PRIMARY KEY,
  node_id bigint NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
  owner text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type text NOT NULL CHECK (type IN ('user', 'group', 'link')),
  share_with text,
  can_edit boolean NOT NULL DEFAULT false,
  token text UNIQUE,
  password_hash text,
  expires_at timestamptz,
  label text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS shares_node ON shares (node_id);
CREATE INDEX IF NOT EXISTS shares_with ON shares (type, share_with);

-- Resumable chunked uploads.
CREATE TABLE IF NOT EXISTS uploads (
  id text PRIMARY KEY,
  owner text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  parent_id bigint NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
  name text NOT NULL,
  size bigint NOT NULL,
  received bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Groupware (CalDAV / CardDAV). ctag doubles as the RFC 6578 sync token.
CREATE TABLE IF NOT EXISTS calendars (
  id bigserial PRIMARY KEY,
  owner text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  uri text NOT NULL,
  name text NOT NULL,
  color text NOT NULL DEFAULT '#0082c9',
  ctag bigint NOT NULL DEFAULT 1,
  UNIQUE (owner, uri)
);

CREATE TABLE IF NOT EXISTS calendar_objects (
  id bigserial PRIMARY KEY,
  collection_id bigint NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
  uri text NOT NULL,
  uid text NOT NULL DEFAULT '',
  etag text NOT NULL,
  data text NOT NULL,
  component text NOT NULL DEFAULT 'VEVENT',
  first_start timestamptz,
  last_end timestamptz,                    -- NULL = unbounded (recurring)
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (collection_id, uri)
);

CREATE TABLE IF NOT EXISTS addressbooks (
  id bigserial PRIMARY KEY,
  owner text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  uri text NOT NULL,
  name text NOT NULL,
  color text NOT NULL DEFAULT '',
  ctag bigint NOT NULL DEFAULT 1,
  UNIQUE (owner, uri)
);

CREATE TABLE IF NOT EXISTS cards (
  id bigserial PRIMARY KEY,
  collection_id bigint NOT NULL REFERENCES addressbooks(id) ON DELETE CASCADE,
  uri text NOT NULL,
  uid text NOT NULL DEFAULT '',
  etag text NOT NULL,
  data text NOT NULL,
  component text NOT NULL DEFAULT 'VCARD',
  first_start timestamptz,
  last_end timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (collection_id, uri)
);

CREATE TABLE IF NOT EXISTS dav_changes (
  kind text NOT NULL,                      -- 'cal' | 'card'
  collection_id bigint NOT NULL,
  uri text NOT NULL,
  token bigint NOT NULL,
  deleted boolean NOT NULL
);
CREATE INDEX IF NOT EXISTS dav_changes_lookup ON dav_changes (kind, collection_id, token);

-- Mobile app releases served for over-the-air updates (APK files live in DATA_DIR/releases).
CREATE TABLE IF NOT EXISTS app_releases (
  id bigserial PRIMARY KEY,
  platform text NOT NULL DEFAULT 'android',
  version_code integer NOT NULL,
  version_name text NOT NULL,
  notes text NOT NULL DEFAULT '',
  size bigint NOT NULL,
  sha256 text NOT NULL,
  file text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (platform, version_code)
);
