const { chromium } = require('/opt/node22/lib/node_modules/playwright');
const fs = require('fs');
const B='/tmp/claude-0/-home-user-expert-octo-train/4ee3de16-e56a-54f6-a66d-17b0e777ccad/scratchpad';
const CSS=fs.readFileSync(B+'/sn_css.txt','utf8'), JS=fs.readFileSync(B+'/sn_js.txt','utf8');

const RENDERERS = {
  none: `function(el){}`,
  // reassigns innerHTML; our span returns as a new element (listeners lost)
  preserve: `function(el){ el.innerHTML = el.innerHTML.replace(
      /\\{\\{c(\\d+)::(.*?)\\}\\}/g, function(m,n){
        return '<span class="ec" data-ordinal="'+n+'">[ ]</span>'; }); }`,
  // rebuilds any line holding a deletion from that line's TEXT (span destroyed)
  destroy: `function(el){
      var parts = el.innerHTML.split(/<br\\s*\\/?>/i);
      for (var i=0;i<parts.length;i++){
        if (!/\\{\\{c\\d+::/.test(parts[i])) continue;
        var d=document.createElement('div'); d.innerHTML=parts[i];
        parts[i]=d.textContent.replace(/\\{\\{c(\\d+)::(.*?)\\}\\}/g,function(m,n){
          return '<span class="ec" data-ordinal="'+n+'">[ ]</span>'; });
      }
      el.innerHTML = parts.join('<br>'); }`,
};

const FIELDS = {
  'aside after cloze, same line':
    '1. {{c1::the four consequences}} <span class="sn" data-sn="why" data-sn-c="1">SAMELINE</span>',
  'aside on its own line':
    '1. {{c1::aa}}<br><span class="sn" data-sn="why" data-sn-c="1">OWNLINE</span>',
  'two asides, one line each':
    '1. {{c1::aa}} <span class="sn" data-sn="why" data-sn-c="1">ONE</span>'+
    '<br>2. {{c2::bb}} <span class="sn" data-sn="why" data-sn-c="2">TWO</span>',
  'aside text repeated elsewhere in field':
    'DUPE 1. {{c1::aa}} <span class="sn" data-sn="why" data-sn-c="1">DUPE</span>',
};

let pass=0, fail=0;
function check(name, cond, detail){
  if (cond) { pass++; console.log('  PASS  '+name); }
  else { fail++; console.log('  FAIL  '+name+'   '+detail); }
}

(async()=>{
 const br=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
 for (const [rname, fn] of Object.entries(RENDERERS)) {
  for (const [fname, field] of Object.entries(FIELDS)) {
   const p=await br.newPage();
   const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
   await p.setContent(`<!doctype html><meta charset=utf-8><style>${CSS}</style><div id=qa>${field}</div>`+
     `<script>window.__snCard=1;<\/script>${JS}`+
     `<script>var ec=${fn};
       setTimeout(function(){ec(document.getElementById('qa'));},60);
       setTimeout(function(){ec(document.getElementById('qa'));},200);<\/script>`);
   await p.waitForTimeout(600);
   console.log(`\n[${rname}] ${fname}`);
   const st = await p.evaluate(()=>{
     const vis=e=>getComputedStyle(e).display!=='none';
     return { sn:[...document.querySelectorAll('span.sn')].map(e=>({t:e.textContent,v:vis(e),off:e.getAttribute('data-sn-off')})),
              chips:[...document.querySelectorAll('.sn-chip')].map(e=>e.textContent),
              screen:document.body.innerText.replace(/\s+/g,' ').trim() };
   });
   let expected = (field.match(/class="sn"/g)||[]).length;
   // Ambiguous text under a renderer that destroys the span: restoring is
   // REFUSED by design, so the explanation stays visible rather than the
   // wrong half of the sentence being hidden.
   const ambiguous = (rname==='destroy' && fname.indexOf('repeated')>=0);
   if (ambiguous) expected = 0;
   // an aside belonging to another deletion is meant to be gone: no chip
   const onThisCard = ambiguous ? 0 : (field.match(/class="sn" data-sn="why"(?: data-sn-c="1")?>/g)||[]).length;
   // every aside must exist, and every c1 aside must be hidden on card 1
   check('all asides present', st.sn.length===expected, JSON.stringify(st));
   check('no aside leaks as visible text', st.sn.every(s=>!s.v||s.off), JSON.stringify(st));
   check('one chip per aside shown here', st.chips.length===onThisCard, JSON.stringify(st.chips));
   check('no stray label text on screen', !/why[A-Z]/.test(st.screen), st.screen);
   // chip must actually reveal
   if (st.chips.length) {
     await p.click('.sn-chip');
     await p.waitForTimeout(120);
     const opened = await p.evaluate(()=>getComputedStyle(document.querySelector('span.sn')).display!=='none');
     check('chip reveals after re-render', opened, 'chip dead');
     await p.click('.sn-chip');
     await p.waitForTimeout(120);
     const closed = await p.evaluate(()=>getComputedStyle(document.querySelector('span.sn')).display==='none');
     check('chip hides again', closed, 'did not re-hide');
   }
   if (ambiguous) {
     check('ambiguous match is refused, not guessed', st.sn.length===0, JSON.stringify(st));
     check('its text is still on screen and readable', /DUPE 1\. \[ \] DUPE/.test(st.screen), st.screen);
   }
   if (errs.length) { fail++; console.log('  FAIL  page errors: '+errs.join(' | ')); }
   await p.close();
  }
 }
 await br.close();
 console.log(`\n==== ${pass} passed, ${fail} failed ====`);
 process.exit(fail?1:0);
})();
