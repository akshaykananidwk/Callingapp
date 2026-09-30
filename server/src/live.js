/** Fan-out of live events to logged-in dashboard browsers (/live WebSocket). */
const clients = new Set();

function add(ws) {
  clients.add(ws);
  ws.on('close', () => clients.delete(ws));
}

function broadcast(event) {
  const msg = JSON.stringify(event);
  for (const ws of clients) {
    if (ws.readyState === 1) ws.send(msg);
  }
}

module.exports = { add, broadcast };
