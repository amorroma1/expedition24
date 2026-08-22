# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 R. Kravcov

"""Pulls naval bases and heliports from OpenStreetMap into raw JSON for `build_poi.py`.

Kept separate from the CSV generator on purpose: this is the half that needs the network and is
therefore the half that is slow, rate-limited and occasionally down. It writes its results to
`tools/poi/raw/` so the generator, and the build, can run offline against a snapshot.

Usage:  python tools/poi/fetch_osm.py [--out DIR]

The Overpass instance is a free public service. The queries below are global, which is only
defensible because both feature classes are rare — a few thousand objects each. Nothing here
should be run in a loop.
"""

import argparse
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

# `out center` gives ways and relations a single representative point, which is all a 5 km
# proximity lock needs and a great deal smaller than the geometry.
QUERIES = {
    # `military=naval_base` alone is far too narrow: most of the world's significant bases are
    # tagged as generic military land and identified only by their name. Norfolk, San Diego,
    # Devonport and Pearl Harbor are all missing without the name-matched arms below.
    "naval": """
[out:json][timeout:600];
(
  nwr["military"="naval_base"];
  nwr["seamark:type"="harbour"]["seamark:harbour:category"="naval"];
  nwr["military"="base"][name~"naval|navy|navale|marina militare|marinestutzpunkt|nav(sta|base)",i];
  nwr["landuse"="military"][name~"naval|navy|navale|navsta|navbase|fleet|submarine",i];
  nwr["military"="harbour"];
);
out center tags;
""",
    "heliport": """
[out:json][timeout:300];
(
  nwr["aeroway"="heliport"];
  nwr["aeroway"="helipad"]["icao"];
  nwr["aeroway"="helipad"]["military"];
  nwr["aeroway"="helipad"]["name"]["operator:type"="military"];
);
out center tags;
""",
}


def fetch(query, attempts=3):
    ctx = ssl.create_default_context()
    try:
        import certifi

        ctx = ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        pass

    last = None
    for attempt in range(attempts):
        for endpoint in ENDPOINTS:
            data = urllib.parse.urlencode({"data": query}).encode()
            req = urllib.request.Request(
                endpoint,
                data=data,
                headers={"User-Agent": "mfd24-poi/1.0 (watch face POI index)"},
            )
            try:
                with urllib.request.urlopen(req, timeout=360, context=ctx) as response:
                    return json.loads(response.read().decode("utf-8"))
            except (urllib.error.URLError, TimeoutError, ValueError) as exc:
                last = exc
                print("  %s failed: %s" % (endpoint, exc), file=sys.stderr)
        # Overpass rate-limits hard; backing off is the difference between slow and banned.
        wait = 30 * (attempt + 1)
        print("  retrying in %ds" % wait, file=sys.stderr)
        time.sleep(wait)
    raise SystemExit("all Overpass endpoints failed: %s" % last)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=os.path.join(here, "raw"))
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    for name, query in QUERIES.items():
        print("fetching %s ..." % name)
        payload = fetch(query)
        elements = payload.get("elements", [])
        path = os.path.join(args.out, "osm_%s.json" % name)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(elements, handle, ensure_ascii=False)
        print("  %d elements -> %s" % (len(elements), path))


if __name__ == "__main__":
    main()
