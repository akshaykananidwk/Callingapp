const config = require('./config');

const SAMPLE_RATE = 16000;

/** Wrap 16 kHz mono PCM16 in a WAV container. */
function toWav(pcm) {
  const h = Buffer.alloc(44);
  h.write('RIFF', 0);
  h.writeUInt32LE(36 + pcm.length, 4);
  h.write('WAVE', 8);
  h.write('fmt ', 12);
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20); // PCM
  h.writeUInt16LE(1, 22); // mono
  h.writeUInt32LE(SAMPLE_RATE, 24);
  h.writeUInt32LE(SAMPLE_RATE * 2, 28);
  h.writeUInt16LE(2, 32);
  h.writeUInt16LE(16, 34);
  h.write('data', 36);
  h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}

/** RMS of PCM16 LE normalised to 0..1. */
function rms(pcm) {
  const n = Math.floor(pcm.length / 2);
  if (!n) return 0;
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const s = pcm.readInt16LE(i * 2);
    sum += s * s;
  }
  return Math.sqrt(sum / n) / 32768;
}

// Phrases Whisper tends to invent on silence / noise.
const HALLUCINATIONS = [
  /^\s*[\[(].*[\])]\s*$/, // [Music], (silence)
  /^\s*(thank you|thanks for watching|you)[.!]?\s*$/i,
  /subtitles? by/i,
  /^\s*\.+\s*$/,
];

function clean(text) {
  const t = String(text || '').replace(/\s+/g, ' ').trim();
  if (!t) return '';
  if (HALLUCINATIONS.some((re) => re.test(t))) return '';
  return t;
}

async function whisperCpp(wav, language) {
  const form = new FormData();
  form.append('file', new Blob([wav], { type: 'audio/wav' }), 'chunk.wav');
  form.append('temperature', '0.0');
  form.append('response_format', 'json');
  form.append('language', language || 'auto');
  const res = await fetch(config.whisperUrl, { method: 'POST', body: form });
  if (!res.ok) throw new Error(`whisper.cpp HTTP ${res.status}`);
  const data = await res.json();
  return data.text || '';
}

async function openai(wav, language) {
  if (!config.openaiKey) throw new Error('OPENAI_API_KEY not set');
  const form = new FormData();
  form.append('file', new Blob([wav], { type: 'audio/wav' }), 'chunk.wav');
  form.append('model', config.openaiModel);
  form.append('response_format', 'json');
  if (language && language !== 'auto') form.append('language', language);
  const res = await fetch('https://api.openai.com/v1/audio/transcriptions', {
    method: 'POST',
    headers: { Authorization: `Bearer ${config.openaiKey}` },
    body: form,
  });
  if (!res.ok) throw new Error(`OpenAI HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const data = await res.json();
  return data.text || '';
}

/** Transcribe a PCM16 chunk. Returns cleaned text ('' when nothing useful). */
async function transcribe(pcm, language) {
  const lang = !language || language === 'auto' ? config.defaultLanguage : language;
  const wav = toWav(pcm);
  switch (config.sttProvider) {
    case 'openai': return clean(await openai(wav, lang));
    case 'none': return '';
    default: return clean(await whisperCpp(wav, lang));
  }
}

async function status() {
  if (config.sttProvider === 'none') return { provider: 'none', ok: false, detail: 'Transcription disabled' };
  if (config.sttProvider === 'openai') {
    return { provider: 'openai', ok: !!config.openaiKey, detail: config.openaiKey ? config.openaiModel : 'OPENAI_API_KEY missing' };
  }
  try {
    const u = new URL(config.whisperUrl);
    const res = await fetch(`${u.protocol}//${u.host}/`, { signal: AbortSignal.timeout(2000) });
    return { provider: 'whisper_cpp', ok: res.status < 500, detail: config.whisperUrl };
  } catch (e) {
    return { provider: 'whisper_cpp', ok: false, detail: `Not reachable at ${config.whisperUrl}` };
  }
}

module.exports = { transcribe, status, rms, toWav, SAMPLE_RATE };
