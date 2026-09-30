const path = require('path');
const fs = require('fs');
const http = require('http');
const express = require('express');
const { WebSocketServer } = require('ws');
const config = require('./config');
const db = require('./db');
const auth = require('./auth');
const stream = require('./stream');
const deviceHub = require('./deviceHub');
const control = require('./control');

process.on('unhandledRejection', (e) => console.error('Unhandled rejection:', e));

const app = express();
app.set('trust proxy', 'loopback');
app.disable('x-powered-by');
app.use(express.json({ limit: '1mb' }));

app.get('/health', (req, res) => res.json({ ok: true }));
const pbx = require('./pbx');
app.use('/pbx', pbx.eventRouter);
app.use('/admin/api/pbx', auth.requireUser, pbx.adminRouter);
app.use('/api', require('./routes/api'));
app.use('/admin/api', require('./routes/admin'));
app.use(['/api', '/admin/api'], (req, res) => res.status(404).json({ error: 'Not found' }));

// Latest APK for sideloading (login required).
const APK_DIR = path.join(__dirname, '..', '..', 'release');
app.get('/download/CallBridge.apk', auth.requireUser, (req, res) => {
  const files = fs.existsSync(APK_DIR) ? fs.readdirSync(APK_DIR).filter((f) => f.endsWith('.apk')).sort() : [];
  if (!files.length) return res.status(404).send('APK not found on server');
  res.download(path.join(APK_DIR, files[files.length - 1]), 'CallBridge.apk');
});

app.use(express.static(path.join(__dirname, '..', 'public'), { extensions: ['html'] }));
app.get('*', (req, res) => res.sendFile(path.join(__dirname, '..', 'public', 'index.html')));

app.use((err, req, res, next) => {
  console.error(err);
  res.status(500).json({ error: 'Server error' });
});

const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });

server.on('upgrade', async (req, socket, head) => {
  const reject = (code, msg) => {
    socket.write(`HTTP/1.1 ${code} ${msg}\r\nConnection: close\r\n\r\n`);
    socket.destroy();
  };
  try {
    const { pathname, searchParams } = new URL(req.url, 'http://localhost');
    if (pathname === '/stream' || pathname === '/callbridge/stream') {
      const token = await auth.findToken(auth.bearer(req) || searchParams.get('token'));
      if (!token) return reject(401, 'Unauthorized');
      wss.handleUpgrade(req, socket, head, (ws) => {
        ws.isAlive = true;
        ws.on('pong', () => { ws.isAlive = true; });
        stream.handleDevice(ws, req).catch((e) => { console.error(e); ws.close(1011, 'error'); });
      });
    } else if (pathname === '/device') {
      const token = await auth.findToken(auth.bearer(req) || searchParams.get('token'));
      if (!token) return reject(401, 'Unauthorized');
      wss.handleUpgrade(req, socket, head, (ws) => {
        ws.isAlive = true;
        ws.on('pong', () => { ws.isAlive = true; });
        deviceHub.attach(ws, token);
      });
    } else if (pathname === '/live') {
      const user = await auth.sessionUser(req);
      if (!user) return reject(401, 'Unauthorized');
      wss.handleUpgrade(req, socket, head, (ws) => {
        ws.isAlive = true;
        ws.on('pong', () => { ws.isAlive = true; });
        control.handle(ws, user);
      });
    } else {
      reject(404, 'Not Found');
    }
  } catch (e) {
    console.error(e);
    reject(500, 'Server Error');
  }
});

// Drop dead sockets.
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000).unref();

// Housekeeping: expired sessions, calls left "active" by a crashed phone.
setInterval(() => {
  db.query('DELETE FROM sessions WHERE expires_at < NOW()').catch(() => {});
  const active = stream.activeCallIds();
  db.query(
    `UPDATE calls SET status = 'completed', ended_at = COALESCE(ended_at, NOW())
     WHERE status = 'active' AND started_at < NOW() - INTERVAL '4 hours' AND NOT (id = ANY($1::uuid[]))`,
    [active]
  ).catch(() => {});
}, 10 * 60 * 1000).unref();

db.migrate()
  .then(() => {
    server.listen(config.port, config.host, () => {
      console.log(`CallBridge server on http://${config.host}:${config.port} (public ${config.publicUrl}, STT ${config.sttProvider})`);
    });
  })
  .catch((e) => {
    console.error('Database migration failed:', e.message);
    process.exit(1);
  });
