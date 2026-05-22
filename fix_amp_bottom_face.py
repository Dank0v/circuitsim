#!/usr/bin/env python3
"""Rewrites every amplifier_cell_*_*pin.json so the `down` face uses the
plain `#side` texture instead of the schematic atlas region from `#top`.

Why: the originals had `down` and `up` sharing the same UV/atlas, so the
bottom face rendered the schematic image mirror-flipped due to Minecraft's
standard down-face orientation. It looked subtly off and the new mirror-
toggle feature made it obvious. The bottom of an amp is rarely seen, so
showing the generic side texture there is the cleanest fix.

Idempotent — run as often as you like.
"""
import json
from pathlib import Path

ROOT = Path(__file__).parent
MODELS_DIR = ROOT / "src/main/resources/assets/circuitsim/models/block"

SIDE_FACE = {"uv": [0, 0, 16, 16], "texture": "#side"}

count = 0
for path in sorted(MODELS_DIR.glob("amplifier_cell_*.json")):
    data = json.loads(path.read_text(encoding="utf-8"))
    changed = False
    for element in data.get("elements", []):
        faces = element.get("faces", {})
        if "down" in faces and faces["down"] != SIDE_FACE:
            faces["down"] = dict(SIDE_FACE)
            changed = True
    if changed:
        path.write_text(json.dumps(data, indent=4) + "\n", encoding="utf-8")
        count += 1
        print(f"patched {path.name}")
print(f"\n{count} model file(s) updated")
