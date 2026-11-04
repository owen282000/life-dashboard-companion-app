#!/usr/bin/env python3
"""Finds the rows and cards panorama.html crops, from what scripts/store-screenshots.sh saved next
to each capture, and writes them to store-assets/raw/rects.js. A heading that wraps moves everything
under it, per language and per screen. Run by render.sh from the repository root."""
import json
import os
import re
import xml.etree.ElementTree as ET

RAW = "store-assets/raw"
LANGUAGES = ["en", "nl", "de", "fr", "es", "it", "pt-BR", "ja", "ar", "hi", "zh-CN"]
WIDTH = 1080


def nodes(language, name):
    path = os.path.join(RAW, "" if language == "en" else language, name + ".xml")
    result = []
    for node in ET.parse(path).iter("node"):
        x0, y0, x1, y1 = map(int, re.findall(r"\d+", node.get("bounds")))
        result.append({"x0": x0, "y0": y0, "x1": x1, "y1": y1, "clickable": node.get("clickable") == "true",
                       "text": node.get("text") or "", "desc": node.get("content-desc") or "",
                       "kind": node.get("class").rsplit(".", 1)[-1]})
    return result


def clickable(items):
    return [n for n in items if n["clickable"]]


def texts(items):
    return [n for n in items if n["text"]]


def rect(x0, y0, x1, y1):
    return [x0, y0, x1 - x0, y1 - y0]


def union(items, pad=0):
    return rect(min(n["x0"] for n in items) - pad, min(n["y0"] for n in items) - pad,
                max(n["x1"] for n in items) + pad, max(n["y1"] for n in items) + pad)


def measure(language):
    home = clickable(nodes(language, "home"))
    dests = [n for n in home if n["x1"] - n["x0"] == WIDTH][:3]

    # Most used today: from the label under the line of minutes per day to the tiles.
    screen = nodes(language, "screen-time")
    line = next(n for n in screen if n["desc"] and n["x1"] - n["x0"] > 900 and not n["clickable"])
    label = next(n for n in screen if n["text"] and n["y0"] >= line["y1"])
    tiles_top = next(n["y0"] for n in screen if n["clickable"] and n["y0"] > label["y1"])
    used = rect(30, label["y0"] - 16, WIDTH - 30, tiles_top - 10)

    webhook = nodes(language, "webhook")
    fields = [n for n in webhook if n["kind"] == "EditText"]
    copy = next(n for n in clickable(webhook) if n["y0"] >= fields[1]["y1"] - 4)
    signing_top = max(n["y1"] for n in texts(webhook) if n["y1"] <= fields[1]["y0"] and n["kind"] != "EditText")
    note = next(n for n in texts(webhook) if n["y0"] >= copy["y1"] and n["kind"] == "TextView" and n["y0"] > copy["y0"] + 20)
    signing = rect(30, signing_top + 6, WIDTH - 30, note["y1"] + 10)

    delivery = nodes(language, "delivery")
    code = max((n for n in delivery if n["x1"] - n["x0"] == WIDTH and n["y0"] > 300), key=lambda n: n["y1"] - n["y0"])
    # A fixed height from the top of the JSON, on a whole number of its lines (46 pixels each).
    payload = rect(37, code["y0"] + 4, WIDTH - 37, code["y0"] + 4 + min(code["y1"] - code["y0"] - 8, 46 * 15 + 28))

    what = clickable(nodes(language, "what"))
    chip = what[1]
    essentials = rect(chip["x0"], chip["y0"] + 3, chip["x1"], chip["y1"] - 3)
    goes_out = union(what[10:12], pad=0)

    category = clickable(nodes(language, "category"))
    switches = union(category[1:5])

    logs = [n for n in nodes(language, "logs") if n["kind"] == "Button" and n["x1"] - n["x0"] == WIDTH]
    log_rows = union(logs[:3])

    backfill = clickable(nodes(language, "backfill"))
    lengths = union(backfill[1:6])
    run = nodes(language, "backfill-run")
    heading = texts(run)[1]
    stop = clickable(run)[1]
    progress = rect(0, heading["y0"] - 20, WIDTH, stop["y0"] - 6)

    paired = nodes(language, "paired")
    connected = texts(paired)[1]
    after = texts(paired)[2]
    connected_rect = rect(0, connected["y0"] - 20, WIDTH, after["y0"] - 4)

    about = clickable(nodes(language, "about"))
    rows = about[1:4]
    split_top, split_bottom = rows[0]["y0"], rows[-1]["y1"]
    privacy, source = about[-2], about[-1]

    return {"dests": union(dests), "used": used, "signing": signing, "payload": payload, "essentials": essentials,
            "goesOut": goes_out, "switches": switches, "logRows": log_rows, "lengths": lengths, "progress": progress,
            "connected": connected_rect, "split": rect(0, split_top, WIDTH, split_bottom), "splitY": (split_top + split_bottom) // 2,
            "privacy": union([privacy]), "source": union([source])}


lines = ["// Written by measure.py from the captures in store-assets/raw/: [x, y, w, h] in capture pixels.", "const MEASURED = {"]
for language in LANGUAGES:
    folder = os.path.join(RAW, "" if language == "en" else language)
    if os.path.exists(os.path.join(folder, "home.xml")):
        lines.append(f"  {json.dumps(language)}: {json.dumps(measure(language))},")
lines.append("};\n")
with open(os.path.join(RAW, "rects.js"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
