# Advanced Map Scan: LiDAR Karst Archaeology GIS Platform

A single-file web app (`index.html`) plus a batch Python pipeline (`pipeline/karst_pipeline.py`) for finding likely prehistoric habitation, hunting and ceremonial sites in forested karst terrain (Cumberland Plateau / Northeast Alabama). They run the terrain-analysis workflow in *Geospatial Archaeology: Comprehensive GIS Workflows for Karst Terrain Analysis* on 1-meter bare-earth LiDAR.

Open `index.html` in a browser (or serve it with GitHub Pages). No build step is needed.

## How the workflow maps to the app

| Workflow phase | Where it lives |
|---|---|
| **Phase 1: Bare-earth LiDAR DTM** | *1. Data Pipeline* downloads the USGS 3DEP bare-earth DTM for the visible area. You can also load your own DTM GeoTIFF (WGS84). A live preview shows the m/px you'll get. Scans are blocked below the **1 m minimum** unless you untick *Require ≤1 m/px DTM*. |
| **Phase 2: Long-term habitation (base camps)** | Scan mode *Phase 2: Long-Term Habitation*. **Step 1:** TPI (10–50 m radius, extreme ± values) isolates bluff crests and bases. **Step 2:** aspect filter keeps S/SE bluff faces (135°–225°). **Step 3:** a Local Relief Model at the bluff bases finds flat dirt benches and flags positive mounding under the drip line as a possible midden. **Step 4:** keeps benches inside a 100–300 m buffer around year-round water and drops any in the 100-year floodplain. The floodplain is estimated from Height Above Nearest Drainage (HAND); a FEMA flood-hazard overlay is available for checking by eye. |
| **Phase 3: Transient hunt camps** | Scan mode *Phase 3: Transient Hunt Camps*. **Step 1:** no aspect restriction, and a smaller TPI radius picks out minor rock outcrops. **Step 2:** choke points: saddles and ridgeline gaps (Hessian saddle test) and narrow canyon funnels (a drainage line with steep walls on both sides). **Step 3:** a viewshed from each outcrop, reporting how much valley floor and shallow stream crossing it can see. A *Show viewshed on map* button draws it. |
| **Phase 4: Deep ceremonial karst** | Scan mode *Phase 4: Deep Ceremonial Karst*. **Step 1:** Fill Sink / closed-depression analysis, flagging large, deep sinks as possible collapsed karst windows. **Step 2:** sinking streams, meaning surface channels that end in a sink, with the drainage area they swallow. **Step 3:** speleological overlay. Load a cave survey (GeoJSON or CSV) and candidates are cross-referenced; a sink counts as verified when it opens into a mapped system long enough to have a Dark Zone. Survey data stays in memory only. |
| **Phase 5: Automation and WebGL visualization** | The in-browser scans automate the raster maths. Export candidates as **GeoJSON** or Shapefile, and the analysis raster as a **GeoTIFF** (EPSG:4326). **Open 3D WebGL Fly-Through** renders the DTM in 3D with candidates in red and pipeline context in amber (south-facing bluff faces, choke points or sinking streams). `pipeline/karst_pipeline.py` runs the same phases in batch with GDAL/Rasterio, and its outputs load back into the app. |

The original scan modes (caves/sinkholes, mounds, rockshelters, settlement likelihood) and training-data features are unchanged.

## Batch pipeline

```bash
pip install -r pipeline/requirements.txt
python pipeline/karst_pipeline.py my_dtm.tif --out results/ --caves known_caves.geojson
```

For each phase it writes analysis GeoTIFFs (TPI, aspect, LRM, HAND, choke points, fill depth, flow accumulation), a candidate mask and candidate GeoJSON. It also writes `dtm_wgs84.tif`. In the web app, use **Load Local DTM** to open `dtm_wgs84.tif` and **Load Pipeline Results** to open `all_candidates.geojson` for review, including 3D. Run with `--help` to see every parameter; the defaults match the web app.

## Responsible use

Every result is an algorithmic lead, not a confirmed site, and needs a field check. Mounds, rockshelters, caves, burials and mud-glyph sites are protected by ARPA, NAGPRA and state cave and burial laws. Don't disturb sites, and keep coordinates and cave-survey data confidential.
