# LiDAR Guide: the Galaxy Watch app

A Wear OS app for the Samsung Galaxy Watch Ultra 2 (and any Wear OS 3+ watch: Galaxy Watch 4 and later, Pixel Watch). It shows the feature waypoints from the LiDAR scanner web app over a LiDAR hillshade, with your live position and heading. It guides you to the waypoint you choose and shows that waypoint's details.

Once the waypoints are on the watch, it works on its own. It needs no phone and no signal: the GPS and compass are the watch's own.

## Screens

- **Map** (opens first). The hillshade, with every waypoint as a coloured letter in the web app's colours. Outlines show when you zoom in. You are the blue dot with an arrow for the way you face, inside a circle for the GPS accuracy.
  - **+** and **−** zoom; double-tap zooms in. Drag to look around.
  - **◎** follows you again after you drag.
  - **N** / **▲** switches between north up and the map turned to face your heading.
  - Tap a waypoint for its details.
  - Once you have chosen a waypoint, a dashed line runs to it, the bottom line gives its distance and direction, and **➤** starts guiding.
- **List** (☰ at the top of the map). **Settings** comes first, then *Receive from phone*, *Filter waypoints* and *History*, then the waypoints, nearest first, with distance, direction and confidence.
- **Filter waypoints.** A switch for each group of waypoints, for example *Mounds*, *Mounds (deep learning)* or *Effigies*, with how many each holds. Hidden groups leave the map, the list and the "nearest" line. *Show all* brings them back, and the choice is remembered.
- **History.** Every package received from the phone (its waypoints, hillshade and satellite imagery) is kept, newest first, with when it came and what it holds. The one on the map has a ✓. Tap one to **Load it on the map** or **Delete** it (tap twice to confirm).
- **Details**. The feature type, distance and bearing from you, confidence, size, height, coordinates, and the notes from the web app's result (shape measures, borrow pits, slope and so on). **➤** guides you to it; **Map** shows it on the map.
- **Guide**. A large arrow pointing to the waypoint, relative to the way you face, so you just walk where it points. It also shows the distance, and "turn left 40°" or "straight ahead". The arrow turns blue when you're on course, and green with a buzz when you arrive (within 10 m; change this in Settings). The screen stays on while you're guided.
  - Without a compass reading, the direction comes from your GPS course while you walk. If there is neither, the arrow points relative to north, with N marked.

## Basemap and hillshade

The map shows a basemap under the LiDAR hillshade, with the hillshade see-through at **70%** by default: the same defaults as the web app (satellite imagery, 70%). When waypoints arrive from the phone, the watch takes the web app's current settings, its basemap and hillshade opacity. You can still change them on the watch:

- **◧** at the top of the map switches the basemap: **Satellite → Topo → Street → None** (the bottom line names the one chosen).
- **Settings › Basemap** does the same.
- **Settings › Hillshade** steps the opacity through 40, 55, 70, 85 and 100%. With no basemap, the hillshade is solid.

The satellite imagery (or topo map) for the sent area comes with the waypoints, so it works with no signal. Other basemaps, and ground outside the sent area, load as map tiles when the watch has Wi-Fi or LTE (with *Download map when needed* on). Tiles are kept on the watch, so places you've seen work offline later.

## Keeping the screen on, and dimming when your arm is down

**Settings › Keep screen on** keeps the display on and LiDAR Guide open, rather than going back to the watch face. Your position keeps updating while it's open.

**Settings › Dim when arm is down** (on by default; it needs *Keep screen on*) saves battery. When your arm hangs at your side, the watch's motion sensor notices: the screen has tipped more than about 70° from facing up for 1.5 seconds. The brightness then goes right down and the map is darkened. Raise your wrist to look and it brightens at once; a tap on the dimmed screen also brings it back.

Keeping the screen on uses more battery than letting the watch sleep, even dimmed. Turn it off when you don't need the map in view.

## Getting the waypoints onto the watch

1. On the watch: **LiDAR Guide › ☰ › Receive from phone**. Allow *Nearby devices* the first time.
2. On the phone, in the LiDAR web app in **Chrome**: scan an area, then under **Watch** press **⌚ Send to watch** and pick the watch.
3. The watch shows progress, then *Received: N waypoints and the map*. Tap *Open the map*. The map opens on the received area, zoomed to show all its waypoints. Press ◎ to go back to following you. Whenever no waypoint is on screen, the bottom line names the nearest one and how far away it is, in which direction.

Options in the web app's Watch box:
- **Waypoints**: all results on the map, or only those in view.
- **Hillshade**: 1 m (sharpest), 2 m (quicker to send), or none.
- **Include the basemap**: sends the satellite imagery (or topo map) shown in the web app, so it works offline. On by default; it roughly doubles the size, so the transfer takes about twice as long.

A 1.5 km area at 1 m is about 300 KB, which takes roughly half a minute to a minute over Bluetooth. Sending again replaces what is on the watch.

Sending uses Web Bluetooth, which is in Chrome on Android (the phone a Galaxy Watch pairs with). The page must be opened over https.

**Map with no phone:** with *Download map when needed* on (Settings), a watch with Wi-Fi or LTE downloads a USGS 3DEP hillshade around you whenever you are off the map it has.

## Installing it on the watch

The app isn't in the Play Store. GitHub builds it from this folder and publishes **LiDAR-Guide.apk** as the **watch-app** release: <https://github.com/evanrobinson1985/Advanced-Map-Scan/releases/tag/watch-app>.

To install the APK (sideloading), with a computer:

1. On the watch: **Settings › About watch › Software information**, tap *Software version* 5 times to turn on Developer options.
2. **Settings › Developer options**: turn on *ADB debugging* and *Wireless debugging*. Note the IP address and port. On Wear OS 4 and later, tap *Pair new device* for a pairing code.
3. On a computer with the Android platform tools (`adb`) on the same Wi-Fi:
   ```
   adb pair 192.168.1.23:41234      # Wear OS 4+: the pairing port and code from the watch
   adb connect 192.168.1.23:5555    # the port shown under Wireless debugging
   adb install LiDAR-Guide.apk
   ```

Without a computer, an Android phone app such as *Bugjaeger* or *Easy Fire Tools* can do the same steps (pair, connect, install) from the phone.

New versions install over the old one with `adb install -r LiDAR-Guide.apk`. If an update ever refuses to install with a signature error, uninstall the old app first and send the waypoints again. That happens only if GitHub's stored signing key was renewed.

## Building it yourself

Open this folder in Android Studio, or run `./gradlew assembleRelease`. You need JDK 17 and the Android SDK (platform 34). `./gradlew testDebugUnitTest` runs the tests of the map maths, the package format and the Bluetooth reassembly.

## How the transfer works

- **The package.** `LWP1`, then the JSON's length (4 bytes, big-endian), then the JSON, then the hillshade JPEG and the basemap JPEG, each with its length in the JSON (`img.len`, `base.len`). `base` has `src` (`satellite` or `topo`) and the same edges as `img`. `view` carries the web app's basemap and hillshade opacity (`base`, `hs`). The JSON holds `name`, `made`, `img` (the hillshade's edges `n s e w` in degrees and its size in pixels) and `wps`. Each waypoint in `wps` has `id lat lon kind letter color score d h conf notes ol`, where `ol` is the outline as `[lat, lon]` pairs.
- **The Bluetooth service.** It is `7b1e0001-5a4c-4b8e-9d3a-2f6c1a9e4d10`, with two characteristics:
  - **RX** (`…0002`, write): the phone sends BEGIN (`1`, size, CRC-32), then DATA pieces (`2`, offset, up to 495 bytes), then END (`3`).
  - **STATUS** (`…0003`, notify): the watch answers `p received total` as pieces arrive, then `ok …` or `err …`.
- The watch checks the size and the CRC-32 before it replaces what it has.
