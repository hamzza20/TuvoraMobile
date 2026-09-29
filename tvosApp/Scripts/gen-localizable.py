#!/usr/bin/env python3
"""Builds the Apple TV app's Localizable.strings from NuvioTV's own translations.

The Apple TV screens use NuvioTV's English copy verbatim (DESIGN-PARITY.md). SwiftUI looks literal
Text("…") strings up in Localizable.strings, so this script collects every UI string literal in
TuvoraTV/Sources, finds the NuvioTV string resource with the same English value, and writes that
key's translation for every NuvioTV locale into Resources/<lang>.lproj/Localizable.strings.

Usage: tvosApp/Scripts/gen-localizable.py  (re-run after adding screens; unmatched strings are reported)
"""
import html, os, re, sys, xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent            # tvosApp/
SOURCES = HERE / "TuvoraTV" / "Sources"
OUT = HERE / "TuvoraTV" / "Resources"
NUVIOTV_RES = Path(os.environ.get("NUVIOTV_RES", "/Users/kush-mac/Documents/projects/nuvio/NuvioTV/app/src/main/res"))
# Second source: the phone app's Compose resources (same key scheme, values-<lang>/strings.xml).
MOBILE_RES = HERE.parent / "composeApp" / "src" / "commonMain" / "composeResources"
MOBILE_LOCALE = {"b+es+419": None, "b+sr+Latn": None, "in": "id", "iw": "he", "no": "nb", "pt-rBR": "pt-BR", "pt-rPT": "pt"}

LOCALES = {  # android values-* dir -> Apple lproj name
    "ar": "ar", "b+es+419": "es-419", "b+sr+Latn": "sr-Latn", "bg": "bg", "bs": "bs", "cs": "cs", "da": "da",
    "de": "de", "el": "el", "es": "es", "fr": "fr", "hi": "hi", "hu": "hu", "in": "id", "it": "it", "iw": "he",
    "ja": "ja", "lt": "lt", "nl": "nl", "no": "nb", "pl": "pl", "pt-rBR": "pt-BR", "pt-rPT": "pt-PT", "ro": "ro",
    "ru": "ru", "sk": "sk", "sl": "sl", "sq": "sq", "sv": "sv", "ta": "ta", "tr": "tr", "uk": "uk", "vi": "vi",
    "zh-rCN": "zh-Hans", "zh-rTW": "zh-Hant",
}

def android_text(el):
    raw = "".join(el.itertext())
    raw = raw.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\u2026", "…")
    raw = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), raw)
    return html.unescape(raw).strip().strip('"')

def load(path):
    if not path.exists(): return {}
    out = {}
    for el in ET.parse(path).getroot().iter("string"):
        if el.get("translatable") == "false": continue
        out[el.get("name")] = android_text(el)
    return out

LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
SKIP = re.compile(r"^(-smoke|md_|ic_|sidebar_|tvos\.|SMOKE|http|tuvora://|com\.|group\.|#|%|[A-Za-z0-9_.]+$)")

def swift_literals():
    found = set()
    for f in SOURCES.rglob("*.swift"):
        for line in f.read_text().splitlines():
            s = line.strip()
            if s.startswith("//") or "NSLog(" in s or "print(" in s or "firstIndex(of:" in s or "arguments.contains" in s: continue
            for m in LITERAL.finditer(line):
                t = m.group(1)
                if "\\(" in t:  # SwiftUI keys an interpolated literal with %@ per String argument
                    t = re.sub(r"\\\((?:[^()]|\([^()]*\))*\)", "%@", t)
                    if "\\(" in t or not re.search(r"[A-Za-z]{2}", t.replace("%@", "")): continue
                if len(t) < 2 or not re.search(r"[A-Za-z]", t): continue
                if SKIP.match(t) and " " not in t and not t[:1].isupper(): continue
                found.add(t.replace('\\"', '"'))
    return found

# NuvioTV keys used with arguments or outside a Text literal; emitted as "tv:<key>".
KEYED = ["cw_next_up", "cw_hours_min_left", "cw_min_left", "type_movie", "type_series",
         "debrid_stream_max_results_count", "debrid_size_range_up_to", "debrid_size_range_min_plus",
         "debrid_size_range_min_max"]

def apple_format(s):  # Android %1$d / %s -> Foundation %1$ld / %@
    return re.sub(r"%(\d+\$)?d", lambda m: f"%{m.group(1) or ''}ld", s).replace("%s", "%@").replace("$s", "$@")

def swift_key(v):  # an English Android value as SwiftUI would key it: "from %1$s" -> "from %@"
    return re.sub(r"%(\d+\$)?s", "%@", v)

def esc(s): return s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")

def main():
    tv_en = {k: swift_key(v) for k, v in load(NUVIOTV_RES / "values" / "strings.xml").items()}
    mob_en = {k: swift_key(v) for k, v in load(MOBILE_RES / "values" / "strings.xml").items()}
    by_tv, by_mob = {}, {}   # English value -> every key carrying it (the first translated one wins)
    for k, v in tv_en.items(): by_tv.setdefault(v, []).append(k)
    for k, v in mob_en.items(): by_mob.setdefault(v, []).append(k)
    literals = swift_literals()
    matched = {t: ("tv", by_tv[t]) if t in by_tv else ("mob", by_mob[t]) for t in literals if t in by_tv or t in by_mob}
    unmatched = sorted(t for t in literals if t not in matched and " " in t)
    for android, apple in LOCALES.items():
        tv = load(NUVIOTV_RES / f"values-{android}" / "strings.xml")
        mob_dir = MOBILE_LOCALE.get(android, android.replace("-r", "-"))
        mob = load(MOBILE_RES / f"values-{mob_dir}" / "strings.xml") if mob_dir else {}
        lines = []
        for t, (src, keys) in sorted(matched.items()):
            cands = [tv.get(k) for k in keys] if src == "tv" else []
            cands += [mob.get(k) for k in by_mob.get(t, [])]
            tr = next((c for c in cands if c and c != t), None)
            if tr and "%@" in t:
                tr = apple_format(tr)
                if re.sub(r"%\d+\$@", "%@", tr) == t: tr = None
            if tr and tr.count("%") == t.count("%"): lines.append(f'"{esc(t)}" = "{esc(tr)}";')
        for k in KEYED:  # format strings the Swift side looks up by NuvioTV key: LK("tv:cw_min_left", …)
            tr = tv.get(k)
            if tr and tr != tv_en.get(k): lines.append(f'"tv:{k}" = "{esc(apple_format(tr))}";')
        d = OUT / f"{apple}.lproj"; d.mkdir(parents=True, exist_ok=True)
        (d / "Localizable.strings").write_text("/* Generated by Scripts/gen-localizable.py from NuvioTV translations. */\n" + "\n".join(lines) + "\n")
    print(f"{len(literals)} literals, {len(matched)} matched ({sum(1 for v in matched.values() if v[0]=='tv')} NuvioTV, {sum(1 for v in matched.values() if v[0]=='mob')} phone), {len(LOCALES)} locales")
    print(f"{len(unmatched)} unmatched multi-word strings (English only), e.g.:")
    for t in unmatched[:25]: print("   ", t)
    print(" ".join(sorted(LOCALES.values())))

if __name__ == "__main__":
    main()
