#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
خادم تحديثات VOLTIR CALL (بدون أي مكتبات خارجية).

الاستخدام:
1) ضع هذا الملف وملف الـAPK في نفس المجلد.
2) شغّله:  python update_server.py
3) اكتب اسم ملف النسخة (مثال: app-debug.apk).
4) سيطبع عنواناً مثل:  http://192.168.1.5:8765
5) ضعه في التطبيق: الإعدادات ← خادم التحديث ← حفظ، ثم «التحقق من التحديث».
"""
import hashlib
import json
import os
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

BASE = os.path.dirname(os.path.abspath(__file__))
PORT = 8765
APK = ""
CACHE = {"mtime": 0.0, "sha": "", "size": 0}


def lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def candidates(name: str):
    """أماكن البحث عن ملف النسخة: مجلد السكريبت، مجلد المشروع، سطح المكتب."""
    home = os.path.expanduser("~")
    return [
        os.path.join(BASE, name),
        os.path.join(os.path.dirname(BASE), name),
        os.path.join(home, "Desktop", name),
        os.path.join(home, "Downloads", name),
    ]


def ask_apk() -> str:
    while True:
        name = input("اكتب اسم ملف النسخة الجديدة (مثال: app-debug.apk): ").strip().strip('"')
        if not name:
            print("لم تكتب اسماً، حاول مرة أخرى.")
            continue
        if os.path.isabs(name) and os.path.isfile(name):
            path = name
        else:
            path = ""
            for c in candidates(os.path.basename(name)):
                if os.path.isfile(c):
                    path = c
                    break
        if not path:
            print("الملف غير موجود. ضعه بجانب السكريبت أو على سطح المكتب.")
            continue
        if not path.lower().endswith(".apk"):
            print("لازم يكون الملف بصيغة .apk")
            continue
        print("تم العثور عليه: " + path)
        return path


def apk_info():
    st = os.stat(APK)
    if st.st_mtime != CACHE["mtime"] or st.st_size != CACHE["size"]:
        h = hashlib.sha256()
        with open(APK, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        CACHE.update(mtime=st.st_mtime, sha=h.hexdigest(), size=st.st_size)
        print("تم تجهيز النسخة: %s (%d بايت)" % (os.path.basename(APK), st.st_size))
    return CACHE


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[%s] %s" % (time.strftime("%H:%M:%S"), fmt % args))

    def _send(self, code: int, ctype: str, body: bytes, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if extra:
            for k, v in extra.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        info = apk_info()
        if u.path in ("/", "/index.html"):
            html = (
                "<html><body dir='rtl' style='font-family:sans-serif'>"
                "<h2>VOLTIR CALL — خادم التحديث</h2>"
                "<p>الملف: <b>%s</b></p><p>الحجم: %d بايت</p>"
                "<p><a href='/apk'>تنزيل APK</a></p></body></html>"
                % (os.path.basename(APK), info["size"])
            ).encode("utf-8")
            self._send(200, "text/html; charset=utf-8", html)
            return
        if u.path in ("/update.json", "/check"):
            q = parse_qs(u.query)
            installed = (q.get("sha") or [""])[0]
            host = self.headers.get("Host") or ("%s:%d" % (lan_ip(), PORT))
            body = json.dumps(
                {
                    "available": installed != info["sha"],
                    "version": time.strftime("%Y-%m-%d %H:%M", time.localtime(info["mtime"])),
                    "name": os.path.basename(APK),
                    "size": info["size"],
                    "sha256": info["sha"],
                    "url": "http://%s/apk" % host,
                }
            ).encode("utf-8")
            self._send(200, "application/json", body)
            return
        if u.path == "/apk" or u.path == "/" + os.path.basename(APK):
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(info["size"]))
            self.send_header(
                "Content-Disposition",
                'attachment; filename="%s"' % os.path.basename(APK),
            )
            self.end_headers()
            with open(APK, "rb") as f:
                while True:
                    b = f.read(1 << 16)
                    if not b:
                        break
                    self.wfile.write(b)
            return
        self.send_error(404)


def main():
    global APK, PORT
    if len(sys.argv) > 1 and sys.argv[1].isdigit():
        PORT = int(sys.argv[1])
    print("=" * 56)
    print("VOLTIR CALL — خادم التحديث المحلي")
    print("=" * 56)
    APK = ask_apk()
    apk_info()
    url = "http://%s:%d" % (lan_ip(), PORT)
    print("-" * 56)
    print("ضع هذا العنوان في التطبيق (الإعدادات ← خادم التحديث):")
    print("   " + url)
    print("الهاتف والكمبيوتر على نفس شبكة الواي فاي. للمقاطعة: Ctrl+C")
    print("إذا لم يتصل الهاتف: اسمح لـ Python بجدار الحماية (Windows Firewall).")
    print("-" * 56)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nتم الإيقاف.")
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
