// Sends email (password reset codes) through an SMTP account set in the environment:
// SMTP_USER + SMTP_PASS (for Gmail: the address and an app password), optional SMTP_HOST/SMTP_PORT/MAIL_FROM.
const nodemailer = require('nodemailer');

const user = process.env.SMTP_USER || '', pass = process.env.SMTP_PASS || '';
const port = Number(process.env.SMTP_PORT) || 465;
const transport = user && pass ? nodemailer.createTransport({
  host: process.env.SMTP_HOST || 'smtp.gmail.com', port, secure: port === 465, auth: { user, pass },
}) : null;

const ready = () => !!transport;
const send = (to, subject, text, html) =>
  transport.sendMail({ from: process.env.MAIL_FROM || `Gallery <${user}>`, to, subject, text, html });

module.exports = { ready, send };
