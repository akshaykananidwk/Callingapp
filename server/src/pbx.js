/**
 * Asterisk + GoIP integration.
 *  - /pbx/event      : called by the Asterisk dialplan (CURL) on incoming / outgoing / answered / end.
 *  - live recording  : Asterisk MixMonitor writes <uid>.sln16 (16 kHz PCM, both sides). We tail it
 *                      and feed the same pipeline as phone audio (transcript, listeners, WAV recording).
 *  - /admin/api/pbx  : status, softphone credentials, incoming-call settings, demo clip.
 */
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');
const express = require('express');
const db = require('./db');
const config = require('./config');
const stream = require('./stream');
const live = require('./live');
const stt = require('./stt');

const DEFAULTS = { incoming_mode: 'ring_then_demo', ring_seconds: '20', language: '' };
const MODES = ['ring', 'ring_then_demo', 'demo'];
const active = new Map(); // asterisk uid -> { callId, file, offset, timer, answered, language }

// ---------------------------------------------------------------------------------------------
async function getSettings() {
  const { rows } = await db.query('SELECT key, value FROM settings');
  const out = { ...DEFAULTS };
  rows.forEach((r) => { out[r.key] = r.value; });
  return out;
}
async function setSetting(key, value) {
  await db.query(
    'INSERT INTO settings (key, value) VALUES ($1, $2) ON CONFLICT (key) DO UPDATE SET value = $2',
    [key, String(value)]
  );
}

async function findCall(uid) {
  if (active.has(uid)) return active.get(uid);
  const { rows } = await db.query('SELECT id, language FROM calls WHERE pbx_uid = $1 ORDER BY created_at DESC LIMIT 1', [uid]);
  if (!rows[0]) return null;
  const c = { callId: rows[0].id, file: path.join(config.pbx.liveDir, `${uid}.sln16`), offset: 0, answered: false, language: rows[0].language };
  active.set(uid, c);
  return c;
}

function readNew(c) {
  try {
    const size = fs.statSync(c.file).size;
    const len = (size - c.offset) & ~1;
    if (len <= 0) return;
    const fd = fs.openSync(c.file, 'r');
    const buf = Buffer.alloc(len);
    fs.readSync(fd, buf, 0, len, c.offset);
    fs.closeSync(fd);
    c.offset += len;
    stream.push(c.callId, buf);
  } catch (e) {
    if (e.code !== 'ENOENT') console.error('[pbx] read failed:', e.message);
  }
}

// ---------------------------------------------------------------------------------------------
const eventRouter = express.Router();

eventRouter.get('/event', async (req, res) => {
  try {
    const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress) && !req.headers['x-forwarded-for'];
    if (!local || !config.pbx.secret || req.query.secret !== config.pbx.secret) return res.status(403).send('forbidden');
    const { type, uid } = req.query;
    const number = req.query.number ? String(req.query.number).slice(0, 20) : null;
    if (!uid) return res.status(400).send('uid required');
    if (type === 'incoming' || type === 'outgoing') {
      const settings = await getSettings();
      const language = settings.language || config.defaultLanguage;
      const { rows } = await db.query(
        `INSERT INTO calls (phone_number, direction, started_at, status, language, pbx_uid)
         VALUES ($1, $2, NOW(), 'ringing', $3, $4) RETURNING *`,
        [number, type, language, uid]
      );
      active.set(uid, { callId: rows[0].id, file: path.join(config.pbx.liveDir, `${uid}.sln16`), offset: 0, answered: false, language });
      live.broadcast({ type: 'call_started', call: rows[0] });
      console.log(`[pbx] ${type} ${number || 'unknown'} (${uid})`);
      if (type === 'incoming') {
        const mode = MODES.includes(settings.incoming_mode) ? settings.incoming_mode : DEFAULTS.incoming_mode;
        return res.type('text/plain').send(`${mode}|${parseInt(settings.ring_seconds, 10) || 20}`);
      }
      return res.type('text/plain').send('ok');
    }

    const c = await findCall(uid);
    if (!c) return res.type('text/plain').send('unknown');

    if (type === 'answered') {
      if (!c.answered) {
        c.answered = true;
        const { rows } = await db.query(
          `UPDATE calls SET status = 'active', started_at = NOW() WHERE id = $1 RETURNING *`, [c.callId]
        );
        stream.open(c.callId, c.language);
        c.timer = setInterval(() => readNew(c), 250);
        live.broadcast({ type: 'call_answered', call: rows[0], by: req.query.by || 'web' });
      }
      return res.type('text/plain').send('ok');
    }

    if (type === 'end') {
      res.type('text/plain').send('ok'); // don't hold up Asterisk
      active.delete(uid);
      clearInterval(c.timer);
      const billsec = parseInt(req.query.billsec, 10);
      if (c.answered) {
        await new Promise((r) => setTimeout(r, 800)); // let MixMonitor flush and close the file
        readNew(c);
        await stream.finish(c.callId);
        fs.rm(c.file, { force: true }, () => {});
      }
      const status = c.answered ? 'completed' : (req.query.status === 'NOANSWER' || req.query.status === 'CANCEL' || req.query.status === '' ? 'missed' : 'failed');
      const { rows } = await db.query(
        `UPDATE calls SET status = $2, ended_at = COALESCE(ended_at, NOW()),
           duration_sec = CASE WHEN $3::int IS NOT NULL THEN $3::int ELSE duration_sec END
         WHERE id = $1 RETURNING *`,
        [c.callId, status, c.answered && !isNaN(billsec) ? billsec : (c.answered ? null : 0)]
      );
      if (rows[0]) live.broadcast({ type: 'call_ended', call: rows[0] });
      console.log(`[pbx] end ${uid} ${status} (${req.query.cause || ''})`);
      return;
    }
    res.status(400).send('unknown type');
  } catch (e) {
    console.error('[pbx] event failed:', e);
    if (!res.headersSent) res.status(500).send('error');
  }
});

// ---------------------------------------------------------------------------------------------
function asteriskCli(cmd) {
  return new Promise((resolve) => {
    execFile('asterisk', ['-rx', cmd], { timeout: 4000 }, (err, stdout) => resolve(err ? null : stdout));
  });
}

async function status() {
  const out = await asteriskCli('pjsip show contacts');
  if (out == null) return { asterisk: false, goip: null, web_contacts: 0 };
  let goip = null;
  let web = 0;
  for (const line of out.split('\n')) {
    const m = /Contact:\s+([^/\s]+)\/(\S+)\s+\S+\s+(\S+)/.exec(line);
    if (!m) continue;
    if (m[1] === 'goip') goip = { contact: m[2].replace(/^sip:/, '').split(';')[0], status: m[3] };
    if (m[1] === 'webrtc') web++;
  }
  return { asterisk: true, goip, web_contacts: web };
}

const DEMO_FILE = () => path.join(config.pbx.soundsDir, 'demo.sln16');

const adminRouter = express.Router();

adminRouter.get('/', async (req, res, next) => {
  try {
    const wsBase = config.publicUrl.replace(/^http/, 'ws');
    const demo = fs.existsSync(DEMO_FILE()) ? fs.statSync(DEMO_FILE()) : null;
    res.json({
      enabled: config.pbx.enabled,
      domain: config.pbx.domain,
      web: { ws_url: config.pbx.wsUrl || `${wsBase}/sip-ws`, uri: `sip:${config.pbx.webUser}@${config.pbx.domain}`, user: config.pbx.webUser, password: config.pbx.webPassword },
      goip: { server: config.pbx.domain, port: 5060, user: config.pbx.goipUser, password: config.pbx.goipPassword },
      settings: await getSettings(),
      demo: demo ? { seconds: Math.round(demo.size / 32000), updated_at: demo.mtime } : null,
      status: config.pbx.enabled ? await status() : null,
    });
  } catch (e) { next(e); }
});

adminRouter.put('/settings', async (req, res, next) => {
  try {
    const { incoming_mode, ring_seconds, language } = req.body || {};
    if (incoming_mode !== undefined) {
      if (!MODES.includes(incoming_mode)) return res.status(400).json({ error: 'Invalid mode' });
      await setSetting('incoming_mode', incoming_mode);
    }
    if (ring_seconds !== undefined) {
      const s = parseInt(ring_seconds, 10);
      if (!(s >= 5 && s <= 120)) return res.status(400).json({ error: 'Ring time must be 5–120 seconds' });
      await setSetting('ring_seconds', s);
    }
    if (language !== undefined) {
      if (!['', 'auto', 'gu', 'hi', 'en'].includes(language)) return res.status(400).json({ error: 'Invalid language' });
      await setSetting('language', language);
    }
    res.json({ ok: true, settings: await getSettings() });
  } catch (e) { next(e); }
});

// Demo clip: the browser converts any audio to 16 kHz mono PCM16 and uploads the raw samples.
adminRouter.post('/demo-audio', express.raw({ type: 'application/octet-stream', limit: '8mb' }), (req, res, next) => {
  try {
    const pcm = req.body;
    if (!Buffer.isBuffer(pcm) || pcm.length < 16000) return res.status(400).json({ error: 'Audio is too short' });
    fs.mkdirSync(config.pbx.soundsDir, { recursive: true });
    const tmp = DEMO_FILE() + '.tmp';
    fs.writeFileSync(tmp, pcm.subarray(0, pcm.length & ~1), { mode: 0o644 });
    fs.renameSync(tmp, DEMO_FILE());
    res.json({ ok: true, seconds: Math.round(pcm.length / 32000) });
  } catch (e) { next(e); }
});

adminRouter.get('/demo-audio', (req, res) => {
  if (!fs.existsSync(DEMO_FILE())) return res.status(404).json({ error: 'No demo clip' });
  res.type('audio/wav').send(stt.toWav(fs.readFileSync(DEMO_FILE())));
});

adminRouter.delete('/demo-audio', (req, res) => {
  fs.rm(DEMO_FILE(), { force: true }, () => res.json({ ok: true }));
});

module.exports = { eventRouter, adminRouter };
