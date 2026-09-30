# Chart pack format

The app doesn't know about any particular chart data. Everything it draws, and everything it
does with chart features (tap info, shallow-water alarm, layer toggles), comes from a
**chart pack**: a folder with a `chartpack.json` next to its tiles.

```
hauraki/
  chartpack.json
  hauraki_gulf_chart_bg.pmtiles
  soundings.pmtiles
  marks.pmtiles
  channels.pmtiles
  sprites/marks.json  marks.png  marks@2x.json  marks@2x.png
  fonts/…            (optional, for fully offline labels)
```

`tools/make_chartpack.py` writes this file for the LINZ build outputs;
`tools/example/chartpack.json` is a generated example.

## Getting packs onto the device

- **Charts → Add → Chart pack (.zip)**: a zip of the folder (the folder itself, or its contents, at the zip root).
- **Charts → Add → Chart pack folder**: pick the folder on the device.
- **Copy directly**: put the folder in the app's charts folder (path shown in Settings → About), e.g. with
  `adb push hauraki /sdcard/Android/data/com.joshuamorley.nzlinz/files/charts/`.
- **Charts → Add → Remote URL**: a `chartpack.json` URL. Relative paths in it resolve against that URL.
  The config is cached, so the pack still styles offline (tiles still need the network).

One pack per region is the intended layout. Packs can reuse the same source, layer, group and
sprite names: the app namespaces everything per pack, and merges groups with the same `id` into
one toggle.

A bare `.pmtiles` file with no config still works: the app styles it automatically from its
metadata.

## `chartpack.json`

```jsonc
{
  "format": 1,                       // required version; the app refuses newer formats
  "id": "hauraki",                   // short id, [A-Za-z0-9_-]
  "title": "Hauraki Gulf and Waitemata",
  "bounds": [174.55, -37.25, 175.95, -35.75],   // optional; used for "Go to"
  "attribution": "Contains data sourced from the LINZ Data Service … Not for navigation.",
  "glyphs": "fonts/{fontstack}/{range}.pbf",     // optional; the first pack that sets it wins
  "sprites": { "marks": "sprites/marks" },       // id -> path without extension

  "groups": [                        // layer toggles in the Charts panel
    { "id": "soundings", "title": "Soundings", "visible": true }
  ],

  "sources": {                       // MapLibre sources; relative paths resolve against the pack
    "soundings": { "type": "vector", "url": "soundings.pmtiles" },
    "chart":     { "type": "raster", "url": "hauraki_gulf_chart_bg.pmtiles", "tileSize": 512 },
    "osm":       { "type": "raster", "tiles": ["https://…/{z}/{x}/{y}.png"], "tileSize": 256 }
  },

  "layers": [ /* MapLibre style layers, plus the extra keys below */ ],
  "alarms": [ /* shallow-water rules */ ],
  "inspect": [ /* tap-info rules */ ],
  "nearest": [ /* nearest-feature lookups, e.g. nearest sounding */ ]
}
```

### Paths

| Written as | Loaded as |
|---|---|
| `soundings.pmtiles` | `pmtiles://file://<pack>/soundings.pmtiles` |
| `https://host/x.pmtiles` | `pmtiles://https://host/x.pmtiles` |
| `sprites/marks` | `file://<pack>/sprites/marks` (+ `.json`/`.png`, `@2x` on dense screens) |
| `https://…`, `file://…`, `asset://…` | unchanged |

### Layers

Standard [MapLibre style layers](https://maplibre.org/maplibre-style-spec/layers/) with these extras:

| Key | Meaning |
|---|---|
| `source` | a key from this pack's `sources` |
| `sourceLayer` | same as `source-layer` (either works) |
| `group` | group id; the layer follows that toggle |
| `sprite` | sprite id whose icons this layer's `icon-image` names. Defaults to the only sprite when the pack has one. Write plain icon names, e.g. `["get", "icon"]`; the app adds the prefix. |
| `z` | optional draw-order override (number) |

Layers from all packs are drawn in this order: rasters (0), fills (1), lines (2), circles (3),
symbols (4). Within a rank, pack and layer order is kept. `background` layers are ignored,
because they would cover other packs.

Fonts: the default glyph server has `Noto Sans Regular`. Glyphs are cached after the first
online use. For fully offline labels, ship `fonts/{fontstack}/{range}.pbf` in the pack and set
`"glyphs"`.

### Alarms

Each rule marks some features as dangerous when their depth is less than the boat's
**draft + safety margin** (Settings). The app checks the tiles currently loaded:

- **Now:** a danger within the alarm distance of the boat, or an area the boat is inside.
- **Ahead:** a danger within the alarm distance of the course line, up to the look-ahead time at current speed.

```jsonc
{
  "source": "soundings",           // pack source key (vector)
  "sourceLayer": "soundings",
  "geometry": "point",             // informational; points use distance, polygons use inside-test
  "depthField": "depth",           // metres, positive down. Omit to treat every matching feature as a danger
  "depthDecimalField": "d_dec",    // optional tenths field: d_int="7", d_dec="2" -> 7.2
  "filter": ["in", ["get", "kind"], ["literal", ["rock", "wreck"]]],   // optional MapLibre expression
  "dangerWhenNoDepth": true,       // missing/blank depth counts as a danger
  "label": "{kind}"                // warning text; {field} placeholders allowed
}
```

### Inspect

Controls what the tap panel shows for features from the listed layers. Layers with no rule
show all their properties.

```jsonc
{
  "layers": ["marks", "lights"],       // pack-local layer ids; [] or omitted = every layer
  "title": ["{name}", "{kind}"],       // first template that isn't blank
  "subtitle": "{label_full}",
  "fields": { "kind": "Type", "label": "Light" },   // field -> label, in order; {} = all
  "hide": ["sort", "d_int", "d_dec"]   // used when fields is empty
}
```

### Nearest

Finds the nearest point feature to a tapped spot (shown in the "Features here" panel, with a
line to it on the map) and to the boat (the DEPTH instrument). It reads the `.pmtiles` archive
directly at its highest zoom, so it isn't affected by label thinning or by what's on screen.

```jsonc
{
  "source": "soundings",        // pack source key; must be a .pmtiles file or URL
  "sourceLayer": "soundings",
  "title": "Nearest sounding",
  "text": "{depth} m",          // optional; defaults to the depth
  "depthField": "depth",        // numeric metres; used by the DEPTH instrument
  "maxDistanceM": 2000          // ignore anything further away
}
```

Packs without `nearest` fall back to their unfiltered point alarm with a numeric depth
(the soundings rule), so older packs get a nearest sounding too.

## Notes for the LINZ build scripts

- `make_marks.py` stores the depth over rocks and wrecks only as the chart-style strings
  `d_int`/`d_dec`, using the absolute value. So a drying height (for example -1.2 m) reads as
  1.2 m. The alarm rule treats blank depths as dangers, but adding a numeric signed `depth` field
  to marks would make the alarm exact. Then use `"depthField": "depth"`.
- The original `cables` layer used a data-driven `line-dasharray`, which MapLibre Native
  doesn't support, so the generated pack draws cables solid.
- Soundings carry a numeric `depth` (negative = drying), which the alarm uses directly.
