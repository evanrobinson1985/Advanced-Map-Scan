#!/usr/bin/env python3
"""
Karst archaeology GIS pipeline: automated raster maths for bare-earth LiDAR DTMs.

Batch companion to the browser app (index.html), following the GIS workflow
for karst terrain analysis:

  Phase 1  Bare-earth DTM, 1 m resolution minimum (checked; override with --allow-coarse)
  Phase 2  Long-term habitation (base camps):
             TPI bluff lines -> solar aspect (135-225 deg) -> LRM benches/middens
             -> 100-300 m year-round water buffer, 100-yr floodplain excluded (HAND)
  Phase 3  Transient hunt camps:
             small-radius TPI outcrops (any aspect) -> terrain choke points
             (saddles, ridgeline gaps, canyon funnels) -> viewshed analysis
  Phase 4  Deep ceremonial karst:
             Fill Sink / closed depressions -> sinking streams -> optional
             speleological overlay (known cave GeoJSON)
  Phase 5  Outputs optimized GeoTIFFs (analysis rasters) and GeoJSON candidate
           geometry, plus a WGS84 copy of the DTM that the web app's
           "Load Local DTM" button can open for 3D WebGL fly-throughs.

Usage:
  python karst_pipeline.py dtm.tif --out results/ [--mode all|basecamp|huntcamp|ceremonial]
                           [--caves known_caves.geojson] [--help for every parameter]

Requires: numpy, scipy, rasterio, scikit-image (pip install -r requirements.txt).
Optional: numba (much faster flow routing on large DTMs).

All candidates are algorithmic leads, not confirmed sites. Cave, burial and
ceremonial site locations are protected under ARPA / NAGPRA and state law;
keep outputs confidential.
"""
import argparse
import json
import math
import os
import sys

import numpy as np
import rasterio
from rasterio import features
from rasterio.warp import calculate_default_transform, reproject, Resampling, transform_geom
from scipy import ndimage
from skimage.morphology import reconstruction

try:
    from numba import njit
except ImportError:  # plain Python fallback - same results, slower on big rasters
    def njit(*args, **kwargs):
        if args and callable(args[0]):
            return args[0]
        return lambda f: f

ACRE_M2 = 4046.86
DR8 = np.array([-1, -1, -1, 0, 0, 1, 1, 1])
DC8 = np.array([-1, 0, 1, -1, 1, -1, 0, 1])
DIST8 = np.array([math.sqrt(2), 1, math.sqrt(2), 1, 1, math.sqrt(2), 1, math.sqrt(2)])


# --------------------------------------------------------------------------
# Raster I/O
# --------------------------------------------------------------------------
def load_dtm(path):
    with rasterio.open(path) as src:
        dem = src.read(1).astype(np.float64)
        profile = src.profile.copy()
        valid = np.ones(dem.shape, bool)
        if src.nodata is not None:
            valid &= dem != src.nodata
        valid &= np.isfinite(dem) & (dem > -1000)
        if src.crs is None:
            sys.exit("DTM has no CRS - can't compute ground distances.")
        if src.crs.is_geographic:
            lat = (src.bounds.top + src.bounds.bottom) / 2
            res_x = abs(src.transform.a) * 111320 * math.cos(math.radians(lat))
            res_y = abs(src.transform.e) * 111320
        else:
            unit = src.crs.linear_units_factor[1] if src.crs.linear_units_factor else 1.0
            res_x, res_y = abs(src.transform.a) * unit, abs(src.transform.e) * unit
    return dem, valid, profile, (res_x + res_y) / 2


def write_tif(path, arr, profile, nodata=-9999.0, dtype="float32"):
    p = profile.copy()
    p.update(driver="GTiff", count=1, dtype=dtype, nodata=nodata, compress="deflate", tiled=True,
             blockxsize=256, blockysize=256, predictor=3 if dtype.startswith("float") else 2)
    if arr.shape[0] < 256 or arr.shape[1] < 256:
        p.pop("blockxsize"); p.pop("blockysize"); p["tiled"] = False
    with rasterio.open(path, "w", **p) as dst:
        dst.write(arr.astype(dtype), 1)


def write_wgs84_copy(path, dem, valid, profile):
    """DTM reprojected to EPSG:4326 - the web app positions rasters by lat/lon bounds."""
    src_arr = np.where(valid, dem, -9999).astype("float32")
    dst_crs = "EPSG:4326"
    transform, w, h = calculate_default_transform(profile["crs"], dst_crs, profile["width"], profile["height"],
                                                  *rasterio.transform.array_bounds(profile["height"], profile["width"], profile["transform"]))
    out = np.full((h, w), -9999, dtype="float32")
    reproject(src_arr, out, src_transform=profile["transform"], src_crs=profile["crs"], src_nodata=-9999,
              dst_transform=transform, dst_crs=dst_crs, dst_nodata=-9999, resampling=Resampling.bilinear)
    p = profile.copy()
    p.update(crs=dst_crs, transform=transform, width=w, height=h)
    write_tif(path, out, p)


# --------------------------------------------------------------------------
# Terrain derivatives
# --------------------------------------------------------------------------
def fill_nodata(dem, valid):
    """Nearest-valid fill so neighborhood filters don't smear NoData values."""
    if valid.all():
        return dem
    idx = ndimage.distance_transform_edt(~valid, return_distances=False, return_indices=True)
    return dem[tuple(idx)]


def box_mean(dem, valid, radius):
    size = 2 * radius + 1
    num = ndimage.uniform_filter(np.where(valid, dem, 0.0), size, mode="nearest")
    den = ndimage.uniform_filter(valid.astype(float), size, mode="nearest")
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(den > 0, num / den, dem)


def slope_aspect(dem, res):
    """Slope (degrees) and compass aspect of the downslope direction (0=N, clockwise; -1 = flat)."""
    dzdn, dzdx = np.gradient(dem, res)   # axis 0 runs south (row+1), so north-rise is -d/drow
    dzdn = -dzdn
    slope = np.degrees(np.arctan(np.hypot(dzdx, dzdn)))
    aspect = np.degrees(np.arctan2(-dzdx, -dzdn)) % 360
    aspect[(dzdx == 0) & (dzdn == 0)] = -1
    return slope, aspect


def tpi(dem, valid, radius_px):
    """Topographic Position Index: Z0 - mean of the surrounding annulus (inner = radius/4)."""
    outer = max(2, radius_px)
    inner = max(1, outer // 4)
    a_out, a_in = (2 * outer + 1) ** 2, (2 * inner + 1) ** 2
    m_out, m_in = box_mean(dem, valid, outer), box_mean(dem, valid, inner)
    return dem - (m_out * a_out - m_in * a_in) / (a_out - a_in)


def in_aspect_window(aspect, a_from, a_to):
    if a_from <= a_to:
        return (aspect >= a_from) & (aspect <= a_to)
    return (aspect >= a_from) | ((aspect >= 0) & (aspect <= a_to))


def fill_sinks(dem, valid):
    """Morphological reconstruction by erosion - the standard 'Fill Sink'."""
    seed = dem.copy()
    seed[1:-1, 1:-1] = dem.max()
    seed[~valid] = dem[~valid]
    return reconstruction(seed, dem, method="erosion")


def d8_directions(filled, valid):
    h, w = filled.shape
    pad = np.pad(filled, 1, mode="constant", constant_values=np.inf)
    vpad = np.pad(valid, 1, mode="constant", constant_values=False)
    best = np.zeros(filled.shape)
    dirs = np.full(filled.shape, -1, np.int8)
    for k in range(8):
        nb = pad[1 + DR8[k]:1 + DR8[k] + h, 1 + DC8[k]:1 + DC8[k] + w]
        nv = vpad[1 + DR8[k]:1 + DR8[k] + h, 1 + DC8[k]:1 + DC8[k] + w]
        drop = np.where(nv, (filled - nb) / DIST8[k], 0)
        better = drop > best
        best[better] = drop[better]
        dirs[better] = k
    dirs[~valid] = -1
    return dirs


@njit(cache=True)
def _accumulate(order, dirs_flat, w, dr8, dc8):
    acc = np.ones(dirs_flat.shape[0])
    for i in order:
        k = dirs_flat[i]
        if k < 0:
            continue
        r, c = i // w, i % w
        acc[(r + dr8[k]) * w + (c + dc8[k])] += acc[i]
    return acc


@njit(cache=True)
def _hand(order, dirs_flat, water_flat, filled_flat, w, dr8, dc8):
    base = np.full(filled_flat.shape[0], np.nan)
    for i in order:
        if water_flat[i]:
            base[i] = filled_flat[i]
            continue
        k = dirs_flat[i]
        if k < 0:
            continue  # never reaches water: unknown, not "at water level"
        r, c = i // w, i % w
        base[i] = base[(r + dr8[k]) * w + (c + dc8[k])]
    return filled_flat - base


def flow_accumulation(filled, dirs, valid):
    h, w = filled.shape
    flat = np.where(valid, filled, -np.inf).ravel()
    order = np.argsort(-flat, kind="stable")
    order = order[np.isfinite(flat[order])]
    return _accumulate(order, dirs.ravel().astype(np.int64), w, DR8, DC8).reshape(h, w)


def hand_strict(filled, dirs, water, valid):
    h, w = filled.shape
    flat = np.where(valid, filled, np.inf).ravel()
    order = np.argsort(flat, kind="stable")
    order = order[np.isfinite(flat[order])]
    return _hand(order, dirs.ravel().astype(np.int64), water.ravel(), filled.ravel(), w, DR8, DC8).reshape(h, w)


def distance_m(mask, res):
    if not mask.any():
        return np.full(mask.shape, np.inf)
    return ndimage.distance_transform_edt(~mask) * res


# --------------------------------------------------------------------------
# Phase 2: long-term habitation (base camps)
# --------------------------------------------------------------------------
def phase2_basecamp(dem, valid, res, flow, a):
    slope, aspect = slope_aspect(dem, res)
    tpi_r = max(2, round(a.bc_tpi_radius / res))
    t = tpi(dem, valid, tpi_r)
    cut = a.bc_tpi_sigma * np.nanstd(t[valid])
    extreme = valid & (np.abs(t) > cut)
    steep = valid & (slope >= a.bc_bluff_slope)
    solar_bluff = steep & (distance_m(extreme, res) <= tpi_r * res) & in_aspect_window(aspect, a.bc_aspect_min, a.bc_aspect_max)

    # LRM with the bluff faces masked out of the trend surface
    lrm_r = max(2, round(a.bc_lrm_radius / res))
    trend = box_mean(dem, valid & ~steep, lrm_r)
    lrm = dem - trend

    bluff_px = max(1, round(a.bc_bluff_dist / res))
    rise = ndimage.maximum_filter(dem, size=2 * bluff_px + 3) - dem
    water = flow["accum"] >= a.perennial_acres * ACRE_M2 / res ** 2
    water_dist = distance_m(water, res)
    hand = hand_strict(flow["filled"], flow["dirs"], water, valid)

    cand = (valid & ~steep & (distance_m(solar_bluff, res) <= bluff_px * res) & (t <= 0) & (rise > 0.1)
            & (rise * 3.28084 >= a.bc_min_bluff_ft) & (slope < a.bc_bench_slope) & (lrm > -0.25)
            & (water_dist <= a.bc_water_buffer) & ~(hand <= a.flood_hand))
    seg = max(4, round(a.bc_segment / res))
    rr, cc = np.indices(cand.shape)
    cand &= (rr % seg != 0) & (cc % seg != 0)

    def describe(region):
        peak_lrm = float(np.max(lrm[region]))
        props = {
            "max_bluff_height_m": round(float(np.max(rise[region])), 2),
            "dist_to_water_m": round(float(np.min(water_dist[region])), 1),
            "hand_m": None if np.all(np.isnan(hand[region])) else round(float(np.nanmedian(hand[region])), 2),
            "peak_lrm_m": round(peak_lrm, 3),
            "midden_flag": peak_lrm >= a.bc_midden,
        }
        return props, 0.15 if props["midden_flag"] else 0.0

    rasters = {"basecamp_tpi": t, "basecamp_aspect": aspect, "basecamp_lrm": lrm, "basecamp_solar_bluffs": solar_bluff.astype("float32"),
               "basecamp_hand": hand}
    return cand, rise, describe, rasters


# --------------------------------------------------------------------------
# Phase 3: transient hunt camps
# --------------------------------------------------------------------------
def saddle_mask(dem, valid, res, scale_m):
    h = max(1, round(scale_m / res))
    sm = box_mean(dem, valid, max(1, h // 2))
    hm = h * res
    p = np.pad(sm, h, mode="edge")
    H, W = sm.shape
    s = lambda dr, dc: p[h + dr:h + dr + H, h + dc:h + dc + W]
    fxx = (s(0, h) - 2 * sm + s(0, -h)) / hm ** 2
    fyy = (s(h, 0) - 2 * sm + s(-h, 0)) / hm ** 2
    fxy = (s(h, h) - s(h, -h) - s(-h, h) + s(-h, -h)) / (4 * hm ** 2)
    det = fxx * fyy - fxy ** 2
    gx = (s(0, h) - s(0, -h)) / (2 * hm)
    gy = (s(h, 0) - s(-h, 0)) / (2 * hm)
    half = (fxx + fyy) / 2
    disc = np.sqrt(np.maximum(half ** 2 - det, 0))
    min_curv = np.minimum(np.abs(half + disc), np.abs(half - disc))
    with np.errstate(divide="ignore", invalid="ignore"):
        step = np.hypot((fyy * gx - fxy * gy) / det, (fxx * gy - fxy * gx) / det)
    return (valid & (det < 0) & (np.degrees(np.arctan(np.hypot(gx, gy))) <= 8)
            & (min_curv >= 1.0 / hm ** 2) & (step <= max(res, hm / 2)))


def funnel_mask(channel, slope, res, width_m, wall_slope=30):
    half = max(1, round(width_m / 2 / res))
    steep = slope >= wall_slope
    H, W = steep.shape
    dirs = [(-1, 0), (-1, 1), (0, 1), (1, 1)]
    out = np.zeros_like(channel)
    for dr, dc in dirs:
        side_a = np.zeros_like(steep)
        side_b = np.zeros_like(steep)
        for s in range(1, half + 1):
            side_a |= np.roll(np.roll(steep, -dr * s, 0), -dc * s, 1)
            side_b |= np.roll(np.roll(steep, dr * s, 0), dc * s, 1)
        out |= channel & side_a & side_b
    return out


def viewshed(dem, valid, r0, c0, res, radius_m, observer_m, floor_fn, n_rays=720):
    z0 = dem[r0, c0] + observer_m
    rpx = max(2, round(radius_m / res))
    s = np.arange(1, rpx + 1)
    cell_ha = res * res / 1e4
    tot = floor = cross = 0.0
    H, W = dem.shape
    for th in np.linspace(0, 2 * np.pi, n_rays, endpoint=False):
        r = np.round(r0 + np.sin(th) * s).astype(int)
        c = np.round(c0 + np.cos(th) * s).astype(int)
        inside = (r >= 0) & (r < H) & (c >= 0) & (c < W)
        if not inside.any():
            continue
        stop = np.argmin(inside) if not inside.all() else len(s)
        r, c, ss = r[:stop], c[:stop], s[:stop]
        tan = (dem[r, c] - z0) / (ss * res)
        tan = np.where(valid[r, c], tan, -np.inf)
        vis = tan >= np.maximum.accumulate(np.concatenate([[-np.inf], tan[:-1]]))
        w = 2 * np.pi * ss / n_rays * cell_ha
        is_floor, is_cross = floor_fn(r, c, z0)
        tot += w[vis].sum(); floor += w[vis & is_floor].sum(); cross += w[vis & is_cross].sum()
    return tot, floor, cross


def phase3_huntcamp(dem, valid, res, flow, a):
    slope, aspect = slope_aspect(dem, res)
    t = tpi(dem, valid, max(2, round(a.hc_tpi_radius / res)))
    cut = a.hc_tpi_sigma * np.nanstd(t[valid])
    channel = flow["accum"] >= a.waterway_acres * ACRE_M2 / res ** 2
    saddles = saddle_mask(dem, valid, res, a.hc_choke_scale)
    funnels = funnel_mask(channel, slope, res, a.hc_funnel_width)
    choke = saddles | funnels
    cand = valid & (t > cut) & (t * 3.28084 >= a.hc_min_prominence_ft) & (slope >= a.hc_outcrop_slope) & (distance_m(choke, res) <= a.hc_choke_dist)

    def floor_fn(r, c, z0):
        return (slope[r, c] < 8) & (dem[r, c] < z0 - 5), channel[r, c] & (slope[r, c] < 5)

    def describe(region):
        rs, cs = np.nonzero(region)
        k = np.argmax(dem[rs, cs])
        tot, fl, cr = viewshed(dem, valid, rs[k], cs[k], res, a.hc_view_radius, a.hc_observer, floor_fn)
        tactical = fl >= a.hc_min_view_ha or cr >= 0.05
        return ({"prominence_m": round(float(t[region].max()), 2), "aspect_deg": round(float(aspect[rs[k], cs[k]]), 0),
                 "viewshed_ha": round(tot, 2), "view_valley_floor_ha": round(fl, 2), "view_crossing_ha": round(cr, 3),
                 "tactical_overlook": bool(tactical)}, 0.25 if tactical else 0.0)

    rasters = {"huntcamp_tpi": t, "huntcamp_choke_points": choke.astype("float32")}
    return cand, t, describe, rasters


# --------------------------------------------------------------------------
# Phase 4: deep ceremonial karst
# --------------------------------------------------------------------------
def load_caves(path, dst_crs):
    """Known caves as lists of projected vertex arrays + attributes (points, lines, polygons)."""
    with open(path) as f:
        gj = json.load(f)
    feats = gj["features"] if gj.get("type") == "FeatureCollection" else [gj]
    caves = []
    for ft in feats:
        geom = transform_geom("EPSG:4326", dst_crs, ft["geometry"])
        props = {k.lower(): v for k, v in (ft.get("properties") or {}).items()}

        def coords(g):
            t = g["type"]
            if t == "Point": return [np.array([g["coordinates"]])]
            if t in ("MultiPoint", "LineString"): return [np.array(g["coordinates"])]
            if t in ("MultiLineString", "Polygon"): return [np.array(x) for x in g["coordinates"]]
            if t == "MultiPolygon": return [np.array(r) for p in g["coordinates"] for r in p]
            return []
        parts = coords(geom)
        length = props.get("length_m") or props.get("length")
        if length is None and geom["type"] in ("LineString", "MultiLineString"):
            length = sum(float(np.hypot(*np.diff(p[:, :2], axis=0).T).sum()) for p in parts)
        caves.append({"name": props.get("name") or props.get("cave_name"), "length_m": float(length) if length else None,
                      "parts": parts, "polygon": geom["type"] in ("Polygon", "MultiPolygon")})
    return caves


def phase4_ceremonial(dem, valid, res, flow, a, transform, crs):
    filled = flow["filled"]
    depth = np.where(valid, filled - dem, 0)
    cand = valid & (depth > a.cer_min_depth)
    interior = np.zeros_like(valid)
    interior[1:-1, 1:-1] = True
    basin = valid & ((depth > 1e-3) | ((flow["dirs"] < 0) & interior))
    labels, n = ndimage.label(basin, structure=np.ones((3, 3)))
    # Sinking streams: channel cells draining onto a filled basin surface
    thr = a.sink_stream_acres * ACRE_M2 / res ** 2
    H, W = dem.shape
    rr, cc = np.nonzero((flow["accum"] >= thr) & (flow["dirs"] >= 0) & (labels == 0))
    k = flow["dirs"][rr, cc]
    tr, tc = rr + DR8[k], cc + DC8[k]
    ok = (tr >= 0) & (tr < H) & (tc >= 0) & (tc < W)
    rr, cc, tr, tc = rr[ok], cc[ok], tr[ok], tc[ok]
    hit = labels[tr, tc] > 0
    inflow = np.zeros(n + 1)
    np.maximum.at(inflow, labels[tr[hit], tc[hit]], flow["accum"][rr[hit], cc[hit]] * res ** 2 / ACRE_M2)
    caves = load_caves(a.caves, crs) if a.caves else []

    def describe(region):
        ls = np.unique(labels[region]); ls = ls[ls > 0]
        inflow_ac = float(inflow[ls].max()) if len(ls) else 0.0
        area = region.sum() * res ** 2
        props = {"max_depth_m": round(float(depth[region].max()), 2), "sinking_stream_inflow_acres": round(inflow_ac, 2),
                 "karst_window_flag": bool(2 * math.sqrt(area / math.pi) >= 30 and depth[region].max() >= 3)}
        boost = (0.15 if inflow_ac > 0 else 0) + (0.1 if props["karst_window_flag"] else 0)
        if caves:
            rs, cs = np.nonzero(region)
            x, y = rasterio.transform.xy(transform, rs.mean(), cs.mean())
            best = None
            for cv in caves:
                d = min(float(np.min(np.hypot(p[:, 0] - x, p[:, 1] - y))) for p in cv["parts"])
                if cv["polygon"] and any(_point_in_ring(x, y, p) for p in cv["parts"]):
                    d = 0.0
                if best is None or d < best[0]:
                    best = (d, cv)
            d, cv = best
            if crs.is_geographic:
                d *= 111320
            props.update({"nearest_cave": cv["name"], "nearest_cave_m": round(d, 1), "cave_length_m": cv["length_m"],
                          "dark_zone_verified": bool(d <= a.cave_match_dist and cv["length_m"] and cv["length_m"] >= a.dark_zone_len)})
            boost += 0.3 if props["dark_zone_verified"] else (0.1 if d <= a.cave_match_dist else 0)
        return props, boost

    keep = (lambda props: props["sinking_stream_inflow_acres"] > 0) if a.require_sinking_stream else None
    rasters = {"ceremonial_fill_depth": depth, "ceremonial_flow_accum_acres": flow["accum"] * res ** 2 / ACRE_M2}
    return cand, depth, describe, rasters, keep


def _point_in_ring(x, y, ring):
    hit = False
    for i in range(len(ring)):
        x1, y1 = ring[i - 1][:2]; x2, y2 = ring[i][:2]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            hit = not hit
    return hit


# --------------------------------------------------------------------------
# Candidate extraction -> GeoJSON
# --------------------------------------------------------------------------
def extract_candidates(mode, cand, metric, describe, keep, res, profile, a):
    labels, n = ndimage.label(cand, structure=[[0, 1, 0], [1, 1, 1], [0, 1, 0]])
    H, W = cand.shape
    min_area = max(4, a.min_area_m2 / res ** 2)
    out = []
    objs = ndimage.find_objects(labels)
    for idx, sl in enumerate(objs, start=1):
        if sl is None:
            continue
        region_local = labels[sl] == idx
        if region_local.sum() < min_area:
            continue
        if sl[0].start == 0 or sl[1].start == 0 or sl[0].stop == H or sl[1].stop == W:
            continue  # clipped by the tile edge: true extent unknown
        region = np.zeros(cand.shape, bool)
        region[sl] = region_local
        props, boost = describe(region)
        if keep and not keep(props):
            continue
        base = min(1.0, float(metric[region].max()) / 10.0) * 0.6 + 0.4 * min(1.0, region.sum() * res ** 2 / 2000)
        score = base + (1 - base) * min(1.0, boost)
        geom = next(g for g, v in features.shapes(region_local.astype("uint8"), mask=region_local,
                                                  transform=rasterio.windows.transform(rasterio.windows.Window.from_slices(*sl), profile["transform"])) if v == 1)
        geom = transform_geom(profile["crs"], "EPSG:4326", geom)
        props.update({"type": mode, "area_m2": round(float(region.sum() * res ** 2), 1), "score_pct": round(score * 100)})
        out.append({"type": "Feature", "geometry": geom, "properties": props})
    out.sort(key=lambda f: -f["properties"]["score_pct"])
    return out


# --------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dtm", help="bare-earth DTM GeoTIFF (projected CRS in meters recommended)")
    ap.add_argument("--out", default="karst_results", help="output folder")
    ap.add_argument("--mode", default="all", choices=["all", "basecamp", "huntcamp", "ceremonial"])
    ap.add_argument("--allow-coarse", action="store_true", help="run even if the DTM is coarser than 1 m")
    ap.add_argument("--min-area-m2", dest="min_area_m2", type=float, default=10.0)
    ap.add_argument("--waterway-acres", type=float, default=1.0, help="drainage area defining a channel (acres)")
    # Phase 2
    g = ap.add_argument_group("Phase 2 - base camps")
    g.add_argument("--bc-tpi-radius", type=float, default=30, help="TPI neighborhood radius, m (10-50)")
    g.add_argument("--bc-tpi-sigma", type=float, default=1.0, help="TPI extreme threshold, std devs")
    g.add_argument("--bc-bluff-slope", type=float, default=40, help="min bluff face slope, deg")
    g.add_argument("--bc-min-bluff-ft", type=float, default=10, help="min bluff height above the bench, ft")
    g.add_argument("--bc-aspect-min", type=float, default=135)
    g.add_argument("--bc-aspect-max", type=float, default=225)
    g.add_argument("--bc-lrm-radius", type=float, default=20, help="LRM smoothing radius, m")
    g.add_argument("--bc-bench-slope", type=float, default=12, help="max bench slope, deg")
    g.add_argument("--bc-bluff-dist", type=float, default=25, help="max bench-to-bluff distance, m")
    g.add_argument("--bc-segment", type=float, default=60, help="bench segment length, m")
    g.add_argument("--bc-midden", type=float, default=0.3, help="LRM mounding that flags a possible midden, m")
    g.add_argument("--bc-water-buffer", type=float, default=300, help="year-round water buffer, m (100-300)")
    g.add_argument("--perennial-acres", type=float, default=100, help="drainage area of a year-round stream, acres")
    g.add_argument("--flood-hand", type=float, default=3.0, help="100-yr floodplain cutoff, m above drainage")
    # Phase 3
    g = ap.add_argument_group("Phase 3 - hunt camps")
    g.add_argument("--hc-tpi-radius", type=float, default=10)
    g.add_argument("--hc-tpi-sigma", type=float, default=1.5)
    g.add_argument("--hc-min-prominence-ft", type=float, default=2)
    g.add_argument("--hc-outcrop-slope", type=float, default=35)
    g.add_argument("--hc-choke-scale", type=float, default=30, help="terrain scale for saddles, m")
    g.add_argument("--hc-choke-dist", type=float, default=75, help="max outcrop-to-choke-point distance, m")
    g.add_argument("--hc-funnel-width", type=float, default=40, help="max canyon funnel width, m")
    g.add_argument("--hc-view-radius", type=float, default=1000)
    g.add_argument("--hc-observer", type=float, default=1.6)
    g.add_argument("--hc-min-view-ha", type=float, default=5)
    # Phase 4
    g = ap.add_argument_group("Phase 4 - ceremonial karst")
    g.add_argument("--cer-min-depth", type=float, default=0.6, help="min closed-depression depth, m")
    g.add_argument("--sink-stream-acres", type=float, default=5)
    g.add_argument("--require-sinking-stream", action="store_true")
    g.add_argument("--caves", help="known caves GeoJSON (WGS84) for the speleological overlay")
    g.add_argument("--cave-match-dist", type=float, default=100)
    g.add_argument("--dark-zone-len", type=float, default=150)
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    dem_raw, valid, profile, res = load_dtm(a.dtm)
    print(f"DTM {profile['width']}x{profile['height']} at {res:.2f} m/px")
    if res > 1.0 + 1e-6 and not a.allow_coarse:
        sys.exit(f"DTM resolution {res:.2f} m is coarser than the 1 m minimum for human-scale features. Use --allow-coarse to override.")
    dem = ndimage.uniform_filter(fill_nodata(dem_raw, valid), 3) if res < 0.75 else fill_nodata(dem_raw, valid)

    print("Hydrology: Fill Sink, D8 flow direction and accumulation...")
    filled = fill_sinks(dem, valid)
    dirs = d8_directions(filled, valid)
    flow = {"filled": filled, "dirs": dirs, "accum": flow_accumulation(filled, dirs, valid)}

    modes = ["basecamp", "huntcamp", "ceremonial"] if a.mode == "all" else [a.mode]
    all_feats = []
    for mode in modes:
        print(f"Running {mode}...")
        keep = None
        if mode == "basecamp":
            cand, metric, describe, rasters = phase2_basecamp(dem, valid, res, flow, a)
        elif mode == "huntcamp":
            cand, metric, describe, rasters = phase3_huntcamp(dem, valid, res, flow, a)
        else:
            cand, metric, describe, rasters, keep = phase4_ceremonial(dem, valid, res, flow, a, profile["transform"], profile["crs"])
        for name, arr in rasters.items():
            write_tif(os.path.join(a.out, f"{name}.tif"), np.where(valid & np.isfinite(arr), arr, -9999), profile)
        write_tif(os.path.join(a.out, f"{mode}_candidates.tif"), cand.astype("uint8"), profile, nodata=0, dtype="uint8")
        feats = extract_candidates(mode, cand, metric, describe, keep, res, profile, a)
        with open(os.path.join(a.out, f"{mode}_candidates.geojson"), "w") as f:
            json.dump({"type": "FeatureCollection", "features": feats}, f)
        print(f"  {len(feats)} candidate(s)")
        all_feats += feats

    with open(os.path.join(a.out, "all_candidates.geojson"), "w") as f:
        json.dump({"type": "FeatureCollection", "features": all_feats}, f)
    write_wgs84_copy(os.path.join(a.out, "dtm_wgs84.tif"), dem_raw, valid, profile)
    print(f"Done. Outputs in {a.out}/ - open dtm_wgs84.tif and all_candidates.geojson in the web app for 3D review.")


if __name__ == "__main__":
    main()
