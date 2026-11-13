"""The QR codes GET /api/connect hands out (starcast/qr.py): the same symbols a well-known encoder makes."""
import hashlib
import random
import shutil
import string
import subprocess
import unittest
import xml.etree.ElementTree as ET

from starcast import qr

# Symbols checked module for module against qrencode (libqrencode 4, level M, 8-bit mode), with the mask it chose.
GOLDEN = (
    ("http://100.64.0.9:8777/", 2, 25, "e825dae8e11c2622ee92679a3cd4990a"),
    ("http://pc.tail1234.ts.net:8777/?token=abcdefghijklmnopqrstuvwx", 3, 33, "d3fd9e8f326b0fd5f2b63479c77e43e6"),
    ("https://music.example.com/" * 6, 2, 53, "80eecbbb641d49e37eabdf949045df43"),      # version 9: version information
    ("x" * 300, 0, 69, "1b0ce35009886d141b2800ee67e99715"),                            # version 13: 16-bit length
)


def digest(rows):
    return hashlib.sha256("".join("1" if v else "0" for r in rows for v in r).encode()).hexdigest()[:32]


def qrencode(text):
    out = subprocess.run(["qrencode", "-l", "M", "-8", "-m", "0", "-t", "ASCII", "-o", "-"], input=text,
                         capture_output=True, text=True, check=True, timeout=10).stdout
    return [[line[i] == "#" for i in range(0, len(line), 2)] for line in out.split("\n") if line]


class QrTests(unittest.TestCase):
    def test_known_symbols(self):
        for text, mask, size, want in GOLDEN:
            rows = qr.encode(text, mask=mask)
            self.assertEqual(len(rows), size, text)
            self.assertEqual(digest(rows), want, text)

    @unittest.skipUnless(shutil.which("qrencode"), "needs qrencode")
    def test_the_same_as_qrencode(self):
        rnd = random.Random(7)
        chars = string.ascii_letters + string.digits + ":/?=.-_"
        for n in list(range(1, 40)) + list(range(40, 400, 23)):
            text = "".join(rnd.choice(chars) for _ in range(n))
            ref = qrencode(text)
            self.assertTrue(any(qr.encode(text, mask=k) == ref for k in range(8)), n)

    def test_function_patterns_and_format(self):
        rows = qr.encode("http://100.64.0.9:8777/")
        n = len(rows)
        finder = [[max(abs(x - 3), abs(y - 3)) not in (2, 4) for x in range(7)] for y in range(7)]
        for ox, oy in ((0, 0), (n - 7, 0), (0, n - 7)):
            self.assertEqual([r[ox:ox + 7] for r in rows[oy:oy + 7]], finder)
        self.assertEqual([rows[6][x] for x in range(8, n - 8)], [x % 2 == 0 for x in range(8, n - 8)])
        self.assertTrue(rows[n - 8][8])                 # the dark module
        # the format information (both copies) says level M and a valid mask
        bits = sum(rows[8][x] << (14 - i) for i, x in enumerate((0, 1, 2, 3, 4, 5, 7, 8)))
        bits |= sum(rows[y][8] << (6 - i) for i, y in enumerate((7, 5, 4, 3, 2, 1, 0)))
        data = (bits ^ 0x5412) >> 10
        self.assertEqual(data >> 3, 0b00)
        rem = data
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        self.assertEqual((data << 10 | rem) ^ 0x5412, bits)

    def test_svg(self):
        doc = ET.fromstring(qr.svg("http://100.64.0.9:8777/", title="a <b>"))
        self.assertEqual(doc.get("viewBox"), "0 0 33 33")      # 25 modules and a quiet zone of 4 on each side
        ns = "{http://www.w3.org/2000/svg}"
        self.assertEqual(doc.find(ns + "title").text, "a <b>")
        self.assertTrue(doc.find(ns + "path").get("d").startswith("M4 4h7v1h-7z"))   # the finder's top row

    def test_too_long(self):
        with self.assertRaises(ValueError):
            qr.encode("x" * 2332)
        self.assertEqual(len(qr.encode("x" * 2331)), 177)       # version 40


if __name__ == "__main__":
    unittest.main()
