"""QR codes for the addresses a phone can open (byte mode, error correction level M), drawn as SVG.

Small on purpose: no dependencies, only what a web address needs. Follows ISO/IEC 18004: Reed-Solomon codewords over
GF(256), the function patterns, the zigzag placement, and the mask with the lowest penalty."""

# error correction codewords per block, and blocks, for versions 1..40 at level M (index 0 unused)
_ECC_PER_BLOCK = (0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28,
                  28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28)
_BLOCKS = (0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29,
           31, 33, 35, 37, 38, 40, 43, 45, 47, 49)
_LEVEL_M = 0b00             # the format information's two bits for level M

# GF(256) with the polynomial x^8 + x^4 + x^3 + x^2 + 1
_EXP = [0] * 512
_LOG = [0] * 256
_x = 1
for _i in range(255):
    _EXP[_i] = _x
    _LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    _EXP[_i] = _EXP[_i - 255]


def _mul(a, b):
    return 0 if a == 0 or b == 0 else _EXP[_LOG[a] + _LOG[b]]


def _generator(degree):
    """Coefficients (highest power first, leading 1 left out) of (x - a^0)(x - a^1)...(x - a^(degree-1))."""
    g = [1]
    for i in range(degree):
        nxt = [0] * (len(g) + 1)
        for j, c in enumerate(g):
            nxt[j] ^= c
            nxt[j + 1] ^= _mul(c, _EXP[i])
        g = nxt
    return g[1:]


def _ecc(data, degree):
    gen = _generator(degree)
    rem = [0] * degree
    for b in data:
        factor = b ^ rem[0]
        rem = rem[1:] + [0]
        for i, c in enumerate(gen):
            rem[i] ^= _mul(c, factor)
    return rem


def _alignment_positions(ver):
    if ver == 1:
        return []
    n = ver // 7 + 2
    size = ver * 4 + 17
    step = 26 if ver == 32 else (ver * 4 + n * 2 + 1) // (n * 2 - 2) * 2
    return [6] + sorted(size - 7 - i * step for i in range(n - 1))


def _raw_modules(ver):
    """Modules left for data and error correction once the function patterns are drawn."""
    n = (16 * ver + 128) * ver + 64
    if ver >= 2:
        k = ver // 7 + 2
        n -= (25 * k - 10) * k - 55
        if ver >= 7:
            n -= 36
    return n


def _data_capacity(ver):
    """Data codewords at level M."""
    return _raw_modules(ver) // 8 - _ECC_PER_BLOCK[ver] * _BLOCKS[ver]


def _codewords(data, ver):
    """Data bytes -> the final codeword sequence (blocks with their error correction, interleaved)."""
    bits = []

    def put(value, length):
        bits.extend((value >> i) & 1 for i in range(length - 1, -1, -1))

    put(0b0100, 4)                                   # byte mode
    put(len(data), 8 if ver <= 9 else 16)
    for b in data:
        put(b, 8)
    cap = _data_capacity(ver) * 8
    put(0, min(4, cap - len(bits)))                  # terminator
    put(0, -len(bits) % 8)
    out = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = (0xEC, 0x11)
    while len(out) < cap // 8:
        out.append(pad[(len(out) - len(bits) // 8) % 2])

    blocks, ecc_len = _BLOCKS[ver], _ECC_PER_BLOCK[ver]
    total = _raw_modules(ver) // 8
    short = blocks - total % blocks                  # blocks one data codeword shorter than the rest
    short_len = total // blocks - ecc_len
    data_blocks, ecc_blocks, k = [], [], 0
    for i in range(blocks):
        n = short_len + (0 if i < short else 1)
        d = out[k:k + n]
        k += n
        data_blocks.append(d)
        ecc_blocks.append(_ecc(d, ecc_len))
    result = []
    for i in range(short_len + 1):
        for d in data_blocks:
            if i < len(d):
                result.append(d[i])
    for i in range(ecc_len):
        for e in ecc_blocks:
            result.append(e[i])
    return result


_MASKS = (
    lambda x, y: (x + y) % 2 == 0,
    lambda x, y: y % 2 == 0,
    lambda x, y: x % 3 == 0,
    lambda x, y: (x + y) % 3 == 0,
    lambda x, y: (x // 3 + y // 2) % 2 == 0,
    lambda x, y: x * y % 2 + x * y % 3 == 0,
    lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0,
    lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0,
)


class _Symbol:
    def __init__(self, ver):
        self.ver = ver
        self.size = ver * 4 + 17
        self.dark = [[False] * self.size for _ in range(self.size)]
        self.fixed = [[False] * self.size for _ in range(self.size)]

    def set(self, x, y, dark):
        self.dark[y][x] = dark
        self.fixed[y][x] = True

    def draw_function_patterns(self):
        s = self.size
        for i in range(s):                           # timing patterns
            self.set(6, i, i % 2 == 0)
            self.set(i, 6, i % 2 == 0)
        for cx, cy in ((3, 3), (s - 4, 3), (3, s - 4)):  # finder patterns with their separators
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    x, y = cx + dx, cy + dy
                    if 0 <= x < s and 0 <= y < s:
                        d = max(abs(dx), abs(dy))
                        self.set(x, y, d not in (2, 4))
        pos = _alignment_positions(self.ver)
        last = len(pos) - 1
        for i, ax in enumerate(pos):
            for j, ay in enumerate(pos):
                if (i, j) in ((0, 0), (0, last), (last, 0)):
                    continue                         # where the finder patterns are
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        self.set(ax + dx, ay + dy, max(abs(dx), abs(dy)) != 1)
        self.draw_format(0)                          # reserves its modules; drawn for real once the mask is chosen
        self.draw_version()

    def draw_format(self, mask):
        data = _LEVEL_M << 3 | mask
        rem = data
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = (data << 10 | rem) ^ 0x5412
        bit = lambda i: (bits >> i) & 1 == 1  # noqa: E731
        s = self.size
        for i in range(6):
            self.set(8, i, bit(i))
        self.set(8, 7, bit(6))
        self.set(8, 8, bit(7))
        self.set(7, 8, bit(8))
        for i in range(9, 15):
            self.set(14 - i, 8, bit(i))
        for i in range(8):
            self.set(s - 1 - i, 8, bit(i))
        for i in range(8, 15):
            self.set(8, s - 15 + i, bit(i))
        self.set(8, s - 8, True)                     # always dark

    def draw_version(self):
        if self.ver < 7:
            return
        rem = self.ver
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        bits = self.ver << 12 | rem
        for i in range(18):
            d = (bits >> i) & 1 == 1
            a, b = self.size - 11 + i % 3, i // 3
            self.set(a, b, d)
            self.set(b, a, d)

    def place(self, codewords):
        s, i, total = self.size, 0, len(codewords) * 8
        right = s - 1
        while right >= 1:
            if right == 6:
                right = 5                            # the vertical timing pattern is skipped
            upward = (right + 1) & 2 == 0
            for vert in range(s):
                y = s - 1 - vert if upward else vert
                for x in (right, right - 1):
                    if not self.fixed[y][x] and i < total:
                        self.dark[y][x] = (codewords[i >> 3] >> (7 - (i & 7))) & 1 == 1
                        i += 1
            right -= 2

    def apply_mask(self, mask):
        f = _MASKS[mask]
        for y in range(self.size):
            for x in range(self.size):
                if not self.fixed[y][x] and f(x, y):
                    self.dark[y][x] = not self.dark[y][x]

    def penalty(self):
        s, m, score = self.size, self.dark, 0
        lines = [row for row in m] + [[m[y][x] for y in range(s)] for x in range(s)]
        for line in lines:
            run, prev = 0, None
            for v in line:                           # runs of five or more of the same colour
                if v == prev:
                    run += 1
                else:
                    if run >= 5:
                        score += run - 2
                    run, prev = 1, v
            if run >= 5:
                score += run - 2
            # dark-light-dark-dark-dark-light-dark with four light modules on one side (outside counts as light)
            padded = [False] * 4 + line + [False] * 4
            for i in range(len(padded) - 10):
                w = padded[i:i + 11]
                if w == [False] * 4 + [True, False, True, True, True, False, True] or \
                        w == [True, False, True, True, True, False, True] + [False] * 4:
                    score += 40
        for y in range(s - 1):                       # 2x2 blocks of one colour
            for x in range(s - 1):
                v = m[y][x]
                if v == m[y][x + 1] == m[y + 1][x] == m[y + 1][x + 1]:
                    score += 3
        dark = sum(sum(row) for row in m)            # how far the share of dark modules is from half
        total = s * s
        score += (abs(dark * 20 - total * 10) + total - 1) // total * 10 - 10
        return score


def encode(text, mask=None):
    """text -> the QR symbol as rows of booleans (True = dark), without the quiet zone. Raises ValueError when it
    doesn't fit (more than 2331 bytes). mask: a mask pattern to use (0-7) instead of the one with the lowest penalty."""
    data = text.encode("utf-8")
    for ver in range(1, 41):
        header = 4 + (8 if ver <= 9 else 16)
        if header + len(data) * 8 <= _data_capacity(ver) * 8:
            break
    else:
        raise ValueError("too long for a QR code")
    sym = _Symbol(ver)
    sym.draw_function_patterns()
    sym.place(_codewords(data, ver))
    if mask is None:
        best = None
        for k in range(8):
            sym.apply_mask(k)
            sym.draw_format(k)
            p = sym.penalty()
            if best is None or p < best[0]:
                best = (p, k)
            sym.apply_mask(k)                        # undo (a mask is its own inverse)
        mask = best[1]
    sym.apply_mask(mask)
    sym.draw_format(mask)
    return sym.dark


def svg(text, quiet=4, title=None):
    """text -> a QR code as an SVG image: dark modules on a white square, with the quiet zone around it."""
    rows = encode(text)
    n = len(rows) + 2 * quiet
    path = []
    for y, row in enumerate(rows):
        x = 0
        while x < len(row):
            if row[x]:
                start = x
                while x < len(row) and row[x]:
                    x += 1
                path.append(f"M{start + quiet} {y + quiet}h{x - start}v1h-{x - start}z")
            else:
                x += 1
    label = ""
    if title:
        t = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        label = f"<title>{t}</title>"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {n} {n}" shape-rendering="crispEdges">{label}'
            f'<rect width="{n}" height="{n}" fill="#fff"/><path fill="#000" d="{"".join(path)}"/></svg>')
