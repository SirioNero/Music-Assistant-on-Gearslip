"""Scrive R.kt (gli id delle scritte, come su Android) e i nomi che stanno dietro ogni id."""
import os, re, sys
res, out = sys.argv[1], sys.argv[2]
text = open(os.path.join(res, "values", "strings.xml"), encoding="utf-8").read()
found = re.findall(r'<(string|plurals) name="([A-Za-z0-9_]+)"', text)
os.makedirs(out, exist_ok=True)
ids = {}
lines = ["package it.xonos.ma", "", "@Suppress(\"ClassName\")", "object R {"]
for kind, base in (("string", 0x7F100000), ("plurals", 0x7F110000)):
    lines.append(f"    object {kind} {{")
    for i, name in enumerate(n for k, n in found if k == kind):
        ids[base + i] = name
        lines.append(f"        const val {name} = {base + i}")
    lines.append("    }")
lines.append("}")
open(os.path.join(out, "R.kt"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
names = ["package screens", "", "/** Il nome della risorsa dietro ogni id di R. */", "internal val RESOURCE_NAMES = mapOf("]
names += [f'    {i} to "{n}",' for i, n in ids.items()]
names.append(")")
open(os.path.join(out, "Names.kt"), "w", encoding="utf-8").write("\n".join(names) + "\n")
