const { chromium } = require('/opt/node22/lib/node_modules/playwright');
const path = require('path');
const BASE = '/tmp/claude-0/-home-user-expert-octo-train/4ee3de16-e56a-54f6-a66d-17b0e777ccad/scratchpad';

const CASES = [
  ['A  no cloze anywhere, own line',      'dsfdfs'],
  ['B  cloze on line 1, aside on line 2', '1. {{c1::the four consequences}}<br>dsfdfs'],
  ['C  aside AFTER cloze, SAME line',     '1. {{c1::the four consequences}} dsfdfs'],
  ['D  aside BEFORE cloze, same line',    'dsfdfs {{c1::the four consequences}}'],
  ['E  two clozes, aside after c2',       '{{c1::aa}}<br>{{c2::bb}} dsfdfs'],
];

(async () => {
  const browser = await chromium.launch({
    executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const page = await browser.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push(String(e)));
  await page.goto('file://' + path.join(BASE, 'field.html'));
  await page.addScriptTag({ path: path.join(BASE, 'sn/web/sidenote.js') });

  for (const [name, html] of CASES) {
    const out = await page.evaluate(async (html) => {
      document.getElementById('wrap').innerHTML = '';
      const host = window.mkField(html);
      const ed = host.editable;
      ed.focus();
      // select exactly the text "dsfdfs"
      const walk = document.createTreeWalker(ed, NodeFilter.SHOW_TEXT);
      let n, hit = null;
      while ((n = walk.nextNode())) { const i = n.nodeValue.indexOf('dsfdfs');
        if (i >= 0) { hit = [n, i]; break; } }
      if (!hit) return { error: 'target text not found' };
      const sel = document.getSelection();
      sel.removeAllRanges();
      const r = document.createRange();
      r.setStart(hit[0], hit[1]); r.setEnd(hit[0], hit[1] + 6);
      sel.addRange(r);
      const shadowSel = host.shadowRoot.getSelection
        ? host.shadowRoot.getSelection() : null;
      return {
        selText: shadowSel ? shadowSel.toString() : '(no shadow getSelection)',
        rangeCount: shadowSel ? shadowSel.rangeCount : -1,
      };
    }, html);

    await page.keyboard.press('Control+Shift+D');
    const after = await page.evaluate(() => {
      const host = document.querySelector('anki-editable');
      return { html: host.editable.innerHTML, last: window.SideNote._last };
    });
    console.log('--- ' + name);
    console.log('    pre   :', JSON.stringify(out));
    console.log('    result:', JSON.stringify(after.last));
    console.log('    field :', after.html);
  }
  if (errs.length) console.log('PAGE ERRORS:', errs);
  await browser.close();
})();
