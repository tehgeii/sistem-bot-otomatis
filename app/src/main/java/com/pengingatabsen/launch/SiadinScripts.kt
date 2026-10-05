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
     * Status halaman Presensi Online (hanya membaca):
     * - LOGIN   = form login tampil
     * - LOADING = belum selesai termuat / data akun belum muncul
     * - WAITING = sudah login dan tertulis "Belum Ada Presensi"
     * - BUTTON  = sudah login, teks itu tidak ada, dan tombol presensi terlihat
     * - NO_TEXT = sudah login, teks itu tidak ada, tapi tombol presensi tidak terlihat
     *
     * Penting: halaman yang BELUM login juga menampilkan "Belum Ada Presensi" (kotak masa studi
     * th/bl/hr kosong), dan kartu presensi dimuat belakangan lewat AJAX. Jadi status hanya dipercaya
     * bila angka masa studi sudah terisi, dan pemanggil harus melihat hasil yang sama beberapa kali.
     */
    val PRESENSI_STATE_SCRIPT = """
        (function(){
          var body = document.body;
          if (!body) return 'LOADING';
          if (document.querySelector('input[type=password]')) return 'LOGIN';
          if (document.readyState !== 'complete') return 'LOADING';
          var text = body.innerText || '';
          var loggedIn = /\d+\s*(th|bl|hr)\b/i.test(text);
          if (!loggedIn) return 'LOADING';
          if (/belum ada presensi/i.test(text)) return 'WAITING';
          if (!/copyright/i.test(text)) return 'LOADING';
          var found = Array.prototype.slice.call(
            document.querySelectorAll('button,a,input[type=button],input[type=submit]')
          ).some(function(b){
            var r = b.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) return false;
            if (b.closest && b.closest('nav,header,.navbar,.sidebar')) return false;
            if (/presensiOnline/i.test(b.getAttribute('href') || '')) return false;
            var t = (b.innerText || b.value || '').trim();
            if (/^presensi\s*online$/i.test(t)) return false;
            return t.length > 0 && t.length < 40 && /presensi|hadir|absen/i.test(t);
          });
          return found ? 'BUTTON' : 'NO_TEXT';
        })();
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
