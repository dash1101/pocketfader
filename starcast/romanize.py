"""Best-effort romanization for lyrics.

Covers Korean (Hangul), Japanese kana, Cyrillic and Greek with plain lookup tables, so no extra
packages are needed. Han characters (Chinese, Japanese kanji) can't be converted without a
dictionary, so they are left as they are; providers that supply their own romanization (for example
NetEase) are preferred by the lyrics service.
"""
import unicodedata

# ------------------------------------------------------------------ Korean
_CHO = ["g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"]
_JUNG = ["a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we",
         "wi", "yu", "eu", "ui", "i"]
# sound of a final consonant when nothing vowel-initial follows it (index = Unicode jongseong order)
_JONG = ["", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t",
         "t", "ng", "t", "t", "k", "t", "p", "t"]
# when a vowel-initial syllable follows, the final consonant moves onto it: (stays, moves)
_LIAISON = {1: ("", "g"), 2: ("", "kk"), 3: ("k", "s"), 4: ("", "n"), 5: ("n", "j"), 6: ("n", ""),
            7: ("", "d"), 8: ("", "r"), 9: ("l", "g"), 10: ("l", "m"), 11: ("l", "b"), 12: ("l", "s"),
            13: ("l", "t"), 14: ("l", "p"), 15: ("l", ""), 16: ("", "m"), 17: ("", "b"), 18: ("p", "s"),
            19: ("", "s"), 20: ("", "ss"), 22: ("", "j"), 23: ("", "ch"), 24: ("", "k"), 25: ("", "t"),
            26: ("", "p"), 27: ("", "")}


def _hangul(run):
    parts = []
    for ch in run:
        s = ord(ch) - 0xAC00
        parts.append((s // 588, (s % 588) // 28, s % 28))     # initial, vowel, final
    res, carry = [], ""
    for i, (cho, jung, jong) in enumerate(parts):
        coda, moved = "", ""
        if jong:
            nxt_silent = i + 1 < len(parts) and parts[i + 1][0] == 11    # next syllable starts with silent ㅇ
            if nxt_silent and jong in _LIAISON:
                coda, moved = _LIAISON[jong]
            else:
                coda = _JONG[jong]
        res.append((carry or _CHO[cho]) + _JUNG[jung] + coda)
        carry = moved
    return "".join(res)


# ------------------------------------------------------------------ Japanese
_KANA = {
    "あ": "a", "い": "i", "う": "u", "え": "e", "お": "o",
    "か": "ka", "き": "ki", "く": "ku", "け": "ke", "こ": "ko",
    "さ": "sa", "し": "shi", "す": "su", "せ": "se", "そ": "so",
    "た": "ta", "ち": "chi", "つ": "tsu", "て": "te", "と": "to",
    "な": "na", "に": "ni", "ぬ": "nu", "ね": "ne", "の": "no",
    "は": "ha", "ひ": "hi", "ふ": "fu", "へ": "he", "ほ": "ho",
    "ま": "ma", "み": "mi", "む": "mu", "め": "me", "も": "mo",
    "や": "ya", "ゆ": "yu", "よ": "yo",
    "ら": "ra", "り": "ri", "る": "ru", "れ": "re", "ろ": "ro",
    "わ": "wa", "ゐ": "i", "ゑ": "e", "を": "o", "ん": "n",
    "が": "ga", "ぎ": "gi", "ぐ": "gu", "げ": "ge", "ご": "go",
    "ざ": "za", "じ": "ji", "ず": "zu", "ぜ": "ze", "ぞ": "zo",
    "だ": "da", "ぢ": "ji", "づ": "zu", "で": "de", "ど": "do",
    "ば": "ba", "び": "bi", "ぶ": "bu", "べ": "be", "ぼ": "bo",
    "ぱ": "pa", "ぴ": "pi", "ぷ": "pu", "ぺ": "pe", "ぽ": "po",
    "ゔ": "vu", "ぁ": "a", "ぃ": "i", "ぅ": "u", "ぇ": "e", "ぉ": "o", "ゃ": "ya", "ゅ": "yu", "ょ": "yo",
}
_DIGRAPH = {
    "きゃ": "kya", "きゅ": "kyu", "きょ": "kyo", "しゃ": "sha", "しゅ": "shu", "しょ": "sho",
    "ちゃ": "cha", "ちゅ": "chu", "ちょ": "cho", "にゃ": "nya", "にゅ": "nyu", "にょ": "nyo",
    "ひゃ": "hya", "ひゅ": "hyu", "ひょ": "hyo", "みゃ": "mya", "みゅ": "myu", "みょ": "myo",
    "りゃ": "rya", "りゅ": "ryu", "りょ": "ryo", "ぎゃ": "gya", "ぎゅ": "gyu", "ぎょ": "gyo",
    "じゃ": "ja", "じゅ": "ju", "じょ": "jo", "ぢゃ": "ja", "ぢゅ": "ju", "ぢょ": "jo",
    "びゃ": "bya", "びゅ": "byu", "びょ": "byo", "ぴゃ": "pya", "ぴゅ": "pyu", "ぴょ": "pyo",
    "ふぁ": "fa", "ふぃ": "fi", "ふぇ": "fe", "ふぉ": "fo", "てぃ": "ti", "でぃ": "di", "とぅ": "tu",
    "うぃ": "wi", "うぇ": "we", "うぉ": "wo", "しぇ": "she", "ちぇ": "che", "じぇ": "je", "つぁ": "tsa",
    "ゔぁ": "va", "ゔぃ": "vi", "ゔぇ": "ve", "ゔぉ": "vo",
}


def _to_hiragana(text):
    out = []
    for ch in text:
        o = ord(ch)
        out.append(chr(o - 0x60) if 0x30A1 <= o <= 0x30F6 else ch)
    return "".join(out)


def _kana(run):
    run = _to_hiragana(run)
    out, i, geminate = [], 0, False
    while i < len(run):
        ch = run[i]
        if ch == "っ":
            geminate = True
            i += 1
            continue
        if ch == "ー":
            for c in reversed("".join(out)):
                if c in "aeiou":
                    out.append(c)
                    break
            i += 1
            continue
        pair = run[i:i + 2]
        if pair in _DIGRAPH:
            syl, i = _DIGRAPH[pair], i + 2
        elif ch in _KANA:
            syl, i = _KANA[ch], i + 1
        else:
            out.append(ch)
            i += 1
            continue
        if geminate:
            syl = ("t" if syl.startswith("ch") else syl[0]) + syl
            geminate = False
        out.append(syl)
    return "".join(out)


# ------------------------------------------------------------------ Cyrillic / Greek
_CYR = dict(zip("абвгдежзийклмнопрстуфхцчшщъыьэюяёіїєґ",
                ["a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t",
                 "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya", "yo", "i", "yi", "ye", "g"]))
_GRK = dict(zip("αβγδεζηθικλμνξοπρσςτυφχψω",
                ["a", "v", "g", "d", "e", "z", "i", "th", "i", "k", "l", "m", "n", "x", "o", "p", "r", "s", "s",
                 "t", "y", "f", "ch", "ps", "o"]))


def _map(text, table):
    out = []
    for ch in text:
        low = ch.lower()
        if low in table:
            r = table[low]
            out.append(r.capitalize() if ch != low and r else r)
        else:
            out.append(ch)
    return "".join(out)


def _strip_marks(text):
    return "".join(c for c in unicodedata.normalize("NFD", text) if not unicodedata.combining(c))


def _script(ch):
    o = ord(ch)
    if 0xAC00 <= o <= 0xD7A3:
        return "hangul"
    if 0x3040 <= o <= 0x30FF:
        return "kana"
    if 0x0400 <= o <= 0x04FF:
        return "cyr"
    if 0x0370 <= o <= 0x03FF or 0x1F00 <= o <= 0x1FFF:
        return "greek"
    return None


def romanize(text):
    """Latin-letter version of `text`, or None when there is nothing to convert."""
    if not text or all(ord(c) < 0x250 for c in text):
        return None
    out, i = [], 0
    while i < len(text):
        kind = _script(text[i])
        if kind is None:
            out.append(text[i])
            i += 1
            continue
        j = i
        while j < len(text) and (_script(text[j]) == kind or (kind == "kana" and text[j] == "ー")):
            j += 1
        run = text[i:j]
        if kind == "hangul":
            out.append(_hangul(run))
        elif kind == "kana":
            out.append(_kana(run))
        elif kind == "cyr":
            out.append(_map(run, _CYR))
        else:
            out.append(_map(_strip_marks(run), _GRK))
        i = j
    res = "".join(out)
    return None if res == text else res
