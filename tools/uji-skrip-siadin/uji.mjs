// Uji skrip pembaca halaman SiAdin (launch/SiadinScripts.kt) di Chromium sungguhan dengan halaman tiruan.
// Skrip diambil LANGSUNG dari file Kotlin, jadi yang diuji sama persis dengan yang dipakai aplikasi.
//
//   node tools/uji-skrip-siadin/uji.mjs
//
// Butuh paket "playwright" (atau "playwright-core") + Chromium. Bila Chromium ada di lokasi lain:
//   CHROMIUM=/jalur/ke/chrome node tools/uji-skrip-siadin/uji.mjs
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const here = dirname(fileURLToPath(import.meta.url));
const KT = join(here, '../../app/src/main/java/com/pengingatabsen/launch/SiadinScripts.kt');
const src = readFileSync(KT, 'utf8');

function raw(re) {
  const m = src.match(re);
  if (!m) throw new Error('tidak ketemu di SiadinScripts.kt: ' + re);
  return m[1];
}
const CARDS = raw(/const val CARDS_JS = """([\s\S]*?)"""/);
const LOGIN = raw(/const val FIND_LOGIN_JS = """([\s\S]*?)"""/);
const TPL = {
  probe: raw(/fun probeScript\(courseName: String\): String = """([\s\S]*?)"""/),
  summary: raw(/fun cardsSummaryScript\(courseName: String\): String = """([\s\S]*?)"""/),
  highlight: raw(/fun highlightScript\(courseName: String\): String = """([\s\S]*?)"""/),
  texts: raw(/val CARD_TEXTS_SCRIPT = """([\s\S]*?)"""/),
};
// Meniru template string Kotlin ($CARDS_JS, $FIND_LOGIN_JS, ${JSONObject.quote(courseName)}).
const render = (tpl, course = '') => tpl
  .replace(/\$CARDS_JS/g, () => CARDS)
  .replace(/\$FIND_LOGIN_JS/g, () => LOGIN)
  .replace(/\$\{JSONObject\.quote\(courseName\)\}/g, () => JSON.stringify(course));

// ---------- Halaman tiruan ----------
const STUDI = '<div class="masa">Masa studi: 4 th 1 bl 9 hr</div>';
const NAVBAR = (tag = 'nav') =>
  `<${tag} class="navbar"><b>SiAdin</b> <span>Sistem Informasi Akademik</span> <span>BUDI DATA PRATAMA</span>${STUDI}</${tag}>`;
const FOOTER = (tag = 'footer') => `<${tag} class="foot">SiAdin | Copyright © Udinus 2008 - 2026 All rights reserved</${tag}>`;
const STATUS = {
  waiting: '<button class="btn" disabled>Belum Jadwalnya</button>',
  open: '<button class="btn btn-primary" id="tombol">Presensi Sekarang</button>',
  done: '<div class="alert-success">Berhasil Presensi</div>',
};
// Kartu gaya bootstrap: judul di card-body.
const card = (name, klpk, pct, st) => `
  <div class="col"><div class="card"><div class="card-body">
    <h6>${name}</h6><p>KDMK: A11.64501</p><p>KLPK: A11.${klpk}</p><p>09 October 2026</p>
    <div>${pct} %</div>${STATUS[st]}<small>Realisasi RPS: Belum Dikonfirmasi</small>
  </div></div></div>`;
// Kartu dengan judul di card-header terpisah dari body.
const cardSplit = (name, klpk, pct, st) => `
  <div class="card"><div class="card-header">${name}</div><div class="card-body">
    <span>KDMK:</span> <b>A11.64501</b><br><span>KLPK:</span> <b>A11.${klpk}</b><br>09 October 2026<br>${pct} %<br>${STATUS[st]}
  </div></div>`;
const page = (cards, { nav = 'nav', foot = 'footer', heading = true } = {}) => `<!doctype html><html><body>
  <div id="app">${NAVBAR(nav)}
    <div class="container">${heading ? '<h4>Presensi Kuliah Online</h4>' : ''}<div class="row">${cards}</div></div>
    ${FOOTER(foot)}
  </div></body></html>`;

const one = (st) => page(card('KRIPTOGRAFI', '4502', '21.43', st));
const oneNoSemantic = (st) => page(card('KRIPTOGRAFI', '4502', '21.43', st), { nav: 'div', foot: 'div' });
const two = page(card('SISTEM INFORMASI', '4507', '28.57', 'open') + card('KRIPTOGRAFI', '4502', '21.43', 'waiting'));
const splitOne = page(cardSplit('KRIPTOGRAFI', '4502', '21.43', 'open'), { nav: 'div', foot: 'div' });
// Terburuk: isi halaman dalam satu elemen (tanpa pembungkus kartu), navbar di elemen lain.
const flat = `<!doctype html><html><body><div>${NAVBAR('div')}<div class="content">Presensi Kuliah Online<br>KRIPTOGRAFI<br>
  KDMK: A11.64501<br>KLPK: A11.4502<br>09 October 2026<br>21.43 %<br>Berhasil Presensi<br>Realisasi RPS: Belum Dikonfirmasi</div>
  ${FOOTER('div')}</div></body></html>`;
const krsOne = `<!doctype html><html><body><div>${NAVBAR('div')}
  <div class="tabs"><a>KRS</a> <a>KHS</a> <a>Jadwal Ujian</a> <a>Presensi Online</a> <a>Daftar Nilai</a> <a>Matrikulasi</a> <a>Semester Antara</a></div>
  <div class="grid"><div class="krs"><div>TECHNOPRENEURSHIP 2 SKS</div><div>KDMK: AF201703 —— KLPK: A11.4502</div>
  <div>SENIN 12.30-14.10 H.5.9</div><div>-</div><div>-</div><div>28.57 %</div></div></div>${FOOTER('div')}</div></body></html>`;
const empty = `<!doctype html><html><body>${NAVBAR()}<h4>Presensi Kuliah Online</h4><p>Belum Ada Presensi</p>${FOOTER()}</body></html>`;
const login = `<!doctype html><html><body><form><input name="nim" type="text"><input type="password"><button type="submit">Masuk</button></form></body></html>`;

// ---------- Kasus uji ----------
const SI = 'Sistem Informasi 4507';
const KR = 'Kriptografi 4502';
const cases = [
  // Satu kartu MATKUL LAIN di halaman; kata "Sistem Informasi" ada di navbar. Tidak boleh dikira kartu SI.
  ['satu kartu lain (waiting) → bukan kartu SI', one('waiting'), 'probe', SI, 'NO_CARD'],
  ['satu kartu lain (dibuka) → bukan kartu SI', one('open'), 'probe', SI, 'NO_CARD'],
  ['satu kartu lain (berhasil) → BUKAN berhasil SI', one('done'), 'probe', SI, 'NO_CARD'],
  ['tanpa tag semantik: kartu lain berhasil → bukan SI', oneNoSemantic('done'), 'probe', SI, 'NO_CARD'],
  ['halaman datar: kartu lain berhasil → bukan SI', flat, 'probe', SI, 'NO_CARD'],
  // Kartu matkul ini tetap dikenali (tidak boleh terlewat).
  ['satu kartu sendiri: waiting', one('waiting'), 'probe', KR, 'WAITING'],
  ['satu kartu sendiri: dibuka', one('open'), 'probe', KR, 'BUTTON'],
  ['satu kartu sendiri: berhasil', one('done'), 'probe', KR, 'DONE'],
  ['tanpa tag semantik: kartu sendiri dibuka', oneNoSemantic('open'), 'probe', KR, 'BUTTON'],
  ['judul di header terpisah: dibuka', splitOne, 'probe', KR, 'BUTTON'],
  ['halaman datar: kartu sendiri berhasil', flat, 'probe', KR, 'DONE'],
  ['nama tanpa kode: kartu sendiri', one('open'), 'probe', 'Kriptografi', 'BUTTON'],
  ['nama tanpa kode, kartu lain dibuka → cadangan dibuka', one('open'), 'probe', 'Pemrograman Game', 'BUTTON'],
  ['dua kartu: SI dibuka', two, 'probe', SI, 'BUTTON'],
  ['dua kartu: Kriptografi menunggu', two, 'probe', KR, 'WAITING'],
  ['dua kartu: matkul lain berkode → NO_CARD', two, 'probe', 'Pemrograman Game 4703', 'NO_CARD'],
  ['tanpa kartu, Belum Ada Presensi', empty, 'probe', KR, 'EMPTY'],
  ['form login', login, 'probe', KR, 'LOGIN_FORM'],
  // Log diagnosis: nama kartu = judul kartu, bukan "SiAdin".
  ['ringkasan: nama kartu satu kartu', one('waiting'), 'summary', SI, (r) => r.includes('KRIPTOGRAFI=waiting') && !/SiAdin=/.test(r)],
  ['ringkasan: halaman datar', flat, 'summary', KR, (r) => r.includes('KRIPTOGRAFI=done*')],
  ['ringkasan: dua kartu', two, 'summary', SI, (r) => r.includes('SISTEM INFORMASI=open*') && r.includes('KRIPTOGRAFI=waiting')],
  // Teks kartu (persentase resmi & impor KRS): hanya isi kartu.
  ['teks kartu satu kartu', one('waiting'), 'texts', '', (r) => {
    const t = JSON.parse(r); return t.length === 1 && /^KRIPTOGRAFI/.test(t[0].trim()) && !/copyright|siadin|informasi akademik|\bth\b/i.test(t[0]);
  }],
  ['teks kartu halaman datar', flat, 'texts', '', (r) => {
    const t = JSON.parse(r); return t.length === 1 && /^KRIPTOGRAFI/.test(t[0].trim()) && !/copyright|kuliah online|siadin/i.test(t[0]) && /21\.43 %/.test(t[0]);
  }],
  ['teks kartu KRS satu kartu', krsOne, 'texts', '', (r) => {
    const t = JSON.parse(r); return t.length === 1 && /^TECHNOPRENEURSHIP 2 SKS/.test(t[0].trim()) && !/KHS|Daftar Nilai|copyright/i.test(t[0]) && /28\.57 %/.test(t[0]);
  }],
  ['teks kartu dua kartu', two, 'texts', '', (r) => {
    const t = JSON.parse(r); return t.length === 2 && /^SISTEM INFORMASI/.test(t[0].trim()) && /^KRIPTOGRAFI/.test(t[1].trim());
  }],
  // Sorotan: hanya untuk kartu sendiri; kartu lain tidak disorot sebagai milik SI.
  ['sorotan: kartu sendiri dibuka', one('open'), 'highlight', KR, (r, st) => r === 'OPEN' && st.outlined === 1],
  ['sorotan: kartu lain dibuka, nama berkode → tidak disorot', one('open'), 'highlight', SI, (r, st) => r === 'WAITING' && st.outlined === 0],
  ['sorotan: dua kartu, kartu lain diredupkan', two, 'highlight', SI, (r, st) => r === 'OPEN' && st.outlined === 1 && st.dimmed === 1],
];

// Penanda "di luar kartu": daftar yang sama dengan SiadinPresensiRulesTest.outsideMarkers (aturan JS = Kotlin).
const OUTSIDE_YES = ['Presensi Kuliah Online', 'Masa studi: 4 th 1 bl', 'Belum Ada Presensi', 'SiAdin | Copyright © Udinus',
  'KHS', 'Jadwal Ujian', 'KRS KHS Jadwal Ujian Presensi Online'];
const OUTSIDE_NO = ['KRIPTOGRAFI', 'KDMK: A11.64501', 'Belum Jadwalnya', 'Realisasi RPS: Belum Dikonfirmasi', '09 October 2026',
  '21.43 %', '3 SKS', 'SENIN 12.30-14.10 H.5.9', 'Presensi Sekarang', 'Berhasil Presensi', 'TECHNOPRENEURSHIP 2 SKS',
  '• KAMIS 12.30-15.00 Kulino'];
const outsideScript = `(function(){ ${CARDS}
  var yes = ${JSON.stringify(OUTSIDE_YES)}, no = ${JSON.stringify(OUTSIDE_NO)};
  return yes.filter(function(t){ return !__outside(t); }).concat(no.filter(function(t){ return __outside(t); })).join(' | ') || 'OK';
})()`;
cases.push(['penanda luar kartu sama dengan aturan Kotlin', empty, 'raw:' + outsideScript, '', 'OK']);

// ---------- Jalankan ----------
let pw;
try { pw = require('playwright'); } catch { pw = require('playwright-core'); }
const browser = await pw.chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
const pg = await browser.newPage({ viewport: { width: 412, height: 915 } });
let failed = 0;
for (const [name, html, kind, course, expect] of cases) {
  await pg.setContent(html, { waitUntil: 'load' });
  let result;
  try {
    result = await pg.evaluate(kind.startsWith('raw:') ? kind.slice(4) : render(TPL[kind], course));
  } catch (e) {
    result = 'ERROR ' + e.message.split('\n')[0];
  }
  const st = await pg.evaluate(() => ({
    outlined: [...document.querySelectorAll('*')].filter((e) => e.style.outline).length,
    dimmed: [...document.querySelectorAll('*')].filter((e) => e.style.opacity === '0.4').length,
  }));
  const ok = typeof expect === 'function' ? !!expect(String(result), st) : result === expect;
  if (!ok) failed++;
  console.log(`${ok ? 'LULUS' : 'GAGAL'}  ${name}` + (ok ? '' : `\n       hasil: ${String(result).slice(0, 300)}`));
}
await browser.close();
console.log(failed ? `\n${failed} dari ${cases.length} GAGAL` : `\nSemua ${cases.length} lulus`);
process.exit(failed ? 1 : 0);
