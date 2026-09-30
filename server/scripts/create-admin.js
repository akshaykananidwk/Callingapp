#!/usr/bin/env node
// Usage: node scripts/create-admin.js <username> <password>
// Creates the dashboard admin, or resets the password if the user exists.
const db = require('../src/db');
const { hashPassword } = require('../src/auth');

(async () => {
  const [username, password] = process.argv.slice(2);
  if (!username || !password || password.length < 8) {
    console.error('Usage: node scripts/create-admin.js <username> <password (min 8 chars)>');
    process.exit(1);
  }
  await db.migrate();
  await db.query(
    `INSERT INTO users (username, password_hash) VALUES ($1, $2)
     ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash`,
    [username, hashPassword(password)]
  );
  await db.query('DELETE FROM sessions WHERE user_id = (SELECT id FROM users WHERE username = $1)', [username]);
  console.log(`Admin "${username}" is ready.`);
  await db.pool.end();
})().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
