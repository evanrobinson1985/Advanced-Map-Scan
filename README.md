# Advanced Map Scan: LiDAR Karst Archaeology GIS Platform

A single-file web app (`index.html`) plus a batch Python pipeline (`pipeline/karst_pipeline.py`) for finding likely prehistoric habitation, hunting and ceremonial sites in forested karst terrain (Cumberland Plateau / Northeast Alabama). They run the terrain-analysis workflow in *Geospatial Archaeology: Comprehensive GIS Workflows for Karst Terrain Analysis* on 1-meter bare-earth LiDAR.

Open `index.html` in a browser (or serve it with GitHub Pages). No build step is needed.

Terrain is drawn with bicubic height interpolation and exact per-pixel lighting from a level-of-detail texture pyramid, so it stays smooth at any zoom and large downloads render in full. Faceted, "low-poly" terrain in the data itself is removed from the map view by *Terrain Smoothing* (display only; scans always use the raw DTM). It covers two cases: coarse source data upsampled into a finer download, which the app detects and flags, and flat triangle facets left in LiDAR DEMs where sparse ground returns under forest canopy were triangulated. Smoothing is off by default (raw data); choose *Auto* or *Strong* to turn it on. It is feature-preserving, so it blends facets but keeps real breaks such as bluff edges sharp.

## How the workflow maps to the app

| Workflow phase | Where it lives |
|---|---|
| **Phase 1: Bare-earth LiDAR DTM** | *1. Data Pipeline* downloads the USGS 3DEP bare-earth DTM for the **entire map view**. Tiles are fetched in parallel and stitched into one seamless grid. *Auto* resolution gives full 1 m LiDAR whenever the view fits the memory budget, and otherwise the finest resolution that fits. You can also load your own DTM GeoTIFF (WGS84). Scans are blocked below the **1 m minimum** unless you untick *Require ≤1 m/px DTM*. On downloads over 16 Mpx, scans run on the part in view. |
| **Phase 2: Long-term habitation (base camps)** | Scan mode *Phase 2: Long-Term Habitation*. **Step 1:** TPI (10–50 m radius, extreme ± values) isolates bluff crests and bases. **Step 2:** aspect filter keeps S/SE bluff faces (135°–225°). **Step 3:** a Local Relief Model at the bluff bases finds flat dirt benches and flags positive mounding under the drip line as a possible midden. **Step 4:** keeps benches inside a 100–300 m buffer around year-round water and drops any in the 100-year floodplain. The floodplain is estimated from Height Above Nearest Drainage (HAND); a FEMA flood-hazard overlay is available for checking by eye. |
| **Phase 3: Transient hunt camps** | Scan mode *Phase 3: Transient Hunt Camps*. **Step 1:** no aspect restriction, and a smaller TPI radius picks out minor rock outcrops. **Step 2:** choke points: saddles and ridgeline gaps (Hessian saddle test) and narrow canyon funnels (a drainage line with steep walls on both sides). **Step 3:** a viewshed from each outcrop, reporting how much valley floor and shallow stream crossing it can see. A *Show viewshed on map* button draws it. |
| **Phase 4: Deep ceremonial karst** | Scan mode *Phase 4: Deep Ceremonial Karst*. **Step 1:** Fill Sink / closed-depression analysis, flagging large, deep sinks as possible collapsed karst windows. **Step 2:** sinking streams, meaning surface channels that end in a sink, with the drainage area they swallow. **Step 3:** speleological overlay. Load a cave survey (GeoJSON or CSV) and candidates are cross-referenced; a sink counts as verified when it opens into a mapped system long enough to have a Dark Zone. Survey data stays in memory only. |
| **Phase 5: Automation and WebGL visualization** | The in-browser scans automate the raster maths. Export candidates as **GeoJSON** or Shapefile, and the analysis raster as a **GeoTIFF** (EPSG:4326). **Open 3D WebGL Fly-Through** renders the DTM in 3D with candidates in red and pipeline context in amber (south-facing bluff faces, choke points or sinking streams). `pipeline/karst_pipeline.py` runs the same phases in batch with GDAL/Rasterio, and its outputs load back into the app. |

**Scan All.** Runs every scan mode on the area, one after another: the three karst phases, caves, mounds, rockshelters and settlement likelihood. Each mode uses its own thresholds. All detections stay on the map with a coloured, lettered marker per mode, and a legend lets you show or hide each type. They also appear in the 3D fly-through in the same colours, and every export includes them all.

**GPX export.** Every detected feature, of every type, becomes a waypoint in a GPX file saved under a name you choose. Desktop Chrome/Edge open a Save dialog so you can pick the folder; other browsers download under that name. Types are told apart by:

- **Symbol:** a standard Garmin-style `<sym>` (Campground, Hunting Area, Tunnel, Mine, Pin Blue, Summit, Block Red, Residence);
- **Type:** a `<type>` field;
- **Name:** a lettered prefix such as `E03 Cave entrance setting`;
- **Colour:** an OsmAnd colour.

Each waypoint also carries its elevation, match score, depth or height, and size.

**Auto-detected scan settings.** After each download (or when you click *Auto-Detect Best Settings for This Area*), the app measures the area to be scanned and sets the scan sliders from what it finds:

- **LiDAR noise:** minimum depth and relief thresholds, and the midden flag.
- **Slope distribution:** bluff, cliff and outcrop slopes.
- **Typical bluff height:** minimum bluff height, TPI and LRM radii.
- **Drainage network:** year-round stream size, channel and sinking-stream thresholds, flood-plain depth and settlement water decay.
- **Area size:** viewshed radius and minimum visible valley area.

A summary lists what was detected and every value set, and all sliders stay adjustable. Confirmed training examples are re-applied afterwards, so thresholds never exclude a site you've confirmed.

**Mound detection (iMound).** The Native Mounds scan uses the published inverted-DEM **iMound** method (Freeland et al. 2016). The terrain is detrended at several scales against the lower of a moving mean and a local quadratic (curved) surface fit, and flipped upside down. The curved fit means a mound tucked against the foot of a hillside, such as Cottonpatch Mound at Russell Cave, isn't swallowed by the slope beside it. The "pits" a fill has to raise are the mounds, measured from their own base contour. Only closed highs qualify, so hillside bulges and ridge noses don't. Candidates must then pass:

- **Persistence** in repeated fills with random 3DEP-grade LiDAR error added (stochastic depression analysis, Lindsay & Creed 2006);
- **Size, height and shape limits** for the conical or platform class, including height-to-width, elongation and solidity. A mound on a mountainside (steeper than the *Flat-ground slope* setting, 8° by default), such as Cottonpatch Mound on Montague Mountain, still counts if it has a clean mound shape (template ≥ 0.85, elongation ≤ 1.5x, circularity ≥ 0.55), which natural hillside knobs rarely have;
- **Template matching** against an ideal conical dome and a flat-topped platform (Davis et al. 2018/2019).

A second *spur pass* detrends with the curved surface alone, so a mound on a spur or ridge nose isn't merged with the spur into one oversized high. A spur-pass mound only counts when the landform under it is either the mound itself or at least 4 times wider (and 60 m or more), which keeps single knobs of a natural knob cluster out. On sloped ground, a candidate that sits among other bumps of its size is treated as a knob or outcrop field.

A flat-top measure labels each mound conical or platform. Tapping the map and choosing *This is a Mound* runs the same checks at that spot and shows which ones pass or fail. The tap snaps to the nearest closed high within about 10 m, the mound-sized one is reported (outlined in brown), and the result opens where you tapped. It is saved as training only when the measured shape is mound-sized, so tapping a spur can't loosen the size limits.

**Horizontal Cave Entrances.** A walk-in cave mouth in a bluff face can't be seen in bare-earth LiDAR. This mode finds the settings such entrances open into: gentle ground or a talus apron at the foot of a real cliff. By default a site must also show a karst drainage sign:

- **A cove or amphitheater head:** the cliff wraps more than about 190° around the spot, which a straight cliff can't do.
- **A spring-cut stream head:** a stream that begins at the bluff foot and runs straight into an incised notch.

Sinks upslope, a south/southeast-facing cliff and a match in a loaded cave survey raise the score. The mode is included in Scan All.

The original scan modes (caves/sinkholes, mounds, rockshelters, settlement likelihood) and training-data features are unchanged.

## Batch pipeline

```bash
pip install -r pipeline/requirements.txt
python pipeline/karst_pipeline.py my_dtm.tif --out results/ --caves known_caves.geojson
```

For each phase it writes analysis GeoTIFFs (TPI, aspect, LRM, HAND, choke points, fill depth, flow accumulation), a candidate mask and candidate GeoJSON. It also writes `dtm_wgs84.tif`. In the web app, use **Load Local DTM** to open `dtm_wgs84.tif` and **Load Pipeline Results** to open `all_candidates.geojson` for review, including 3D. Run with `--help` to see every parameter; the defaults match the web app.

## Responsible use

Every result is an algorithmic lead, not a confirmed site, and needs a field check. Mounds, rockshelters, caves, burials and mud-glyph sites are protected by ARPA, NAGPRA and state cave and burial laws. Don't disturb sites, and keep coordinates and cave-survey data confidential.
