#!/usr/bin/env python3
"""Fusionne les fiches de recherche (JSON) en app/src/main/assets/guide.json (compact, validé)."""
import json, glob, sys, os
src = sys.argv[1] if len(sys.argv) > 1 else "/home/claude/research"
out = "app/src/main/assets/guide.json"
fiches, seen = [], set()
def add(f):
    if not isinstance(f, dict) or not f.get("titre") or not f.get("etapes"): return
    i = f.get("id") or f["titre"]
    while i in seen: i += "-b"
    seen.add(i)
    srcs = [s for s in f.get("sources", []) if isinstance(s, str) and s.startswith("http")]
    fiches.append({
        "id": i, "c": f.get("categorie", "Divers"), "t": f["titre"], "r": f.get("resume", ""),
        "n": 1 if f.get("niveau") == "non_conventionnelle" else 0,
        "v": 1 if f.get("confiance") == "confirmee" and len(srcs) >= 2 else 0,
        "k": f.get("risque", "faible"), "p": 1 if f.get("perte_donnees") else 0,
        "m": f.get("marques", ["toutes"]), "a": f.get("android", "tous"),
        "nec": f.get("necessite", []), "e": f["etapes"], "w": f.get("attention", ""), "s": srcs[:3]})
for p in sorted(glob.glob(os.path.join(src, "*.json"))):
    b = os.path.basename(p)
    if b[:2].isdigit() and "b-" in b[:4] or b.endswith("b.json") or "-paquets" in b or "prefixes" in b or "marques" in b and b.startswith("07b"): continue
    d = json.load(open(p))
    if isinstance(d, dict): d = d.get("explications", [])
    for f in d: add(f)
json.dump(fiches, open(out, "w"), ensure_ascii=False, separators=(",", ":"))
print(len(fiches), "fiches", os.path.getsize(out) // 1024, "Ko")
