#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Odchyti zlate fixtures z ziveho RegioJet API do ../fixtures.

Fixtures su vstupom pre unit testy Kotlin modulu `core`, ktory sa vdaka nim
testuje bez siete a bez Android SDK. Skript sa da kedykolvek pustit znovu,
ked treba fixtures obnovit - vyber spoja je deterministicky (najblizsi
priamy vlak s najvacsou pestrostou typov voznov).

    python capture_fixtures.py [--date YYYY-MM-DD]
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://brn-ybus-pubapi.sa.cz/restapi"
HEADERS = {
    "Accept": "application/json",
    "X-Lang": "sk",
    "X-Currency": "EUR",
    "X-Application-Origin": "WEB",
    "User-Agent": "Mozilla/5.0 rjseat-fixture-capture/1.0",
}
PRAHA = 372825000
KOSICE = 1763018007
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "fixtures"))


def _open_with_retry(req, attempts=5):
    """API pri dlhsich davkach obcas zahodi spojenie - retry s narastajucou pauzou."""
    last = None
    for i in range(attempts):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read()
        except urllib.error.HTTPError:
            raise                                   # 4xx riesi volajuci
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last = e
            wait = 2.0 * (i + 1)
            print("  ... %s, opakujem za %.0fs" % (type(e).__name__, wait))
            time.sleep(wait)
    raise last


def call(path, params=None, body=None):
    url = API + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    headers = dict(HEADERS)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/1.1.0+json"
        headers["Accept"] = "application/1.1.0+json"
    req = urllib.request.Request(url, data=data, headers=headers,
                                 method="POST" if body is not None else "GET")
    return json.loads(_open_with_retry(req).decode("utf-8"))


def fetch_text(url):
    req = urllib.request.Request(url, headers={"User-Agent": HEADERS["User-Agent"]})
    return _open_with_retry(req).decode("utf-8", "replace")


def slim(free_seats_response):
    """Odpoved /routes/freeSeats orezana na polia, ktore parser naozaj cita."""
    out = []
    for section in free_seats_response:
        vehicles = []
        for v in section.get("vehicles") or []:
            decks = []
            for d in v.get("decks") or []:
                decks.append({
                    "number": d.get("number"), "name": d.get("name"),
                    "layoutURL": d.get("layoutURL"),
                    "freeSeats": [{"index": s["index"], "seatClass": s.get("seatClass")}
                                  for s in d.get("freeSeats") or []],
                    "occupiedSeats": [{"index": s["index"], "seatClass": s.get("seatClass")}
                                      for s in d.get("occupiedSeats") or []]})
            vehicles.append({"number": v.get("number"), "type": v.get("type"),
                             "standard": v.get("standard"),
                             "seatClasses": [{"name": c.get("name")}
                                             for c in v.get("seatClasses") or []],
                             "decks": decks})
        out.append({"sectionId": section.get("sectionId"),
                    "fixedSeatReservation": section.get("fixedSeatReservation"),
                    "vehicles": vehicles})
    return out


def write(name, obj):
    path = os.path.join(OUT, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if isinstance(obj, str):
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(obj)
        size = len(obj.encode("utf-8"))
    else:
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(obj, fh, ensure_ascii=False, indent=1, sort_keys=True)
        size = os.path.getsize(path)
    print("  %-46s %6.1f kB" % (name, size / 1024.0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default=(dt.date.today() + dt.timedelta(days=1)).isoformat())
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    print("Odchytavam fixtures pre %s -> %s, datum %s" % (PRAHA, KOSICE, args.date))

    # --- ciselniky -------------------------------------------------------
    seat_classes = call("/consts/seatClasses")
    write("seatClasses.json", seat_classes)

    locations = call("/consts/locations")
    stations = []
    for country in locations:
        if country.get("code") not in ("CZ", "SK"):
            continue
        for city in country.get("cities", []):
            for st in city.get("stations", []):
                if "TRAIN_STATION" in (st.get("stationsTypes") or []):
                    stations.append({"id": st["id"], "fullname": st.get("fullname"),
                                     "country": country["code"]})
    write("stations.json", stations)

    # --- vyber spoja -----------------------------------------------------
    search = call("/routes/search/simple", {
        "tariffs": "REGULAR", "fromLocationType": "STATION", "fromLocationId": PRAHA,
        "toLocationType": "STATION", "toLocationId": KOSICE, "departureDate": args.date})
    write("search.json", search)

    direct = [r for r in search.get("routes", [])
              if not r.get("transfersCount") and (r.get("departureTime") or "").startswith(args.date)]
    if not direct:
        sys.exit("Na %s nie je priamy spoj." % args.date)

    # Prejdu sa vsetky priame spoje dna: hlavny sa vyberie ten s najvacsou
    # pestrostou voznov, ale layouty sa zbieraju zo vsetkych - inak by v
    # testoch chybali prave tie problematicke vozne (Relax Bm3xx, AK42, BK novy),
    # ktore v jednej suprave naraz nejazdia.
    best, harvest = None, []
    for route in direct:
        time.sleep(0.4)
        seats = call("/routes/freeSeats", body={"sections": [
            {"sectionId": route["id"], "fromStationId": PRAHA, "toStationId": KOSICE}]})
        kinds = {v["decks"][0]["name"] for v in seats[0]["vehicles"]}
        print("  spoj %s %s -> %d typov voznov" % (
            route["id"], route["departureTime"][11:16], len(kinds)))
        harvest.append(seats)
        if best is None or len(kinds) > best[0]:
            best = (len(kinds), route, seats)
    _, route, seats_praha = best
    route_id = route["id"]
    print("vybrany spoj %s (%s)" % (route_id, route["departureTime"][11:16]))

    detail = call("/routes/%s/simple" % route_id, {
        "routeId": route_id, "fromStationId": PRAHA,
        "toStationId": KOSICE, "tariffs": "REGULAR"})
    write("routeDetail.json", detail)
    write("freeSeats_praha.json", seats_praha)

    line_code = ((detail.get("sections") or [{}])[0].get("line") or {}).get("code")

    # --- cestovny poriadok spoja ----------------------------------------
    timetables = call("/consts/timetables")
    mine = [t for t in timetables
            if t.get("connectionCode") == line_code
            and any(s["stationId"] == KOSICE for s in t.get("stations") or [])]
    write("timetable.json", mine)

    stops = []
    if mine:
        idx = {s["stationId"]: s for s in mine[0]["stations"]}
        lo, hi = idx[PRAHA]["index"], idx[KOSICE]["index"]
        stops = [s["stationId"] for s in mine[0]["stations"]
                 if lo < s["index"] < hi and (s.get("departure") or "").strip()]

    # obsadenost z medzilahlych stanic - na testy "od ktorej stanice sa uvolni".
    # Odpoved sa oreze na to, co parser naozaj cita; plne odpovede maju cez 2 MB
    # a v repozitari testov nemaju co robit.
    per_stop = {str(PRAHA): slim(seats_praha)}
    for sid in stops:
        time.sleep(0.4)
        try:
            per_stop[str(sid)] = slim(call("/routes/freeSeats", body={"sections": [
                {"sectionId": route_id, "fromStationId": sid, "toStationId": KOSICE}]}))
        except urllib.error.HTTPError as e:
            print("  ! %s preskocene (HTTP %s)" % (sid, e.code))
    write("freeSeats_byStation.json", per_stop)

    # --- layouty voznov --------------------------------------------------
    layouts = {}
    for seats in harvest:
        for v in seats[0]["vehicles"]:
            deck = v["decks"][0]
            url = deck.get("layoutURL")
            if not url:
                continue
            name = url.rsplit("/", 1)[-1]
            if name in layouts:
                continue
            layouts[name] = {"vehicleNumber": v["number"], "deckName": deck.get("name"),
                             "url": url, "seats": sorted(
                                 [s["index"] for s in deck["freeSeats"]] +
                                 [s["index"] for s in deck["occupiedSeats"]])}
            write("layouts/" + name, fetch_text(url))
    write("layouts/index.json", layouts)

    write("meta.json", {"capturedFor": args.date, "routeId": route_id,
                        "sectionId": (detail.get("sections") or [{}])[0].get("id"),
                        "lineCode": line_code, "fromStationId": PRAHA,
                        "toStationId": KOSICE,
                        "departureTime": route.get("departureTime"),
                        "stopStationIds": [PRAHA] + stops})
    print("Hotovo -> %s" % OUT)


if __name__ == "__main__":
    main()
