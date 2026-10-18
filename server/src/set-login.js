// Sets the login: PV_USER=name PV_PASS=secret node src/set-login.js  (signs out every device)
const crypto = require('crypto');
const { db, setSetting } = require('./db');
const user = (process.env.PV_USER || '').trim(), pw = process.env.PV_PASS || '';
if (!user || pw.length < 4) { console.error('Set PV_USER and PV_PASS (4+ characters).'); process.exit(1); }
const salt = crypto.randomBytes(16).toString('hex');
setSetting('username', user);
setSetting('password', `${salt}:${crypto.scryptSync(pw, salt, 64).toString('hex')}`);
db.prepare('DELETE FROM sessions').run();
console.log('Login updated for ' + user);
