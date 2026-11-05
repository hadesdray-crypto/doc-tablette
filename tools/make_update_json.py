#!/usr/bin/env python3
"""Fabrique update.json lu par l'appli pour proposer la mise à jour sur place."""
import hashlib, json, sys

code, name, url, apk, notes = sys.argv[1:6]
sha = hashlib.sha256(open(apk, "rb").read()).hexdigest()
lines = [l.strip() for l in open(notes, encoding="utf-8") if l.strip()]
print(json.dumps({"versionCode": int(code), "versionName": name, "apkUrl": url, "sha256": sha, "notes": lines}, ensure_ascii=False, indent=2))
