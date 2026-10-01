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

**Go to a location.** The search box at the top right of the map (a 🔍 button on a phone) and the Search box in the panel take coordinates or an address. The Search box in the panel works the same way. Coordinates are read in any common form:
- decimal degrees (`34.9745, -85.8136` or `34.9745 N 85.8136 W`; longitude-first as GIS software writes it also works);
- degrees-minutes-seconds (`34°58'28"N 85°48'49"W`) and degrees with decimal minutes;
- UTM (`16S 608297 3870858`);
- pasted map links (`…/@34.97,-85.81,17z`).

Anything else is looked up as an address or place name with OpenStreetMap's Nominatim, falling back to Esri's World Geocoder; several matches are listed to choose from. A red pin marks the spot and shows it in decimal degrees, DMS and UTM.

**Elevation data.** Downloads come from the USGS 3DEP elevation service: the best bare-earth DEM available at each spot (1 m LiDAR where USGS has it, coarser DEMs elsewhere), resampled by the service onto the app's lat/lon grid. Tap the map and choose **What data is here?** to see which source raster is under a point, with its cell size, so you know whether small features can be trusted. For the most faithful input, download the original USGS 1 m DEM tiles (apps.nationalmap.gov/downloader, "Elevation Products (3DEP)", 1 meter DEM) and open them with **Load Local DTM**. UTM tiles (NAD83, NAD83(2011) or WGS84) are converted in the browser, and several tiles can be picked at once and are merged. Only the part under the current map view is read, so a full 10 × 10 km tile works on a phone. Values are sampled straight from the original cells, skipping the service's resampling. WGS84 lat/lon GeoTIFFs still load as before. The NAD83 to WGS84 datum offset (about 1 m) is not applied.

**Mound detection (iMound).** The Native Mounds scan uses the published inverted-DEM **iMound** method (Freeland et al. 2016). The terrain is detrended at several scales against the lower of a moving mean and a local quadratic (curved) surface fit, and flipped upside down. The curved fit means a mound tucked against the foot of a hillside, such as Cottonpatch Mound at Russell Cave, isn't swallowed by the slope beside it. The "pits" a fill has to raise are the mounds, measured from their own base contour. Only closed highs qualify, so hillside bulges and ridge noses don't. Candidates must then pass:

- **Persistence** in repeated fills with random 3DEP-grade LiDAR error added (stochastic depression analysis, Lindsay & Creed 2006);
- **Size, height and shape limits** for the conical or platform class, including height-to-width, elongation and solidity. A mound on a mountainside (steeper than the *Flat-ground slope* setting, 8° by default), such as Cottonpatch Mound on Montague Mountain, still counts if it has a clean mound shape (template ≥ 0.85, elongation ≤ 1.5x, circularity ≥ 0.45), which natural hillside knobs rarely have;
- **Template matching** against an ideal conical dome and a flat-topped platform (Davis et al. 2018/2019).

A second *spur pass* detrends with the curved surface alone, so a mound on a spur or ridge nose isn't merged with the spur into one oversized high. A spur-pass mound only counts when the landform under it is either the mound itself or at least 4 times wider (and 60 m or more), which keeps single knobs of a natural knob cluster out. On sloped ground, a candidate that sits among other bumps of its size is treated as a knob or outcrop field. On sloped ground iMound measures a mound from its uphill rim, so it reads far too low and too narrow (Cottonpatch Mound: 1 ft). On any tilted ground (over 2°), the height, width and shape used by the size rules are measured above a curved (quadratic) surface fitted to the open hillside around the mound. A curve rather than a flat plane is used because it follows the bend where a mountain slope eases into flatter ground. The summit is the point standing highest above that surface, not the highest raw point, which on a slope is the mound's uphill shoulder. The ring of ground used for the fit moves outward until it is clear of the mound's footprint and the height has stopped changing, but stays within about 25 m, so bluffs, cave mouths and gullies farther away don't count. The footprint is the ground standing at least 5% of the mound's height above the surface, and never less than the hillside's own lumpiness (the fit's scatter), so it doesn't spill downhill over ordinary ground (the brown outline in *This is a Mound*), and roundness is read from its 25% contour. The candidate only counts if the ground around it is even (fit scatter at most 35% of the mound's height) and its footprint stays compact (at most 3× its detected top); otherwise it is a boulder, ledge knob or landform. Nothing on ground steeper than 20° is kept, since earthen fill doesn't stay put on slopes that steep.

**Borrow pits.** The earth for a mound was dug nearby, and the holes often survive: pits beside the mound (often ponding water) or a ditch around it. For each mound candidate, the app first finds the mound's true base from the natural ground around it. It then searches from the base out to 2.5× the mound's radius (15–60 m) for dug-out ground: closed hollows, plus ground below a surface fitted to the surroundings, which catches scoops cut into a hillside. Hollows that run out through the search area, or run away from the mound, are drainage and are ignored. Pieces curving around the mound at a steady distance make up a ditch. Steep cone-shaped holes are flagged as possible natural sinkholes. A pit whose lowest part is dead flat over a real area (within 3 cm, at least 12 m² or a quarter of the pit) is flagged as probably holding water. In 3DEP data a pond is hydro-flattened to its water surface, so that pit is deeper, and holds more, than measured; its volume is reported as a minimum, and a pond pit at the base counts as strong evidence. The result lists each pit's side, depth, size and distance from the base, plus ditch coverage and the dug-out volume against the mound's volume. Old pits fill in, and ponded ones are hydro-flattened in 3DEP, so the ratio is usually well under 1. Pits right at the base with ≥ 0.2× the mound's volume, or a ditch at least halfway round, count as a strong sign of construction and raise the ranking; any pits count as possible. The *Borrow pits* setting can also show only mounds with pits, or switch the search off. Pits are drawn as dashed blue circles and included in the exports.

A flat-top measure labels each mound conical or platform. Tapping the map and choosing *This is a Mound* runs the same checks at that spot and shows which ones pass or fail. The tap snaps to the nearest closed high within about 10 m, the mound-sized one is reported (outlined in brown), and the result opens where you tapped. It is saved as a confirmed example when the measured shape is round and compact enough for a mound class. Oversized or irregular shapes, like a spur, are not saved. Confirmations don't change the Conical / Platform / All Shapes size limits.

**Training outlines.** In *Training Data*, **Outline a Mound or Look-alike** lets you tap points around the base of a known mound, where it meets the natural ground, then Finish. The app fits the natural ground surface in a band outside your line (skipping the first 3 m, so a slightly tight line doesn't lift it) and measures:
- height above that ground, equal-area width, length and width with the long-axis bearing, area and volume;
- roundness, height-to-width ratio, side steepness, flat-top fraction and shape;
- the ground's slope, facing direction and roughness, base and top elevations, and borrow pits.

It also records what the scanner itself sees there (the same measurement the scan uses), and says whether your line looks tight or wide, alongside its own estimate of the base. Save it as a mound, or as a look-alike (tree throw, boulder, spoil pile, natural knob, road or berm).

In later mound scans:
- candidates like your outlined mounds rank higher, and their popup names the closest one;
- candidates like your look-alikes rank lower;
- anything inside a look-alike outline is skipped.

Comparisons use the scanner's view of each outline, so they are like for like. With five or more outlined mounds the scanner can see, an opt-in switch replaces the built-in size classes with ranges learned from them. That narrows as well as widens, so outline a representative mix first. Outlines export as GeoJSON (true and scanner measurements as attributes) and import by merging, so outlines from different trips and devices combine.

**Confirmed mounds.** A mound you confirm, whether with an outline saved as a mound, *This is a Mound*, or the checkbox on a scan result, is placed on the map straight away as a mound waypoint with a green check. It counts in the legend and goes into the GPX, GeoJSON and Shapefile exports; outlined mounds export with the shape you drew. Every later mound scan that covers it shows it again, with the height and size you recorded, whether or not the scan picks it out. A scan hit on the same mound is merged into it, and the popup says whether this scan also found it. Confirmations that are impossibly large or irregular for a mound, such as a whole spur saved by an old version of the check, are left off.

**Learning from your mounds.** Every confirmed mound also stores what the scanner measured on it. Later scans rank candidates like your mounds higher and name the closest one. Once 5 confirmed mounds have scanner measurements, *Mound Shape* gets **Learned from my confirmed mounds**, selected automatically the first time and remembered after that. It fills the Mound settings sliders with the best ranges for mounds like yours: minimum height, width range, roundness, stretch, template match and height-to-width. The ranges cover what your mounds span plus a safety margin that is wide with few examples and tightens as you add more (15% + 50%/√N); from 20 examples on, the 5th and 95th percentiles are used, so one odd example can't stretch them. Every new confirmation updates them. The sliders stay adjustable, and choosing another shape goes back to the built-in presets. The learned ranges look for mounds like the ones you've confirmed, so confirm a range of types (conical and platform, large and small).

**Not a real feature.** Each result's popup has two well-separated buttons, a green **✓ Yes, it's real** and a red **✗ Not real**. Not real removes the result from the map and the legend straight away, and remembers what it looked like: width, height, roundness, stretch and side steepness, plus the template match and ground slope for mounds. This works in every scan mode:
- scans skip that spot;
- candidates resembling it are skipped, on the current map and in later scans, at the level set by **Avoid look-alikes** in Training Data: Light (90% similar), **Normal (80%, the default)**, Strict (65%), or Off (only ranked lower);
- a looser resemblance lowers the ranking, with a warning in the popup.

Similarity to anything you confirmed real wins a tie. That matters because a small real mound and a dirt pile can measure almost the same: confirming the real mounds of that size keeps them from being dropped. "Not real" marks made before features were recorded learn what the spot looks like the next time a scan covers it.

**Undoing mistakes.** **Undo last training change** in the Training Data panel reverses your most recent training actions, one at a time, up to the last 30. Undo steps are saved with the training data, so they survive a reload. It covers:
- a confirmed feature (any size limits it had loosened are put back);
- "Not a real feature" (results it took off the map come back);
- an outline, a settlement site, a burial/ritual mark, or a removal.

**Review Training Data** lists everything you've taught the app, newest first, with **Go to** and **Remove** for each item. A confirmed mound's popup also has "Added by mistake? Remove this confirmation". Removing or undoing a confirmed mound takes it off the map and brings back the scan's own result for that spot. The legend and learned settings update with every change.

**Show Training Data on Map** (in the Training panel, or **Show all on map** in the review list) puts everything on the map at once and zooms to fit it all:

- confirmed features: the scan mode's colour with a green ✓;
- "Not real" marks: red squares with a ✗;
- outlines: green for mounds, red dashed for look-alikes;
- known settlement sites.

A bar at the top counts each kind and has **Fit all**, **List** and **Hide**.

Tap an item for its details and **Edit**, **Zoom in** or **Remove**. **Edit** changes:

- what it is: real ↔ not real, or mound ↔ look-alike for an outline;
- its height (or depth) and width in feet;
- its name or note.

**Move** lets you drag the marker to where the feature really is; an outline moves with it.

Every edit is one Undo step. Confirmed mounds on the map, the look-alike avoidance and the learned settings follow each edit straight away.

**Keeping training data.** Training data is saved in two places in the browser (localStorage and IndexedDB, the newer copy wins on load), and the app asks the browser to keep the site's storage persistent. Both copies belong to the exact address the app is opened from, including the port, and are lost if the site's data is cleared. So the Training Data panel counts confirmations not yet in a backup file, with **Back up now**. Where the browser supports it, **Keep a backup file updated automatically** rewrites a backup file after every confirmation. **Import Training Data** merges with what is already there, so restoring an older backup never loses newer work.

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
