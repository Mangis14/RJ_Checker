#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Referencna implementacia geometrie vozna - SPECIFIKACIA pre Kotlin modul `core`.

Ulohou je z SVG layoutu vozna odvodit, ktore miesta su navzajom "vedla",
"oproti" a ktore tvoria jeden oddiel (kupe / stolik / dvojica). RegioJet
v API posiela iba cisla obsadenych a volnych miest, ziadnu topologiu - ta sa
da ziskat jedine z obrazku layoutu.

Postup:
  1. z SVG sa vytiahnu pozicie miest (id ako "a32", "c32", "n32", "s32";
     Illustrator pri duplicitnych id pridava priponu "_1_")
  2. dlhsia os = dlzka vozna, kratsia = sirka
  3. pozicie naprieč voznom sa zoskupia do stlpcov, najvacsia medzera = ulicka
  4. pozicie po dlzke sa zoskupia do radov
  5. ak sa medzery medzi radmi striedaju maly/velky (kupe, stolik), maly
     odstup = rady otocene k sebe -> tvoria oddiel; inak ide o radove
     sedenie (letecky styl) a "oproti" neexistuje

Vystup pre kazde miesto:
  nextTo  - miesta v tom istom rade na tej istej strane ulicky
  facing  - miesto priamo oproti (najblizsi stlpec v protilahlom rade)
  bay     - vsetky miesta v oddiele (kupe/stolik), vratane seba
  window / aisle - poloha voci okna a ulicke
"""

from __future__ import annotations

import re
from collections import defaultdict

MIN_OVERLAP = 0.80      # aka cast miest z API musi layout pokryt
JITTER_FRACTION = 0.25  # zlomok rozostupu radov, pod ktorym ide uz len o zakmit
ALTERNATION = 0.80      # ako presne sa musia medzery radov striedat pre oddiely
AISLE_RATIO = 1.4       # o kolko musi byt ulicka sirsia ako priemerna medzera


# ------------------------------------------------------------ parsovanie SVG

def _element_xy(attrs):
    cx = re.search(r'\bcx="(-?[\d.]+)"', attrs)
    cy = re.search(r'\bcy="(-?[\d.]+)"', attrs)
    if cx and cy:
        return float(cx.group(1)), float(cy.group(1))
    m = re.search(r'\btransform="matrix\(\s*[-\d.eE]+\s+[-\d.eE]+\s+[-\d.eE]+\s+'
                  r'[-\d.eE]+\s+(-?[\d.eE]+)\s+(-?[\d.eE]+)\s*\)"', attrs)
    if m:
        return float(m.group(1)), float(m.group(2))
    m = re.search(r'\btransform="translate\(\s*(-?[\d.eE]+)[,\s]+(-?[\d.eE]+)', attrs)
    if m:
        return float(m.group(1)), float(m.group(2))
    m = re.search(r'\bd="[Mm]\s*(-?[\d.]+)[,\s]+(-?[\d.]+)', attrs)
    if m:
        return float(m.group(1)), float(m.group(2))
    x = re.search(r'\bx="(-?[\d.]+)"', attrs)
    y = re.search(r'\by="(-?[\d.]+)"', attrs)
    if x and y:
        return float(x.group(1)), float(y.group(1))
    return None


def seat_positions(svg, api_seats):
    """
    Cislo miesta -> (x, y). Vyberie tu skupinu id, ktora najlepsie pokryva
    zoznam miest z API. Presna zhoda sa nevyzaduje - niektore layouty su
    o krok starsie ako realne cislovanie vo vozni.
    """
    if not svg:
        return {}, 0.0
    groups = defaultdict(dict)
    for m in re.finditer(r'<([a-zA-Z][\w:-]*)((?:"[^"]*"|\'[^\']*\'|[^>"\'])*)>', svg):
        attrs = m.group(2)
        idm = re.search(r'\bid="([A-Za-z_-]*?)(\d+)(?:_\d+_)?"', attrs)
        if not idm:
            continue
        xy = _element_xy(attrs)
        if xy is not None:
            groups[idm.group(1)].setdefault(int(idm.group(2)), xy)

    wanted = set(api_seats)
    best, best_score = {}, 0.0
    for pos in groups.values():
        hit = len(wanted & set(pos))
        score = hit / float(len(wanted)) if wanted else 0.0
        # pri rovnakom pokryti vyhrava skupina s menej zbytocnymi miestami
        if score > best_score or (score == best_score and score > 0
                                  and len(set(pos) - wanted) < len(set(best) - wanted)):
            best, best_score = pos, score
    if best_score < MIN_OVERLAP:
        return {}, best_score
    return {n: xy for n, xy in best.items() if n in wanted}, best_score


# ------------------------------------------------------------ zoskupovanie

def _gap_threshold(gaps, min_ratio):
    """
    Najde hranicu, ktora rozdeli medzery na 'male' a 'velke'.

    Hlada najvacsi relativny skok v usporiadanych medzerach - tym sa kalibruje
    sama podla layoutu a nezavisi na absolutnych jednotkach SVG. Vrati None,
    ak su vsetky medzery podobne (ziadna zmysluplna hranica neexistuje).
    """
    ordered = sorted(g for g in gaps if g > 0)
    if len(ordered) < 2:
        return None
    best_i, best_ratio = None, 0.0
    for i in range(len(ordered) - 1):
        ratio = ordered[i + 1] / max(ordered[i], 1e-6)
        if ratio > best_ratio:
            best_ratio, best_i = ratio, i
    if best_i is None or best_ratio < min_ratio:
        return None
    return (ordered[best_i] + ordered[best_i + 1]) / 2.0


def _auto_cluster(values):
    """
    Zoskupi pozicie do radov / stlpcov bez zadanej tolerancie.

    Zlucuju sa VYLUCNE zakmity: to iste sedadlo je v SVG casto zakreslene
    viacerymi prvkami (kruzok, cislo, tvar) s odchylkou desatin bodu, kym
    skutocny odstup medzi radmi je desiatky bodov. Hranica sa preto hlada na
    SPODKU rozdelenia medzier.

    Zamerne sa NEhlada najvacsia medzera - tou je predstavok v strede vozna
    a delenie podla neho by zlucilo vsetky rady do dvoch skupin.
    """
    vals = sorted(set(round(v, 2) for v in values))
    if len(vals) <= 1:
        return list(vals)
    gaps = [vals[i + 1] - vals[i] for i in range(len(vals) - 1)]

    # Mierka sa odvodi od najvacsich medzier - tie zodpovedaju skutocnemu
    # rozostupu radov/stlpcov. Zakmit toho isteho sedadla je oproti nim
    # radovo mensi, takze staci zlucit vsetko pod zlomkom tejto mierky.
    ordered = sorted(gaps)
    top = ordered[-max(1, len(ordered) // 4):]
    tol = JITTER_FRACTION * (sum(top) / len(top))

    groups, current = [], [vals[0]]
    for i, v in enumerate(vals[1:]):
        if gaps[i] > tol:
            groups.append(current)
            current = []
        current.append(v)
    groups.append(current)
    return [sum(g) / len(g) for g in groups]


def _numbering_bays(seats, rows_of):
    """
    Oddiely podla cislovania miest. RegioJet cisluje kupe po desiatkach
    (1-6, 11-16, 21-26, ...), co je nezavisly a velmi silny signal - v cislach
    su diery presne tam, kde konci kupe.

    Prijme sa len vtedy, ak skupiny pokryvaju vsetky miesta, su rovnako velke
    a kazda lezi presne v dvoch radoch (dve lavice otocene k sebe).
    """
    groups = defaultdict(list)
    for s in seats:
        groups[s // 10].append(s)
    sizes = {len(g) for g in groups.values()}
    if len(sizes) != 1:
        return None
    size = sizes.pop()
    if size < 4 or size % 2:
        return None
    for g in groups.values():
        if len({rows_of[s] for s in g}) != 2:
            return None
    return [sorted(g) for _, g in sorted(groups.items())]


def _nearest(value, centers):
    return min(range(len(centers)), key=lambda i: abs(centers[i] - value))


def _alternating_bays(rows):
    """
    Spari rady otocene k sebe, ked sa odstupy radov pravidelne STRIEDAJU.

    Testuje sa priamo hypoteza striedania - porovnaju sa odstupy na sudych
    a neparnych poziciach. Samotna bimodalita by nestacila: velkopriestorovy
    vozen ma tiez jednu vycnievajucu medzeru (predstavok), a ta z neho
    oddielovy vozen nerobi.

    Ktora skupina odstupov je "vnutri oddielu" sa NEhada: miesta otocene k
    sebe potrebuju priestor na nohy pre dvoch, kym dve lavice chrbtami k sebe
    oddeluje len tenka opierka. Vnutri oddielu je preto odstup VACSI.

    Vrati zoznam oddielov ako zoznamov indexov radov, alebo None.
    """
    if len(rows) < 4:
        return None
    gaps = [rows[i + 1] - rows[i] for i in range(len(rows) - 1)]
    even = [g for i, g in enumerate(gaps) if i % 2 == 0]
    odd = [g for i, g in enumerate(gaps) if i % 2 == 1]
    if not even or not odd:
        return None
    mean_even = sum(even) / len(even)
    mean_odd = sum(odd) / len(odd)
    big, small = (even, odd) if mean_even > mean_odd else (odd, even)
    ratio = max(mean_even, mean_odd) / max(min(mean_even, mean_odd), 1e-6)
    if ratio < 1.10:
        return None
    # rozdelenie musi byt aj konzistentne, nie len v priemere
    boundary = (sum(big) / len(big) + sum(small) / len(small)) / 2.0
    consistent = (sum(1 for g in big if g > boundary) +
                  sum(1 for g in small if g <= boundary)) / float(len(gaps))
    if consistent < ALTERNATION:
        return None

    phase = 0 if mean_even > mean_odd else 1     # faza VACSICH odstupov
    bays, i = [], 0
    while i < len(rows):
        if i < len(gaps) and i % 2 == phase:
            bays.append([i, i + 1])
            i += 2
        else:
            bays.append([i])
            i += 1
    return bays


# ------------------------------------------------------------ model vozna

class CoachLayout:
    """Topologia vozna odvodena z layoutu."""

    def __init__(self, positions):
        self.positions = positions
        xs = [p[0] for p in positions.values()]
        ys = [p[1] for p in positions.values()]
        self.length_axis = 1 if (max(ys) - min(ys)) >= (max(xs) - min(xs)) else 0
        self.cross_axis = 1 - self.length_axis

        cross = [p[self.cross_axis] for p in positions.values()]
        length = [p[self.length_axis] for p in positions.values()]
        self.columns = _auto_cluster(cross)
        self.rows = _auto_cluster(length)

        # ulicka = vyrazne najvacsia medzera medzi stlpcami; kupejovy vozen
        # ulicku medzi miestami nema (chodbicka je vedla oddielu, nie v nom)
        self.aisle_after = None
        if len(self.columns) >= 4:
            gaps = [(self.columns[i + 1] - self.columns[i], i)
                    for i in range(len(self.columns) - 1)]
            width, i = max(gaps)
            if width > AISLE_RATIO * (sum(g for g, _ in gaps) / len(gaps)):
                self.aisle_after = i

        self.seat_row = {n: _nearest(p[self.length_axis], self.rows)
                         for n, p in positions.items()}
        self.seat_col = {n: _nearest(p[self.cross_axis], self.columns)
                         for n, p in positions.items()}

        # Oddiely: prvotne z cislovania miest (spolahlivejsie), inak z geometrie.
        # seat_bay mapuje miesto -> mnozina miest v oddiele; pri radovom sedeni
        # zostane prazdne a "oproti" sa oznami ako nezname.
        self.seat_bay, self.bay_source = {}, "radove sedenie"
        by_numbering = _numbering_bays(sorted(positions), self.seat_row)
        if by_numbering:
            for group in by_numbering:
                for s in group:
                    self.seat_bay[s] = group
            self.bay_source = "cislovanie miest"
        else:
            row_bays = _alternating_bays(self.rows)
            if row_bays:
                for bay_rows in row_bays:
                    if len(bay_rows) < 2:
                        continue
                    members = sorted(n for n in positions
                                     if self.seat_row[n] in bay_rows)
                    for s in members:
                        self.seat_bay[s] = members
                self.bay_source = "geometria radov"
        self.row_seating = not self.seat_bay

    def side_of(self, seat):
        """0 / 1 podla strany ulicky; None ak vozen ulicku nema (kupe)."""
        if self.aisle_after is None:
            return None
        return 0 if self.seat_col[seat] <= self.aisle_after else 1

    # --- dotazy --------------------------------------------------------
    def next_to(self, seat):
        """
        Miesta na tej istej lavici / v tom istom rade.

        V kupe sa hranicou riadi oddiel, nie ulicka - kupe ziadnu ulicku vnutri
        nema, chodbicka vedie vedla neho. Pri radovom sedeni oddeluje dvojice
        ulicka.
        """
        row = self.seat_row[seat]
        members = self.seat_bay.get(seat)
        if members:
            pool = [n for n in members if n != seat and self.seat_row[n] == row]
        else:
            side = self.side_of(seat)
            pool = [n for n in self.positions
                    if n != seat and self.seat_row[n] == row
                    and (side is None or self.side_of(n) == side)]
        return sorted(pool, key=lambda n: abs(self.positions[n][self.cross_axis]
                                              - self.positions[seat][self.cross_axis]))

    def facing(self, seat):
        """
        Miesto priamo oproti - v ramci oddielu ten druhy rad, najblizsi stlpec.
        None znamena "nezname / neexistuje", nie "prazdne".
        """
        members = self.seat_bay.get(seat)
        if not members:
            return None
        row = self.seat_row[seat]
        mine = self.positions[seat][self.cross_axis]
        cands = [n for n in members if self.seat_row[n] != row]
        if not cands:
            return None
        return min(cands, key=lambda n: abs(self.positions[n][self.cross_axis] - mine))

    def bay(self, seat):
        """
        Cely oddiel vratane seba - kupe alebo stolik. Pri radovom sedeni
        vrati len miesta vedla seba (dvojica pri okne / pri ulicke).
        """
        members = self.seat_bay.get(seat)
        if members:
            return list(members)
        return sorted([seat] + self.next_to(seat))

    def neighbours(self, seat):
        """Kompletne susedstvo miesta, usporiadane podla blizkosti."""
        nx = self.next_to(seat)
        fc = self.facing(seat)
        bay = [n for n in self.bay(seat) if n != seat]
        ordered, seen = [], set()
        for n in nx + ([fc] if fc else []) + bay:
            if n is not None and n not in seen:
                seen.add(n)
                ordered.append(n)
        return {"seat": seat, "nextTo": nx, "facing": fc,
                "bay": self.bay(seat), "ordered": ordered}

    def describe(self):
        return ("stlpcov=%d, radov=%d, ulicka=%s, oddiely: %s"
                % (len(self.columns), len(self.rows),
                   "za %d" % self.aisle_after if self.aisle_after is not None else "nie",
                   self.bay_source))


    def confidence(self, seat):
        """
        Nakolko sa da susedstvo tohto miesta brat ako iste.

        Vrati "iste" / "neiste" a duvod. Radove sedenie bez najdenej ulicky
        znamena, ze layout sa neda spolahlivo precitat - potom je lepsie
        priznat viac kandidatov ako tvrdit jedno nespravne miesto.
        """
        if self.seat_bay.get(seat):
            return "iste", None
        if self.aisle_after is None and len(self.next_to(seat)) > 1:
            return "neiste", "v layoute sa nenasla ulicka, susedia su len kandidati"
        return "iste", None


def build_layout(svg, api_seats):
    pos, score = seat_positions(svg, api_seats)
    if not pos:
        return None, score
    layout = CoachLayout(pos)
    layout.coverage = score
    return layout, score
