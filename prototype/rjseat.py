#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RJ Seat Checker - funkcny prototyp.

Pre zadany spoj a miesto povie, kto sedi okolo teba, od ktorej stanice sa
ktore susedne miesto uvolni, navrhne pokojnejsie miesto vo vlaku a vie to
sledovat opakovane a hlasit zmeny.

Poznamka k udajom: RegioJet zverejnuje IBA obsadenost miest - ci je miesto
volne pre dany usek. Ziadne udaje o cestujucich neexistuju a tento nastroj ich
nehlada. "Info o spolucestujucich" tu znamena: ktore miesta okolo teba su
obsadene a kde ten clovek nastupuje alebo vystupuje.

    python rjseat.py --date 2026-08-01 --departure 21:45 --coach 6 --seat 32
    python rjseat.py --date 2026-08-01 --departure 21:45 --recommend C0
    python rjseat.py --date 2026-08-01 --departure 21:45 --coach 6 --seat 32 --watch 300

Bez zavislosti - standardna kniznica Pythonu 3.8+.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tools"))
from seatgeom import build_layout                                     # noqa: E402

API = "https://brn-ybus-pubapi.sa.cz/restapi"

# X-Application-Origin: APP je hodnota, ktoru oficialna dokumentacia uvadza
# pre mobilnu aplikaciu. Content-Type aj Accept musia byt verzovane, inak
# endpoint freeSeats odpoveda HTTP 400 "Unexpected error".
BASE_HEADERS = {
    "Accept": "application/json",
    "X-Lang": "sk",
    "X-Currency": "EUR",
    "X-Application-Origin": "APP",
    "User-Agent": "rjseat-prototype/1.0",
}
VERSIONED = "application/1.1.0+json"

PRAHA, KOSICE = 372825000, 1763018007
SLEEP = 0.35

# rozpoznanie kupe podla textu, ktory RegioJet posiela v seatConstraint
QUIET_HINT = "rusiv"          # "obmedzene pouzivanie rusivych zariadeni"
CHILDREN_HINT = "etsk"        # "detske kupe"
TABLE_HINT = "stol"           # "miesto pri stoliku"


class ApiError(RuntimeError):
    def __init__(self, message, code=None):
        super().__init__(message)
        self.code = code


# ------------------------------------------------------------------- API

def _call(method, path, params=None, body=None, retries=4):
    url = API + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    headers = dict(BASE_HEADERS)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = VERSIONED
        headers["Accept"] = VERSIONED

    last = None
    for attempt in range(retries):
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=45) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", "replace")
            if e.code != 429 and 400 <= e.code < 500:
                raise ApiError("HTTP %s %s: %s" % (e.code, path, payload[:200]), e.code)
            last = ApiError("HTTP %s %s" % (e.code, path), e.code)
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last = ApiError("%s pri %s" % (type(e).__name__, path))
        time.sleep(1.5 * (attempt + 1))
    raise last


def stations():
    out = {}
    for country in _call("GET", "/consts/locations"):
        for city in country.get("cities", []):
            for st in city.get("stations", []):
                out[st["id"]] = st.get("fullname") or st.get("name") or "?"
    return out


def seat_class_titles():
    try:
        return {c["key"]: c.get("title") or c["key"]
                for c in _call("GET", "/consts/seatClasses")}
    except ApiError:
        return {}


def search(from_id, to_id, date):
    data = _call("GET", "/routes/search/simple", {
        "tariffs": "REGULAR", "fromLocationType": "STATION", "fromLocationId": from_id,
        "toLocationType": "STATION", "toLocationId": to_id, "departureDate": date})
    return [r for r in data.get("routes", [])
            if not r.get("transfersCount")
            and "TRAIN" in (r.get("vehicleTypes") or [])
            and (r.get("departureTime") or "").startswith(date)]


def route_detail(route_id, from_id, to_id):
    return _call("GET", "/routes/%s/simple" % route_id, {
        "routeId": route_id, "fromStationId": from_id,
        "toStationId": to_id, "tariffs": "REGULAR"})


def free_seats(section_id, from_id, to_id, seat_class=None):
    """
    Obsadenost pre usek. Vrati vsetky vozne naraz - preto jeden prechod
    zastavkami staci na analyzu vlastneho miesta aj na odporucanie.
    """
    # Pozor: publikovany spec (SwaggerHub 1.1.0) uvadza tariffs a seatClass ako
    # povinne polia na najvyssej urovni, ale nasadeny endpoint ich takto odmieta
    # ("request.body.json.property.unrecognized"). Realne ocakava vnoreny
    # seatPreference - rovnako, ako to posiela ich vlastny frontend.
    body = {"sections": [{"sectionId": section_id,
                          "fromStationId": from_id, "toStationId": to_id}],
            "seatPreference": {"tariffs": ["REGULAR"]}}
    if seat_class:
        body["seatPreference"]["seatClass"] = seat_class
    data = _call("POST", "/routes/freeSeats", body=body)
    if isinstance(data, dict):
        raise ApiError("freeSeats: %s" % data.get("message"))
    return data[0]["vehicles"] if data else []


_TT = None


def stops_of(line_code, from_id, to_id, date, dep_hhmm):
    """Zastavky spoja z cestovneho poriadku, v geografickom poradi."""
    global _TT
    if _TT is None:
        _TT = _call("GET", "/consts/timetables")
    scored = []
    for tt in _TT:
        idx = {s["stationId"]: s for s in tt.get("stations") or []}
        if from_id not in idx or to_id not in idx:
            continue
        if idx[from_id]["index"] >= idx[to_id]["index"]:
            continue
        score = 0
        if line_code and tt.get("connectionCode") == line_code:
            score += 100
        if (idx[from_id].get("departure") or "")[:5] == dep_hhmm:
            score += 50
        if (tt.get("validFrom") or "") <= date <= (tt.get("validTo") or ""):
            score += 10
        if score:
            scored.append((score, tt, idx))
    if not scored:
        return []
    _, tt, idx = max(scored, key=lambda c: c[0])
    lo, hi = idx[from_id]["index"], idx[to_id]["index"]
    return [{"stationId": s["stationId"], "index": s["index"],
             "departure": s["departure"][:5], "exact": False, "bookable": None}
            for s in sorted(tt["stations"], key=lambda s: s["index"])
            if lo <= s["index"] < hi and (s.get("departure") or "").strip()]


def refine_times(route_id, stops, to_id):
    """
    Doplni realny cas odjazdu. Zastavky, z ktorych sa nastupit neda, sa
    NEZAHADZUJU - vlak tam zastavuje a obsadenost pre ne plati, takze prave
    tam je vidno, kde niekto vystupuje. Ich cas ostava z poriadku (~).
    """
    for i, stop in enumerate(stops):
        if i:
            time.sleep(SLEEP)
        try:
            detail = route_detail(route_id, stop["stationId"], to_id)
        except ApiError:
            stop["bookable"] = False
            continue
        dep = detail.get("departureTime") or ""
        if dep:
            stop["departure"] = dep[11:16]
            stop["exact"] = True
        stop["bookable"] = True
    return stops


# ------------------------------------------------- model suprava / obsadenost

_LAYOUTS = {}


def layout_for(deck, seats):
    url = deck.get("layoutURL") or ""
    key = (url, tuple(seats))
    if key in _LAYOUTS:
        return _LAYOUTS[key]
    svg = None
    if url:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": BASE_HEADERS["User-Agent"]})
            with urllib.request.urlopen(req, timeout=45) as resp:
                svg = resp.read().decode("utf-8", "replace")
        except Exception:
            svg = None
    layout, _ = build_layout(svg, seats) if svg else (None, 0.0)
    _LAYOUTS[key] = layout
    return layout


def coach_of(vehicles, number):
    for v in vehicles:
        if v.get("number") == number:
            return v, (v.get("decks") or [{}])[0]
    return None, None


def occupancy(deck):
    """cislo miesta -> True ak volne."""
    state = {s["index"]: False for s in deck.get("occupiedSeats") or []}
    state.update({s["index"]: True for s in deck.get("freeSeats") or []})
    return state


def _deaccent(text):
    """
    Odstrani diakritiku pred porovnavanim.

    RegioJet posiela seatConstraint v jazyku podla hlavicky X-Lang, takze
    hladanie "rusiv" by na slovenskom "rusivych zariadeni" nesedelo - bez tohto
    kroku sa tiche kupe nikdy neoznacilo.
    """
    return "".join(c for c in unicodedata.normalize("NFD", text.lower())
                   if not unicodedata.combining(c))


def seat_flags(deck):
    """cislo miesta -> mnozina priznakov z seatConstraint (stolik, ticho, deti)."""
    flags = {}
    for group in ("freeSeats", "occupiedSeats"):
        for s in deck.get(group) or []:
            text = _deaccent(s.get("seatConstraint") or "")
            f = set()
            if TABLE_HINT in text:
                f.add("stolik")
            if QUIET_HINT in text:
                f.add("ticho")
            if CHILDREN_HINT in text:
                f.add("deti")
            flags[s["index"]] = f
    return flags


def scan(route, from_id, to_id, names, verbose=True):
    """
    Jeden prechod zastavkami. Vrati vsetko, co treba na analyzu aj odporucanie:
    obsadenost kazdeho vozna pre kazdy usek <zastavka> -> ciel.
    """
    detail = route_detail(route["id"], from_id, to_id)
    section = (detail.get("sections") or [{}])[0]
    section_id = section.get("id") or detail.get("mainSectionId") or route["id"]
    line = (section.get("line") or {}).get("code")
    date = (route.get("departureTime") or "")[:10]
    dep = (route.get("departureTime") or "")[11:16]

    stops = stops_of(line, from_id, to_id, date, dep)
    if not stops:
        stops = [{"stationId": from_id, "index": 1, "departure": dep,
                  "exact": True, "bookable": True}]
    else:
        if verbose:
            print("Zistujem zastavky a realne casy...")
        refine_times(route["id"], stops, to_id)

    per_stop, failed = [], []
    for i, stop in enumerate(stops):
        if i:
            time.sleep(SLEEP)
        try:
            vehicles = free_seats(section_id, stop["stationId"], to_id)
        except ApiError as e:
            # chyby sa nesmu prehltnut - tichy vypadok zastavky by sa prejavil
            # az ako nezmyselny vysledok o dva kroky neskor
            failed.append((names.get(stop["stationId"], "?"), str(e)))
            continue
        per_stop.append({"stop": stop, "vehicles": vehicles,
                         "name": names.get(stop["stationId"], "?")})
    if verbose:
        print("  obsadenost nacitana z %d/%d zastavok" % (len(per_stop), len(stops)))
        for name, err in failed:
            print("  ! %s preskocene: %s" % (name, err))
    if not per_stop:
        raise ApiError("Nepodarilo sa nacitat ani jednu zastavku (%s)"
                       % (failed[0][1] if failed else "bez detailu"))
    return {"sectionId": section_id, "line": line, "date": date,
            "departure": dep, "arrival": (route.get("arrivalTime") or "")[11:16],
            "perStop": per_stop, "failedStops": failed}


# --------------------------------------------------------------- analyzy

def frees_at(per_stop, coach, seat):
    """Prva zastavka, od ktorej je miesto volne az do ciela; None ak nikdy."""
    for entry in per_stop:
        _, deck = coach_of(entry["vehicles"], coach)
        if deck and occupancy(deck).get(seat):
            return entry
    return None


def analyse_seat(scan_data, coach, seat, names, class_titles):
    per_stop = scan_data["perStop"]
    if not per_stop:
        return None
    vehicle, deck = coach_of(per_stop[0]["vehicles"], coach)
    if not vehicle:
        return {"error": "Vo vlaku nie je vozen c. %s. Vozne: %s" % (
            coach, ", ".join(str(v["number"]) for v in per_stop[0]["vehicles"]))}
    state = occupancy(deck)
    if seat not in state:
        return {"error": "Vo vozni %s nie je miesto %s. Miesta %s-%s." % (
            coach, seat, min(state), max(state))}

    all_seats = sorted(state)
    layout = layout_for(deck, all_seats)
    if layout is None or seat not in layout.positions:
        return {"error": "Layout vozna %s sa nepodarilo precitat, susedstvo neviem urcit."
                         % deck.get("name")}

    n = layout.neighbours(seat)
    conf, why = layout.confidence(seat)
    flags = seat_flags(deck)
    return {
        "coachName": deck.get("name"),
        "coachClass": ", ".join(class_titles.get(c["name"], c["name"])
                                for c in vehicle.get("seatClasses") or []),
        "seat": seat, "nextTo": n["nextTo"], "facing": n["facing"], "bay": n["bay"],
        "baySource": layout.bay_source, "confidence": conf, "confidenceWhy": why,
        "myFlags": flags.get(seat, set()),
        "neighbourInfo": [{"seat": s, "freeFromStart": state.get(s, False),
                           "freesAt": None if state.get(s) else frees_at(per_stop, coach, s),
                           "flags": flags.get(s, set())}
                          for s in n["ordered"]],
        "freeInCoach": sum(1 for v in state.values() if v), "coachTotal": len(all_seats),
    }


def recommend(scan_data, seat_class, class_titles, limit=6):
    """
    Navrhne pokojnejsie miesta.

    Kluc k spravnemu citaniu dat: obsadenost sa vracia pre USEK. Miesto volne
    pre usek <vychodzia> -> ciel je volne po celu cestu - kratsi usek ho uz
    obsadit nemoze. Preto sa "pokoj" pocita zo stavu na zaciatku, a prechod
    zastavkami sluzi na opacnu otazku: kde sa OBSADENE miesto uvolni.

    Kazdy oddiel je v zozname len raz - osem takmer rovnakych miest z jedneho
    vozna nikomu nepomoze.
    """
    per_stop = scan_data["perStop"]
    if not per_stop:
        return []
    out, seen_bays = [], set()
    for vehicle in per_stop[0]["vehicles"]:
        classes = {c["name"] for c in vehicle.get("seatClasses") or []}
        if seat_class and seat_class not in classes:
            continue
        deck = (vehicle.get("decks") or [{}])[0]
        state = occupancy(deck)
        all_seats = sorted(state)
        layout = layout_for(deck, all_seats)
        if layout is None:
            continue
        flags = seat_flags(deck)

        for seat in all_seats:
            if not state.get(seat) or seat not in layout.positions:
                continue                              # navrhujeme len volne miesta
            n = layout.neighbours(seat)
            others = [s for s in n["bay"] if s != seat]
            key = (vehicle["number"], frozenset(n["bay"]))
            if key in seen_bays:
                continue
            seen_bays.add(key)

            free_now = [s for s in others if state.get(s)]
            taken = [s for s in others if not state.get(s)]
            f = flags.get(seat, set())

            # kedy je oddiel konecne cely prazdny; None = niekto tam zostane
            empty_from, unresolved = None, False
            for s in taken:
                entry = frees_at(per_stop, vehicle["number"], s)
                if entry is None:
                    unresolved = True
                    break
                if empty_from is None or entry["stop"]["index"] > empty_from["stop"]["index"]:
                    empty_from = entry
            if unresolved:
                empty_from = None

            score = 0.0
            if others:
                score += 4.0 * len(free_now) / len(others)
                if not taken:
                    score += 1.5 + 0.4 * len(others)   # cely oddiel prazdny celu cestu
                elif empty_from is not None:
                    score += 1.0                       # aspon sa vyprazdni po ceste
            if "ticho" in f:
                score += 2.5
            if "deti" in f:
                score -= 3.0
            out.append({"coach": vehicle["number"], "coachName": deck.get("name"),
                        "seat": seat, "score": score, "flags": f,
                        "bay": n["bay"], "baySize": len(others),
                        "freeNow": len(free_now), "taken": taken,
                        "emptyFrom": empty_from,
                        "isCompartment": bool(layout.seat_bay.get(seat))})
    out.sort(key=lambda r: (-r["score"], r["coach"], r["seat"]))
    # najviac 2 na vozen - osem takmer rovnakych dvojic z jedneho vozna
    # vyzera ako vyber, ale ziadny nie je
    picked, per_coach = [], {}
    for r in out:
        if per_coach.get(r["coach"], 0) >= 2:
            continue
        per_coach[r["coach"]] = per_coach.get(r["coach"], 0) + 1
        picked.append(r)
        if len(picked) >= limit:
            break
    return picked


# --------------------------------------------------------------- vystup

def _time(stop):
    return ("%s" if stop.get("exact", True) else "~%s") % stop["departure"]


def print_analysis(info, scan_data, to_name):
    if info.get("error"):
        print(info["error"])
        return
    print("Vozen %s - %s" % (info["coachName"], info["coachClass"]))
    extra = ", ".join(sorted(info["myFlags"])) or "-"
    print("Tvoje miesto %s   oddiel %s (%s), priznaky: %s" % (
        info["seat"], ",".join(map(str, info["bay"])), info["baySource"], extra))
    if info["confidence"] != "iste":
        print("! susedstvo je NEISTE: %s" % info["confidenceWhy"])
    print()
    print("Kto je okolo teba (usek -> %s):" % to_name)
    for item in info["neighbourInfo"]:
        s = item["seat"]
        rel = []
        if s in info["nextTo"]:
            rel.append("vedla")
        if s == info["facing"]:
            rel.append("oproti")
        if not rel:
            rel.append("v oddiele")
        tag = ("+" + ",".join(sorted(item["flags"]))) if item["flags"] else ""
        if item["freeFromStart"]:
            print("  miesto %-3s %-10s VOLNE celu cestu %s" % (s, "/".join(rel), tag))
        elif item["freesAt"]:
            print("  miesto %-3s %-10s obsadene, uvolni sa v %s (%s) %s" % (
                s, "/".join(rel), item["freesAt"]["name"],
                _time(item["freesAt"]["stop"]), tag))
        else:
            print("  miesto %-3s %-10s obsadene po celej trase %s" % (s, "/".join(rel), tag))
    print()
    print("Vo vozni volnych %d/%d miest." % (info["freeInCoach"], info["coachTotal"]))
    if any(not e["stop"].get("exact", True) for e in scan_data["perStop"]):
        print("~ = cas zo zakladneho poriadku (spoj je pretrasovany, usek sa nepredava)")


def print_recommendation(rows, class_titles, seat_class):
    if not rows:
        print("Pre triedu %s som nenasiel volne miesto." % (seat_class or "-"))
        return
    print("Najpokojnejsie volne miesta%s:" % (
        " v triede %s" % class_titles.get(seat_class, seat_class) if seat_class else ""))
    for r in rows:
        kind = "kupe" if r["isCompartment"] else "dvojica"
        where = "%s %s" % (kind, ",".join(map(str, r["bay"])))
        if not r["taken"]:
            stav = "cele volne celu cestu"
        elif r["emptyFrom"]:
            stav = "volne %d/%d, cele prazdne od %s (%s)" % (
                r["freeNow"], r["baySize"], r["emptyFrom"]["name"],
                _time(r["emptyFrom"]["stop"]))
        else:
            stav = "volne %d/%d, niekto zostava az do ciela" % (r["freeNow"], r["baySize"])
        tag = ("  +" + ",".join(sorted(r["flags"]))) if r["flags"] else ""
        print("  vozen %-2s miesto %-3s  %-26s %s%s" % (
            r["coach"], r["seat"], where, stav, tag))


def snapshot(info):
    """Stav susedstva na porovnanie medzi kolami sledovania."""
    if not info or info.get("error"):
        return {}
    return {i["seat"]: bool(i["freeFromStart"]) for i in info["neighbourInfo"]}


def diff_alerts(old, new):
    alerts = []
    for seat, free in new.items():
        if seat in old and old[seat] != free:
            alerts.append("miesto %s sa %s" % (seat, "UVOLNILO" if free else "obsadilo"))
    return alerts


# --------------------------------------------------------------- CLI

def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass

    p = argparse.ArgumentParser(description="RJ Seat Checker - susedia, odporucanie, sledovanie")
    p.add_argument("--date", default=dt.date.today().isoformat())
    p.add_argument("--from", dest="from_id", type=int, default=PRAHA)
    p.add_argument("--to", dest="to_id", type=int, default=KOSICE)
    p.add_argument("--departure", help="cas odjazdu HH:MM")
    p.add_argument("--coach", type=int, help="cislo vozna")
    p.add_argument("--seat", type=int, help="cislo miesta")
    p.add_argument("--recommend", metavar="TRIEDA", nargs="?", const="",
                   help="navrhnut pokojnejsie miesto (napr. C0, C1, C2)")
    p.add_argument("--watch", type=int, metavar="SEKUND", help="opakovat a hlasit zmeny")
    p.add_argument("--json", dest="json_out")
    args = p.parse_args(argv)

    names = stations()
    class_titles = seat_class_titles()
    to_name = names.get(args.to_id, "?")

    routes = search(args.from_id, args.to_id, args.date)
    if not routes:
        raise SystemExit("Na %s nie je priamy vlak %s -> %s." % (
            args.date, names.get(args.from_id), to_name))
    if args.departure:
        routes = [r for r in routes if (r.get("departureTime") or "")[11:16] == args.departure]
        if not routes:
            raise SystemExit("Spoj %s v den %s neexistuje." % (args.departure, args.date))
    elif len(routes) > 1:
        print("Priame spoje %s: %s" % (args.date, ", ".join(
            (r.get("departureTime") or "")[11:16] for r in routes)))
        print("Vyber jeden cez --departure HH:MM.")
        return 0
    route = routes[0]

    previous = {}
    while True:
        stamp = dt.datetime.now().strftime("%H:%M:%S")
        data = scan(route, args.from_id, args.to_id, names, verbose=True)
        print()
        print("=" * 74)
        print("Vlak %s   %s %s -> %s %s   (%s)  [%s]" % (
            data["line"], names.get(args.from_id), data["departure"],
            to_name, data["arrival"], data["date"], stamp))
        print("=" * 74)

        info = None
        if args.coach and args.seat:
            info = analyse_seat(data, args.coach, args.seat, names, class_titles)
            print_analysis(info, data, to_name)
            current = snapshot(info)
            for a in diff_alerts(previous, current):
                print("  >>> ZMENA: %s" % a)
            previous = current

        if args.recommend is not None:
            print()
            print_recommendation(recommend(data, args.recommend or None,
                                          class_titles, limit=8),
                                 class_titles, args.recommend or None)

        if args.json_out:
            with open(args.json_out, "w", encoding="utf-8") as fh:
                json.dump({"scannedAt": stamp, "train": data["line"],
                           "date": data["date"], "analysis": info,
                           "recommend": recommend(data, args.recommend or None,
                                                  class_titles, 8)
                           if args.recommend is not None else None},
                          fh, ensure_ascii=False, indent=2, default=lambda o: sorted(o))
            print("\nJSON -> %s" % args.json_out)

        if not args.watch:
            return 0
        print("\nDalsi sken za %s s (Ctrl+C ukonci)..." % args.watch)
        time.sleep(args.watch)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
