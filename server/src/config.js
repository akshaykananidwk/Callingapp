require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });

const env = process.env;
module.exports = {
  port: parseInt(env.PORT || '3000', 10),
  host: env.HOST || '127.0.0.1',
  publicUrl: (env.PUBLIC_URL || 'http://localhost:3000').replace(/\/+$/, ''),
  databaseUrl: env.DATABASE_URL || 'postgres://callbridge@127.0.0.1:5432/callbridge',
  sttProvider: (env.STT_PROVIDER || 'whisper_cpp').toLowerCase(),
  whisperUrl: env.WHISPER_URL || 'http://127.0.0.1:8765/inference',
  openaiKey: env.OPENAI_API_KEY || '',
  openaiModel: env.OPENAI_MODEL || 'whisper-1',
  defaultLanguage: env.DEFAULT_LANGUAGE || 'auto',
  chunkSeconds: Math.min(Math.max(parseFloat(env.CHUNK_SECONDS || '5'), 2), 20),
  recordingsDir: env.RECORDINGS_DIR || '/var/lib/callbridge/recordings',
  pbx: {
    enabled: env.PBX_ENABLED === '1',
    domain: env.PBX_DOMAIN || '',
    wsUrl: env.PBX_WS_URL || '',
    secret: env.PBX_SECRET || '',
    webUser: env.SIP_WEB_USER || 'webrtc',
    webPassword: env.SIP_WEB_PASSWORD || '',
    goipUser: env.GOIP_USER || 'goip',
    goipPassword: env.GOIP_PASSWORD || '',
    liveDir: env.PBX_LIVE_DIR || '/var/lib/callbridge/live',
    soundsDir: env.PBX_SOUNDS_DIR || '/var/lib/callbridge/sounds',
  },
  secureCookies: (env.PUBLIC_URL || '').startsWith('https://'),
};
