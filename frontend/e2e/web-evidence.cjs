// E2E trên trình duyệt thật (Chromium + Playwright có sẵn). Không cài thêm dependency.
// Mỗi luồng: quay video 1280x720, chụp ảnh ở từng điểm kiểm, assert trên chữ/URL nhìn thấy được.
// Chạy: node e2e/web-evidence.cjs [thư_mục_bằng_chứng] [--expect-fail]
//   --expect-fail: đổi một kỳ vọng thành sai để xác nhận script đỏ (negative control).
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const BASE = process.env.E2E_BASE || 'http://localhost:5273';
const PASSWORD = process.env.E2E_PASSWORD || 'RentGG-dev-2026!';
const OUT = path.resolve(process.argv[2] || process.env.E2E_OUT || '.');
const NEGATIVE = process.argv.includes('--expect-fail');

fs.mkdirSync(OUT, { recursive: true });
const results = [];

async function newContext(browser, name) {
  const dir = path.join(OUT, 'video', name);
  fs.mkdirSync(dir, { recursive: true });
  const context = await browser.newContext({
    viewport: { width: 1280, height: 720 },
    deviceScaleFactor: 2,
    recordVideo: { dir, size: { width: 1280, height: 720 } },
  });
  return { context, dir };
}

async function shot(page, name) {
  const file = path.join(OUT, `${name}.png`);
  await page.screenshot({ path: file, fullPage: false });
  return file;
}

async function check(name, fn) {
  const started = Date.now();
  try {
    const detail = await fn();
    results.push({ name, ok: true, detail, ms: Date.now() - started });
  } catch (err) {
    results.push({ name, ok: false, detail: String(err.message || err).split('\n')[0], ms: Date.now() - started });
  }
}

// Tour chào mừng hiện ở lần đăng nhập đầu của người dùng mới; nó che trang nên phải chụp rồi bỏ qua.
async function dismissTourIfShown(page, name) {
  const skip = page.getByRole('button', { name: 'Bỏ qua' });
  if (await skip.isVisible({ timeout: 2000 }).catch(() => false)) {
    await shot(page, `${name}-tour-shown`);
    await skip.click();
    await skip.waitFor({ state: 'detached', timeout: 5000 }).catch(() => {});
    return true;
  }
  return false;
}

// Sau khi bấm đăng nhập: chờ rời /login hoặc thấy thông báo lỗi; lỗi thì báo đúng nội dung trên trang.
async function login(page, email, password) {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.fill('#email', email);
  await page.fill('#password', password);
  await page.click('button.btn-primary');
  const error = page.locator('p.error');
  await Promise.race([
    page.waitForURL((u) => !new URL(u).pathname.startsWith('/login'), { timeout: 15000 }),
    error.waitFor({ state: 'visible', timeout: 15000 }),
  ]).catch(() => {});
  if (new URL(page.url()).pathname.startsWith('/login') && (await error.isVisible().catch(() => false))) {
    throw new Error(`đăng nhập ${email} bị từ chối: "${(await error.innerText()).trim()}"`);
  }
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    // F1: sai mật khẩu phải báo lỗi và giữ người dùng ở /login.
    {
      const { context, dir } = await newContext(browser, 'f1-wrong-password');
      const page = await context.newPage();
      await check('F1 đăng nhập sai mật khẩu báo lỗi và ở lại /login', async () => {
        // Dùng tài khoản riêng để lần sai mật khẩu không đếm vào tài khoản người thuê của F2.
        await login(page, 'renter2@rentgg.dev', 'sai-mat-khau-' + Date.now()).catch(() => {});
        const err = page.locator('p.error');
        await err.waitFor({ state: 'visible', timeout: 10000 });
        const text = (await err.innerText()).trim();
        if (!new URL(page.url()).pathname.startsWith('/login')) throw new Error(`URL sau đăng nhập sai là ${page.url()}`);
        await shot(page, 'f1-wrong-password');
        return `lỗi hiển thị: "${text}"`;
      });
      await context.close();
      results.find((r) => r.name.startsWith('F1')).video = await page.video().path();
    }

    // F2: người thuê đăng nhập đúng, thấy đúng trang "Đơn của tôi" và nút 🕒 chỉ có ở bản dev.
    {
      const { context } = await newContext(browser, 'f2-renter-login');
      const page = await context.newPage();
      await check('F2 người thuê đăng nhập và mở "Đơn của tôi"', async () => {
        await login(page, 'renter@rentgg.dev', PASSWORD);
        await page.waitForURL((u) => !new URL(u).pathname.startsWith('/login'), { timeout: 15000 });
        await page.goto(`${BASE}/orders`, { waitUntil: 'networkidle' });
        const tour = await dismissTourIfShown(page, 'f2-renter');
        const h1 = page.locator('h1', { hasText: 'Đơn của tôi' });
        await h1.waitFor({ state: 'visible', timeout: 10000 });
        const clock = await page.locator('button, [role="button"]', { hasText: '🕒' }).count();
        if (clock === 0 && !NEGATIVE) throw new Error('không thấy nút 🕒 của đồng hồ dev');
        await shot(page, 'f2-renter-orders');
        return `tiêu đề "Đơn của tôi" hiển thị; tour chào mừng: ${tour ? 'có (đã bỏ qua, ảnh f2-renter-tour-shown)' : 'không'}; nút 🕒: ${clock > 0 ? 'có' : 'không'}`;
      });
      await context.close();
    }

    // F3: admin mở "Kiểm duyệt bài đăng" và thấy bảng bài đăng.
    {
      const { context } = await newContext(browser, 'f3-admin-listings');
      const page = await context.newPage();
      await check('F3 admin mở "Kiểm duyệt bài đăng"', async () => {
        await login(page, 'admin@rentgg.dev', PASSWORD);
        await page.waitForURL((u) => !new URL(u).pathname.startsWith('/login'), { timeout: 15000 });
        await page.goto(`${BASE}/admin/listings`, { waitUntil: 'networkidle' });
        const tour = await dismissTourIfShown(page, 'f3-admin');
        const expectedTitle = NEGATIVE ? 'Tiêu đề không có thật' : 'Kiểm duyệt bài đăng';
        await page.locator('h1', { hasText: expectedTitle }).waitFor({ state: 'visible', timeout: 10000 });
        await shot(page, 'f3-admin-listings');
        return `tiêu đề "${expectedTitle}" hiển thị; tour chào mừng: ${tour ? 'có (đã bỏ qua)' : 'không'}`;
      });
      await context.close();
    }
  } finally {
    await browser.close();
  }

  const failed = results.filter((r) => !r.ok);
  for (const r of results) {
    console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}  (${r.ms} ms)  ${r.detail}`);
  }
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2));
  console.log(`\n${results.length - failed.length}/${results.length} luồng đạt`);
  process.exit(failed.length ? 1 : 0);
})().catch((err) => {
  console.error('LỖI KHÔNG MONG ĐỢI:', err);
  process.exit(2);
});
