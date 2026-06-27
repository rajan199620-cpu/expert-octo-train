// Amazon product availability + price checker (Playwright/Chromium).
// Prints a single JSON line to stdout describing what was observed.
// Used by the hourly cloud cron loop to watch a Lenovo Precision Pen 2 listing.

import pw from '/opt/node22/lib/node_modules/playwright/index.js';
const { chromium } = pw;

const URL = process.env.PRODUCT_URL ||
  'https://www.amazon.in/Lenovo-Precision-Pen-Laptop-Recognition/dp/B0BR4N1ZRW/';

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

function out(o) { console.log(JSON.stringify(o)); }

const proxyServer = process.env.HTTPS_PROXY || process.env.https_proxy || undefined;

const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  headless: true,
  args: ['--no-sandbox', '--disable-blink-features=AutomationControlled'],
  proxy: proxyServer ? { server: proxyServer } : undefined,
});

try {
  const ctx = await browser.newContext({
    userAgent: UA,
    locale: 'en-IN',
    viewport: { width: 1280, height: 900 },
    extraHTTPHeaders: { 'Accept-Language': 'en-IN,en;q=0.9' },
  });
  const page = await ctx.newPage();
  const resp = await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  const status = resp ? resp.status() : 0;
  await page.waitForTimeout(2500);

  const data = await page.evaluate(() => {
    const t = (sel) => {
      const el = document.querySelector(sel);
      return el ? el.textContent.trim().replace(/\s+/g, ' ') : null;
    };
    const title = t('#productTitle');
    const availability = t('#availability') || t('#availability span') || t('#outOfStock');
    const addToCart = !!document.querySelector('#add-to-cart-button');
    const buyNow = !!document.querySelector('#buy-now-button');
    // Price: prefer the buy-box price, fall back to any a-offscreen price.
    let priceText =
      t('#corePriceDisplay_desktop_feature_div .a-price .a-offscreen') ||
      t('#corePrice_feature_div .a-price .a-offscreen') ||
      t('.a-price .a-offscreen');
    const captcha = /Enter the characters you see|Type the characters|not a robot|api-services-support@amazon/i
      .test(document.body ? document.body.innerText : '');
    const bodyLen = document.body ? document.body.innerText.length : 0;
    return { title, availability, addToCart, buyNow, priceText, captcha, bodyLen };
  });

  // Parse a numeric rupee value out of the price string, if any.
  let priceValue = null;
  if (data.priceText) {
    const m = data.priceText.replace(/[, ]/g, '').match(/(\d+(?:\.\d+)?)/);
    if (m) priceValue = parseFloat(m[1]);
  }

  // Decide stock status. In stock = a buyable button present, or availability
  // text that is clearly positive and not a "currently unavailable" message.
  const availLower = (data.availability || '').toLowerCase();
  const negative = /unavailable|out of stock|currently not|sold out/.test(availLower);
  const positive = /in stock|only \d+ left|available/.test(availLower);
  let inStock = null; // null = could not determine
  if (data.captcha) inStock = null;
  else if (data.addToCart || data.buyNow) inStock = true;
  else if (negative) inStock = false;
  else if (positive) inStock = true;
  else if (data.title) inStock = false; // page loaded, no buy button, no positive text

  out({
    ok: true,
    httpStatus: status,
    url: URL,
    checkedAt: new Date().toISOString(),
    title: data.title,
    availabilityText: data.availability,
    addToCart: data.addToCart,
    buyNow: data.buyNow,
    priceText: data.priceText,
    priceValue,
    captcha: data.captcha,
    bodyLen: data.bodyLen,
    inStock,
  });
} catch (e) {
  out({ ok: false, error: String(e && e.message || e), checkedAt: new Date().toISOString() });
} finally {
  await browser.close();
}
