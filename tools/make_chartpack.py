#!/usr/bin/env python3
"""
Write chartpack.json for a folder of chart build outputs, so the app can load it.

The folder is expected to hold what build_all.sh / build_regions.sh produce:
  hauraki_gulf_chart_bg.pmtiles   raster background (depth tints, contours, land)
  soundings.pmtiles               vector, source-layer "soundings"
  marks.pmtiles                   vector, source-layer "marks"
  channels.pmtiles                vector, source-layer "channels"
  sprites/marks.json .png (+ @2x) icons for marks
Missing files are left out of the pack.

Usage:
  python3 make_chartpack.py regions/hauraki --region hauraki --regions-file regions.json --zip
  python3 make_chartpack.py dist/assets/Map --id nz --title "NZ charts" --zip

--zip writes <folder>.zip next to the folder; import that zip in the app (Charts > Add),
or copy the folder into the app's charts folder (shown in Settings).
Source data: LINZ, CC BY 4.0. NOT FOR NAVIGATION.
"""
import argparse
import json
import os
import zipfile

ATTRIBUTION = ("Contains data sourced from the LINZ Data Service licensed for reuse "
               "under CC BY 4.0. Not for navigation.")
FONT = ["Noto Sans Regular"]
HALO = "rgba(255,255,255,0.8)"
LIMIT_KINDS = ["cable_area", "pipeline_area", "restricted", "anchorage", "dumping",
               "marine_farm", "military", "caution"]


def background_layers():
    return [{
        "id": "chart", "type": "raster", "source": "chart", "group": "background",
        "paint": {"raster-opacity": 1, "raster-resampling": "linear", "raster-fade-duration": 0},
    }]


def channel_layers():
    def L(d):
        d.update({"source": "channels", "sourceLayer": "channels", "group": "channels"})
        return d
    return [
        L({"id": "limit-areas", "type": "line",
           "filter": ["in", ["get", "kind"], ["literal", LIMIT_KINDS]],
           "paint": {"line-color": "#c800ff", "line-width": 1.2, "line-dasharray": [5, 2, 1, 2]}}),
        L({"id": "cables", "type": "line",
           "filter": ["in", ["get", "kind"], ["literal", ["cable", "pipeline"]]],
           "paint": {"line-color": "#c800ff", "line-width": 1, "line-opacity": 0.8}}),
        L({"id": "dredged-fill", "type": "fill",
           "filter": ["==", ["get", "kind"], "dredged"],
           "paint": {"fill-color": "#8a8a8a", "fill-opacity": 0.12}}),
        L({"id": "dredged-line", "type": "line",
           "filter": ["==", ["get", "kind"], "dredged"],
           "paint": {"line-color": "#555555", "line-width": 1, "line-dasharray": [4, 2]}}),
        L({"id": "tss", "type": "fill",
           "filter": ["in", ["get", "kind"], ["literal", ["tss_lane", "tss_zone", "precautionary"]]],
           "paint": {"fill-color": "#c800ff", "fill-opacity": 0.08, "fill-outline-color": "#c800ff"}}),
        L({"id": "fairway", "type": "line",
           "filter": ["in", ["get", "kind"], ["literal", ["fairway", "tss_boundary", "deepwater"]]],
           "paint": {"line-color": "#c800ff", "line-width": 1.2, "line-dasharray": [6, 3]}}),
        L({"id": "ferry", "type": "line",
           "filter": ["==", ["get", "kind"], "ferry"],
           "paint": {"line-color": "#c800ff", "line-width": 1.5, "line-dasharray": [2, 2]}}),
        L({"id": "tracks", "type": "line",
           "filter": ["in", ["get", "kind"], ["literal", ["track", "route"]]],
           "paint": {"line-color": "#101010", "line-width": 1.2}}),
        L({"id": "navlines", "type": "line",
           "filter": ["==", ["get", "kind"], "navline"],
           "paint": {"line-color": "#101010", "line-width": 1, "line-dasharray": [5, 3]}}),
        L({"id": "line-labels", "type": "symbol",
           "filter": ["all", ["!=", ["get", "label"], ""],
                      ["in", ["get", "kind"], ["literal", ["ferry", "track", "route", "navline", "fairway"]]]],
           "layout": {"symbol-placement": "line", "symbol-spacing": 400,
                      "text-field": ["step", ["zoom"], "", 12, ["get", "label"]],
                      "text-font": FONT, "text-size": 10,
                      "text-keep-upright": True, "text-offset": [0, -0.7]},
           "paint": {"text-color": ["match", ["get", "kind"], ["ferry", "fairway"], "#a000cc", "#101010"],
                     "text-halo-color": HALO, "text-halo-width": 1}}),
        L({"id": "limit-labels", "type": "symbol",
           "filter": ["all", ["==", ["get", "label_point"], True],
                      ["in", ["get", "kind"], ["literal", LIMIT_KINDS]]],
           "layout": {"text-field": ["step", ["zoom"], "", 13, ["get", "label"]],
                      "text-font": FONT, "text-size": 11, "text-max-width": 10,
                      "text-rotation-alignment": "viewport", "text-optional": True},
           "paint": {"text-color": "#c800ff", "text-halo-color": HALO, "text-halo-width": 1}}),
        L({"id": "dredged-labels", "type": "symbol",
           "filter": ["all", ["==", ["get", "label_point"], True], ["==", ["get", "kind"], "dredged"]],
           "layout": {"text-field": ["step", ["zoom"], "", 14, ["get", "label"]],
                      "text-font": FONT, "text-size": 10, "text-rotation-alignment": "viewport"},
           "paint": {"text-color": "#555555", "text-halo-color": HALO, "text-halo-width": 1}}),
    ]


def sounding_layers():
    def L(d):
        d.update({"source": "soundings", "sourceLayer": "soundings", "group": "soundings"})
        return d
    text = {
        "text-field": ["format", ["get", "d_int"], {}, ["get", "d_dec"], {"font-scale": 0.72}],
        "text-font": FONT,
        "text-size": ["interpolate", ["linear"], ["zoom"], 11, 10, 14, 12, 17, 14],
        "text-rotation-alignment": "viewport", "text-pitch-alignment": "viewport",
        "text-allow-overlap": False, "text-padding": 1, "symbol-sort-key": ["get", "sort"],
    }
    return [
        L({"id": "depths", "type": "symbol", "filter": ["!", ["get", "dry"]],
           "layout": dict(text), "paint": {"text-color": "#101010"}}),
        L({"id": "drying", "type": "symbol", "filter": ["get", "dry"],
           "layout": dict(text), "paint": {"text-color": "#1f5e2e"}}),
    ]


def mark_layers():
    def L(d):
        d.update({"source": "marks", "sourceLayer": "marks", "group": "marks"})
        return d
    size = ["interpolate", ["linear"], ["zoom"], 10, 0.6, 14, 0.9, 17, 1.1]
    return [
        L({"id": "marks", "type": "symbol", "sprite": "marks",
           "filter": ["!=", ["get", "kind"], "light"],
           "layout": {"icon-image": ["get", "icon"], "icon-size": size,
                      "icon-anchor": ["match", ["get", "kind"], ["buoy", "beacon"], "bottom", "center"],
                      "icon-rotation-alignment": "viewport", "icon-allow-overlap": True,
                      "symbol-sort-key": ["get", "sort"]}}),
        L({"id": "lights", "type": "symbol", "sprite": "marks",
           "filter": ["==", ["get", "kind"], "light"],
           "layout": {"icon-image": ["get", "icon"], "icon-size": size,
                      "icon-rotation-alignment": "viewport", "icon-allow-overlap": True,
                      "text-field": ["step", ["zoom"], "", 12, ["get", "label"], 15, ["get", "label_full"]],
                      "text-font": FONT, "text-size": 10,
                      "text-anchor": "left", "text-offset": [1.1, 0.4], "text-optional": True,
                      "text-rotation-alignment": "viewport"},
           "paint": {"text-color": "#101010", "text-halo-color": HALO, "text-halo-width": 1}}),
        L({"id": "danger-depths", "type": "symbol",
           "filter": ["!=", ["get", "d_int"], ""],
           "layout": {"text-field": ["step", ["zoom"], "", 13,
                                     ["format", ["get", "d_int"], {}, ["get", "d_dec"], {"font-scale": 0.72}]],
                      "text-font": FONT, "text-size": 10,
                      "text-offset": [0, 1.2], "text-rotation-alignment": "viewport"},
           "paint": {"text-color": "#101010"}}),
        L({"id": "names", "type": "symbol",
           "filter": ["!=", ["get", "name"], ""],
           "layout": {"text-field": ["step", ["zoom"], "", 14, ["get", "name"]],
                      "text-font": FONT, "text-size": 10,
                      "text-anchor": "right", "text-offset": [-1.1, 0], "text-optional": True,
                      "text-rotation-alignment": "viewport"},
           "paint": {"text-color": "#333333", "text-halo-color": HALO, "text-halo-width": 1}}),
    ]


def build(folder, pack_id, title, bbox):
    has = lambda p: os.path.isfile(os.path.join(folder, p))
    sources, layers, groups, alarms, inspect, nearest = {}, [], [], [], [], []
    sprites = {}

    if has("hauraki_gulf_chart_bg.pmtiles"):
        sources["chart"] = {"type": "raster", "url": "hauraki_gulf_chart_bg.pmtiles", "tileSize": 512}
        layers += background_layers()
        groups.append({"id": "background", "title": "Chart"})

    if has("channels.pmtiles"):
        sources["channels"] = {"type": "vector", "url": "channels.pmtiles"}
        layers += channel_layers()
        groups.append({"id": "channels", "title": "Channels & areas"})
        inspect.append({"layers": [l["id"] for l in channel_layers()],
                        "title": ["{label}", "{name}", "{kind}"],
                        "fields": {"kind": "Type", "name": "Name", "orient": "Direction"}})

    if has("soundings.pmtiles"):
        sources["soundings"] = {"type": "vector", "url": "soundings.pmtiles"}
        layers += sounding_layers()
        groups.append({"id": "soundings", "title": "Soundings"})
        alarms.append({"source": "soundings", "sourceLayer": "soundings", "geometry": "point",
                       "depthField": "depth", "label": "sounding"})
        # Nearest sounding to a tapped spot / the boat, read at the archive's max zoom.
        nearest.append({"source": "soundings", "sourceLayer": "soundings", "title": "Nearest sounding",
                        "text": "{depth} m", "depthField": "depth", "maxDistanceM": 2000})
        inspect.append({"layers": ["depths", "drying"], "title": "Sounding {depth} m",
                        "fields": {}, "hide": ["d_int", "d_dec", "sort"]})

    if has("marks.pmtiles"):
        sources["marks"] = {"type": "vector", "url": "marks.pmtiles"}
        layers += mark_layers()
        groups.append({"id": "marks", "title": "Marks & lights"})
        if has("sprites/marks.json"):
            sprites["marks"] = "sprites/marks"
        # Rocks, wrecks and obstructions: d_int/d_dec is the charted depth over them.
        # Ones with no depth (drying/awash rocks) always count as dangers.
        alarms.append({"source": "marks", "sourceLayer": "marks", "geometry": "point",
                       "depthField": "d_int", "depthDecimalField": "d_dec",
                       "filter": ["in", ["get", "kind"], ["literal", ["rock", "wreck", "obstruction"]]],
                       "dangerWhenNoDepth": True, "label": "{kind}"})
        inspect.append({"layers": [l["id"] for l in mark_layers()],
                        "title": ["{name}", "{kind}"],
                        "subtitle": "{label_full}",
                        "fields": {"kind": "Type", "icon": "Symbol", "label": "Light"},
                        "hide": ["sort", "d_int", "d_dec"]})

    if not sources:
        raise SystemExit(f"No chart files found in {folder}")

    pack = {
        "format": 1,
        "id": pack_id,
        "title": title,
        "attribution": ATTRIBUTION,
        "groups": groups,
        "sources": sources,
        "layers": layers,
        "alarms": alarms,
        "inspect": inspect,
        "nearest": nearest,
    }
    if bbox:
        pack["bounds"] = bbox
    if sprites:
        pack["sprites"] = sprites
    return pack


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder")
    ap.add_argument("--region", help="name in regions.json (sets id, title and bounds)")
    ap.add_argument("--regions-file", default="regions.json")
    ap.add_argument("--id")
    ap.add_argument("--title")
    ap.add_argument("--bbox", help="min_lon,min_lat,max_lon,max_lat")
    ap.add_argument("--zip", action="store_true", help="also write <folder>.zip")
    a = ap.parse_args()

    pack_id, title, bbox = a.id, a.title, None
    if a.bbox:
        bbox = [float(v) for v in a.bbox.split(",")]
    if a.region:
        with open(a.regions_file) as fh:
            regions = {r["name"]: r for r in json.load(fh)["regions"]}
        if a.region not in regions:
            raise SystemExit(f"Unknown region {a.region!r}")
        r = regions[a.region]
        pack_id = pack_id or r["name"]
        title = title or r.get("title", r["name"])
        bbox = bbox or r["bbox"]
    folder = a.folder.rstrip("/\\")
    pack_id = pack_id or os.path.basename(os.path.abspath(folder))
    title = title or pack_id

    pack = build(folder, pack_id, title, bbox)
    out = os.path.join(folder, "chartpack.json")
    with open(out, "w") as fh:
        json.dump(pack, fh, indent=1)
    print(f">> wrote {out}  ({len(pack['layers'])} layers, groups: "
          f"{', '.join(g['id'] for g in pack['groups'])})")

    if a.zip:
        zpath = os.path.abspath(folder) + ".zip"
        keep = {"chartpack.json"} | {s["url"] for s in pack["sources"].values() if "url" in s}
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:  # PMTiles are already compressed
            for root, _, files in os.walk(folder):
                for f in files:
                    full = os.path.join(root, f)
                    rel = os.path.relpath(full, folder).replace(os.sep, "/")
                    if rel in keep or rel.startswith("sprites/") or rel.startswith("fonts/"):
                        z.write(full, f"{pack_id}/{rel}")
        print(f">> wrote {zpath}  ({os.path.getsize(zpath) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
