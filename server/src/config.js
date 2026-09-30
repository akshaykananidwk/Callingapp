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
  secureCookies: (env.PUBLIC_URL || '').startsWith('https://'),
};
