// E2E trên trình duyệt thật (Chromium + Playwright có sẵn). Không cài thêm dependency.
// Mỗi luồng: quay video 1280x720, chụp ảnh ở từng điểm kiểm, assert trên chữ/URL nhìn thấy được.
// Thứ tự quan trọng: luồng chủ duyệt đơn (F4) chạy trước luồng người thuê xác minh (F2).
// Chạy: node e2e/web-evidence.cjs [thư_mục_bằng_chứng] [--expect-fail]
//   --expect-fail: đổi một kỳ vọng thành sai để xác nhận script đỏ (negative control).
// Lưu ý giới hạn đăng nhập: 5 lần/phút theo IP+email. Mỗi vai chỉ đăng nhập một lần mỗi lần chạy.
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const BASE = process.env.E2E_BASE || 'http://localhost:5273';
const PASSWORD = process.env.E2E_PASSWORD || 'RentGG-dev-2026!';
const OUT = path.resolve(process.argv[2] || process.env.E2E_OUT || '.');
const NEGATIVE = process.argv.includes('--expect-fail');

fs.mkdirSync(OUT, { recursive: true });
const results = [];
const ORDER_FILE = path.join(OUT, 'awaiting-order-url.txt');

async function newContext(browser, name) {
  const dir = path.join(OUT, 'video', name);
  fs.mkdirSync(dir, { recursive: true });
  const context = await browser.newContext({
    viewport: { width: 1280, height: 720 },
    deviceScaleFactor: 2,
    recordVideo: { dir, size: { width: 1280, height: 720 } },
  });
  return context;
}

async function shot(page, name) {
  await page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: false });
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
  // Tour có thể render trễ sau khi trang tải xong, nên chờ tối đa 3 giây thay vì kiểm tra tức thời.
  if (await skip.waitFor({ state: 'visible', timeout: 3000 }).then(() => true).catch(() => false)) {
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
  if (new URL(page.url()).pathname.startsWith('/login')) {
    const msg = (await error.isVisible().catch(() => false)) ? (await error.innerText()).trim() : 'không rời được /login';
    throw new Error(`đăng nhập ${email} thất bại: "${msg}"`);
  }
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    // F1: sai mật khẩu phải báo lỗi và giữ người dùng ở /login. Dùng tài khoản riêng renter2.
    {
      const context = await newContext(browser, 'f1-wrong-password');
      const page = await context.newPage();
      await check('F1 đăng nhập sai mật khẩu báo lỗi và ở lại /login', async () => {
        await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
        await page.fill('#email', 'renter2@rentgg.dev');
        await page.fill('#password', 'sai-mat-khau-' + Date.now());
        await page.click('button.btn-primary');
        const err = page.locator('p.error');
        await err.waitFor({ state: 'visible', timeout: 10000 });
        const text = (await err.innerText()).trim();
        if (!new URL(page.url()).pathname.startsWith('/login')) throw new Error(`URL sau đăng nhập sai là ${page.url()}`);
        await shot(page, 'f1-wrong-password');
        return `lỗi hiển thị: "${text}"`;
      });
      await context.close();
    }

    // F4: chủ account duyệt đơn đang chờ (seed "Chờ chủ account duyệt") và thấy đơn chuyển khỏi hàng chờ.
    {
      const context = await newContext(browser, 'f4-owner-approves');
      const page = await context.newPage();
      await check('F4 chủ duyệt đơn "Chờ chủ account duyệt"', async () => {
        await login(page, 'owner@rentgg.dev', PASSWORD);
        await page.goto(`${BASE}/orders`, { waitUntil: 'networkidle' });
        // Tour có thể hiện sau khi vào /orders, nên kiểm tra lại ở đây.
        await dismissTourIfShown(page, 'f4-owner');
        await page.getByRole('button', { name: 'Tôi cho thuê' }).click();
        const row = page.locator('tr', { hasText: 'Chờ chủ account duyệt' }).first();
        await row.waitFor({ state: 'visible', timeout: 10000 });
        await shot(page, 'f4-owner-list-awaiting');
        const link = row.locator('a').first();
        const href = await link.getAttribute('href');
        await link.click();
        await page.waitForURL(/\/orders\//, { timeout: 10000 });
        const approve = page.getByRole('button', { name: 'Duyệt đơn' });
        await approve.waitFor({ state: 'visible', timeout: 10000 });
        await shot(page, 'f4-owner-before-approve');
        await approve.click();
        await page.locator('#approve').waitFor({ state: 'detached', timeout: 10000 });
        await page.getByText('Chờ chủ account duyệt', { exact: true }).first().waitFor({ state: 'detached', timeout: 10000 });
        await shot(page, 'f4-owner-after-approve');
        fs.writeFileSync(ORDER_FILE, `${BASE}${href}`);
        return `đơn ${href} đã duyệt; khu "Duyệt đơn" đã biến mất`;
      });
      await context.close();
    }

    // F2: người thuê mở đơn vừa được duyệt và thấy không còn nút "Duyệt đơn" (chủ không duyệt lại được).
    {
      const context = await newContext(browser, 'f2-renter-verifies');
      const page = await context.newPage();
      await check('F2 người thuê thấy đơn và danh sách có dữ liệu', async () => {
        await login(page, 'renter@rentgg.dev', PASSWORD);
        await page.goto(`${BASE}/orders`, { waitUntil: 'networkidle' });
        await page.locator('h1', { hasText: 'Đơn của tôi' }).waitFor({ state: 'visible', timeout: 10000 });
        await dismissTourIfShown(page, 'f2-renter-list');
        const rows = await page.locator('table tbody tr').count();
        if (rows < 1 && !NEGATIVE) throw new Error('danh sách đơn của người thuê không có dòng nào');
        await shot(page, 'f2-renter-orders');
        return `danh sách có ${rows} dòng đơn`;
      });
      await check('F2 người thuê xác minh đơn đã được chủ duyệt', async () => {
        if (!fs.existsSync(ORDER_FILE)) throw new Error('F4 chưa ghi địa chỉ đơn; không có đơn để xác minh');
        const url = fs.readFileSync(ORDER_FILE, 'utf8').trim();
        await page.goto(url, { waitUntil: 'networkidle' });
        await dismissTourIfShown(page, 'f2-renter-order');
        // Khu "Duyệt đơn" chỉ hiện với chủ, nên kiểm tra nhãn trạng thái nhìn thấy được.
        const approved = page.getByText('Đã xác nhận', { exact: true }).first();
        const awaiting = page.getByText('Chờ chủ account duyệt', { exact: true });
        if (NEGATIVE) {
          // Kỳ vọng sai có chủ đích: đòi nhãn "Chờ chủ account duyệt" vẫn còn (đã bị đổi, nên luồng phải đỏ).
          await awaiting.first().waitFor({ state: 'visible', timeout: 5000 });
        } else {
          await approved.waitFor({ state: 'visible', timeout: 10000 });
          const stillAwaiting = await awaiting.count();
          if (stillAwaiting > 0) throw new Error('nhãn "Chờ chủ account duyệt" vẫn còn sau khi duyệt');
        }
        await shot(page, 'f2-renter-order-after-approve');
        return `đơn ${url} hiển thị "Đã xác nhận", không còn "Chờ chủ account duyệt"`;
      });
      await context.close();
    }

    // F3: admin mở "Kiểm duyệt bài đăng", có tiêu đề và có dữ liệu bài đăng.
    {
      const context = await newContext(browser, 'f3-admin-listings');
      const page = await context.newPage();
      await check('F3 admin mở "Kiểm duyệt bài đăng"', async () => {
        await login(page, 'admin@rentgg.dev', PASSWORD);
        await page.goto(`${BASE}/admin/listings`, { waitUntil: 'networkidle' });
        await dismissTourIfShown(page, 'f3-admin');
        const expectedTitle = NEGATIVE ? 'Tiêu đề không có thật' : 'Kiểm duyệt bài đăng';
        await page.locator('h1', { hasText: expectedTitle }).waitFor({ state: 'visible', timeout: 10000 });
        // Mỗi bài đăng là một thẻ div.card.stack (khu xác minh account là section, không tính).
        await page.getByText(/Không có listing|Đang tải|Chờ/).first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
        const cards = await page.locator('div.card.stack').count();
        const empty = await page.getByText('Không có listing ở trạng thái này.').count();
        if (cards < 1 && !NEGATIVE) throw new Error(`không có bài đăng nào ở trạng thái mặc định (thẻ=${cards}, trống=${empty})`);
        await shot(page, 'f3-admin-listings');
        return `tiêu đề "${expectedTitle}" hiển thị; ${cards} bài đăng ở trạng thái mặc định`;
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
