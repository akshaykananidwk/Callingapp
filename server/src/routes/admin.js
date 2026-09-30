/** Dashboard-only endpoints: login, tokens, account, stats. Mounted at /admin/api. */
const express = require('express');
const db = require('../db');
const auth = require('../auth');
const stt = require('../stt');
const config = require('../config');

const router = express.Router();
const wrap = (fn) => (req, res, next) => fn(req, res, next).catch(next);

router.post('/login', wrap(async (req, res) => {
  const ip = req.ip;
  if (!auth.loginAllowed(ip)) return res.status(429).json({ error: 'Too many attempts. Try again in 15 minutes.' });
  const { username, password } = req.body || {};
  const { rows } = await db.query('SELECT * FROM users WHERE username = $1', [String(username || '')]);
  const user = rows[0];
  if (!user || !auth.verifyPassword(String(password || ''), user.password_hash)) {
    return res.status(401).json({ error: 'Wrong username or password' });
  }
  auth.loginSucceeded(ip);
  await auth.createSession(res, user.id);
  res.json({ ok: true, username: user.username });
}));

router.post('/logout', wrap(async (req, res) => {
  await auth.destroySession(req, res);
  res.json({ ok: true });
}));

router.use(auth.requireUser);

router.get('/me', (req, res) => res.json({ username: req.user.username }));

router.post('/password', wrap(async (req, res) => {
  const { current, next: nextPw } = req.body || {};
  if (!nextPw || String(nextPw).length < 8) return res.status(400).json({ error: 'New password must be at least 8 characters' });
  const { rows } = await db.query('SELECT password_hash FROM users WHERE id = $1', [req.user.id]);
  if (!auth.verifyPassword(String(current || ''), rows[0].password_hash)) {
    return res.status(401).json({ error: 'Current password is wrong' });
  }
  await db.query('UPDATE users SET password_hash = $1 WHERE id = $2', [auth.hashPassword(String(nextPw)), req.user.id]);
  res.json({ ok: true });
}));

router.get('/tokens', wrap(async (req, res) => {
  const { rows } = await db.query(
    `SELECT t.id, t.name, t.token_prefix, t.created_at, t.last_used_at, t.revoked_at,
            d.last_seen_at, d.battery, d.network, d.service_status
     FROM api_tokens t LEFT JOIN devices d ON d.token_id = t.id
     ORDER BY t.revoked_at NULLS FIRST, t.created_at DESC`
  );
  res.json({ tokens: rows });
}));

router.post('/tokens', wrap(async (req, res) => {
  const name = String(req.body?.name || '').trim().slice(0, 100) || 'Phone';
  const token = await auth.createToken(name);
  res.status(201).json(token); // plaintext token is returned only here
}));

router.delete('/tokens/:id', wrap(async (req, res) => {
  await db.query('UPDATE api_tokens SET revoked_at = NOW() WHERE id = $1 AND revoked_at IS NULL', [req.params.id]);
  res.json({ ok: true });
}));

router.get('/overview', wrap(async (req, res) => {
  const stats = (await db.query(
    `SELECT
       (SELECT COUNT(*)::int FROM calls) AS total_calls,
       (SELECT COUNT(*)::int FROM calls WHERE started_at >= date_trunc('day', NOW())) AS calls_today,
       (SELECT COALESCE(SUM(duration_sec), 0)::int FROM calls) AS total_seconds,
       (SELECT COUNT(*)::int FROM api_tokens WHERE revoked_at IS NULL) AS active_tokens`
  )).rows[0];
  res.json({ stats, stt: await stt.status(), public_url: config.publicUrl });
}));

module.exports = router;
