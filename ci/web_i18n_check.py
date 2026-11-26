#!/usr/bin/env python3
"""Every web UI string has its English: each literal t('...') in web/src must be a key of the
English tables (web/src/i18n.ts, and the ones added with addEnglish(), such as the templates'
and the beta's own). The web app is written in Hebrew; English is looked up by the Hebrew text,
as in the Android app (ci/i18n_check.py). A string with no Hebrew letter needs no English.
Also catches a key written twice in one table. Exits 1 and lists what's missing."""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent
src = root / "web/src"

# A JS string literal in single or double quotes.
LIT = r"""'((?:[^'\\\n]|\\.)*)'|"((?:[^"\\\n]|\\.)*)\""""
key_re = re.compile(r"^\s*(?:" + LIT + r")\s*:", re.M)
use_re = re.compile(r"\bt\(\s*(?:" + LIT + r")\s*[,)]")
hebrew = re.compile(r"[֐-׿]")
# The tables: the EN object in i18n.ts and every object literal passed to addEnglish().
table_re = re.compile(r"(?:const\s+\w+\s*:\s*Record<string,\s*string>\s*=|addEnglish\()\s*\{(.*?)\n\}", re.S)


def unescape(s: str) -> str:
    def rep(m: re.Match) -> str:
        c = m.group(1)
        if c.startswith("u"):
            return chr(int(c[1:], 16))
        return {"n": "\n", "t": "\t"}.get(c, c)
    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", rep, s)


def literal(m: re.Match) -> str:
    return unescape(m.group(1) if m.group(1) is not None else m.group(2))


known: set[str] = set()
dupes: list[tuple[pathlib.Path, str]] = []
files = sorted(p for p in src.rglob("*") if p.suffix in (".ts", ".tsx"))
for f in files:
    text = f.read_text(encoding="utf-8")
    for table in table_re.finditer(text):
        keys = [literal(m) for m in key_re.finditer(table.group(1))]
        seen: set[str] = set()
        for k in keys:
            if k in seen:
                dupes.append((f.relative_to(root), k))
            seen.add(k)
        known.update(keys)

missing: dict[str, pathlib.Path] = {}
uses = 0
for f in files:
    for m in use_re.finditer(f.read_text(encoding="utf-8")):
        k = literal(m)
        if not hebrew.search(k):
            continue
        uses += 1
        if k not in known:
            missing.setdefault(k, f.relative_to(root))

for f, k in dupes:
    print(f"{f}: \"{k}\" is there more than once")
for k, f in missing.items():
    print(f"{f}: no English for \"{k}\"")
print(f"web i18n: {len(known)} strings in English, {uses} uses, {len(missing)} missing, {len(dupes)} repeated")
sys.exit(1 if missing or dupes else 0)
