# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 R. Kravcov

"""Turns the OpenStreetMap snapshot in `raw/` into the CSV sources the packer reads.

Deliberately offline and deterministic: `fetch_osm.py` owns the network, this owns the rules. Run
it again on the same snapshot and you get the same bytes, which is what lets the generated CSVs be
reviewed in a diff rather than taken on trust.

Usage:  python tools/poi/build_poi.py
"""

import csv
import json
import os
import re
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
DATA = os.path.join(HERE, "data")

TYPE_AIRPORT = 0
TYPE_PORT = 1
FLAG_MILITARY = 1
FLAG_HELIPAD = 2

MAX_CODE = 6

# Words that identify the *kind* of place rather than the place, so they are exactly what a short
# code should drop. Multilingual because the names are: "Base navale de Toulon" has to reduce to
# TOULON, and it will not if only the English generics are listed.
GENERIC = {
    # English
    "NAVAL", "NAVY", "BASE", "BASIN", "STATION", "STN", "MILITARY", "MARINE", "MARINA",
    "PORT", "HARBOUR", "HARBOR", "DOCK", "DOCKYARD", "SHIPYARD", "WHARF", "PIER", "QUAY",
    "FLEET", "SQUADRON", "COMMAND", "CENTRE", "CENTER", "FACILITY", "INSTALLATION",
    "COASTGUARD", "GUARD", "DEFENCE", "DEFENSE", "FORCES", "FORCE", "ARMY", "AIR",
    "ACTIVITIES", "ACTIVITY", "SUPPORT", "REGIONAL", "HEADQUARTERS", "HQ", "DISTRICT",
    "SUBMARINE", "AMPHIBIOUS", "WEAPONS", "SUPPLY", "ANNEX", "COMPLEX", "AREA", "YARD",
    "UNITED", "STATES", "US", "USA", "USN", "ROYAL", "NATIONAL", "FEDERAL", "COUNTY",
    "SELF", "MARITIME", "SEA", "COAST", "OPERATIONS", "TRAINING", "SCHOOL", "DEPOT",
    # Other languages, transliterated to bare Latin the same way the names are
    "NAVALE", "NAVALES", "MILITARE", "MILITAIRE", "MARINEBASIS", "MARINESTUTZPUNKT",
    "ESTACION", "ESTACAO", "BASO", "BAZA", "BAZASI", "STAZIONE", "STATIONE", "PUERTO",
    "PORTO", "PUERTOS", "HAFEN", "HAVN", "HAMN", "SATAMA", "AGENCIA", "AGENCIE",
    "COMANDO", "COMANDANCIA", "CUARTEL", "KASERNE", "FLOTTILLE", "FLOTTILJ", "ESCUELA",
    # Articles and connectives
    "DE", "DEL", "DELLA", "DI", "DA", "DAS", "DOS", "LA", "LE", "LES", "EL", "AL",
    "THE", "OF", "AND", "ET", "Y", "VAN", "DER", "DEN", "DU", "AUX", "ZU", "IM",
    "ST",
}


def ascii_upper(text):
    """Latin letters only. Cyrillic, Thai and Arabic come back empty, which is the signal to skip."""
    folded = unicodedata.normalize("NFKD", text)
    stripped = folded.encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^A-Za-z0-9 ]+", " ", stripped).upper()


def code_from_name(name):
    """
    A short, readable label. Returns None when nothing legible survives.

    Takes the *last* meaningful word, not the first. Naming conventions the world over put the
    kind of place first and the place itself last -- "Naval Station Norfolk", "Base navale de
    Toulon", "United States Fleet Activities Sasebo" -- so reading from the front reliably
    produces the least useful part of the name. Taking the front gave NAVALE for Toulon and
    UNITED for Sasebo, which is worse than no label at all: it is a confident wrong answer.
    """
    words = [w for w in ascii_upper(name).split() if w]
    if not words:
        return None
    meaningful = [w for w in words if w not in GENERIC and not w.isdigit() and len(w) > 1]
    if not meaningful:
        meaningful = [w for w in words if len(w) > 1] or words
    code = meaningful[-1][:MAX_CODE]
    # A short tail word leaves room to carry the one before it, which is what keeps
    # "Pearl Harbor" and "Souda Bay" from collapsing into their least specific half.
    if len(code) < 4 and len(meaningful) > 1:
        code = (meaningful[-2] + code)[:MAX_CODE]
    code = re.sub(r"[^A-Z0-9]", "", code)
    return code if len(code) >= 2 else None


def point_of(element):
    lat = element.get("lat")
    lon = element.get("lon")
    if lat is None or lon is None:
        centre = element.get("center") or {}
        lat = centre.get("lat")
        lon = centre.get("lon")
    if lat is None or lon is None:
        return None
    if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
        return None
    return round(float(lat), 5), round(float(lon), 5)


def load(name):
    path = os.path.join(RAW, "osm_%s.json" % name)
    if not os.path.exists(path):
        raise SystemExit("missing %s -- run fetch_osm.py first" % path)
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def best_name(tags):
    return tags.get("name:en") or tags.get("int_name") or tags.get("name") or ""


def build_naval():
    rows, skipped = [], 0
    for element in load("naval"):
        tags = element.get("tags", {})
        point = point_of(element)
        if point is None:
            skipped += 1
            continue
        name = best_name(tags)
        # OSM occasionally carries a description in the name field ("ACV assault hovercraft
        # Kongbang class?"). A question mark or an over-long string is the usual tell.
        if "?" in name or len(name) > 80:
            skipped += 1
            continue
        code = code_from_name(name)
        if code is None:
            # No Latin-script name: a six-character code invented from nothing would be a
            # label nobody could act on, so the site is left out rather than guessed at.
            skipped += 1
            continue
        rows.append((code, point[0], point[1], TYPE_PORT, FLAG_MILITARY))
    return rows, skipped


def build_heliports():
    rows, skipped = [], 0
    for element in load("heliport"):
        tags = element.get("tags", {})
        point = point_of(element)
        if point is None:
            skipped += 1
            continue
        # Only sites carrying a real ICAO code. The rest are overwhelmingly unnamed rooftop and
        # hospital pads, and "H" three hundred metres away is noise, not a readout.
        icao = (tags.get("icao") or "").strip().upper()
        if not re.fullmatch(r"[A-Z0-9]{3,6}", icao):
            skipped += 1
            continue
        flags = FLAG_HELIPAD
        if tags.get("military") or tags.get("operator:type") == "military":
            flags |= FLAG_MILITARY
        rows.append((icao, point[0], point[1], TYPE_AIRPORT, flags))
    return rows, skipped


# Roughly two kilometres of latitude. One base is commonly tagged several times over -- a node
# for the entrance, a way for the land, a relation for the whole -- and inside the 5 km lock those
# are all the same answer.
NEAR_DEGREES = 0.018


def dedupe(rows):
    """Collapses the same code repeated at effectively the same place."""
    kept = []
    for row in sorted(rows):
        code, lat, lon = row[0], row[1], row[2]
        if any(
            other[0] == code
            and abs(other[1] - lat) < NEAR_DEGREES
            and abs(other[2] - lon) < NEAR_DEGREES
            for other in kept[-40:]
        ):
            continue
        kept.append(row)
    return kept


def write(filename, header, rows):
    path = os.path.join(DATA, filename)
    rows = sorted(dedupe(rows))
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(header)
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["code", "lat", "lon", "type", "flags"])
        for code, lat, lon, kind, flags in rows:
            writer.writerow([code, "%.5f" % lat, "%.5f" % lon, kind, flags])
    print("%-18s %5d rows" % (filename, len(rows)))
    return rows


def main():
    os.makedirs(DATA, exist_ok=True)

    naval, naval_skipped = build_naval()
    heli, heli_skipped = build_heliports()

    write(
        "navalbases.csv",
        "# Naval bases and naval harbours. Source: OpenStreetMap (ODbL), via tools/poi/fetch_osm.py.\n"
        "# GENERATED by tools/poi/build_poi.py -- do not hand-edit; edit the generator or refetch.\n"
        "# Codes are derived from the site name, not from UN/LOCODE: OSM carries no code for these.\n"
        "# Sites without a Latin-script name are dropped rather than given an invented label.\n"
        "# Columns: code,lat,lon,type,flags   (type 1 = port, flags 1 = military -> warship glyph)\n",
        naval,
    )
    write(
        "heliports.csv",
        "# Heliports carrying a real ICAO code. Source: OpenStreetMap (ODbL), via fetch_osm.py.\n"
        "# GENERATED by tools/poi/build_poi.py -- do not hand-edit; edit the generator or refetch.\n"
        "# Unnamed rooftop and hospital pads are deliberately excluded: a pictogram with no usable\n"
        "# identifier beside it tells the wearer nothing.\n"
        "# Columns: code,lat,lon,type,flags   (type 0 = airfield, flags 2 = helipad, +1 = military)\n",
        heli,
    )
    print("skipped: %d naval (no point or no Latin name), %d heliports (no ICAO)"
          % (naval_skipped, heli_skipped), file=sys.stderr)


if __name__ == "__main__":
    main()
