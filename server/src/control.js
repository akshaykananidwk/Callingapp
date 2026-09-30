/** Commands and microphone audio coming from dashboard browsers over /live. */
const live = require('./live');
const deviceHub = require('./deviceHub');
const stream = require('./stream');

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function handle(ws, user) {
  live.add(ws);
  ws.send(JSON.stringify({ type: 'phones', devices: deviceHub.list() }));

  ws.on('message', async (data, isBinary) => {
    if (isBinary) {
      // Browser microphone → phone loudspeaker.
      if (ws.talkCall) stream.sendToPhone(ws.talkCall, Buffer.from(data));
      return;
    }
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }
    const reply = (res) => ws.readyState === 1 && ws.send(JSON.stringify({ type: 'cmd_result', req: msg.req, cmd: msg.type, ...res }));
    const tokenId = msg.token_id || null;

    switch (msg.type) {
      case 'dial': {
        const number = String(msg.number || '').replace(/[^\d+*#]/g, '');
        if (number.length < 3) return reply({ ok: false, error: 'Enter a valid number' });
        console.log(`[web ${user.username}] dial ${number}`);
        return reply(await deviceHub.command(tokenId, { type: 'dial', number }));
      }
      case 'answer':
      case 'hangup':
        console.log(`[web ${user.username}] ${msg.type}`);
        return reply(await deviceHub.command(tokenId, { type: msg.type }));
      case 'speaker':
        return reply(await deviceHub.command(tokenId, { type: 'speaker', on: !!msg.on }));
      case 'listen':
        ws.listenCall = UUID_RE.test(msg.call_id || '') ? msg.call_id : null;
        return reply({ ok: true });
      case 'talk':
        ws.talkCall = msg.on && UUID_RE.test(msg.call_id || '') ? msg.call_id : null;
        return reply({ ok: true });
    }
  });
}

module.exports = { handle };
