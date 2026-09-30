/** REST API for the phone and the dashboard (PRD section 07). Mounted at /api. */
const express = require('express');
const db = require('../db');
const stream = require('../stream');
const live = require('../live');
const { requireAuth } = require('../auth');

const router = express.Router();
router.use(requireAuth);

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const wrap = (fn) => (req, res, next) => fn(req, res, next).catch(next);

function checkId(req, res) {
  if (!UUID_RE.test(req.params.id)) { res.status(400).json({ error: 'Invalid call id' }); return false; }
  return true;
}

// POST /calls/start  {number, direction, timestamp, language} -> {call_id}
router.post('/calls/start', wrap(async (req, res) => {
  const { number, direction, timestamp, language } = req.body || {};
  const dir = direction === 'outgoing' ? 'outgoing' : 'incoming';
  const started = timestamp && !isNaN(Date.parse(timestamp)) ? new Date(timestamp) : new Date();
  const { rows } = await db.query(
    `INSERT INTO calls (phone_number, direction, started_at, status, language, token_id)
     VALUES ($1, $2, $3, 'active', $4, $5) RETURNING *`,
    [number ? String(number).slice(0, 20) : null, dir, started, String(language || 'auto').slice(0, 8), req.token?.id || null]
  );
  live.broadcast({ type: 'call_started', call: rows[0] });
  res.status(201).json({ call_id: rows[0].id });
}));

// POST /calls/:id/end  {duration, number}
router.post('/calls/:id/end', wrap(async (req, res) => {
  if (!checkId(req, res)) return;
  const duration = parseInt(req.body?.duration, 10);
  const number = req.body?.number ? String(req.body.number).slice(0, 20) : null;
  await stream.endCall(req.params.id);
  const { rows } = await db.query(
    `UPDATE calls SET status = 'completed',
       ended_at = COALESCE(ended_at, NOW()),
       duration_sec = COALESCE($2, duration_sec, EXTRACT(EPOCH FROM (NOW() - started_at))::int),
       phone_number = COALESCE(phone_number, $3)
     WHERE id = $1 RETURNING *`,
    [req.params.id, isNaN(duration) ? null : duration, number]
  );
  if (!rows[0]) return res.status(404).json({ error: 'Call not found' });
  live.broadcast({ type: 'call_ended', call: rows[0] });
  res.json({ ok: true, call: rows[0] });
}));

// GET /calls?page&limit&from_date&to_date&number
router.get('/calls', wrap(async (req, res) => {
  const page = Math.max(parseInt(req.query.page, 10) || 1, 1);
  const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 50, 1), 200);
  const where = [];
  const params = [];
  if (req.query.number) { params.push(`%${req.query.number}%`); where.push(`c.phone_number ILIKE $${params.length}`); }
  if (req.query.from_date && !isNaN(Date.parse(req.query.from_date))) { params.push(new Date(req.query.from_date)); where.push(`c.started_at >= $${params.length}`); }
  if (req.query.to_date && !isNaN(Date.parse(req.query.to_date))) { params.push(new Date(req.query.to_date)); where.push(`c.started_at < $${params.length}`); }
  const w = where.length ? `WHERE ${where.join(' AND ')}` : '';
  const total = (await db.query(`SELECT COUNT(*)::int AS n FROM calls c ${w}`, params)).rows[0].n;
  params.push(limit, (page - 1) * limit);
  const { rows } = await db.query(
    `SELECT c.*,
       (SELECT string_agg(text, ' ' ORDER BY offset_ms) FROM (
          SELECT text, offset_ms FROM transcript_segments s WHERE s.call_id = c.id ORDER BY offset_ms LIMIT 3) t) AS text
     FROM calls c ${w} ORDER BY c.started_at DESC NULLS LAST
     LIMIT $${params.length - 1} OFFSET $${params.length}`,
    params
  );
  res.json({ calls: rows, total, page, limit });
}));

// GET /calls/search?q=
router.get('/calls/search', wrap(async (req, res) => {
  const q = String(req.query.q || '').trim();
  if (!q) return res.json({ results: [] });
  const { rows } = await db.query(
    `SELECT c.id, c.phone_number, c.direction, c.started_at, c.duration_sec, c.status,
            s.text, s.offset_ms
     FROM transcript_segments s JOIN calls c ON c.id = s.call_id
     WHERE to_tsvector('simple', s.text) @@ plainto_tsquery('simple', $1)
        OR s.text ILIKE '%' || $1 || '%'
        OR c.phone_number ILIKE '%' || $1 || '%'
     ORDER BY c.started_at DESC, s.offset_ms
     LIMIT 200`,
    [q]
  );
  res.json({ results: rows });
}));

// GET /calls/:id
router.get('/calls/:id', wrap(async (req, res) => {
  if (!checkId(req, res)) return;
  const { rows } = await db.query('SELECT * FROM calls WHERE id = $1', [req.params.id]);
  if (!rows[0]) return res.status(404).json({ error: 'Call not found' });
  res.json({ call: rows[0] });
}));

// GET /calls/:id/transcript
router.get('/calls/:id/transcript', wrap(async (req, res) => {
  if (!checkId(req, res)) return;
  const call = (await db.query('SELECT * FROM calls WHERE id = $1', [req.params.id])).rows[0];
  if (!call) return res.status(404).json({ error: 'Call not found' });
  const { rows } = await db.query(
    'SELECT id, text, offset_ms, confidence FROM transcript_segments WHERE call_id = $1 ORDER BY offset_ms, created_at',
    [req.params.id]
  );
  res.json({ call, segments: rows });
}));

// GET /calls/:id/export.txt
router.get('/calls/:id/export.txt', wrap(async (req, res) => {
  if (!checkId(req, res)) return;
  const call = (await db.query('SELECT * FROM calls WHERE id = $1', [req.params.id])).rows[0];
  if (!call) return res.status(404).send('Call not found');
  const { rows } = await db.query(
    'SELECT text, offset_ms FROM transcript_segments WHERE call_id = $1 ORDER BY offset_ms, created_at',
    [req.params.id]
  );
  const mmss = (ms) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;
  const lines = [
    `Call: ${call.phone_number || 'Unknown'} (${call.direction})`,
    `Started: ${call.started_at?.toISOString?.() || call.started_at}`,
    `Duration: ${call.duration_sec ?? '-'} s`,
    '',
    ...rows.map((r) => `[${mmss(r.offset_ms)}] ${r.text}`),
  ];
  res.setHeader('Content-Disposition', `attachment; filename="call-${call.id}.txt"`);
  res.type('text/plain; charset=utf-8').send(lines.join('\n'));
}));

// DELETE /calls/:id (dashboard only)
router.delete('/calls/:id', wrap(async (req, res) => {
  if (!req.user) return res.status(403).json({ error: 'Dashboard login required' });
  if (!checkId(req, res)) return;
  await db.query('DELETE FROM calls WHERE id = $1', [req.params.id]);
  res.json({ ok: true });
}));

// POST /device/heartbeat {battery, network, service_status}
router.post('/device/heartbeat', wrap(async (req, res) => {
  if (!req.token) return res.status(403).json({ error: 'Device token required' });
  const { battery, network, service_status } = req.body || {};
  const { rows } = await db.query(
    `INSERT INTO devices (token_id, battery, network, service_status, last_seen_at)
     VALUES ($1, $2, $3, $4, NOW())
     ON CONFLICT (token_id) DO UPDATE SET battery = $2, network = $3, service_status = $4, last_seen_at = NOW()
     RETURNING *`,
    [req.token.id, Number.isFinite(+battery) ? +battery : null, String(network || '').slice(0, 20), String(service_status || '').slice(0, 20)]
  );
  live.broadcast({ type: 'device', device: { ...rows[0], name: req.token.name, online: true } });
  res.json({ ok: true });
}));

// GET /device/status
router.get('/device/status', wrap(async (req, res) => {
  const { rows } = await db.query(
    `SELECT d.*, t.name, (d.last_seen_at > NOW() - INTERVAL '150 seconds') AS online
     FROM devices d JOIN api_tokens t ON t.id = d.token_id
     WHERE t.revoked_at IS NULL ORDER BY d.last_seen_at DESC`
  );
  const active = (await db.query(`SELECT * FROM calls WHERE status = 'active' ORDER BY started_at DESC LIMIT 10`)).rows;
  res.json({ ok: true, devices: rows, active_calls: active, online: rows.some((d) => d.online) });
}));

module.exports = router;
