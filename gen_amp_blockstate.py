#!/usr/bin/env python3
"""Generates assets/circuitsim/blockstates/amplifier.json.

Every cell in the 5x5 amp footprint has an exhaustive list of variants
keyed by (cell_kind, facing, local_x, local_z, offset_pin, mirrored).
The model file path:
  - mirrored=false: amplifier_cell_{col}_{row}_{5pin|7pin}
  - mirrored=true:  amplifier_cell_{col}_{row}_{5pin|7pin}_mirrored
The mirrored model files (generated separately by gen_amp_mirrored_models.py)
both relocate the atlas slice AND V-flip it in place, so the schematic
triangle visually flips instead of producing the overlapping-diagonal
"hourglass" artifact we got from just re-pointing at a sibling cell's
non-mirrored model.

Run once and commit the output; no runtime dependency.
"""
import json
from pathlib import Path

FACINGS = [("north", 0), ("east", 90), ("south", 180), ("west", 270)]
OFFSETS = [("false", "5pin"), ("true", "7pin")]


def kind_for(col: int, row: int, offset_enabled: bool, mirrored: bool) -> str:
    """Mirror of AmplifierBlock.kindFor on the Java side."""
    if col == 0 and row == 0:
        return "anchor"
    eff_row = (4 - row) if mirrored else row
    if col == 0 and eff_row == 1: return "vinp"
    if col == 0 and eff_row == 3: return "vinn"
    if col == 1 and eff_row == 0: return "vcc"
    if col == 1 and eff_row == 4: return "vee"
    if col == 4 and eff_row == 2: return "vout"
    if col == 3 and eff_row == 0: return "off1" if offset_enabled else "body"
    if col == 3 and eff_row == 4: return "off2" if offset_enabled else "body"
    return "body"


def main() -> None:
    variants: dict[str, dict] = {}
    for col in range(5):
        for row in range(5):
            for offset_str, offset_suffix in OFFSETS:
                offset_enabled = offset_str == "true"
                for mirrored_str in ("false", "true"):
                    mirrored = mirrored_str == "true"
                    kind = kind_for(col, row, offset_enabled, mirrored)
                    suffix = (
                        f"{offset_suffix}_mirrored" if mirrored else offset_suffix
                    )
                    model = (
                        f"circuitsim:block/amplifier_cell_{col}_{row}_{suffix}"
                    )
                    for facing, rot in FACINGS:
                        key = (
                            f"cell_kind={kind},"
                            f"facing={facing},"
                            f"local_x={col},"
                            f"local_z={row},"
                            f"mirrored={mirrored_str},"
                            f"offset_pin={offset_str}"
                        )
                        entry: dict = {"model": model}
                        if rot:
                            entry["y"] = rot
                        variants[key] = entry

    output = {"variants": variants}
    target = Path(__file__).parent / "src/main/resources/assets/circuitsim/blockstates/amplifier.json"
    body = json.dumps(output, indent=4, ensure_ascii=False)
    target.write_text(body + "\n", encoding="utf-8")
    print(f"wrote {target}  ({len(variants)} variants)")


if __name__ == "__main__":
    main()
