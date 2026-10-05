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

**Effigy & linear mounds.** Effigy mounds (birds, bears, panthers, water spirits) and linear mounds are long, narrow and low: usually 1–3 ft high and 15–40 ft wide, but tens to hundreds of feet long, with wings, legs and tails. The mound scan looks for round closed highs, so it misses them. The **Effigy & Linear Mounds** scan mode works like this:

1. **Remove the slope.** It subtracts the general tilt and shape of the ground.
2. **Keep the narrow rises.** A morphological top-hat keeps every raised feature narrower than *Max width*, at its own height, whatever its length or shape. Hills and broad swells drop out.
3. **Outline them.** It keeps features at least *Min height* high, following their tapering ends down to 40% of that.
   - **One effigy is one object.** Plough-worn or eroded stretches can drop a wing, neck or tail below *Min height* for a few feet, which would split one effigy into several "mounds". Gaps up to *Join gaps up to* wide (20 ft by default) are bridged first, so the pieces are traced and classified as one shape. In a test, a bird with a 13 ft ploughed-out gap in one wing was two unrelated pieces without joining, and one bird with its full wingspan with it.
   - **An effigy beside a ridge.** Where the raised ground spreads over 1,300 ft, or part of it stands taller than any effigy (10 ft), only the ground at effigy height is kept. Ridges and bluff edges are removed together with their sides. This cuts an effigy free of a ridge its wing or tail runs into, instead of tracing the whole network as one tangled "effigy".
   - **This is an Effigy.** The popup opens where you tapped. If the raised ground there joins a larger network, only the part within 330 ft of the tap is traced, and the popup says so. A tangle is drawn as an outline, not a mass of lines. Tapping a ridge taller than any effigy says so.
4. **Trace the shape.** It thins each feature to its centre line and reads its limbs, length, width, height and side steepness.
5. **Classify it.**
   - Two long, roughly opposite limbs make a **bird**, and its wingspan is reported.
   - Other branched shapes are animal effigies.
   - A single strip is a linear mound.
6. **Rule out look-alikes.**
   - Dead-straight features over 650 ft long, or long ones running off the edge of the area: roads, fences, railroads.
   - Features taller than 10 ft: embankments.
   - Broad swells with gentle sides.
   - Sets of parallel strips side by side: plough ridges, terraces, crop rows.

Results are lime-green "F" markers with the traced shape drawn on the map. Each popup has **Yes** / **Not real**. Results like effigies you've confirmed rank higher; ones like what you marked not real are skipped or ranked lower. The comparison includes the shape class, so a strip marked not real doesn't hide birds.

Settings: *Min height* (lower it for plough-worn groups), *Max width*, *Min length*, *Min side steepness*, *Join gaps up to*, *Include linear mounds* and *Skip sets of parallel strips*. Tap the map and choose **This is an Effigy** to trace the feature under your tap. It shows every check, why the scan skips it if it does, and lets you save it as a confirmed example. The mode is included in Scan All, the legend, the 3D view and every export.

**Shell middens, mounds & rings.** Shell middens are heaps of discarded shell along coasts, estuaries and rivers. They range from low scatters to heaps 50 ft high, long ridges along old shorelines, and shell rings 150–300 ft across around an open plaza. Their size and shape differ from earthen mounds, so teaching the mound scan or network with them would teach it the wrong shape. They have their own scan mode, **Shell Middens, Mounds & Rings**, their own training data and their own deep-learning model. The scan:

1. removes the ground's trend and keeps every raised feature narrower than *Max width*, at its own height;
2. outlines the features at least *Min height* high;
3. measures each one: length, width, height, volume, side steepness, any enclosed plaza, and how much of its outline it fills;
4. classifies it as a **closed shell ring**, an **open ring or crescent**, a **shell ridge**, or a **midden heap**;
5. rules out natural hills over 80 ft high, long even levees, roads and spoil banks, features with gentle sides, and sets of parallel beach ridges or dunes.

Results are teal "O" markers with the footprint outlined. Settings: *Min height*, *Max width*, *Min size*, *Min side steepness* and *Skip sets of parallel ridges*. Tap the map and choose **This is a Shell Midden** to check the feature under your tap and save it. Dunes and dredge-spoil banks look much like middens in LiDAR, so mark them *Not real* to teach the scan and model. Shell middens are protected archaeological sites and often hold burials.

**House pads and building foundations.** A building removed from a bare-earth DTM leaves a flat, straight-sided patch where the ground was filled in under it. A house built on a raised pad, common on flood plains, leaves the pad. Both look like low mounds. Two options under *Max results per mode* keep them out of the mound, effigy and shell midden scans and the deep-learning scans:

- **Skip house pads and building foundations.** The raised area above half its height is compared with the smallest rectangle that fits around it, at every angle. A candidate is skipped when it is rectangular, flat-topped and straight-edged:
  - **rectangular:** it fills at least 85% of that rectangle;
  - **flat-topped:** at least 60% of it is within 15% of its top height;
  - **straight-edged:** its outline runs along the rectangle's sides.

  Long buildings (up to 8:1) and L-shaped houses with straight walls are caught too. A mound is domed: only about a quarter of it is that close to its top, so round mounds, even small ones, are never caught. Flat-topped rectangles over 150 ft across or 6.5 ft tall are only flagged and ranked lower, never skipped, so platform mounds stay. Borderline shapes are kept but marked "could be a house pad or building foundation". In tests on synthetic terrain, all 9 house pads were skipped and all 6 round mounds and both platform mounds were kept. With the check off, the effigy scan reported 3 of those pads as "linear mounds".
- **Skip buildings mapped in OpenStreetMap.** Building outlines for the loaded area come from the Overpass service, once per area. A candidate on or beside a mapped building is skipped. This sends the area's bounds to Overpass; without a connection the check is skipped.

The status line says how many candidates each check skipped.

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

**Review training images.** **🖼 Review training images** (in the Training panel, the Deep Learning section, and the training data list) shows the terrain under every training item as a shaded picture. That's the same sample each deep-learning model learns from, so you can spot items that aren't really mounds and fix them.

- **Model:** choose *Mounds*, *Effigy & linear mounds* or *Shell middens & rings*. Each picture covers that model's window (48 m, 144 m or 216 m square). A red ring marks the labelled spot, drawn at the recorded size for mounds. A real one should be a clear rise right in the middle.
- **Filters:** All, Real, Not real, Outlines, and *No image yet* (items whose area hasn't been loaded).
- **Sorting:** newest, oldest, lowest first, or *Network disagrees most first*. That last option needs the training data check to have run with a trained mound model; it lists the real mounds the network doubts and the "not real" marks it thinks are mounds.
- **Each card:** **Not real** / **Real** switches the label, 🗑 removes the item, and **Go to** shows it on the map. A "not real" item stays in your training data as a "not a mound" example, which also teaches the network.
- **All of them:** the first 60 pictures show at once and more load as you scroll. *Showing X of Y* at the top counts them, and **Show all** at the bottom loads the rest at once.
- **Getting the missing pictures:** in the *No image yet* view, **Download terrain for all N without a picture** downloads the terrain around those items and takes their pictures for this model. If you select pictures first, only the selected ones are fetched; this works in any view. Items are grouped into areas of at most about a mile square, downloaded one after another, and **Stop** ends after the current area. The result line says how many pictures were taken.
- **Changing the kind:** each card's *Kind* menu moves an item between mound, effigy / linear mound and shell midden. *Change selected to...* moves a whole selection. The map editor (**Edit** on a training item shown on the map) has the same *Kind* menu. Real items stay real and not-real marks stay not real; each change can be undone. The item then teaches the other kind's scan and deep-learning model. Its old terrain picture is dropped, and the new model takes one the next time the area is loaded (at once if it already is). Train both models again afterwards. Mound outlines always stay with the mound model.
- **Several at once:** click pictures to select them (or **Select all shown**), then **Mark selected not real**, **Mark selected real** or **Remove selected**.
- **Undo:** every change is an ordinary training edit, so **Undo** reverses it. Train the model again to use the cleaned data.

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

**Checking training data for issues.** A wrong record, such as a hill saved as a mound, a mound counted twice or a "Not real" mark tapped by mistake, quietly misleads the scans and the network. The Training Data panel shows **⚠ N possible issues… Review** whenever any are found. **Check training data for issues** (also in the Deep Learning section) lists them.

Rule checks always run:

- **Size no mound has:** more than 350 m across or 32 m tall. Monks Mound, the largest, is about 30 m tall and 290 m long.
- **No size recorded.**
- **Impossibly steep:** taller than half its width.
- **Very low** (under 15 cm) or **very small** (under 3 m across).
- **Terrain doesn't match:** the terrain sample under the point rises less than a third of the recorded height, so the point may be off the mound.
- **Confirmed twice:** two confirmations within 5 m.
- **Contradicts a "Not real" mark:** a confirmed mound within 20 m of a "Not real" mark. Scans skip anything that close to such a mark, so the mound would be hidden.

When a deep-learning model is trained, its opinion is added: confirmed mounds it scores under 30%, and "Not real" marks it scores 90% or more. Its scores are saved with the model, so the panel's count includes them.

Each issue has three buttons:

- **Go to & edit** opens the item's editor on the training-data map. A bar shows the issue, with **It's accurate** and **Back to the list**.
- **It's accurate** records that you checked it. That issue isn't raised again for that item unless you later change its position, size or label. It can be undone like any edit.
- **Remove** deletes the item.

Training a deep-learning model also reports how many issues it found.

**Scale bar.** A scale bar at the bottom left of the map shows distance in feet, switching to miles when zoomed out. On phones it sits just above the folded settings panel.

**Keeping training data.** Training data is saved in two places in the browser (localStorage and IndexedDB, the newer copy wins on load), and the app asks the browser to keep the site's storage persistent. Both copies belong to the exact address the app is opened from, including the port, and are lost if the site's data is cleared. So the Training Data panel counts confirmations not yet in a backup file, with **Back up now**. Where the browser supports it, **Keep a backup file updated automatically** rewrites a backup file after every confirmation. **Import Training Data** merges with what is already there, so restoring an older backup never loses newer work.

**Horizontal Cave Entrances.** A walk-in cave mouth in a bluff face can't be seen in bare-earth LiDAR. This mode finds the settings such entrances open into: gentle ground or a talus apron at the foot of a real cliff. By default a site must also show a karst drainage sign:

- **A cove or amphitheater head:** the cliff wraps more than about 190° around the spot, which a straight cliff can't do.
- **A spring-cut stream head:** a stream that begins at the bluff foot and runs straight into an incised notch.

Sinks upslope, a south/southeast-facing cliff and a match in a loaded cave survey raise the score. The mode is included in Scan All.

The original scan modes (caves/sinkholes, mounds, rockshelters, settlement likelihood) and training-data features are unchanged.

**Experimental: deep-learning mound detection** (panel section 6). A small convolutional neural network learns what mounds look like from your own training data, instead of following the scan's hand-written rules.

What it learns from:

- **Mounds:** every confirmed mound and every outline saved as a mound.
- **Not mounds:** every "Not real" mound mark, every look-alike outline, and random ground from each area you load. Almost all ground is not a mound, and that random ground keeps 35 m clear of every mound you know of.

How it works:

- **Terrain samples.** It looks at a 40 m × 40 m square of bare-earth terrain at 1 m, with the ground's overall tilt removed, so a mound on a hillside looks like one on flat ground. It gets three views of each square:
  - relief, the height above that ground;
  - fine relief, which picks out small sharp bumps such as tree throws, boulders and knobs next to broad smooth rises;
  - steepness. It needs a sample of the terrain under each training item, taken whenever a DTM covering that item is loaded. After you first use the section, every DTM you load adds its samples automatically; **Collect samples from this DTM** does it on demand. Samples are kept in this browser (IndexedDB) and build up across areas and sessions. The panel counts the items that still need a DTM loaded around them. Its **Show them on the map** link marks each one: orange for mounds, grey for not-mounds. It groups them into dashed areas small enough for one download, most items first. A bar steps through the areas with **Prev** and **Next**. **Download this area**, on the bar or in any marker's popup, fits the map to the area, downloads it and takes the samples straight away. The bar then moves on to the next area, until every item has a sample.
- **Train model.** Training happens in the browser with TensorFlow.js, which loads from a CDN on first use. Each round shows every mound shifted, rotated, mirrored and a little taller or lower, because mounds are rare compared with ordinary ground. Training always starts from the current data, so edits, removals and Undo are honoured. With 5 or more mounds, a held-out check reports how many unseen mounds the network found and how many unseen not-mounds it wrongly flagged. It holds back whole areas, about 1.2 miles square, until about a fifth of the mounds are in them, together with all the ground in those areas. Anything within 330 ft of a held-back mound is also left out of training. The check is therefore on places the network never saw: mounds of one group look alike, so holding back random mounds would overstate accuracy. When all your examples are in one area it holds back random items instead, and says the result is optimistic.
- **Scan with deep learning.** It scores a 40 m window every 4 m over the area in view, up to 2.5 km² at a time. Every peak is scored again turned and mirrored all 8 ways and the average decides, so a lucky angle can't create a false alarm. The held-out check during training is averaged the same way. Peaks above the **Confidence** setting are kept at least 15 m apart. Each peak is measured with the same mound measurer as "This is a Mound". A peak with no closed high under it is shown but flagged as a likely false alarm. Your "Not real" marks and look-alike outlines always win.
- **Results.** Results are purple "D" markers on their own layer and legend entry, separate from the regular scan. A candidate on one of your confirmed mounds says so, which is a quick sanity check.
- **Cross-check with the regular scan.** If the regular mound scan has results on the map, candidates it also found are marked "The regular mound scan found this too" and listed first. Next come candidates with a measurable rise. Candidates smaller across than your smallest mound size setting (often tree throws, boulders or knobs) are flagged and listed last. Run the regular scan first, then the deep-learning scan, to get the cross-check.
- **After an upgrade.** When the method is upgraded, the panel asks you to train again. Your samples are kept.
- **Mounds too big for the window.** Mounds recorded wider than 35 m (115 ft) are left out of deep-learning training, and the panel says how many. The network would see only their flat top or a slope and learn that slopes are mounds. The regular scan handles big platform mounds.
- **Background across all areas.** Background is kept up to 4,000 samples. Over the cap, samples are trimmed from whichever area has the most, so every area keeps a share. An area whose background was dropped by an older version is sampled again the next time it is loaded.
- **Look-alikes (Mounds and Large & platform mounds models).** Random background is nearly always plain ground, so on its own it never shows the network the bumps that fool it: knobs and ridge noses on hillsides, boulders, tree throws, banks and sinkhole rims. A network trained that way can score well on its held-out check and still flag dozens of knobs on rugged ground. Look-alike samples fill that gap:
  - **From each area you load.** Up to 60 raised spots per area (25 for the large model) that clearly fail the mound rules: on ground steeper than 12°, no closed rise on ground steeper than 8°, far smaller than a mound, or a ridge. (On gentle ground a worn, low mound can fail to measure, so a flat spot with no closed rise is hidden from results but never taught as "not a mound".) Areas collected before this existed get their look-alikes the next time their terrain is loaded.
  - **From each scan.** The scan's own clear false alarms, judged by the same rules, are kept automatically (**Learn from clear false alarms**, on by default). The status line says how many; train again to teach the network not to flag them.
  - **Safeguards.** Nothing within 115 ft of a confirmed mound is used, and a spot that could be an unrecorded mound (a closed, mound-sized rise on gentle ground) never is. Look-alikes are kept up to 4,000 (1,500 for the large model), trimmed by area like background, and saved in model files. The held-out check reports how many look-alikes it wrongly flagged.
  - **Training memory.** Training now feeds the samples in chunks, so the larger sample set doesn't need all its inputs in memory at once.
- **Save deep-learning model / Load deep-learning model.** Saving writes one file (`lidar-deep-learning-model-DATE.json.gz`). It holds the trained network, all its terrain samples, its accuracy check and a copy of your training data, which says which samples are mounds. Load it to restore everything after clearing the browser, or to use the model on another device. Loading adds to what is already there: samples and training data are merged without duplicates, and the network in the file replaces the one in the browser. If the file's network was made by an older method, its samples are still loaded and you press Train model.
- **Answering.** **Yes** / **Not real** on a candidate saves to the same training data as the regular scan and takes its terrain sample at once. Train again to update the network.

**Openness views.** Each network also sees positive and negative openness. These are standard LiDAR-archaeology views: for each cell, the highest and lowest angles to the ground in 8 directions, out to 10 cells (33 ft for mounds, 66 ft for effigies, 98 ft for shell middens). A mound top is open to the sky all round, and a ditch or borrow pit is closed in. Openness doesn't depend on how the window's ground is levelled, so it brings out low, broad features such as plough-worn mounds. Each stored terrain sample's openness is worked out once per training; a scan works it out once for the whole area. In synthetic tests, the views didn't show a clear gain: on hard terrain with 7–12 inch mounds, the six-view and four-view networks found about as many mounds (9 vs 8 of 14 in held-out areas), with slightly fewer false alarms. Holding back by area showed a much bigger effect: networks trained on two areas found far more mounds in an unseen area than one trained on one area. Varied training areas matter more than extra views.

**Slope in the deep-learning models.** Every window the networks see has the ground's tilt and curve removed: a curved surface fitted to the window's border (a hilltop, hollow or valley side, not just a flat tilt). A mound on a curved 25° hillside reaches the network at the same 1.2 m as on flat ground in tests. Each window also carries a fourth view, the ground slope under it, so a network can learn that a bump on a steep hillside is less likely a mound. Deep-learning mound results follow the mound scan's hillside rules:

- nothing on ground steeper than 20°;
- above the *Flat-ground slope* setting, a candidate without a clean mound shape is flagged as a likely natural knob and listed last;
- each popup gives the ground slope.

**Doubtful hits are hidden.** By default the deep-learning mound scan hides hits that fail the mound rules. These are hits on ground steeper than the *Flat-ground slope* setting (whatever their shape: a small round knob on a hillside passes the shape test), with no closed rise, smaller than your smallest mound size, or a ridge. One of your confirmed mounds, or a spot the regular scan also found, is always shown. The status line says how many were hidden; tick **Show doubtful hits** to see them. In one user's real training data, 97% of 349 confirmed mounds stand on ground of 8° or less. At Russell Cave (rugged karst and an escarpment, one mound), that user's model gave 56 hits; with doubtful hits hidden it gives 3, including the mound.

This changed what the networks see, so each model must be trained once more. Your terrain samples are kept, and the panel says when to retrain.

**Train all models with new data.** This button in the Deep Learning section trains every model that has data it hasn't learned from yet, one after another, and leaves the rest alone. Each model remembers exactly what it was trained on: which items and labels, and how much background ground. Any of these counts as new data:

- a new confirmation, "Not real" mark, outline or tour answer;
- a removal, a relabel or a change of kind;
- a newly taken terrain picture;
- background from a newly loaded area.

A model that has never been trained, or whose method was upgraded, is trained too. One with fewer than 3 examples with a picture is skipped. A bar shows progress, then each model's result. If nothing is new, the panel says why for each model ("up to date", "only 1 shell midden with a picture").

**Large & platform mounds model.** The Mounds model looks at 130 ft squares, so mounds wider than about 115 ft are left out of its training: platform mounds, and big conical mounds like Grave Creek. A fourth model, *Large & platform mounds*, covers them:

- **What it sees:** 650 ft squares at 8 ft per cell, with the same six views as the others.
- **What it learns from:** the confirmed mounds you already have, but only those recorded as 100 to 590 ft across. Smaller mounds are left to the Mounds model and don't count as "not a mound" to it. It also learns from your "Not real" mound marks on things at least 80 ft across, such as natural hills and ridges, and from background ground.
- **Measuring its results:** each candidate is measured as the rise above a plane fitted to the ground around it, widened until the whole mound fits. The scan's own mound measurer is tuned for smaller mounds.
- **Results:** violet "P" diamonds.
- **Getting started:** pick it in the **Model** menu, open **Review training images** and use **Download terrain** for its "No image yet" items. Then train it, or press **Train all models with new data**. Tour answers and Yes / Not real on mound results take pictures for both mound models.

In a synthetic test it found all 8 large mounds in two areas it never saw, with no false alarms.

**Re-measure.** In the training data map's **Edit** popup for a mound (or shell midden), **Re-measure** measures the feature under the point from the loaded terrain and fills in its height and width; **Save** keeps them. Mounds up to about 115 ft use the scan's mound measurer; bigger ones use the rise above the surrounding ground. In tests that was within about 10% of the true size for platform and conical mounds 120–440 ft across. Use it to fix records like a mound saved at the size of a whole site, then train again.

**Save all models and the training data folder.** Next to *Save* / *Load deep-learning model*:

- **💾 Save all models** saves every model's file (network, terrain samples and training data) and a plain training data backup (`lidar-training-data-DATE.json`) in one go. Models with nothing in them yet are skipped. On desktop Chrome and Edge, the files go into your **training data folder**, which you choose once; the app remembers it and asks for permission again after a restart. Other browsers, including phones, download them to your Downloads folder.
- **📁 Open training data folder** opens your system's file window inside that folder. You see the saved files and their dates there, and any you pick (one or several) are loaded: model files and training data backups alike. Cancel just closes it. A web page can't open a folder in Explorer or Finder itself, so this is the closest it can get; the page shows the folder's name only, not its full path. Where folders aren't supported, it opens a file chooser for your Downloads instead.
- **Load deep-learning model** now takes several files at once, of any of the models; each selects its own model.

**Three models.** The **Model** menu at the top of the Deep Learning section switches between *Mounds*, *Effigy & linear mounds* and *Shell middens & rings*. Each has its own samples, network, accuracy check and save file. The effigy model:

- learns from your confirmed effigies (scan results answered Yes, or *This is an Effigy*) and your "Not real" marks in the effigy mode, plus random ground;
- looks at **128 m squares at 2 m**, so a whole bird fits;
- sees the same narrow-relief view as the effigy scan, plus the broad relief around it and the steepness of its sides;
- traces each candidate with the effigy scan's own analysis (shape, limbs, wingspan, size);
- shows results as olive "F" diamonds, cross-checked with the effigy scan.

The shell model learns only from shell middens you confirmed and your "Not real" marks in the shell mode, plus random ground. It looks at 192 m squares at 3 m, so a whole shell ring fits. Its views are the raised bodies, the broad relief around them, and the steepness of the ground. Results are teal "O" diamonds, cross-checked with the shell scan.

The effigy and shell models report each feature once. A long effigy can light up several windows along its body, so hits that fall on the same traced feature are merged into one candidate, the most confident.

Every DTM you load collects samples for each model once you have used it. Save files are named per model, and loading one selects its model.

**Training-site tour.** **🗺 Training-site tour (public mound sites)** in the Deep Learning section collects training candidates from known mound sites without you driving the map. The list starts with the 34 public parks and monuments in `docs/mound-training-sites.csv`. A second batch of 104 public sites (`docs/mound-training-sites-2.csv`) is added automatically the next time you open the list; sites you already finished or removed are skipped. Their locations are approximate. When a site is toured, or you press **Go to**, the app looks up its name on the map and uses that spot if it is within 8 km of the estimate. The list marks each site as *approximate* or *from map lookup*; check an approximate one with Go to before relying on it. A third batch of 18 shell middens and shell rings is added the same way. Seven second-batch sites in Florida were reclassified because they are shell sites; that applies only to sites not yet toured. Each site is marked *Mounds*, *Effigies*, *Mounds + effigies*, *Shell middens* or *Mounds + shell middens*.

- **Run tour.** For each waiting site, the app:
  1. zooms in on the site and downloads only a square around it at 1 m: 500 m (1,650 ft) by default, or 300 m or 1 km, chosen with *Area scanned at each site* (addresses are looked up first);
  2. runs the mound scan, the effigy scan, or both;
  3. queues every candidate with a terrain sample for both models;
  4. collects ordinary ground for both models, kept clear of the candidates.

  Nothing is added to your training data until you review it. A site whose download fails, or has no 1 m LiDAR while *Require 1 m* is on, is retried the next time. **Stop** finishes the current site and stops.
- **Review candidates.** A card shows each candidate, with shaded terrain of the spot and the map zoomed onto it. Answer **Yes, it's real**, **Not real** or **Not sure (skip)**; the keys are Y, N and S. **Back** (B) undoes the last answer. **Pause** keeps the rest for later. Each answer goes into your training data with its terrain sample straight away, even though that site's terrain is no longer loaded.
- **One effigy or midden, not several mounds.** At a site scanned for mounds and effigies or shell middens, a mound candidate inside an effigy's or midden's footprint is dropped. It is part of that feature (a body, a wing tip, a heap on a ring), and labelling it would teach the mound model that those shapes are mounds.
- **Training all three models automatically.** When you answer the last candidate, the app trains every model you gave answers for, one after another: Mounds, Effigy & linear mounds, then Shell middens & rings. A bar shows the progress, then each model's result. A model with fewer than 3 confirmed examples can't train yet, and its sites stay on the list. **🧠 Train all models** in the tour list does the same at any time, for example after pausing a review.
- **Sites leave the list once trained.** A site is finished when it has been toured, all its candidates are answered, and the model for each kind you labelled there has been trained since. A site whose candidates were all mounds needs only the Mounds model. A site with effigies also needs the Effigy & linear mounds model. Finished sites are removed from the list, and the training message says how many left. Finished and removed default sites are remembered, so they never come back.
- **Adding sites.** Enter coordinates or an address, or use the map centre, with an optional name and the kind of mounds there. Sites within 200 m of one already listed are refused. Add only places you know have mounds, preferably public ones. **Remove** takes a site off the list, along with its unanswered candidates.
- **Adding many sites at once.** **Import sites (CSV or Excel)** reads a CSV (comma, semicolon or tab separated) or an Excel / OpenDocument sheet (.xlsx, .xls, .ods; the reader loads from a CDN on first use). It needs a header row, then one site per row. Columns are found by their names, in any order:
  - *Name*;
  - *Latitude* and *Longitude*, or one *Coordinates* column in any format the search box takes, or an *Address* to look up when the site is toured;
  - optionally *Kind*: mounds, effigy, shell, both, or mounds + shell. Words like "effigy mounds" or "shell midden" work too, and blank rows use the kind picked beside the button.

  Sites within 650 ft of one already listed or finished are skipped, and the result line says how many were added and why any rows were skipped. **Download site list** saves the whole list, waiting and finished, in the same format. `docs/all-training-sites.csv` (and `.xlsx`) lists all 353 suggested sites in that format.
  `docs/mound-training-sites-3.csv` holds 197 more public mound sites for **Import sites**. None repeats an earlier list, and each is at least 1 km from an earlier site. Positions come from public sources: 70 from USGS place names (GNIS, the named mound on the topographic map), 101 from historical markers (HMdb; these include the Louisiana Ancient Mounds Trail, the Mississippi Mound Trail and the Alabama Indigenous Mound Trail; the marker usually stands within sight of the mound, so tour these at 1000 m), and 2 from the National Register (address-restricted listings are left out). The other 24 are parks found by name lookup when toured. The *Location accuracy* and *Source* columns say which is which. Fourteen Florida Everglades and marsh mounds are low and broad and are marked to be reviewed carefully. Natural hills named "mound", destroyed mounds, Civil War earthworks and shell middens were left out.

Expect rough results with fewer than about 20 confirmed mounds (or effigies). Random ground can occasionally contain an unrecorded mound, which slightly confuses the network. Mounds wider than about 35 m don't fit in its window.

## Batch pipeline

```bash
pip install -r pipeline/requirements.txt
python pipeline/karst_pipeline.py my_dtm.tif --out results/ --caves known_caves.geojson
```

For each phase it writes analysis GeoTIFFs (TPI, aspect, LRM, HAND, choke points, fill depth, flow accumulation), a candidate mask and candidate GeoJSON. It also writes `dtm_wgs84.tif`. In the web app, use **Load Local DTM** to open `dtm_wgs84.tif` and **Load Pipeline Results** to open `all_candidates.geojson` for review, including 3D. Run with `--help` to see every parameter; the defaults match the web app.

## Responsible use

Every result is an algorithmic lead, not a confirmed site, and needs a field check. Mounds, rockshelters, caves, burials and mud-glyph sites are protected by ARPA, NAGPRA and state cave and burial laws. Don't disturb sites, and keep coordinates and cave-survey data confidential.
