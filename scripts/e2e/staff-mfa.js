// Browser check for the staff second factor: enrol, wrong code, right code, back-office page.
const { chromium } = require('playwright');
const crypto = require('crypto');

const BASE = 'http://localhost:5273';
const EMAIL = 'admin@rentgg.dev';
const PASSWORD = 'RentGG-dev-2026!';

function base32Decode(s) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, value = 0; const out = [];
  for (const ch of s.replace(/=+$/, '').toUpperCase()) {
    value = (value << 5) | alphabet.indexOf(ch); bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
}
function totp(secret, atMs) {
  const counter = Math.floor(atMs / 1000 / 30);
  const buf = Buffer.alloc(8); buf.writeBigUInt64BE(BigInt(counter));
  const h = crypto.createHmac('sha1', base32Decode(secret)).update(buf).digest();
  const o = h[h.length - 1] & 0x0f;
  const bin = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(bin % 1000000).padStart(6, '0');
}
async function dismiss(page) {
  const o = page.locator('.overlay');
  if (await o.count()) { await page.keyboard.press('Escape'); await o.waitFor({ state: 'detached', timeout: 5000 }).catch(() => {}); }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (m) => console.log(new Date().toISOString().slice(11, 19), m);

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const fail = async (what) => { await page.screenshot({ path: '/tmp/claude-pg16/e2e/fail.png' }); throw new Error(what); };

  // 1. Unenrolled staff: login, then the enrolment panel.
  await page.goto(`${BASE}/login`);
  await page.fill('#email', EMAIL);
  await page.fill('#password', PASSWORD);
  await page.click('button.btn-primary');
  await page.getByRole('button', { name: 'Tạo khoá bí mật' }).waitFor({ timeout: 15000 }).catch(() => fail('enrol panel not shown'));
  log('enrolment panel shown');
  await dismiss(page);
  await page.getByRole('button', { name: 'Tạo khoá bí mật' }).click();
  const secret = (await page.locator('code').first().innerText()).trim();
  log('secret read from page (length ' + secret.length + ')');

  // 2. Confirm with the current step.
  await page.fill('#mfaCode', totp(secret, Date.now()));
  await dismiss(page);
  await page.getByRole('button', { name: 'Xác nhận' }).click();
  await page.getByText('Đã bật xác thực hai bước').waitFor({ timeout: 15000 }).catch(() => fail('confirm did not succeed'));
  log('enrolled');
  await dismiss(page);
  await page.getByRole('main').getByRole('button', { name: 'Đăng xuất' }).click();
  await page.waitForURL('**/login', { timeout: 15000 }).catch(() => {});

  // 3. Wait for the next 30-second step (the confirmed code cannot be reused).
  const nextStepIn = 30000 - (Date.now() % 30000) + 1500;
  log('waiting ' + Math.round(nextStepIn / 1000) + 's for the next code');
  await sleep(nextStepIn);

  // 4. Password only: the server asks for a code. Wrong code first.
  await page.fill('#email', EMAIL);
  await page.fill('#password', PASSWORD);
  await page.click('button.btn-primary');
  await page.locator('#totpCode').waitFor({ timeout: 15000 }).catch(() => fail('code field not shown'));
  log('code field shown');
  await page.fill('#totpCode', '000000');
  await page.click('button.btn-primary');
  await page.getByText('Mã xác thực không đúng').waitFor({ timeout: 15000 }).catch(() => fail('wrong code message not shown'));
  log('wrong code rejected with message');

  // 5. Right code: the back-office page loads with data.
  await page.fill('#totpCode', totp(secret, Date.now()));
  await page.click('button.btn-primary');
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15000 }).catch(() => fail('still on login after right code'));
  await page.waitForLoadState('networkidle');
  await dismiss(page);
  const text = await page.getByRole('main').innerText();
  const denied = /Bạn không có quyền|Bật xác thực hai bước|Cần mã xác thực/.test(text);
  const hasData = /Account chờ xác minh|Game mẫu|Account Vàng/.test(text);
  log(`landed on ${new URL(page.url()).pathname}; denied=${denied}; hasData=${hasData}`);
  if (denied || !hasData) await fail('admin page did not load data');
  await page.screenshot({ path: '/tmp/claude-pg16/e2e/admin-page.png' });
  log('OK');
  await browser.close();
})().catch((e) => { console.error('FAILED:', e.message); process.exit(1); });
