"""Controlla le traduzioni di Xonos contro l'inglese: chiavi, segnaposto, plurali, escaping."""
import re, glob, os
import xml.etree.ElementTree as ET
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
NEEDED = {"ru": {"one", "few", "many", "other"}, "zh": {"other"}}
FMT = re.compile(r"%(\d+\$)?[sd]")

def load(path):
    root = ET.parse(path).getroot()
    strings, plurals, fixed = {}, {}, set()
    for e in root:
        if e.tag == "string":
            if e.get("translatable") == "false":
                fixed.add(e.get("name"))
            strings[e.get("name")] = e.text or ""
        elif e.tag == "plurals":
            plurals[e.get("name")] = {i.get("quantity"): i.text or "" for i in e}
    return strings, plurals, fixed

base, base_plurals, fixed = load(f"{RES}/values/strings.xml")
problems = 0
for path in sorted(glob.glob(f"{RES}/values*/strings.xml")):
    lang = os.path.basename(os.path.dirname(path)).removeprefix("values").removeprefix("-") or "en"
    strings, plurals, _ = load(path)
    expected = set(base) - (fixed if lang != "en" else set())
    if set(strings) != expected:
        print(lang, "chiavi diverse:", set(strings) ^ expected); problems += 1
    for key, text in strings.items():
        if sorted(FMT.findall(text)) != sorted(FMT.findall(base[key])):
            print(lang, key, "segnaposto diversi:", text); problems += 1
        if re.search(r"(?<!\\)'", text) or re.search(r'(?<!\\)"', text):
            print(lang, key, "apostrofo o virgolette senza escape"); problems += 1
    if set(plurals) != set(base_plurals):
        print(lang, "plurali diversi"); problems += 1
    for name, forms in plurals.items():
        need = NEEDED.get(lang, {"one", "other"})
        if set(forms) != need:
            print(lang, name, "forme", sorted(forms), "invece di", sorted(need)); problems += 1
        for q, text in forms.items():
            if FMT.findall(text) != ["", ] and text.count("%d") != 1:
                print(lang, name, q, "manca %d:", text); problems += 1
    print(f"{lang}: {len(strings)} testi, {len(plurals)} plurali")
print("problemi:", problems)
