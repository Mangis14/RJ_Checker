#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RegioJet - od ktorej stanice sa uvolni miesto v kupe.

Prejde vsetky zastavky vlaku od vychodzej stanice smerom k cielu a pre kazdu
z nich zisti obsadenost sedadiel v zadanom vozni (default vozen 6) pre usek
"<zastavka> -> Kosice". Vysledkom je mapa, od ktorej stanice je ktore miesto
v kupe volne az do cielovej stanice.

Nic nerezervuje ani nepotvrdzuje - iba cita verejne data o volnych miestach,
teda presne to iste, co vidi web pri vybere miesta.

Pouzitie:
    python regiojet_kupe.py --date 2026-08-14
    python regiojet_kupe.py --date 2026-08-14 --departure 07:41
    python regiojet_kupe.py --date 2026-08-14 --coach 6 --seat 32 --json vysledok.json
    python regiojet_kupe.py --date 2026-08-14 --departure 07:41 --watch 300

Bez zavislosti - iba standardna kniznica Pythonu 3.8+.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://brn-ybus-pubapi.sa.cz/restapi"

# Endpoint /routes/freeSeats vyzaduje verzovany Content-Type, inak vracia
# "Unexpected error". X-Application-Origin je povinny pre cast endpointov.
BASE_HEADERS = {
    "Accept": "application/json",
    "X-Lang": "sk",
    "X-Currency": "EUR",
    "X-Application-Origin": "WEB",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) regiojet-kupe-check/1.0",
}

DEFAULT_FROM = 372825000    # Praha - hl.n.
DEFAULT_TO = 1763018007     # Kosice - zst.

SLEEP_BETWEEN_CALLS = 0.4   # slusnost k API
MAX_RETRIES = 4

FREE, TAKEN, ABSENT = "free", "taken", "absent"


# ---------------------------------------------------------------- HTTP vrstva

class ApiError(RuntimeError):
    """Chyba vratena API (4xx). code/payload sluzia na rozlisenie priciny."""

    def __init__(self, message, code=None, payload=None):
        super().__init__(message)
        self.code = code
        self.payload = payload


def _request(method, path, params=None, body=None, extra_headers=None, retries=MAX_RETRIES):
    url = API + path
    if params:
        url += "?" + urllib.parse.urlencode(params)

    data = None
    headers = dict(BASE_HEADERS)
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        # Accept musi byt verzovany rovnako ako Content-Type, inak API odpovie
        # HTTP 400 "Unexpected error".
        headers["Content-Type"] = "application/1.1.0+json"
        headers["Accept"] = "application/1.1.0+json"
    if extra_headers:
        headers.update(extra_headers)

    last_err = None
    for attempt in range(retries):
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", "replace")
            # 4xx okrem 429 su chyby requestu - opakovanie nepomoze
            if e.code != 429 and 400 <= e.code < 500:
                raise ApiError("HTTP %s pri %s %s: %s" % (e.code, method, path, payload[:400]),
                               code=e.code, payload=payload)
            last_err = RuntimeError("HTTP %s pri %s: %s" % (e.code, path, payload[:200]))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            last_err = RuntimeError("%s pri %s: %s" % (type(e).__name__, path, e))
        time.sleep(1.5 * (attempt + 1))
    raise last_err


def get(path, params=None, extra_headers=None):
    return _request("GET", path, params=params, extra_headers=extra_headers)


def post(path, body, extra_headers=None):
    return _request("POST", path, body=body, extra_headers=extra_headers)


# ---------------------------------------------------------------- API wrappery

def load_stations():
    """id -> (plny nazov, kod krajiny); a mapa nazov(lower) -> id."""
    data = get("/consts/locations")
    by_id, by_name = {}, {}
    for country in data:
        for city in country.get("cities", []):
            for st in city.get("stations", []):
                by_id[st["id"]] = (st.get("fullname") or st.get("name") or "?", country.get("code", ""))
                by_name[(st.get("fullname") or "").lower()] = st["id"]
    return by_id, by_name


def load_seat_class_titles():
    try:
        return {c["key"]: c.get("title") or c["key"] for c in get("/consts/seatClasses")}
    except RuntimeError:
        return {}


def search_routes(from_id, to_id, date):
    data = get("/routes/search/simple", {
        "tariffs": "REGULAR",
        "fromLocationType": "STATION", "fromLocationId": from_id,
        "toLocationType": "STATION", "toLocationId": to_id,
        "departureDate": date,
    })
    out = []
    for r in data.get("routes", []):
        # len priame vlakove spoje v pozadovany den
        if r.get("transfersCount"):
            continue
        if "TRAIN" not in (r.get("vehicleTypes") or []):
            continue
        if not (r.get("departureTime") or "").startswith(date):
            continue
        out.append(r)
    return out


def route_detail(route_id, from_id, to_id):
    return get("/routes/%s/simple" % route_id, {
        "routeId": route_id, "fromStationId": from_id,
        "toStationId": to_id, "tariffs": "REGULAR",
    })


def free_seats(section_id, from_id, to_id):
    """Vrati zoznam vozidiel s volnymi/obsadenymi miestami pre dany usek."""
    body = {"sections": [{
        "sectionId": section_id,
        "fromStationId": from_id,
        "toStationId": to_id,
    }]}
    data = post("/routes/freeSeats", body)
    if isinstance(data, dict):
        raise RuntimeError("freeSeats vratilo chybu: %s" % data)
    return data[0]["vehicles"] if data else []


_TIMETABLES = None


def load_timetables():
    global _TIMETABLES
    if _TIMETABLES is None:
        _TIMETABLES = get("/consts/timetables")
    return _TIMETABLES


def stops_of_train(line_code, from_id, to_id, date, dep_hhmm):
    """
    Zastavky vlaku medzi from_id a to_id (vratane vychodzej, bez cielovej).

    Cestovny poriadok sa hlada podla kodu spoja (napr. "RJ 1021"), casu odjazdu
    z vychodzej stanice a platnosti k datumu. Berie sa iba to, co ma uvedeny cas
    odjazdu - zaznamy so symbolom "<" a bez casu su navazujuce spoje, nie
    zastavky tohto vlaku. Poradie je geograficke (index v poriadku), co plati
    aj pre pretrasovane spoje, u ktorych casy sedia len ciastocne.
    """
    scored = []
    for tt in load_timetables():
        idx = {s["stationId"]: s for s in tt.get("stations") or []}
        if from_id not in idx or to_id not in idx:
            continue
        if idx[from_id]["index"] >= idx[to_id]["index"]:
            continue
        score = 0
        if line_code and tt.get("connectionCode") == line_code:
            score += 100
        dep = (idx[from_id].get("departure") or "")[:5]
        if dep and dep_hhmm and dep == dep_hhmm:
            score += 50
        valid_from, valid_to = tt.get("validFrom"), tt.get("validTo")
        if valid_from and valid_to and valid_from <= date <= valid_to:
            score += 10
        if score:
            scored.append((score, tt, idx))

    if not scored:
        return []
    _, tt, idx = max(scored, key=lambda c: c[0])

    lo, hi = idx[from_id]["index"], idx[to_id]["index"]
    stops = []
    for s in tt["stations"]:
        if not (lo <= s["index"] < hi):
            continue
        if not (s.get("departure") or "").strip():
            continue                      # navazujuci spoj, nie zastavka
        stops.append({"stationId": s["stationId"], "index": s["index"],
                      "departure": s["departure"][:5], "exactTime": False,
                      "bookable": None})
    stops.sort(key=lambda s: s["index"])
    return stops


def refine_times(route_id, stops, to_id, verbose=True):
    """
    Doplni k zastavkam presny cas odjazdu pre dany den.

    Ide o to, ze pri vyluke moze mat spoj iny cas, nez je v zakladnom poriadku.
    Presny cas sa da zistit z /routes/{id}/simple, ale ten funguje len pre
    useky, ktore RegioJet naozaj predava. Zastavky, z ktorych sa nastupit neda,
    sa NEZAHADZUJU - vlak tam zastavuje a freeSeats pre ne vracia platnu
    obsadenost, takze prave tam je vidno, kde niekto vystupuje. Ich cas
    zostava z poriadku a oznaci sa ako pribllizny.
    """
    exact = approx = 0
    for i, stop in enumerate(stops):
        if i:
            time.sleep(SLEEP_BETWEEN_CALLS)
        try:
            detail = route_detail(route_id, stop["stationId"], to_id)
        except ApiError:
            stop["bookable"] = False      # usek sa nepredava, cas ostava z poriadku
            approx += 1
            continue
        except RuntimeError:
            approx += 1
            continue
        dep = detail.get("departureTime") or ""
        if dep:
            stop["departure"] = dep[11:16]
            stop["exactTime"] = True
        stop["bookable"] = True
        exact += 1
    if verbose:
        print("  presny cas %d zastavok, priblizny (usek sa nepredava) %d" % (exact, approx))
    return stops


# ------------------------------------------------- kupe z SVG layoutu vozna

_LAYOUT_CACHE = {}


def _fetch_svg(url):
    if url in _LAYOUT_CACHE:
        return _LAYOUT_CACHE[url]
    try:
        req = urllib.request.Request(url, headers={"User-Agent": BASE_HEADERS["User-Agent"]})
        with urllib.request.urlopen(req, timeout=30) as resp:
            svg = resp.read().decode("utf-8", "replace")
    except Exception:
        svg = None
    _LAYOUT_CACHE[url] = svg
    return svg


def _element_xy(attrs):
    """Poloha elementu - podporuje oba formaty layoutov, ktore RegioJet pouziva."""
    cx = re.search(r'\bcx="(-?[\d.]+)"', attrs)
    cy = re.search(r'\bcy="(-?[\d.]+)"', attrs)
    if cx and cy:                                    # <circle> (export z Illustratora)
        return float(cx.group(1)), float(cy.group(1))
    m = re.search(r'\btransform="matrix\(\s*[-\d.eE]+\s+[-\d.eE]+\s+[-\d.eE]+\s+'
                  r'[-\d.eE]+\s+(-?[\d.eE]+)\s+(-?[\d.eE]+)\s*\)"', attrs)
    if m:                                            # <text transform="matrix(...)">
        return float(m.group(1)), float(m.group(2))
    m = re.search(r'\btransform="translate\(\s*(-?[\d.eE]+)[,\s]+(-?[\d.eE]+)', attrs)
    if m:
        return float(m.group(1)), float(m.group(2))
    m = re.search(r'\bd="[Mm]\s*(-?[\d.]+)[,\s]+(-?[\d.]+)', attrs)
    if m:                                            # <path> (export z Figmy)
        return float(m.group(1)), float(m.group(2))
    x, y = re.search(r'\bx="(-?[\d.]+)"', attrs), re.search(r'\by="(-?[\d.]+)"', attrs)
    if x and y:
        return float(x.group(1)), float(y.group(1))
    return None


def seat_positions(svg, all_seats):
    """
    Cislo miesta -> (x, y) z layoutu vozna.

    Layouty pouzivaju rozne konvencie id ("a32" z Figmy, "c32"/"n32"/"s32"
    z Illustratora), preto sa zoberu vsetky prefixy a vyberie sa ten, ktoreho
    cisla presne zodpovedaju miestam, ktore pre vozen hlasi API.
    """
    if not svg:
        return {}
    groups = {}
    for m in re.finditer(r'<([a-zA-Z][\w:-]*)((?:"[^"]*"|\'[^\']*\'|[^>"\'])*)>', svg):
        attrs = m.group(2)
        idm = re.search(r'\bid="([A-Za-z_-]*?)(\d+)"', attrs)
        if not idm:
            continue
        xy = _element_xy(attrs)
        if xy is None:
            continue
        groups.setdefault(idm.group(1), {})[int(idm.group(2))] = xy

    wanted = set(all_seats)
    for prefix in sorted(groups, key=lambda p: -len(groups[p])):
        if set(groups[prefix]) == wanted:
            return groups[prefix]
    return {}


def compartment_from_layout(pos, seat, all_seats):
    """
    Kupe = dva susedne rady sedadiel. Sedadla sa najprv zoskupia do radov
    podla pozicie na dlhsej osi vozna, potom sa susedne rady parovo spoja.
    Vrati zoznam cisel miest v kupe s hladanym miestom, alebo None.
    """
    if seat not in pos or len(pos) < 4:
        return None

    xs = [p[0] for p in pos.values()]
    ys = [p[1] for p in pos.values()]
    axis = 1 if (max(ys) - min(ys)) >= (max(xs) - min(xs)) else 0   # dlhsia os vozna

    rows, current = [], []
    for num in sorted(pos, key=lambda n: (pos[n][axis], pos[n][1 - axis])):
        v = pos[num][axis]
        if current and abs(v - pos[current[-1]][axis]) > 3.0:
            rows.append(current)
            current = []
        current.append(num)
    if current:
        rows.append(current)

    if len(rows) < 2 or len(rows) % 2:
        return None

    for i in range(0, len(rows), 2):
        group = sorted(rows[i] + rows[i + 1])
        if seat in group:
            # kupe ma zmysel iba ak sedi na realne miesta vo vozni
            if len(group) < 2 or not set(group) <= set(all_seats):
                return None
            return group
    return None


def compartment_by_numbering(seat, all_seats):
    """Zaloha: RegioJet cisluje kupe po desiatkach (31-36 = jedno kupe)."""
    tens = seat // 10
    group = sorted(n for n in all_seats if n // 10 == tens)
    return group or [seat]


def resolve_compartment(deck, seat, all_seats, override=None):
    if override:
        return sorted(override), "zadane rucne"
    pos = seat_positions(_fetch_svg(deck.get("layoutURL") or ""), all_seats)
    by_layout = compartment_from_layout(pos, seat, all_seats)
    by_number = compartment_by_numbering(seat, all_seats)
    if by_layout:
        note = "z layoutu vozna" if by_layout == by_number else "z layoutu vozna (cislovanie by dalo %s)" % _fmt_seats(by_number)
        return by_layout, note
    return by_number, "z cislovania miest (layout sa nepodarilo nacitat)"


def _fmt_seats(seats):
    return ",".join(str(s) for s in seats)


# ---------------------------------------------------------------- jadro logiky

def coach_snapshot(vehicles, coach_no):
    """Vrati (deck, mapa cislo_miesta -> volne?) pre zadany vozen."""
    for v in vehicles:
        if v.get("number") != coach_no:
            continue
        deck = (v.get("decks") or [None])[0]
        if not deck:
            continue
        state = {}
        for s in deck.get("occupiedSeats") or []:
            state[s["index"]] = False
        for s in deck.get("freeSeats") or []:
            state[s["index"]] = True
        return v, deck, state
    return None, None, None


def describe_coaches(vehicles, class_titles):
    lines = []
    for v in sorted(vehicles, key=lambda v: v.get("number") or 0):
        deck = (v.get("decks") or [{}])[0]
        seats = sorted([s["index"] for s in (deck.get("freeSeats") or [])] +
                       [s["index"] for s in (deck.get("occupiedSeats") or [])])
        classes = ", ".join(class_titles.get(c["name"], c["name"]) for c in v.get("seatClasses") or [])
        lines.append("  vozen %-3s %-24s %-22s miesta %s-%s (%d)" % (
            v.get("number"), (deck.get("name") or "?")[:24], classes[:22],
            seats[0] if seats else "-", seats[-1] if seats else "-", len(seats)))
    return lines


def scan_train(route, from_id, to_id, coach_no, seat_no, station_names,
               class_titles, compartment_override=None, verbose=True):
    """Prejde vsetky zastavky vlaku a vrati vysledok skenu."""
    route_id = route["id"]
    detail = route_detail(route_id, from_id, to_id)
    section = (detail.get("sections") or [{}])[0]
    section_id = section.get("id") or detail.get("mainSectionId") or route_id
    line_code = (section.get("line") or {}).get("code")
    dep_hhmm = (route.get("departureTime") or "")[11:16]
    arr_hhmm = (route.get("arrivalTime") or "")[11:16]
    date = (route.get("departureTime") or "")[:10]

    header = "Vlak %s   %s %s  ->  %s %s   (%s)" % (
        line_code or route_id,
        station_names.get(from_id, ("?", ""))[0], dep_hhmm,
        station_names.get(to_id, ("?", ""))[0], arr_hhmm, date)

    if verbose:
        print(header)
        print("Zistujem zastavky a realne casy...")
    stops = stops_of_train(line_code, from_id, to_id, date, dep_hhmm)
    if not stops:
        stops = [{"stationId": from_id, "index": 1, "departure": dep_hhmm,
                  "exactTime": True, "bookable": True}]
        if verbose:
            print("  ! cestovny poriadok spoja sa nenasiel, kontrolujem iba vychodziu stanicu")
    else:
        refine_times(route_id, stops, to_id, verbose)

    result = {
        "train": line_code, "routeId": route_id, "sectionId": section_id,
        "date": date, "departure": dep_hhmm, "arrival": arr_hhmm,
        "header": header, "coach": coach_no, "seat": seat_no,
        "coachName": None, "coachClass": None, "compartment": [],
        "compartmentSource": None, "rows": [], "coachTotal": None,
        "coaches": [], "error": None,
    }
    if verbose:
        print()

    compartment, comp_source, all_seats = None, None, None

    for i, stop in enumerate(stops):
        if i:
            time.sleep(SLEEP_BETWEEN_CALLS)
        sid = stop["stationId"]
        name = stop.get("name") or station_names.get(sid, ("id %s" % sid, ""))[0]
        try:
            vehicles = free_seats(section_id, sid, to_id)
        except RuntimeError as e:
            result["rows"].append({"stationId": sid, "name": name,
                                   "departure": stop["departure"], "error": str(e)})
            if verbose:
                print("  %-34s %s  CHYBA: %s" % (name, stop["departure"], e))
            continue

        if not result["coaches"]:
            result["coaches"] = describe_coaches(vehicles, class_titles)

        vehicle, deck, state = coach_snapshot(vehicles, coach_no)
        if vehicle is None:
            result["error"] = ("Vo vlaku %s nie je vozen c. %s. Dostupne vozne:\n%s"
                              % (line_code or route_id, coach_no,
                                 "\n".join(result["coaches"])))
            return result

        if compartment is None:
            all_seats = sorted(state)
            if seat_no not in state:
                result["error"] = ("Vo vozni %s (%s) nie je miesto c. %s. Miesta: %s-%s.\n%s"
                                  % (coach_no, deck.get("name"), seat_no,
                                     all_seats[0], all_seats[-1],
                                     "\n".join(result["coaches"])))
                return result
            compartment, comp_source = resolve_compartment(deck, seat_no, all_seats,
                                                           compartment_override)
            result["compartment"] = compartment
            result["compartmentSource"] = comp_source
            result["coachName"] = deck.get("name")
            result["coachClass"] = ", ".join(
                class_titles.get(c["name"], c["name"]) for c in vehicle.get("seatClasses") or [])
            result["coachTotal"] = len(all_seats)
            if verbose:
                print("Vozen %s - %s - %s" % (coach_no, result["coachName"], result["coachClass"]))
                print("Kupe s miestom %s: %s  (%s)" % (seat_no, _fmt_seats(compartment), comp_source))
                print()
                print("%-34s %-6s %s   %-7s %s" % (
                    "odkial (-> " + station_names.get(to_id, ("?", ""))[0] + ")", "odch.",
                    " ".join("%3d" % s for s in compartment), "kupe", "vozen"))
                print("-" * (34 + 7 + 4 * len(compartment) + 18))

        seats = {}
        for s in compartment:
            seats[s] = FREE if state.get(s) else (TAKEN if s in state else ABSENT)
        free_in_coach = sum(1 for v in state.values() if v)

        row = {"stationId": sid, "name": name, "departure": stop["departure"],
               "exactTime": stop.get("exactTime", True),
               "bookable": stop.get("bookable"),
               "seats": seats, "freeInCompartment": sum(1 for v in seats.values() if v == FREE),
               "freeInCoach": free_in_coach, "error": None}
        result["rows"].append(row)

        if verbose:
            marks = " ".join("%3s" % _mark(seats[s]) for s in compartment)
            print("%-34s %-6s %s   %d/%-5d %d/%d" % (
                name[:34], _fmt_time(row), marks,
                row["freeInCompartment"], len(compartment),
                free_in_coach, result["coachTotal"]))

    if verbose and any(not r.get("exactTime", True) for r in result["rows"]):
        print()
        print("~ = cas zo zakladneho cestovneho poriadku; tento usek RegioJet nepredava,")
        print("    takze presny cas pre dany den sa neda overit a spoj moze byt pretrasovany.")
        print("    Obsadenost je platna - vlak tam zastavuje, len tam nemozes nastupit.")

    return result


def _fmt_time(row):
    return ("%s" if row.get("exactTime", True) else "~%s") % row["departure"]


def _mark(state):
    return {FREE: "o", TAKEN: "X", ABSENT: "-"}[state]


def summarize(result):
    """
    Pre kazde miesto v kupe najde prvu stanicu, od ktorej je volne do ciela.

    Ak je miesto volne pre usek A -> ciel, je z povahy veci volne aj pre kazdy
    kratsi usek zacinajuci neskor. Ak sa taky rozpor objavi, znamena to, ze
    niekto miesto zarezervoval pocas skenu - taky zaznam sa oznaci.
    """
    out = []
    rows = [r for r in result["rows"] if not r.get("error")]
    for seat in result["compartment"]:
        first_i = None
        for i, r in enumerate(rows):
            if r["seats"].get(seat) == FREE:
                first_i = i
                break
        taken_later = []
        if first_i is not None:
            taken_later = [r["name"] for r in rows[first_i + 1:]
                           if r["seats"].get(seat) == TAKEN]
        first = rows[first_i] if first_i is not None else None
        out.append({"seat": seat,
                    "fromStationId": first["stationId"] if first else None,
                    "fromStation": first["name"] if first else None,
                    "departure": first["departure"] if first else None,
                    "exactTime": first.get("exactTime", True) if first else None,
                    "bookable": first.get("bookable") if first else None,
                    "freeWholeWay": first_i == 0,
                    "inconsistentAt": taken_later})
    return out


def print_summary(summary, to_name):
    print()
    print("Od ktorej stanice je miesto volne az do %s:" % to_name)
    warn = False
    for s in summary:
        if s["fromStation"] is None:
            print("  miesto %-3s obsadene po celej trase" % s["seat"])
        else:
            t = ("%s" if s.get("exactTime", True) else "~%s") % s["departure"]
            note = "" if s.get("bookable") is not False else "  [odtialto sa neda nastupit]"
            if s["freeWholeWay"]:
                print("  miesto %-3s volne uz z %s (%s) - cela cesta%s" % (
                    s["seat"], s["fromStation"], t, note))
            else:
                print("  miesto %-3s volne od %s (%s)%s" % (
                    s["seat"], s["fromStation"], t, note))
        if s["inconsistentAt"]:
            warn = True
            print("      ! pozor: neskor uz obsadene (%s) - niekto ho zabral pocas skenu,"
                  " sken zopakuj" % ", ".join(s["inconsistentAt"][:3]))
    if warn:
        print("  (obsadenost sa meni v realnom case)")


# ---------------------------------------------------------------- CLI

def resolve_station(value, by_name, by_id, label):
    if value is None:
        return None
    if re.fullmatch(r"\d+", str(value)):
        sid = int(value)
        if sid not in by_id:
            raise SystemExit("Stanica s id %s neexistuje (%s)." % (sid, label))
        return sid
    needle = str(value).strip().lower()
    exact = by_name.get(needle)
    if exact:
        return exact
    hits = [(n, i) for n, i in by_name.items() if needle in n]
    if len(hits) == 1:
        return hits[0][1]
    if not hits:
        raise SystemExit("Stanicu '%s' som nenasiel (%s)." % (value, label))
    raise SystemExit("Stanica '%s' je nejednoznacna (%s). Vyber jednu:\n%s" % (
        value, label, "\n".join("  %s  (id %s)" % (by_id[i][0], i) for _, i in sorted(hits)[:15])))


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass

    p = argparse.ArgumentParser(
        description="Zisti, od ktorej stanice sa uvolni miesto v kupe vlaku RegioJet.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Priklad:\n"
               "  python regiojet_kupe.py --date 2026-08-14 --departure 07:41\n"
               "  python regiojet_kupe.py --date 2026-08-14 --coach 6 --seat 32 --json out.json\n")
    p.add_argument("--date", default=dt.date.today().isoformat(),
                   help="datum odjazdu YYYY-MM-DD (default dnes)")
    p.add_argument("--from", dest="from_station", default=str(DEFAULT_FROM),
                   help="vychodzia stanica - nazov alebo id (default Praha - hl.n.)")
    p.add_argument("--to", dest="to_station", default=str(DEFAULT_TO),
                   help="cielova stanica - nazov alebo id (default Kosice - zst.)")
    p.add_argument("--coach", type=int, default=6, help="cislo vozna (default 6)")
    p.add_argument("--seat", type=int, default=32, help="cislo miesta v kupe (default 32)")
    p.add_argument("--departure", help="vybrat len spoj s tymto casom odjazdu, napr. 07:41")
    p.add_argument("--compartment", help="rucne zadane miesta v kupe, napr. 31,32,33,34,35,36")
    p.add_argument("--json", dest="json_out", help="ulozit vysledok do JSON suboru")
    p.add_argument("--watch", type=int, metavar="SEKUND",
                   help="opakovat sken kazdych N sekund (Ctrl+C ukonci)")
    args = p.parse_args(argv)

    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", args.date):
        raise SystemExit("--date musi byt vo formate YYYY-MM-DD.")

    override = None
    if args.compartment:
        override = [int(x) for x in re.findall(r"\d+", args.compartment)]
        if args.seat not in override:
            override.append(args.seat)

    print("Nacitavam stanice a cestovne poriadky...")
    by_id, by_name = load_stations()
    class_titles = load_seat_class_titles()
    from_id = resolve_station(args.from_station, by_name, by_id, "--from")
    to_id = resolve_station(args.to_station, by_name, by_id, "--to")
    to_name = by_id[to_id][0]

    def one_pass():
        routes = search_routes(from_id, to_id, args.date)
        if not routes:
            raise SystemExit("Na %s som nenasiel priamy vlak %s -> %s." % (
                args.date, by_id[from_id][0], to_name))
        if args.departure:
            want = args.departure.strip()
            routes = [r for r in routes if (r.get("departureTime") or "")[11:16] == want]
            if not routes:
                raise SystemExit("Spoj s odjazdom %s v den %s neexistuje." % (want, args.date))
        else:
            print("Priame spoje %s: %s" % (
                args.date, ", ".join((r.get("departureTime") or "")[11:16] for r in routes)))
            print("(--departure HH:MM obmedzi sken na jeden spoj)")

        results = []
        for route in routes:
            print()
            print("=" * 78)
            res = scan_train(route, from_id, to_id, args.coach, args.seat,
                             by_id, class_titles, override)
            if res.get("error"):
                print(res["error"])
            else:
                res["summary"] = summarize(res)
                print_summary(res["summary"], to_name)
            results.append(res)
        return results

    while True:
        stamp = dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        print()
        print("### sken %s" % stamp)
        try:
            results = one_pass()
        except RuntimeError as e:
            print("Sken zlyhal: %s" % e)
            results = []

        if args.json_out and results:
            payload = {"scannedAt": stamp, "date": args.date,
                       "from": by_id[from_id][0], "to": to_name,
                       "coach": args.coach, "seat": args.seat, "trains": results}
            with open(args.json_out, "w", encoding="utf-8") as fh:
                json.dump(payload, fh, ensure_ascii=False, indent=2)
            print()
            print("JSON ulozeny do %s" % args.json_out)

        if not args.watch:
            return 0
        print()
        print("Dalsi sken za %s s (Ctrl+C ukonci)..." % args.watch)
        try:
            time.sleep(args.watch)
        except KeyboardInterrupt:
            print()
            return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
