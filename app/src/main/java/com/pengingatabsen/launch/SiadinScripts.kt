package com.pengingatabsen.launch

import android.net.Uri
import org.json.JSONObject

/**
 * Skrip & aturan bersama untuk SiAdin web, dipakai browser mini dan pengecek presensi di latar.
 * Semua skrip hanya membaca halaman atau mengisi form LOGIN; tidak ada yang menekan tombol presensi.
 */
object SiadinScripts {
    /** Kredensial hanya untuk halaman HTTPS di domain yang sama dengan URL tujuan (mis. *.dinus.ac.id). */
    fun isTrusted(url: String, targetUrl: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        val targetHost = Uri.parse(targetUrl).host?.lowercase() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (host == targetHost) return true
        val labels = targetHost.split('.')
        // mhs.dinus.ac.id → izinkan juga *.dinus.ac.id (mis. halaman SSO kampus).
        val parent = if (labels.size >= 4) labels.drop(1).joinToString(".") else targetHost
        return host == parent || host.endsWith(".$parent")
    }

    fun siteRoot(targetUrl: String): String {
        val uri = Uri.parse(targetUrl)
        return "${uri.scheme}://${uri.host}/"
    }

    /**
     * Membaca kartu "Presensi Kuliah Online" di halaman (hanya membaca).
     * Setiap kartu berisi nama matkul, KDMK/KLPK, tanggal, dan satu tombol:
     * - "Belum Jadwalnya" (atau teks berisi "belum")   → state 'waiting'
     * - tombol nonaktif lainnya (mis. sudah presensi)  → state 'done'
     * - tombol aktif                                   → state 'open'
     * Kartu dicocokkan ke matkul jadwal lewat kata-kata nama matkul (≥3 huruf, tanpa angka).
     * Bila tidak ada kartu yang cocok, semua kartu dipakai sebagai cadangan (nama di jadwal bisa beda).
     */
    private const val CARDS_JS = """
        function __pShown(e){ var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
        function __norm(s){ return (s || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim(); }
        function __cards(course){
          var tokens = __norm(course).split(' ').filter(function(t){ return t.length >= 3 && !/^\d+$/.test(t); });
          var btns = Array.prototype.slice.call(
            document.querySelectorAll('button,a,input[type=button],input[type=submit],[role=button]')
          ).filter(function(b){
            if (!__pShown(b)) return false;
            if (b.closest && b.closest('nav,header,footer,.navbar,.sidebar')) return false;
            if (/presensiOnline/i.test(b.getAttribute('href') || '')) return false;
            var t = (b.innerText || b.value || '').trim();
            if (!t || t.length > 40 || /^presensi\s*online$/i.test(t)) return false;
            return /belum|presensi|hadir|absen|isi|masuk|sudah|klik/i.test(t);
          });
          return btns.map(function(b){
            // Naik ke elemen kartu: pembungkus terdekat yang memuat KDMK/KLPK.
            var card = b, i = 0;
            while (card.parentElement && i < 8 && !/kdmk|klpk/i.test(card.innerText || '')) { card = card.parentElement; i++; }
            var inCard = /kdmk|klpk/i.test(card.innerText || '');
            var ct = __norm(card.innerText);
            var t = (b.innerText || b.value || '').trim();
            var st = window.getComputedStyle(b);
            var disabled = b.disabled === true || b.getAttribute('aria-disabled') === 'true' ||
              /disabled/i.test(b.getAttribute('class') || '') || st.pointerEvents === 'none' || st.cursor === 'not-allowed';
            var state = /belum/i.test(t) ? 'waiting' : (disabled ? 'done' : 'open');
            var match = tokens.length > 0 && tokens.every(function(k){ return ct.indexOf(k) >= 0; });
            return { el: b, state: state, match: match, inCard: inCard };
          }).filter(function(c){ return c.inCard; });
        }
        function __pick(course){
          var all = __cards(course);
          var matched = all.filter(function(c){ return c.match; });
          return { all: all, matched: matched.length > 0, cand: matched.length ? matched : all };
        }
    """

    /**
     * Status presensi untuk satu matkul (hanya membaca):
     * - LOGIN   = form login tampil
     * - LOADING = belum selesai termuat / data akun atau kartu belum muncul
     * - WAITING = "Belum Ada Presensi", atau kartu matkul masih "Belum Jadwalnya"
     * - BUTTON  = tombol presensi aktif (sudah dibuka dosen)
     * - DONE    = kartu matkul ini tombolnya nonaktif tapi bukan "belum" (kemungkinan sudah presensi)
     * - NO_TEXT = sudah login & termuat, tapi tidak ada kartu maupun tulisan "Belum Ada Presensi"
     *
     * Halaman yang BELUM login juga bertuliskan "Belum Ada Presensi" (kotak masa studi th/bl/hr kosong),
     * jadi status hanya dipercaya bila angka masa studi sudah terisi.
     */
    fun presensiStateScript(courseName: String): String = """
        (function(course){
          $CARDS_JS
          var body = document.body;
          if (!body) return 'LOADING';
          if (document.querySelector('input[type=password]')) return 'LOGIN';
          if (document.readyState !== 'complete') return 'LOADING';
          var text = body.innerText || '';
          if (!/\d+\s*(th|bl|hr)\b/i.test(text)) return 'LOADING';
          if (/belum ada presensi/i.test(text)) return 'WAITING';
          var p = __pick(course);
          if (p.cand.some(function(c){ return c.state === 'open'; })) return 'BUTTON';
          if (p.matched && p.cand.some(function(c){ return c.state === 'done'; })) return 'DONE';
          if (p.all.length) return 'WAITING';
          if (!/copyright/i.test(text)) return 'LOADING';
          return 'NO_TEXT';
        })(${JSONObject.quote(courseName)});
    """

    /**
     * Untuk browser mini: sorot tombol presensi AKTIF (bingkai kuning, digulir ke tengah) dan pasang
     * listener klik yang hanya memberi tahu aplikasi saat PENGGUNA menekannya. Tidak pernah memanggil click().
     * Hasil: WAITING / OPEN / UNKNOWN.
     */
    fun highlightScript(courseName: String): String = """
        (function(course){
          $CARDS_JS
          var text = document.body ? (document.body.innerText || '') : '';
          if (/belum ada presensi/i.test(text)) return 'WAITING';
          var p = __pick(course);
          var open = p.cand.filter(function(c){ return c.state === 'open'; })[0];
          if (open) {
            var b = open.el;
            if (!b.__pengingat) {
              b.__pengingat = true;
              b.style.outline = '4px solid #F2B705';
              b.style.outlineOffset = '3px';
              b.style.boxShadow = '0 0 0 8px rgba(242,183,5,.35)';
              b.addEventListener('click', function(){
                try { PengingatAbsen.onPresensiClicked(); } catch (e) {}
              }, true);
            }
            b.scrollIntoView({block: 'center', behavior: 'smooth'});
            return 'OPEN';
          }
          if (p.all.length) return 'WAITING';
          return 'UNKNOWN';
        })(${JSONObject.quote(courseName)});
    """

    /**
     * Kode JS bersama: mencari form login yang BENAR-BENAR tampil di layar.
     * Syarat: tepat satu kolom password terlihat (form ganti password / menu tersembunyi diabaikan),
     * ada kolom NIM/username, dan tombol Masuk/Login. Hasil: objek {pw, user, btn, form} atau string status.
     */
    private const val FIND_LOGIN_JS = """
        function __shown(e){
          var r = e.getBoundingClientRect(), s = window.getComputedStyle(e);
          return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' &&
            r.right > 0 && r.bottom > 0 && r.left < window.innerWidth && r.top < Math.max(window.innerHeight, document.documentElement.scrollHeight);
        }
        function __findLogin(){
          var pws = Array.prototype.slice.call(document.querySelectorAll('input[type=password]')).filter(__shown);
          if (pws.length === 0) return 'NO_FORM';
          if (pws.length !== 1) return 'NOT_LOGIN';
          var pw = pws[0], scope = pw.form || document;
          var texts = Array.prototype.slice.call(scope.querySelectorAll('input')).filter(function(e){
            var t = (e.getAttribute('type') || 'text').toLowerCase();
            return __shown(e) && ['text','email','number','tel'].indexOf(t) >= 0;
          });
          var user = texts.filter(function(e){
            return /nim|user|login|email|npm|induk/i.test((e.name||'') + ' ' + (e.id||'') + ' ' + (e.placeholder||'') + ' ' + (e.getAttribute('aria-label')||''));
          })[0] || (texts.length === 1 ? texts[0] : null);
          if (!user) return 'NOT_LOGIN';
          var btns = Array.prototype.slice.call(scope.querySelectorAll('button,input[type=submit],input[type=button],a')).filter(__shown);
          var btn = btns.filter(function(b){ return /masuk|login|log in|sign\s*in/i.test(b.innerText || b.value || ''); })[0] ||
            btns.filter(function(b){ return (b.getAttribute('type') || '').toLowerCase() === 'submit'; })[0];
          if (!btn) return 'NOT_LOGIN';
          return { pw: pw, user: user, btn: btn };
        }
    """

    /** Hanya mendeteksi apakah halaman login sedang tampil. */
    val DETECT_LOGIN_SCRIPT = """
        (function(){
          $FIND_LOGIN_JS
          var f = __findLogin();
          return typeof f === 'string' ? f : 'LOGIN_PAGE';
        })();
    """

    /** Isi NIM & password pada form login yang tampil, lalu tekan tombol Masuk/Login. */
    fun fillLoginScript(nim: String, password: String): String = """
        (function(nim, pw){
          $FIND_LOGIN_JS
          var f = __findLogin();
          if (typeof f === 'string') return f;
          var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
          function put(el, v){
            el.focus();
            setter.call(el, v);
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            el.blur();
          }
          put(f.user, nim);
          put(f.pw, pw);
          setTimeout(function(){ f.btn.click(); }, 300);
          return 'SUBMITTED';
        })(${JSONObject.quote(nim)}, ${JSONObject.quote(password)});
    """
}
