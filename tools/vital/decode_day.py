# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 R. Kravcov
"""Turn a raw day packet off the watch back into a CSV.

The packet arrives either through a camera (scan the QR on EXPORT RAW and paste the text) or
through a microphone (``minimodem --rx 1200`` next to the speaker, which prints the same text).
Either way it is four lines, the last of which is Base64 of a deflated grid:

    MFD24 VITAL RAW 1
    SIERRA-07 1a2b3c4d
    DAYS 20323 20324
    eJx1k...

Usage:
    python tools/vital/decode_day.py packet.txt > day.csv
    minimodem --rx 1200 | python tools/vital/decode_day.py - > day.csv

The CSV is one row per quarter-hour, exactly what the watch recorded: no sleep runs, no resting
rate, nothing inferred. That is the point — the inference is what is usually under suspicion, so
it gets re-run on this side rather than shipped with the data.
"""

import base64
import csv
import datetime
import sys
import zlib

HEADER = "MFD24 VITAL RAW 1"
BIN_COUNT = 96
BIN_MINUTES = 15
DAY_BYTES = 4 + BIN_COUNT * 4

FLAGS = [
    (0x01, "SAMPLED"),
    (0x02, "ON_BODY"),
    (0x04, "CHARGING"),
    (0x08, "MOVING"),
    (0x10, "SLEEP"),
]


def parse(text):
    lines = [line.strip() for line in text.strip().splitlines() if line.strip()]
    if not lines or lines[0] != HEADER:
        raise SystemExit("not a raw day packet: first line is %r" % (lines[0] if lines else ""))
    raw = zlib.decompress(base64.b64decode(lines[3]))
    if len(raw) % DAY_BYTES:
        raise SystemExit("payload of %d bytes is not a whole number of days" % len(raw))
    days = []
    for d in range(len(raw) // DAY_BYTES):
        base = d * DAY_BYTES
        epoch_day = int.from_bytes(raw[base:base + 4], "big")
        bins = []
        for i in range(BIN_COUNT):
            p = base + 4 + i * 4
            bins.append((raw[p], raw[p + 1], int.from_bytes(raw[p + 2:p + 4], "big")))
        days.append((epoch_day, bins))
    return lines[1], days


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else "-"
    text = sys.stdin.read() if source == "-" else open(source, encoding="utf-8").read()
    callsign, days = parse(text)
    print("# %s" % callsign, file=sys.stderr)

    out = csv.writer(sys.stdout, lineterminator="\n")
    out.writerow(["date", "local_time", "bpm", "steps", "flags"])
    for epoch_day, bins in days:
        date = datetime.date(1970, 1, 1) + datetime.timedelta(days=epoch_day)
        for i, (flags, bpm, steps) in enumerate(bins):
            minutes = i * BIN_MINUTES
            names = "|".join(name for bit, name in FLAGS if flags & bit) or "-"
            out.writerow([
                date.isoformat(),
                "%02d:%02d" % (minutes // 60, minutes % 60),
                bpm if bpm else "",
                steps,
                names,
            ])


if __name__ == "__main__":
    main()
