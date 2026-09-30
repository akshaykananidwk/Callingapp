const { Pool } = require('pg');
const config = require('./config');

const pool = new Pool({ connectionString: config.databaseUrl, max: 10 });

const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  username VARCHAR(64) UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS api_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(100) NOT NULL,
  token_hash TEXT UNIQUE NOT NULL,
  token_prefix VARCHAR(16) NOT NULL,
  last_used_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS devices (
  token_id UUID PRIMARY KEY REFERENCES api_tokens(id) ON DELETE CASCADE,
  battery INT,
  network VARCHAR(20),
  service_status VARCHAR(20),
  last_seen_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS calls (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  phone_number VARCHAR(20),
  direction VARCHAR(10),
  started_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  duration_sec INT,
  status VARCHAR(20),
  language VARCHAR(8),
  token_id UUID REFERENCES api_tokens(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ DEFAULT NOW()
);
ALTER TABLE calls ADD COLUMN IF NOT EXISTS recording_path TEXT;
ALTER TABLE calls ADD COLUMN IF NOT EXISTS pbx_uid TEXT;
CREATE INDEX IF NOT EXISTS idx_calls_pbx_uid ON calls(pbx_uid);

CREATE TABLE IF NOT EXISTS settings (
  key VARCHAR(64) PRIMARY KEY,
  value TEXT
);
CREATE INDEX IF NOT EXISTS idx_calls_started ON calls(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_calls_number ON calls(phone_number);

CREATE TABLE IF NOT EXISTS transcript_segments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  call_id UUID REFERENCES calls(id) ON DELETE CASCADE,
  text TEXT,
  offset_ms INT,
  confidence FLOAT,
  created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_segments_call ON transcript_segments(call_id, offset_ms);
CREATE INDEX IF NOT EXISTS idx_transcript_text ON transcript_segments USING gin(to_tsvector('simple', text));
`;

async function migrate() {
  await pool.query(SCHEMA);
}

module.exports = { pool, query: (text, params) => pool.query(text, params), migrate };
