#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Overi odvodenu topologiu miest oproti vsetkym odchytenym layoutom.

Sluzi ako spec harness: co tu prejde, to sa prenesie do JUnit testov Kotlin
modulu `core`. Tvrdenia su len tie, ktore sa daju obhajit z fyzickej podoby
vozna - napr. kupejovy vozen Bk/AK ma 6-miestne kupe, velkopriestorovy 2+2
vozen ma presne jedneho suseda vedla.

    python validate_geom.py
"""

from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from seatgeom import build_layout                                    # noqa: E402

FX = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   "..", "fixtures"))

# Ocakavania podla realnej podoby vozna.
#   bay6      - kupejovy vozen, oddiel pre 6
#   pairs2x2  - velkopriestor 2+2, presne jeden sused vedla
#   uncertain - ZNAMA LIMITACIA: layout sa neda spolahlivo precitat, poziadavkou
#               je iba to, aby to kod PRIZNAL (viac kandidatov + priznak
#               "neiste"), nie aby tvrdil jedno nespravne miesto
EXPECT = {
    "Vuz AK (42)":        {"kind": "bay6", "seat": 32},
    "Vuz Bk (42)":        {"kind": "bay6", "seat": 32, "bay": [31, 32, 33, 34, 35, 36],
                           "nextTo": [31, 33], "facing": 35},
    "Vuz Bm Astra (80)":  {"kind": "pairs2x2", "seat": 32},
    "Vuz Bp2xx (80) LOW cost": {"kind": "pairs2x2", "seat": 32},
    "Vuz Bp1xx (75) LOW cost": {"kind": "pairs2x2", "seat": 32},
    # SVG tohto vozna je o verziu starsie ako cislovanie z API (ma miesto 26,
    # API ma 35) a stlpce sa z neho necitaju cisto, takze ulicka sa nenajde.
    "Vuz Bm3xx (54) Relax":    {"kind": "uncertain", "seat": 32},
}


def check(name, info, layout):
    """Vrati zoznam chyb; prazdny zoznam = vsetko v poriadku."""
    problems = []
    exp = EXPECT.get(name)
    if not exp:
        return problems
    seat = exp["seat"]
    if seat not in layout.positions:
        return ["miesto %s nie je v layoute" % seat]
    n = layout.neighbours(seat)

    if exp["kind"] == "bay6":
        if len(n["bay"]) != 6:
            problems.append("kupe ma mat 6 miest, ma %d (%s)"
                            % (len(n["bay"]), n["bay"]))
        if len(n["nextTo"]) != 2:
            problems.append("v kupe maju byt 2 miesta vedla, je %d (%s)"
                            % (len(n["nextTo"]), n["nextTo"]))
        if n["facing"] is None:
            problems.append("v kupe ma existovat miesto oproti")
    elif exp["kind"] == "pairs2x2":
        if layout.aisle_after is None:
            problems.append("velkopriestorovy vozen ma mat ulicku, nenasla sa")
        if len(n["nextTo"]) != 1:
            problems.append("pri 2+2 ma byt presne 1 miesto vedla, je %d (%s)"
                            % (len(n["nextTo"]), n["nextTo"]))
        if layout.confidence(seat)[0] != "iste":
            problems.append("tento vozen sa ma citat isto, hlasi neistotu")
    elif exp["kind"] == "uncertain":
        # poziadavka je priznat neistotu, nie uhadnut spravne
        if layout.confidence(seat)[0] != "neiste":
            problems.append("layout sa neda citat spolahlivo, ale kod to nepriznava"
                            " (vedla=%s)" % n["nextTo"])

    for key in ("bay", "nextTo", "facing"):
        if key in exp and n[key] != exp[key]:
            problems.append("%s ma byt %s, je %s" % (key, exp[key], n[key]))
    return problems


def main():
    idx = json.load(open(os.path.join(FX, "layouts", "index.json"), encoding="utf-8"))
    failures = 0
    for fname, info in sorted(idx.items(), key=lambda kv: kv[1]["deckName"]):
        svg = open(os.path.join(FX, "layouts", fname), encoding="utf-8",
                   errors="replace").read()
        layout, score = build_layout(svg, info["seats"])
        name = info["deckName"]
        if layout is None:
            print("FAIL %-26s layout sa nepodarilo precitat (pokrytie %.0f%%)"
                  % (name, score * 100))
            failures += 1
            continue

        problems = check(name, info, layout)
        status = "OK  " if not problems else "FAIL"
        failures += bool(problems)
        print("%s %-26s n=%-3d %s" % (status, name, len(info["seats"]), layout.describe()))
        for seat in sorted({EXPECT.get(name, {}).get("seat", 0), 12} & set(layout.positions)):
            n = layout.neighbours(seat)
            print("       miesto %-3s vedla=%-11s oproti=%-5s oddiel=%s" % (
                seat, ",".join(map(str, n["nextTo"])) or "-", n["facing"] or "-",
                ",".join(map(str, n["bay"]))))
        for p in problems:
            print("       ! %s" % p)

    print()
    print("vysledok: %d layoutov, %d s chybou" % (len(idx), failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
