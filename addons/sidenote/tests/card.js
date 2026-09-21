const { chromium } = require('/opt/node22/lib/node_modules/playwright');
const fs = require('fs');
const BASE = '/tmp/claude-0/-home-user-expert-octo-train/4ee3de16-e56a-54f6-a66d-17b0e777ccad/scratchpad';
const CSS = fs.readFileSync(BASE + '/sn_css.txt', 'utf8');
const JS  = fs.readFileSync(BASE + '/sn_js.txt', 'utf8');   // includes <script> tags

// The saved field, exactly as the editor test produced it (case C + case B).
const FIELD =
  '1. {{c1::the four consequences}} <span class="sn" data-sn="why" data-sn-c="1">SAMELINE</span>' +
  '<br>2. {{c2::something else}}' +
  '<br><span class="sn" data-sn="why" data-sn-c="1">OWNLINE</span>';

// Two plausible Enhanced Cloze renderers. Neither is the real one.
const EC = {
  // (1) regex over innerHTML: only the {{cN::..}} runs are replaced.
  preserve: `function(el){ el.innerHTML = el.innerHTML.replace(
      /\\{\\{c(\\d+)::(.*?)\\}\\}/g,
      function(m,n,body){ return '<span class="ec" data-ec="'+n+'">[ ]</span>'; }); }`,
  // (2) per-line rebuild: any line holding a cloze is regenerated from its TEXT.
  destroy: `function(el){
      var parts = el.innerHTML.split(/<br\\s*\\/?>/i);
      for (var i=0;i<parts.length;i++){
        if (!/\\{\\{c\\d+::/.test(parts[i])) continue;
        var d = document.createElement('div'); d.innerHTML = parts[i];
        var text = d.textContent;
        parts[i] = text.replace(/\\{\\{c(\\d+)::(.*?)\\}\\}/g,
          function(m,n){ return '<span class="ec" data-ec="'+n+'">[ ]</span>'; });
      }
      el.innerHTML = parts.join('<br>'); }`,
};

(async () => {
  const browser = await chromium.launch({
    executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  for (const [name, fn] of Object.entries(EC)) {
    const page = await browser.newPage();
    const errs = []; page.on('pageerror', e => errs.push(String(e)));
    await page.setContent(
      `<!doctype html><html><head><meta charset="utf-8"><style>${CSS}</style></head>` +
      `<body><div id="qa">${FIELD}</div>` +
      `<script>window.__snCard = 1;</script>${JS}` +
      // Enhanced Cloze re-renders AFTER page load, and again on every reveal.
      `<script>var ec = ${fn};
         setTimeout(function(){ ec(document.getElementById('qa')); }, 60);
         setTimeout(function(){ ec(document.getElementById('qa')); }, 200);
      <\/script></body></html>`);
    await page.waitForTimeout(700);
    const r = await page.evaluate(() => {
      const vis = el => { const s = getComputedStyle(el);
        return s.display !== 'none' && s.visibility !== 'hidden'; };
      return {
        spans: [...document.querySelectorAll('span.sn')].map(e => ({
          text: e.textContent, ready: e.getAttribute('data-sn-ready'),
          off: e.getAttribute('data-sn-off'), visible: vis(e) })),
        chips: [...document.querySelectorAll('.sn-chip')].map(e => e.textContent),
        onScreen: document.body.innerText.replace(/\s+/g, ' ').trim(),
      };
    });
    console.log('=== Enhanced Cloze mock: ' + name.toUpperCase());
    console.log('    span.sn found :', JSON.stringify(r.spans));
    console.log('    chips         :', JSON.stringify(r.chips));
    console.log('    text on screen:', r.onScreen);
    if (errs.length) console.log('    PAGE ERRORS:', errs);
    await page.close();
  }
  await browser.close();
})();
