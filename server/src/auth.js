const crypto = require('crypto');
const db = require('./db');
const config = require('./config');

const SESSION_COOKIE = 'cb_session';
const SESSION_DAYS = 30;

// ---- Passwords (scrypt, no native deps) --------------------------------------------------
function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, 64);
  return `scrypt$${salt.toString('hex')}$${hash.toString('hex')}`;
}

function verifyPassword(password, stored) {
  const [algo, saltHex, hashHex] = String(stored).split('$');
  if (algo !== 'scrypt' || !saltHex || !hashHex) return false;
  const expected = Buffer.from(hashHex, 'hex');
  const actual = crypto.scryptSync(password, Buffer.from(saltHex, 'hex'), expected.length);
  return crypto.timingSafeEqual(expected, actual);
}

// ---- API tokens (stored as SHA-256 hash, shown once) --------------------------------------
const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');

async function createToken(name) {
  const token = 'cb_' + crypto.randomBytes(24).toString('base64url');
  const { rows } = await db.query(
    `INSERT INTO api_tokens (name, token_hash, token_prefix) VALUES ($1, $2, $3)
     RETURNING id, name, token_prefix, created_at`,
    [name, sha256(token), token.slice(0, 10)]
  );
  return { ...rows[0], token };
}

async function findToken(token) {
  if (!token) return null;
  const { rows } = await db.query(
    `UPDATE api_tokens SET last_used_at = NOW()
     WHERE token_hash = $1 AND revoked_at IS NULL RETURNING id, name`,
    [sha256(token)]
  );
  return rows[0] || null;
}

function bearer(req) {
  const h = req.headers.authorization || '';
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m ? m[1].trim() : null;
}

// ---- Sessions ------------------------------------------------------------------------------
function parseCookies(header) {
  const out = {};
  String(header || '').split(';').forEach((part) => {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  });
  return out;
}

async function createSession(res, userId) {
  const id = crypto.randomBytes(32).toString('base64url');
  await db.query(
    `INSERT INTO sessions (id, user_id, expires_at) VALUES ($1, $2, NOW() + INTERVAL '${SESSION_DAYS} days')`,
    [sha256(id), userId]
  );
  res.setHeader('Set-Cookie', cookie(id, SESSION_DAYS * 86400));
}

function cookie(value, maxAge) {
  return `${SESSION_COOKIE}=${encodeURIComponent(value)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge}` +
    (config.secureCookies ? '; Secure' : '');
}

async function destroySession(req, res) {
  const id = parseCookies(req.headers.cookie)[SESSION_COOKIE];
  if (id) await db.query('DELETE FROM sessions WHERE id = $1', [sha256(id)]);
  res.setHeader('Set-Cookie', cookie('', 0));
}

async function sessionUser(req) {
  const id = parseCookies(req.headers.cookie)[SESSION_COOKIE];
  if (!id) return null;
  const { rows } = await db.query(
    `SELECT u.id, u.username FROM sessions s JOIN users u ON u.id = s.user_id
     WHERE s.id = $1 AND s.expires_at > NOW()`,
    [sha256(id)]
  );
  return rows[0] || null;
}

// ---- Middleware ----------------------------------------------------------------------------
/** Phone (Bearer token) or dashboard (session cookie). */
async function requireAuth(req, res, next) {
  try {
    const t = bearer(req);
    if (t) {
      const token = await findToken(t);
      if (!token) return res.status(401).json({ error: 'Invalid or revoked token' });
      req.token = token;
      return next();
    }
    const user = await sessionUser(req);
    if (!user) return res.status(401).json({ error: 'Not authenticated' });
    req.user = user;
    next();
  } catch (e) {
    next(e);
  }
}

async function requireUser(req, res, next) {
  try {
    const user = await sessionUser(req);
    if (!user) return res.status(401).json({ error: 'Not authenticated' });
    req.user = user;
    next();
  } catch (e) {
    next(e);
  }
}

// Simple in-memory login throttle: 10 attempts / 15 min per IP.
const attempts = new Map();
function loginAllowed(ip) {
  const now = Date.now();
  const a = attempts.get(ip) || { count: 0, since: now };
  if (now - a.since > 15 * 60 * 1000) { a.count = 0; a.since = now; }
  a.count++;
  attempts.set(ip, a);
  return a.count <= 10;
}
function loginSucceeded(ip) { attempts.delete(ip); }

module.exports = {
  hashPassword, verifyPassword, createToken, findToken, bearer,
  createSession, destroySession, sessionUser, requireAuth, requireUser,
  loginAllowed, loginSucceeded,
};
