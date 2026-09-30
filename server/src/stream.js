/**
 * Phone audio stream (PRD section 08).
 * Binary frames: 16 kHz mono PCM16. Audio is cut into ~CHUNK_SECONDS pieces at a quiet point,
 * transcribed in order, saved as transcript_segments and pushed to the phone + dashboard.
 */
const fs = require('fs');
const path = require('path');
const db = require('./db');
const config = require('./config');
const stt = require('./stt');
const live = require('./live');

const BYTES_PER_MS = 32; // 16000 Hz * 2 bytes / 1000
const FRAME_BYTES = 6400; // 200 ms
const SILENCE_RMS = 0.006;
const IDLE_FINALIZE_MS = 10 * 60 * 1000;

/** call_id -> CallStream (survives phone reconnects during a call). */
const streams = new Map();

class CallStream {
  constructor(callId, language) {
    this.callId = callId;
    this.language = language;
    this.buffer = [];
    this.bufferBytes = 0;
    this.chunkStartMs = 0;
    this.receivedBytes = 0;
    this.queue = Promise.resolve();
    this.sockets = new Set();
    this.finished = false;
    this.idleTimer = null;
    this.openRecording();
  }

  // ---- Automatic recording: <RECORDINGS_DIR>/<call_id>.wav ----
  openRecording() {
    try {
      fs.mkdirSync(config.recordingsDir, { recursive: true });
      this.recordingPath = path.join(config.recordingsDir, `${this.callId}.wav`);
      const exists = fs.existsSync(this.recordingPath);
      this.recording = fs.createWriteStream(this.recordingPath, { flags: 'a' });
      if (!exists) this.recording.write(stt.toWav(Buffer.alloc(0))); // header, sizes fixed on finish
      this.recording.on('error', (e) => { console.error('recording error', e.message); this.recording = null; });
    } catch (e) {
      console.error(`[stream ${this.callId}] cannot record:`, e.message);
      this.recording = null;
    }
  }

  async closeRecording() {
    if (!this.recording) return null;
    await new Promise((r) => this.recording.end(r));
    this.recording = null;
    const size = fs.statSync(this.recordingPath).size;
    if (size <= 44) { fs.unlinkSync(this.recordingPath); return null; }
    const fd = fs.openSync(this.recordingPath, 'r+');
    const b = Buffer.alloc(4);
    b.writeUInt32LE(size - 8, 0); fs.writeSync(fd, b, 0, 4, 4);
    b.writeUInt32LE(size - 44, 0); fs.writeSync(fd, b, 0, 4, 40);
    fs.closeSync(fd);
    return this.recordingPath;
  }

  /** Browser microphone audio → phone (played on its loudspeaker). */
  sendToPhone(buf) {
    for (const ws of this.sockets) if (ws.readyState === 1) ws.send(buf, { binary: true });
  }

  attach(ws) {
    this.sockets.add(ws);
    clearTimeout(this.idleTimer);
    ws.on('close', () => {
      this.sockets.delete(ws);
      if (!this.sockets.size && !this.finished) {
        this.idleTimer = setTimeout(() => this.finish(), IDLE_FINALIZE_MS);
      }
    });
  }

  push(data) {
    if (this.finished) return;
    if (this.recording) this.recording.write(data);
    live.sendAudio(this.callId, data);
    this.buffer.push(data);
    this.bufferBytes += data.length;
    this.receivedBytes += data.length;
    const target = config.chunkSeconds * 1000 * BYTES_PER_MS;
    const lastFrame = data.length >= FRAME_BYTES ? data.subarray(data.length - FRAME_BYTES) : data;
    const quiet = stt.rms(lastFrame) < SILENCE_RMS;
    // Cut on a quiet frame once we have enough audio, or hard-cut at 1.8x target.
    if ((this.bufferBytes >= target && quiet) || this.bufferBytes >= target * 1.8) this.cut();
  }

  cut() {
    if (!this.bufferBytes) return;
    const pcm = Buffer.concat(this.buffer);
    const offsetMs = this.chunkStartMs;
    this.chunkStartMs += Math.round(pcm.length / BYTES_PER_MS);
    this.buffer = [];
    this.bufferBytes = 0;
    this.queue = this.queue.then(() => this.transcribe(pcm, offsetMs)).catch((e) => {
      console.error(`[stream ${this.callId}] transcribe failed:`, e.message);
      live.broadcast({ type: 'error', call_id: this.callId, message: e.message });
    });
  }

  async transcribe(pcm, offsetMs) {
    if (pcm.length < 0.5 * 1000 * BYTES_PER_MS) return; // < 0.5 s
    if (stt.rms(pcm) < SILENCE_RMS) return; // silence: skip (avoids hallucinations)
    const text = await stt.transcribe(pcm, this.language);
    if (!text) return;
    await db.query(
      'INSERT INTO transcript_segments (call_id, text, offset_ms) VALUES ($1, $2, $3)',
      [this.callId, text, offsetMs]
    );
    const msg = { type: 'transcript', call_id: this.callId, text, ts: offsetMs };
    for (const ws of this.sockets) if (ws.readyState === 1) ws.send(JSON.stringify(msg));
    live.broadcast(msg);
  }

  /** Flush remaining audio, wait for transcription, mark call completed. */
  async finish() {
    if (this.finished) return this.queue;
    this.finished = true;
    clearTimeout(this.idleTimer);
    this.cut();
    await this.queue;
    streams.delete(this.callId);
    const recordingPath = await this.closeRecording().catch((e) => { console.error(e); return null; });
    const { rows } = await db.query(
      `UPDATE calls SET status = 'completed', recording_path = COALESCE($2, recording_path),
         ended_at = COALESCE(ended_at, NOW()),
         duration_sec = COALESCE(duration_sec, EXTRACT(EPOCH FROM (NOW() - started_at))::int)
       WHERE id = $1 RETURNING *`,
      [this.callId, recordingPath]
    );
    if (rows[0]) live.broadcast({ type: 'call_ended', call: rows[0] });
  }
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Handle an authenticated phone WebSocket. */
async function handleDevice(ws, req) {
  const callId = String(req.headers['x-call-id'] || new URL(req.url, 'http://x').searchParams.get('call_id') || '');
  if (!UUID_RE.test(callId)) return ws.close(4400, 'Missing or invalid X-Call-ID');
  const { rows } = await db.query('SELECT id, language FROM calls WHERE id = $1', [callId]);
  if (!rows[0]) return ws.close(4404, 'Unknown call');

  const headerLang = String(req.headers['x-language'] || '').toLowerCase();
  let stream = streams.get(callId);
  if (!stream) {
    stream = new CallStream(callId, headerLang || rows[0].language || 'auto');
    streams.set(callId, stream);
  }
  stream.attach(ws);

  ws.on('message', (data, isBinary) => {
    if (isBinary) return stream.push(Buffer.from(data));
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }
    if (msg.type === 'end') {
      stream.finish()
        .catch((e) => console.error('finish failed', e))
        .finally(() => ws.close(1000, 'call finalized'));
    }
  });
}

/** Called from POST /calls/:id/end — finalize if the socket never sent "end". */
async function endCall(callId) {
  const s = streams.get(callId);
  if (s) await s.finish();
}

function activeCallIds() {
  return [...streams.keys()];
}

function sendToPhone(callId, buf) {
  const s = streams.get(callId);
  if (s) s.sendToPhone(buf);
}

module.exports = { handleDevice, endCall, activeCallIds, sendToPhone };
