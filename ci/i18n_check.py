#!/usr/bin/env python3
"""Every UI string has its English: each literal t("...") in the app's code must be a key of
English.kt (the app is written in Hebrew; English is looked up by the Hebrew text). Also catches
a key written twice. Exits 1 and lists what's missing."""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent
src = root / "app/src/main/java"
english = src / "com/elad/faceblur/i18n/English.kt"

key_re = re.compile(r'^\s*"((?:[^"\\]|\\.)*)"\s+to\s+"', re.M)
use_re = re.compile(r'\bt\(\s*"((?:[^"\\]|\\.)*)"\s*\)')

keys = key_re.findall(english.read_text(encoding="utf-8"))
dupes = sorted({k for k in keys if keys.count(k) > 1})
known = set(keys)

missing = {}
for f in sorted(src.rglob("*.kt")):
    if f == english:
        continue
    for m in use_re.finditer(f.read_text(encoding="utf-8")):
        if m.group(1) not in known:
            missing.setdefault(m.group(1), f.relative_to(root))

for k in dupes:
    print(f"English.kt: \"{k}\" is there more than once")
for k, f in missing.items():
    print(f"{f}: no English for \"{k}\"")
print(f"i18n: {len(known)} strings in English, {len(missing)} missing, {len(dupes)} repeated")
sys.exit(1 if missing or dupes else 0)
