/** Logged-in dashboard browsers (/live WebSocket): event fan-out + per-client audio routing. */
const clients = new Set();

function add(ws) {
  ws.listenCall = null; // call id whose audio this browser receives
  ws.talkCall = null; // call id this browser's microphone is sent to
  clients.add(ws);
  ws.on('close', () => clients.delete(ws));
}

function broadcast(event) {
  const msg = JSON.stringify(event);
  for (const ws of clients) {
    if (ws.readyState === 1) ws.send(msg);
  }
}

/** Forward phone audio to browsers listening to this call. */
function sendAudio(callId, buf) {
  for (const ws of clients) {
    if (ws.listenCall === callId && ws.readyState === 1 && ws.bufferedAmount < 256 * 1024) {
      ws.send(buf, { binary: true });
    }
  }
}

module.exports = { add, broadcast, sendAudio, clients };
