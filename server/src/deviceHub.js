/**
 * Persistent control connection to each phone (/device WebSocket).
 * The dashboard sends commands (dial / answer / hangup / speaker) and the phone reports
 * its call state (ringing / offhook / idle) so the website knows about calls instantly.
 */
const crypto = require('crypto');
const live = require('./live');

/** token_id -> { ws, tokenId, name, info, state, connectedAt } */
const devices = new Map();
const pending = new Map(); // command id -> { resolve, timer }

function view(d) {
  return {
    token_id: d.tokenId,
    name: d.name,
    online: d.ws.readyState === 1,
    info: d.info,
    state: d.state,
    connected_at: d.connectedAt,
  };
}

function list() {
  return [...devices.values()].map(view);
}

function attach(ws, token) {
  const old = devices.get(token.id);
  if (old && old.ws !== ws) old.ws.terminate();
  const d = { ws, tokenId: token.id, name: token.name, info: {}, state: { state: 'idle' }, connectedAt: new Date().toISOString() };
  devices.set(token.id, d);
  live.broadcast({ type: 'phone', device: view(d) });

  ws.on('message', (data, isBinary) => {
    if (isBinary) return;
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }
    switch (msg.type) {
      case 'hello':
        d.info = { app_version: msg.app_version, battery: msg.battery, network: msg.network, auto_answer: msg.auto_answer, permissions: msg.permissions };
        live.broadcast({ type: 'phone', device: view(d) });
        break;
      case 'state':
        d.state = { state: msg.state, number: msg.number || null, direction: msg.direction || null, since: new Date().toISOString() };
        live.broadcast({ type: 'phone', device: view(d) });
        if (msg.state === 'ringing') live.broadcast({ type: 'incoming', device: view(d), number: msg.number || null });
        break;
      case 'ack': {
        const p = pending.get(msg.id);
        if (p) { clearTimeout(p.timer); pending.delete(msg.id); p.resolve({ ok: !!msg.ok, error: msg.error || null }); }
        break;
      }
      case 'log':
        console.log(`[phone ${d.name}] ${msg.message}`);
        live.broadcast({ type: 'phone_log', token_id: d.tokenId, name: d.name, message: String(msg.message || '').slice(0, 300) });
        break;
    }
  });

  ws.on('close', () => {
    if (devices.get(token.id) === d) {
      devices.delete(token.id);
      live.broadcast({ type: 'phone', device: { ...view(d), online: false } });
    }
  });
}

/** Send a command to a phone and wait for its ack. */
function command(tokenId, cmd) {
  const d = tokenId ? devices.get(tokenId) : [...devices.values()].find((x) => x.ws.readyState === 1);
  if (!d || d.ws.readyState !== 1) return Promise.resolve({ ok: false, error: 'Phone is not connected' });
  const id = crypto.randomUUID();
  return new Promise((resolve) => {
    const timer = setTimeout(() => { pending.delete(id); resolve({ ok: false, error: 'Phone did not respond' }); }, 15000);
    pending.set(id, { resolve, timer });
    d.ws.send(JSON.stringify({ ...cmd, id }));
  });
}

module.exports = { attach, command, list };
