"""Riassume i risultati di Macrobenchmark (i file *benchmarkData.json in una cartella) in una tabella Markdown.

Uso: python3 summary.py <cartella> [titolo]
"""
import glob, json, os, sys

root = sys.argv[1]
title = sys.argv[2] if len(sys.argv) > 2 else "Misure"
rows = []
for path in sorted(glob.glob(os.path.join(root, "**", "*benchmarkData.json"), recursive=True)):
    data = json.load(open(path))
    device = data.get("context", {}).get("build", {}).get("model", "?")
    for b in data.get("benchmarks", []):
        name = b["className"].rsplit(".", 1)[-1] + "." + b["name"].split("[")[0]
        mode = b.get("params", {}).get("mode") or (b["name"].split("[")[1].rstrip("]") if "[" in b["name"] else "")
        for metric, m in sorted(b.get("metrics", {}).items()):
            rows.append((name, mode, metric, f"{m['median']:.1f}", f"{m['minimum']:.1f}", f"{m['maximum']:.1f}", len(m.get("runs", []))))
        for metric, m in sorted(b.get("sampledMetrics", {}).items()):
            rows.append((name, mode, metric, f"P50 {m['P50']:.1f}", f"P90 {m['P90']:.1f}", f"P99 {m['P99']:.1f}", len(m.get("runs", []))))

out = [f"## {title}", ""]
if not rows:
    out.append("Nessun risultato.")
else:
    out += ["| Prova | Compilazione | Metrica | Mediana / P50 | Min / P90 | Max / P99 | Campioni |", "|---|---|---|---|---|---|---|"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
print("\n".join(out))
