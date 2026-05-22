#!/usr/bin/env python3
"""Generates mirrored companion models for every amp cell.

For each existing amplifier_cell_{col}_{row}_{5pin|7pin}.json we write a
sibling amplifier_cell_{col}_{row}_{5pin|7pin}_mirrored.json. The mirrored
model:
  - draws the atlas slice from (col, 4-row), so the cell at world (col, row)
    displays the content that lived at the geometrically opposite row;
  - swaps v1 ↔ v2 in the up-face UV, vertically flipping that slice in
    place — without this the contents of (col, 4-row) drop straight onto
    (col, row) without re-orientation, producing the overlapping-diagonal
    "hourglass" effect we saw in the broken render.
The other faces (down, north/south/east/west) all use the plain side
texture, so they don't need mirroring.

Idempotent — run any time the atlas geometry changes.
"""
import json
from pathlib import Path

ROOT = Path(__file__).parent
MODELS_DIR = ROOT / "src/main/resources/assets/circuitsim/models/block"

# Cell size in model-UV units: the atlas is 5x5 cells covering the full
# 16-unit face, so each cell spans 16/5 = 3.2 units.
CELL = 16.0 / 5.0

count = 0
for offset_variant in ("5pin", "7pin"):
    top_texture = f"circuitsim:block/amplifier_top_{offset_variant}"
    for col in range(5):
        for row in range(5):
            src_row = 4 - row  # slice from the geometrically opposite row

            u1 = col * CELL
            u2 = (col + 1) * CELL
            # Vertically-flipped UV: top of the rendered face pulls pixels
            # from v=(src_row+1)*CELL, bottom pulls from v=src_row*CELL.
            v1 = (src_row + 1) * CELL
            v2 = src_row * CELL

            model = {
                "textures": {
                    "particle": "circuitsim:block/amplifier_side",
                    "top":      top_texture,
                    "side":     "circuitsim:block/amplifier_side",
                },
                "elements": [
                    {
                        "from": [0, 0, 0],
                        "to":   [16, 16, 16],
                        "faces": {
                            "up":    {"uv": [u1, v1, u2, v2], "texture": "#top"},
                            "down":  {"uv": [0, 0, 16, 16],   "texture": "#side"},
                            "north": {"uv": [0, 0, 16, 16],   "texture": "#side"},
                            "south": {"uv": [0, 0, 16, 16],   "texture": "#side"},
                            "east":  {"uv": [0, 0, 16, 16],   "texture": "#side"},
                            "west":  {"uv": [0, 0, 16, 16],   "texture": "#side"},
                        },
                    }
                ],
            }

            out_path = MODELS_DIR / f"amplifier_cell_{col}_{row}_{offset_variant}_mirrored.json"
            out_path.write_text(json.dumps(model, indent=4) + "\n", encoding="utf-8")
            count += 1

print(f"wrote {count} mirrored model file(s)")
