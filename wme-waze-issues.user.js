// ==UserScript==
// @name            WME — Drive reports (waze-issues)
// @description     Show driving map reports from waze-issues.ster.by: speed-limit road signs and speed-bump markers; Done / Dismiss.
// @namespace       https://github.com/ixxvivxxi/wme-scripts
// @homepageURL     https://github.com/ixxvivxxi/waze-issues
// @version         2026.07.26.005
// @match           https://www.waze.com/*/editor*
// @match           https://www.waze.com/editor*
// @match           https://beta.waze.com/*/editor*
// @match           https://beta.waze.com/editor*
// @exclude         https://www.waze.com/*user/*editor/*
// @grant           GM_xmlhttpRequest
// @inject-into     page
// @run-at          document-idle
// @connect         waze-issues.ster.by
// @connect         127.0.0.1
// @connect         localhost
// ==/UserScript==

/* global OpenLayers, GM_xmlhttpRequest, unsafeWindow */
/* jshint esversion: 11 */

(function () {
  'use strict';

  const SCRIPT_ID = 'wme-waze-issues';
  const SCRIPT_NAME = 'Drive reports';
  const STORAGE_API_BASE = 'wmeWazeIssues_apiBase';
  const STORAGE_FOLLOW = 'wmeWazeIssues_followMap';
  const DEFAULT_API_BASE = 'https://waze-issues.ster.by';
  const ICON_PX = 36;
  const MAX_BBOX_SPAN_DEG = 0.34;
  /** GPS accuracy disc — strong enough to see at street zoom. */
  const ACC_FILL = 'rgba(25, 118, 210, 0.38)';
  const ACC_STROKE = 'rgba(13, 71, 161, 0.95)';
  const ACC_STROKE_WIDTH = 2.5;
  const ACC_RING_POINTS = 48;
  /** Travel direction arrow length / head size in meters. */
  const HEADING_ARROW_M = 28;
  const HEADING_HEAD_M = 9;
  const HEADING_STROKE = '#e65100';
  const HEADING_FILL = 'rgba(230, 81, 0, 0.9)';

  let sdk = null;
  let statusEl = null;
  let apiBaseEl = null;
  let followEl = null;
  let popupEl = null;
  let ol2Layer = null;
  let ol2AccLayer = null;
  let ol6Layer = null;
  let cachedOl6 = null;
  let lastViewportKey = '';
  let fetchInFlight = false;
  let mapHooksOk = false;
  let mapWatchTries = 0;
  let pollTimer = null;
  /** @type {Record<string, string>} */
  const iconCache = Object.create(null);
  /** @type {any[]} */
  let lastReports = [];

  function pageWin() {
    return typeof unsafeWindow !== 'undefined' ? unsafeWindow : window;
  }

  function W() {
    return pageWin().W;
  }

  function storageGet(key, fallback) {
    try {
      const v = localStorage.getItem(key);
      return v == null || v === '' ? fallback : v;
    } catch (_) {
      return fallback;
    }
  }

  function storageSet(key, value) {
    try {
      localStorage.setItem(key, String(value));
    } catch (_) {}
  }

  function storageGetBool(key, fallback) {
    const v = storageGet(key, fallback ? '1' : '0');
    return v === '1' || v === 'true';
  }

  function debounce(fn, ms) {
    let t = null;
    return function () {
      const self = this;
      const args = arguments;
      if (t) clearTimeout(t);
      t = setTimeout(function () {
        t = null;
        fn.apply(self, args);
      }, ms);
    };
  }

  function setStatus(text) {
    if (statusEl) statusEl.textContent = text || '';
  }

  function apiBase() {
    const v = (apiBaseEl && apiBaseEl.value.trim()) || storageGet(STORAGE_API_BASE, DEFAULT_API_BASE);
    return v.replace(/\/+$/, '');
  }

  function httpJson(method, url, bodyObj) {
    return new Promise(function (resolve, reject) {
      const headers = {
        Accept: 'application/json',
      };
      let data = undefined;
      if (bodyObj != null) {
        headers['Content-Type'] = 'application/json';
        data = JSON.stringify(bodyObj);
      }
      if (typeof GM_xmlhttpRequest === 'function') {
        GM_xmlhttpRequest({
          method: method,
          url: url,
          anonymous: true,
          headers: headers,
          data: data,
          timeout: 60_000,
          onload: function (resp) {
            if (resp.status >= 200 && resp.status < 300) {
              try {
                resolve(resp.responseText ? JSON.parse(resp.responseText) : null);
              } catch (e) {
                reject(new Error('Invalid JSON'));
              }
            } else {
              reject(
                new Error(
                  'HTTP ' + resp.status + ' ' + String(resp.responseText || '').slice(0, 200),
                ),
              );
            }
          },
          onerror: function () {
            reject(new Error('Network error'));
          },
          ontimeout: function () {
            reject(new Error('Timeout'));
          },
        });
        return;
      }
      const opts = { method: method, headers: headers, credentials: 'omit' };
      if (data != null) opts.body = data;
      fetch(url, opts)
        .then(function (res) {
          if (!res.ok) {
            return res.text().then(function (t) {
              throw new Error('HTTP ' + res.status + (t ? ': ' + t.slice(0, 160) : ''));
            });
          }
          if (res.status === 204) return null;
          return res.json();
        })
        .then(resolve)
        .catch(reject);
    });
  }

  /** Belarus / EU style circular speed-limit sign (3.24). */
  function speedLimitEndSignDataUrl() {
    const key = 'sl:end';
    if (iconCache[key]) return iconCache[key];
    const s = ICON_PX;
    const c = document.createElement('canvas');
    c.width = s;
    c.height = s;
    const ctx = c.getContext('2d');
    const cx = s / 2;
    const cy = s / 2;
    const r = s / 2 - 1.5;

    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.fillStyle = '#ffffff';
    ctx.fill();
    ctx.lineWidth = Math.max(2.5, s * 0.08);
    ctx.strokeStyle = '#7a7a7a';
    ctx.stroke();

    const inset = s * 0.22;
    ctx.beginPath();
    ctx.moveTo(inset, inset);
    ctx.lineTo(s - inset, s - inset);
    ctx.lineWidth = Math.max(3, s * 0.14);
    ctx.strokeStyle = '#7a7a7a';
    ctx.lineCap = 'round';
    ctx.stroke();

    iconCache[key] = c.toDataURL('image/png');
    return iconCache[key];
  }

  function speedLimitSignDataUrl(kmh) {
    if (kmh === 0 || kmh === '0') return speedLimitEndSignDataUrl();
    const key = 'sl:' + kmh;
    if (iconCache[key]) return iconCache[key];
    const s = ICON_PX;
    const c = document.createElement('canvas');
    c.width = s;
    c.height = s;
    const ctx = c.getContext('2d');
    const cx = s / 2;
    const cy = s / 2;
    const r = s / 2 - 1.5;

    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.fillStyle = '#ffffff';
    ctx.fill();
    ctx.lineWidth = Math.max(3, s * 0.12);
    ctx.strokeStyle = '#e30613';
    ctx.stroke();

    const text = String(kmh);
    ctx.fillStyle = '#111111';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    let fontSize = text.length >= 3 ? s * 0.38 : s * 0.48;
    ctx.font = 'bold ' + fontSize + 'px Arial, Helvetica, sans-serif';
    ctx.fillText(text, cx, cy + 1);

    iconCache[key] = c.toDataURL('image/png');
    return iconCache[key];
  }

  /** Speed bump add: yellow diamond warning-style. */
  function bumpSignDataUrl(removed) {
    const key = removed ? 'bump:rm' : 'bump:add';
    if (iconCache[key]) return iconCache[key];
    const s = ICON_PX;
    const c = document.createElement('canvas');
    c.width = s;
    c.height = s;
    const ctx = c.getContext('2d');
    const cx = s / 2;
    const cy = s / 2;
    const half = s * 0.38;

    ctx.beginPath();
    ctx.moveTo(cx, cy - half);
    ctx.lineTo(cx + half, cy);
    ctx.lineTo(cx, cy + half);
    ctx.lineTo(cx - half, cy);
    ctx.closePath();
    ctx.fillStyle = '#f7d117';
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = '#111';
    ctx.stroke();

    // bump silhouette
    ctx.beginPath();
    ctx.moveTo(cx - half * 0.55, cy + half * 0.15);
    ctx.quadraticCurveTo(cx, cy - half * 0.35, cx + half * 0.55, cy + half * 0.15);
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = '#111';
    ctx.stroke();

    if (removed) {
      ctx.beginPath();
      ctx.moveTo(cx - half * 0.45, cy - half * 0.45);
      ctx.lineTo(cx + half * 0.45, cy + half * 0.45);
      ctx.moveTo(cx + half * 0.45, cy - half * 0.45);
      ctx.lineTo(cx - half * 0.45, cy + half * 0.45);
      ctx.lineWidth = 2.5;
      ctx.strokeStyle = '#c62828';
      ctx.stroke();
    }

    iconCache[key] = c.toDataURL('image/png');
    return iconCache[key];
  }

  /** Generic map issue: orange circle with exclamation. */
  function generalIssueDataUrl() {
    const key = 'general';
    if (iconCache[key]) return iconCache[key];
    const s = ICON_PX;
    const c = document.createElement('canvas');
    c.width = s;
    c.height = s;
    const ctx = c.getContext('2d');
    const cx = s / 2;
    const cy = s / 2;
    const r = s / 2 - 1.5;
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.fillStyle = '#ffc107';
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = '#212121';
    ctx.stroke();
    ctx.fillStyle = '#212121';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.font = 'bold ' + s * 0.55 + 'px Arial, Helvetica, sans-serif';
    ctx.fillText('!', cx, cy + 1);
    iconCache[key] = c.toDataURL('image/png');
    return iconCache[key];
  }

  function iconForReport(r) {
    if (r.issueType === 'speed_limit') {
      const kmh = r.payload && r.payload.valueKmh != null ? Number(r.payload.valueKmh) : NaN;
      if (kmh === 0) return speedLimitEndSignDataUrl();
      return speedLimitSignDataUrl(Number.isFinite(kmh) ? kmh : '?');
    }
    if (r.issueType === 'general') return generalIssueDataUrl();
    if (r.issueType === 'speed_bump_remove') return bumpSignDataUrl(true);
    if (r.issueType === 'speed_bump_add') return bumpSignDataUrl(false);
    return generalIssueDataUrl();
  }

  function labelForReport(r) {
    if (r.issueType === 'speed_limit') {
      const kmh = r.payload && r.payload.valueKmh != null ? r.payload.valueKmh : '?';
      if (Number(kmh) === 0) return 'End of speed limit';
      return String(kmh) + ' km/h';
    }
    if (r.issueType === 'general') return 'General issue';
    if (r.issueType === 'speed_bump_remove') return 'Bump removed';
    if (r.issueType === 'speed_bump_add') return 'Bump added';
    return r.issueType || 'Issue';
  }

  function accuracyMeters(r) {
    const acc = r && r.payload && r.payload.accuracyM;
    const n = Number(acc);
    return Number.isFinite(n) && n > 0 ? n : null;
  }

  /** WGS84 ring around (lon,lat) with geodesic radius in meters. */
  function accuracyRingLonLat(lon, lat, radiusM) {
    const ring = [];
    for (let i = 0; i <= ACC_RING_POINTS; i++) {
      const brng = (i * 360) / ACC_RING_POINTS;
      ring.push(destinationLonLat(lon, lat, brng, radiusM));
    }
    return ring;
  }

  function destinationLonLat(lon, lat, bearingDeg, distM) {
    const R = 6378137;
    const brng = (bearingDeg * Math.PI) / 180;
    const lat1 = (lat * Math.PI) / 180;
    const lon1 = (lon * Math.PI) / 180;
    const ang = distM / R;
    const lat2 = Math.asin(
      Math.sin(lat1) * Math.cos(ang) +
        Math.cos(lat1) * Math.sin(ang) * Math.cos(brng),
    );
    const lon2 =
      lon1 +
      Math.atan2(
        Math.sin(brng) * Math.sin(ang) * Math.cos(lat1),
        Math.cos(ang) - Math.sin(lat1) * Math.sin(lat2),
      );
    return [(lon2 * 180) / Math.PI, (lat2 * 180) / Math.PI];
  }

  function headingDegOf(r) {
    const h = r && r.headingDeg;
    const n = Number(h);
    return Number.isFinite(n) ? n : null;
  }

  /** Shaft + arrowhead triangle in WGS84 for travel direction. */
  function headingArrowLonLat(lon, lat, headingDeg) {
    const tip = destinationLonLat(lon, lat, headingDeg, HEADING_ARROW_M);
    const left = destinationLonLat(tip[0], tip[1], headingDeg + 155, HEADING_HEAD_M);
    const right = destinationLonLat(tip[0], tip[1], headingDeg - 155, HEADING_HEAD_M);
    return {
      shaft: [
        [lon, lat],
        tip,
      ],
      head: [left, tip, right, left],
    };
  }

  function packBBox(minLon, minLat, maxLon, maxLat) {
    const a = Math.min(minLon, maxLon);
    const b = Math.min(minLat, maxLat);
    const c = Math.max(minLon, maxLon);
    const d = Math.max(minLat, maxLat);
    if (![a, b, c, d].every(Number.isFinite)) return null;
    if (c - a > MAX_BBOX_SPAN_DEG || d - b > MAX_BBOX_SPAN_DEG) {
      const cx = (a + c) / 2;
      const cy = (b + d) / 2;
      const half = MAX_BBOX_SPAN_DEG / 2;
      return {
        minLon: cx - half,
        minLat: cy - half,
        maxLon: cx + half,
        maxLat: cy + half,
      };
    }
    return { minLon: a, minLat: b, maxLon: c, maxLat: d };
  }

  async function getViewportBBox() {
    try {
      if (sdk && sdk.Map && typeof sdk.Map.getMapExtent === 'function') {
        const raw = sdk.Map.getMapExtent();
        const ext = raw && typeof raw.then === 'function' ? await raw : raw;
        if (ext && ext.length >= 4) {
          return packBBox(Number(ext[0]), Number(ext[1]), Number(ext[2]), Number(ext[3]));
        }
      }
    } catch (_) {}

    const mapW = W();
    if (!mapW || !mapW.map || typeof mapW.map.getOLMap !== 'function') return null;
    const olm = mapW.map.getOLMap();
    if (!olm) return null;

    const olGlobal = pageWin().ol;
    if (olGlobal && olGlobal.proj && typeof olm.getView === 'function') {
      try {
        const view = olm.getView();
        const size = typeof olm.getSize === 'function' ? olm.getSize() : null;
        if (view && size && typeof view.calculateExtent === 'function') {
          const ext = view.calculateExtent(size);
          const fromProj = view.getProjection();
          if (ext && fromProj) {
            const sw = olGlobal.proj.transform([ext[0], ext[1]], fromProj, 'EPSG:4326');
            const ne = olGlobal.proj.transform([ext[2], ext[3]], fromProj, 'EPSG:4326');
            return packBBox(sw[0], sw[1], ne[0], ne[1]);
          }
        }
      } catch (_) {}
    }

    if (typeof OpenLayers !== 'undefined' && typeof olm.getExtent === 'function') {
      try {
        const ext = olm.getExtent();
        const proj =
          typeof olm.getProjectionObject === 'function'
            ? olm.getProjectionObject()
            : olm.projection;
        if (!ext || !proj) return null;
        const wgs = new OpenLayers.Projection('EPSG:4326');
        const sw = new OpenLayers.LonLat(ext[0], ext[1]).transform(proj, wgs);
        const ne = new OpenLayers.LonLat(ext[2], ext[3]).transform(proj, wgs);
        return packBBox(sw.lon, sw.lat, ne.lon, ne.lat);
      } catch (_) {}
    }
    return null;
  }

  function getZoom() {
    try {
      const mapW = W();
      if (mapW && mapW.map && typeof mapW.map.getZoom === 'function') {
        const z = Number(mapW.map.getZoom());
        if (Number.isFinite(z)) return z;
      }
    } catch (_) {}
    try {
      if (sdk && sdk.Map && typeof sdk.Map.getZoomLevel === 'function') {
        const z = Number(sdk.Map.getZoomLevel());
        if (Number.isFinite(z)) return z;
      }
    } catch (_) {}
    return null;
  }

  function ensureOl2Layer(olm) {
    if (ol2Layer || typeof OpenLayers === 'undefined') return;
    try {
      ol2AccLayer = new OpenLayers.Layer.Vector(SCRIPT_ID + '-accuracy', {
        styleMap: new OpenLayers.StyleMap(
          new OpenLayers.Style(
            {
              fillColor: '${fillColor}',
              fillOpacity: '${fillOpacity}',
              strokeColor: '${strokeColor}',
              strokeOpacity: 0.95,
              strokeWidth: '${strokeWidth}',
              strokeLinecap: 'round',
              strokeLinejoin: 'round',
            },
            {
              context: {
                fillColor: function (f) {
                  return f.attributes.kind === 'heading' ? '#e65100' : '#1976d2';
                },
                fillOpacity: function (f) {
                  return f.attributes.kind === 'heading' ? 0.9 : 0.38;
                },
                strokeColor: function (f) {
                  return f.attributes.kind === 'heading' ? '#bf360c' : '#0d47a1';
                },
                strokeWidth: function (f) {
                  return f.attributes.kind === 'heading' ? 4 : ACC_STROKE_WIDTH;
                },
              },
            },
          ),
        ),
        renderers: ['Canvas', 'SVG', 'VML'],
        displayInLayerSwitcher: false,
      });
      olm.addLayer(ol2AccLayer);

      const style = new OpenLayers.Style({
        externalGraphic: '${iconUrl}',
        graphicWidth: ICON_PX,
        graphicHeight: ICON_PX,
        graphicXOffset: -ICON_PX / 2,
        graphicYOffset: -ICON_PX / 2,
        graphicTitle: '${title}',
        cursor: 'pointer',
      });
      ol2Layer = new OpenLayers.Layer.Vector(SCRIPT_ID + '-layer', {
        styleMap: new OpenLayers.StyleMap({ default: style }),
        renderers: ['Canvas', 'SVG', 'VML'],
        displayInLayerSwitcher: false,
      });
      olm.addLayer(ol2Layer);
      ol2Layer.events.register('featureclick', ol2Layer, function (e) {
        const f = e && e.feature;
        const r = f && f.attributes && f.attributes.report;
        if (r) showPopup(r, e);
        return false;
      });
      // OL2 often needs select control for clicks
      try {
        const sel = new OpenLayers.Control.SelectFeature(ol2Layer, {
          hover: false,
          clickout: true,
          onSelect: function (feat) {
            const r = feat && feat.attributes && feat.attributes.report;
            if (r) showPopup(r, null);
          },
        });
        olm.addControl(sel);
        sel.activate();
      } catch (_) {}
    } catch (e) {
      console.warn('[Drive reports] OL2 layer:', e);
      ol2Layer = null;
      ol2AccLayer = null;
    }
  }

  function resolveOl6(olm) {
    if (cachedOl6) return cachedOl6;
    const win = pageWin();
    if (win.ol && win.ol.layer && win.ol.layer.Vector) {
      cachedOl6 = win.ol;
      return cachedOl6;
    }
    try {
      if (olm && olm.constructor && olm.constructor.prototype) {
        // WME sometimes keeps ol on the map instance module — skip if missing
      }
    } catch (_) {}
    return null;
  }

  function ensureOl6Layer(olm) {
    if (ol6Layer) return;
    const ol = resolveOl6(olm);
    if (!ol || !ol.layer || !ol.source || !ol.geom || !ol.style) return;
    try {
      const src = new ol.source.Vector();
      ol6Layer = new ol.layer.Vector({
        source: src,
        zIndex: 900,
        style: function (feature) {
          const kind = feature.get('kind');
          if (kind === 'accuracy') {
            return new ol.style.Style({
              fill: new ol.style.Fill({ color: ACC_FILL }),
              stroke: new ol.style.Stroke({
                color: ACC_STROKE,
                width: ACC_STROKE_WIDTH,
              }),
            });
          }
          if (kind === 'heading-shaft') {
            return new ol.style.Style({
              stroke: new ol.style.Stroke({
                color: HEADING_STROKE,
                width: 4,
                lineCap: 'round',
                lineJoin: 'round',
              }),
            });
          }
          if (kind === 'heading-head') {
            return new ol.style.Style({
              fill: new ol.style.Fill({ color: HEADING_FILL }),
              stroke: new ol.style.Stroke({ color: '#bf360c', width: 1.5 }),
            });
          }
          const url = feature.get('iconUrl');
          return new ol.style.Style({
            image: new ol.style.Icon({
              src: url,
              scale: 1,
              anchor: [0.5, 0.5],
            }),
          });
        },
      });
      olm.addLayer(ol6Layer);
      olm.on('click', function (evt) {
        const feat = olm.forEachFeatureAtPixel(evt.pixel, function (f, layer) {
          if (layer === ol6Layer) return f;
          return null;
        });
        if (feat) {
          const r = feat.get('report');
          if (r) showPopup(r, evt);
        }
      });
    } catch (e) {
      console.warn('[Drive reports] OL6 layer:', e);
      ol6Layer = null;
    }
  }

  function clearLayers() {
    try {
      if (ol2Layer && ol2Layer.destroyFeatures) ol2Layer.destroyFeatures();
    } catch (_) {}
    try {
      if (ol2AccLayer && ol2AccLayer.destroyFeatures) ol2AccLayer.destroyFeatures();
    } catch (_) {}
    try {
      if (ol6Layer) {
        const src = ol6Layer.getSource && ol6Layer.getSource();
        if (src && src.clear) src.clear();
      }
    } catch (_) {}
    lastReports = [];
  }

  function setLayersVisible(v) {
    try {
      if (ol2Layer && ol2Layer.setVisibility) ol2Layer.setVisibility(!!v);
    } catch (_) {}
    try {
      if (ol2AccLayer && ol2AccLayer.setVisibility) ol2AccLayer.setVisibility(!!v);
    } catch (_) {}
    try {
      if (ol6Layer && ol6Layer.setVisible) ol6Layer.setVisible(!!v);
    } catch (_) {}
  }

  function fillOl2(olm, reports) {
    ensureOl2Layer(olm);
    if (!ol2Layer) return;
    ol2Layer.destroyFeatures();
    if (ol2AccLayer) ol2AccLayer.destroyFeatures();
    const proj =
      typeof olm.getProjectionObject === 'function'
        ? olm.getProjectionObject()
        : olm.projection;
    if (!proj) return;
    const wgs = new OpenLayers.Projection('EPSG:4326');
    const markers = [];
    const overlays = [];
    for (let i = 0; i < reports.length; i++) {
      const r = reports[i];
      const lon = Number(r.lon);
      const lat = Number(r.lat);
      if (!Number.isFinite(lon) || !Number.isFinite(lat)) continue;
      const acc = accuracyMeters(r);
      if (acc != null && ol2AccLayer) {
        const ring = accuracyRingLonLat(lon, lat, acc);
        const pts = [];
        for (let j = 0; j < ring.length; j++) {
          const p = new OpenLayers.Geometry.Point(ring[j][0], ring[j][1]).transform(
            wgs,
            proj,
          );
          pts.push(p);
        }
        overlays.push(
          new OpenLayers.Feature.Vector(
            new OpenLayers.Geometry.Polygon([new OpenLayers.Geometry.LinearRing(pts)]),
            { kind: 'accuracy', report: r },
          ),
        );
      }
      const heading = headingDegOf(r);
      if (heading != null && ol2AccLayer) {
        const arrow = headingArrowLonLat(lon, lat, heading);
        const shaftPts = arrow.shaft.map(function (ll) {
          return new OpenLayers.Geometry.Point(ll[0], ll[1]).transform(wgs, proj);
        });
        overlays.push(
          new OpenLayers.Feature.Vector(new OpenLayers.Geometry.LineString(shaftPts), {
            kind: 'heading',
            report: r,
          }),
        );
        const headPts = arrow.head.map(function (ll) {
          return new OpenLayers.Geometry.Point(ll[0], ll[1]).transform(wgs, proj);
        });
        overlays.push(
          new OpenLayers.Feature.Vector(
            new OpenLayers.Geometry.Polygon([new OpenLayers.Geometry.LinearRing(headPts)]),
            { kind: 'heading', report: r },
          ),
        );
      }
      const ll = new OpenLayers.LonLat(lon, lat).transform(wgs, proj);
      const geom = new OpenLayers.Geometry.Point(ll.lon, ll.lat);
      markers.push(
        new OpenLayers.Feature.Vector(geom, {
          report: r,
          iconUrl: iconForReport(r),
          title: labelForReport(r),
        }),
      );
    }
    if (overlays.length && ol2AccLayer) ol2AccLayer.addFeatures(overlays);
    if (markers.length) ol2Layer.addFeatures(markers);
  }

  function fillOl6(olm, reports) {
    ensureOl6Layer(olm);
    if (!ol6Layer) return;
    const ol = resolveOl6(olm);
    if (!ol) return;
    const src = ol6Layer.getSource();
    src.clear();
    const view = olm.getView && olm.getView();
    const fromProj = view && view.getProjection && view.getProjection();
    if (!fromProj || !ol.proj) return;
    const feats = [];
    for (let i = 0; i < reports.length; i++) {
      const r = reports[i];
      const lon = Number(r.lon);
      const lat = Number(r.lat);
      if (!Number.isFinite(lon) || !Number.isFinite(lat)) continue;
      const acc = accuracyMeters(r);
      if (acc != null) {
        const ring = accuracyRingLonLat(lon, lat, acc).map(function (ll) {
          return ol.proj.fromLonLat(ll, fromProj);
        });
        feats.push(
          new ol.Feature({
            geometry: new ol.geom.Polygon([ring]),
            kind: 'accuracy',
            report: r,
          }),
        );
      }
      const heading = headingDegOf(r);
      if (heading != null) {
        const arrow = headingArrowLonLat(lon, lat, heading);
        const shaft = arrow.shaft.map(function (ll) {
          return ol.proj.fromLonLat(ll, fromProj);
        });
        const head = arrow.head.map(function (ll) {
          return ol.proj.fromLonLat(ll, fromProj);
        });
        feats.push(
          new ol.Feature({
            geometry: new ol.geom.LineString(shaft),
            kind: 'heading-shaft',
            report: r,
          }),
        );
        feats.push(
          new ol.Feature({
            geometry: new ol.geom.Polygon([head]),
            kind: 'heading-head',
            report: r,
          }),
        );
      }
      const xy = ol.proj.fromLonLat([lon, lat], fromProj);
      feats.push(
        new ol.Feature({
          geometry: new ol.geom.Point(xy),
          kind: 'marker',
          report: r,
          iconUrl: iconForReport(r),
        }),
      );
    }
    if (feats.length) src.addFeatures(feats);
  }

  function applyReports(reports) {
    lastReports = reports || [];
    const mapW = W();
    if (!mapW || !mapW.map || typeof mapW.map.getOLMap !== 'function') return;
    const olm = mapW.map.getOLMap();
    if (!olm) return;
    if (typeof olm.getView === 'function' && pageWin().ol) {
      fillOl6(olm, lastReports);
    }
    if (typeof OpenLayers !== 'undefined') {
      fillOl2(olm, lastReports);
    }
  }

  function hidePopup() {
    if (popupEl && popupEl.parentNode) popupEl.parentNode.removeChild(popupEl);
    popupEl = null;
  }

  function showPopup(r) {
    hidePopup();
    popupEl = document.createElement('div');
    popupEl.style.cssText =
      'position:fixed;z-index:99999;left:50%;top:72px;transform:translateX(-50%);' +
      'min-width:260px;max-width:360px;background:#fff;border:1px solid #bbb;' +
      'border-radius:8px;box-shadow:0 8px 24px rgba(0,0,0,.18);padding:12px 14px;' +
      'font:13px/1.35 system-ui,sans-serif;color:#222;';
    const heading =
      r.headingDeg != null && Number.isFinite(Number(r.headingDeg))
        ? Number(r.headingDeg).toFixed(0) + '°'
        : '—';
    const accRaw = r.payload && r.payload.accuracyM;
    const accuracy =
      accRaw != null && Number.isFinite(Number(accRaw))
        ? '±' + Math.round(Number(accRaw)) + ' m'
        : '—';
    const when = r.createdAt ? new Date(r.createdAt).toLocaleString() : '';
    popupEl.innerHTML =
      '<div style="font-weight:600;margin-bottom:6px">' +
      escapeHtml(labelForReport(r)) +
      '</div>' +
      '<div style="font-size:12px;color:#555;margin-bottom:8px">' +
      'by <b>' +
      escapeHtml(r.reporterNick || '—') +
      '</b><br>' +
      escapeHtml(when) +
      '<br>heading ' +
      escapeHtml(heading) +
      ' · GPS ' +
      escapeHtml(accuracy) +
      '<br>' +
      Number(r.lat).toFixed(6) +
      ', ' +
      Number(r.lon).toFixed(6) +
      (r.description
        ? '<br><br>' + escapeHtml(r.description)
        : '') +
      '</div>';
    const row = document.createElement('div');
    row.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap';
    const btnDone = document.createElement('button');
    btnDone.type = 'button';
    btnDone.textContent = 'Done';
    btnDone.style.cssText = 'padding:6px 10px;cursor:pointer';
    btnDone.addEventListener('click', function () {
      patchStatus(r.id, 'done');
    });
    const btnDismiss = document.createElement('button');
    btnDismiss.type = 'button';
    btnDismiss.textContent = 'Dismiss';
    btnDismiss.style.cssText = 'padding:6px 10px;cursor:pointer';
    btnDismiss.addEventListener('click', function () {
      patchStatus(r.id, 'dismissed');
    });
    const btnClose = document.createElement('button');
    btnClose.type = 'button';
    btnClose.textContent = 'Close';
    btnClose.style.cssText = 'padding:6px 10px;cursor:pointer;margin-left:auto';
    btnClose.addEventListener('click', hidePopup);
    row.appendChild(btnDone);
    row.appendChild(btnDismiss);
    row.appendChild(btnClose);
    popupEl.appendChild(row);
    document.body.appendChild(popupEl);
  }

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  async function patchStatus(id, status) {
    try {
      setStatus('Updating…');
      await httpJson('PATCH', apiBase() + '/api/reports/' + encodeURIComponent(id), {
        status: status,
      });
      hidePopup();
      lastViewportKey = '';
      await loadViewport();
      setStatus('Marked ' + status);
    } catch (e) {
      setStatus((e && e.message) || String(e));
    }
  }

  async function fetchReports(bbox) {
    const q =
      '/api/reports/bbox?minLon=' +
      encodeURIComponent(bbox.minLon) +
      '&minLat=' +
      encodeURIComponent(bbox.minLat) +
      '&maxLon=' +
      encodeURIComponent(bbox.maxLon) +
      '&maxLat=' +
      encodeURIComponent(bbox.maxLat) +
      '&status=pending';
    const data = await httpJson('GET', apiBase() + q, null);
    return (data && data.reports) || [];
  }

  async function loadViewport() {
    if (!followEl || !followEl.checked) {
      clearLayers();
      setStatus('Layer off');
      return;
    }
    if (fetchInFlight) return;
    const bbox = await getViewportBBox();
    if (!bbox) {
      setStatus('No map extent yet');
      return;
    }
    const z = getZoom();
    const key =
      (z != null && Number.isFinite(z) ? z.toFixed(1) : 'z') +
      ':' +
      bbox.minLon.toFixed(4) +
      ',' +
      bbox.minLat.toFixed(4) +
      ',' +
      bbox.maxLon.toFixed(4) +
      ',' +
      bbox.maxLat.toFixed(4);
    if (key === lastViewportKey) return;
    fetchInFlight = true;
    setStatus('Loading…');
    try {
      storageSet(STORAGE_API_BASE, apiBase());
      const reports = await fetchReports(bbox);
      applyReports(reports);
      lastViewportKey = key;
      setStatus(reports.length + ' pending report(s)');
    } catch (e) {
      lastViewportKey = '';
      clearLayers();
      setStatus((e && e.message) || String(e));
      console.warn('[Drive reports]', e);
    } finally {
      fetchInFlight = false;
    }
  }

  const debouncedLoad = debounce(function () {
    loadViewport().catch(function (e) {
      console.warn(e);
    });
  }, 400);

  function startMapHooks() {
    if (mapHooksOk) return;
    const mapW = W();
    if (!mapW || !mapW.map || typeof mapW.map.getOLMap !== 'function') return;
    const olm = mapW.map.getOLMap();
    if (!olm) return;

    const onStart = function () {
      setLayersVisible(false);
      hidePopup();
    };
    const onEnd = function () {
      setLayersVisible(true);
      debouncedLoad();
    };

    try {
      if (typeof olm.on === 'function' && typeof olm.getView === 'function') {
        olm.on('movestart', onStart);
        olm.on('moveend', onEnd);
        mapHooksOk = true;
      }
    } catch (_) {}

    try {
      if (!mapHooksOk && olm.events && olm.events.register) {
        olm.events.register('movestart', olm, onStart);
        olm.events.register('zoomstart', olm, onStart);
        olm.events.register('moveend', olm, onEnd);
        olm.events.register('zoomend', olm, onEnd);
        mapHooksOk = true;
      }
    } catch (_) {}

    if (mapHooksOk) window.setTimeout(debouncedLoad, 300);
  }

  function ensureMapHooksLoop() {
    if (mapHooksOk || mapWatchTries > 400) return;
    mapWatchTries++;
    startMapHooks();
    if (!mapHooksOk) window.setTimeout(ensureMapHooksLoop, 800);
  }

  function startPoll() {
    if (pollTimer != null) return;
    pollTimer = window.setInterval(function () {
      if (!followEl || !followEl.checked || document.hidden || fetchInFlight) return;
      debouncedLoad();
    }, 3500);
  }

  function buildPanel(tabPane) {
    const root = document.createElement('div');
    root.style.cssText = 'padding:8px;font:13px/1.35 system-ui,sans-serif';

    const title = document.createElement('div');
    title.style.fontWeight = '600';
    title.textContent = 'Drive reports';
    root.appendChild(title);

    const followRow = document.createElement('label');
    followRow.style.cssText = 'display:flex;gap:8px;align-items:center;margin-top:10px';
    followEl = document.createElement('input');
    followEl.type = 'checkbox';
    followEl.checked = storageGetBool(STORAGE_FOLLOW, true);
    followEl.addEventListener('change', function () {
      storageSet(STORAGE_FOLLOW, followEl.checked ? '1' : '0');
      lastViewportKey = '';
      debouncedLoad();
    });
    followRow.appendChild(followEl);
    followRow.appendChild(document.createTextNode('Show pending reports'));
    root.appendChild(followRow);

    const baseLbl = document.createElement('div');
    baseLbl.style.cssText = 'margin-top:10px;font-size:12px;color:#555';
    baseLbl.textContent = 'API base';
    root.appendChild(baseLbl);
    apiBaseEl = document.createElement('input');
    apiBaseEl.type = 'text';
    apiBaseEl.value = storageGet(STORAGE_API_BASE, DEFAULT_API_BASE);
    apiBaseEl.style.cssText = 'width:100%;box-sizing:border-box;margin-top:2px';
    apiBaseEl.addEventListener('change', function () {
      storageSet(STORAGE_API_BASE, apiBase());
      lastViewportKey = '';
      debouncedLoad();
    });
    root.appendChild(apiBaseEl);

    const reload = document.createElement('button');
    reload.type = 'button';
    reload.textContent = 'Reload';
    reload.style.cssText = 'margin-top:10px;padding:6px 10px;cursor:pointer';
    reload.addEventListener('click', function () {
      lastViewportKey = '';
      loadViewport().catch(function (e) {
        setStatus((e && e.message) || String(e));
      });
    });
    root.appendChild(reload);

    statusEl = document.createElement('div');
    statusEl.style.cssText = 'margin-top:8px;font-size:12px;color:#333';
    root.appendChild(statusEl);

    const hint = document.createElement('div');
    hint.style.cssText = 'margin-top:10px;font-size:11px;color:#777';
    hint.textContent =
      'Orange arrow = travel direction. Blue disc = GPS accuracy. Click a marker for Done / Dismiss.';
    root.appendChild(hint);

    tabPane.appendChild(root);
  }

  let initDone = false;

  async function runMain() {
    if (initDone) return;
    const win = pageWin();
    if (!win.SDK_INITIALIZED || typeof win.getWmeSdk !== 'function') return;
    try {
      await win.SDK_INITIALIZED;
      sdk = win.getWmeSdk({ scriptId: SCRIPT_ID, scriptName: SCRIPT_NAME });
      const { tabLabel, tabPane } = await sdk.Sidebar.registerScriptTab();
      tabLabel.textContent = 'Drive reports';
      buildPanel(tabPane);
      window.setTimeout(ensureMapHooksLoop, 2500);
      window.setTimeout(startPoll, 3000);
      window.setTimeout(debouncedLoad, 3500);
      initDone = true;
    } catch (e) {
      console.error('[Drive reports] init:', e);
    }
  }

  function scheduleRun() {
    runMain().catch(function (e) {
      console.error(e);
    });
  }

  function onDomReady() {
    scheduleRun();
    document.addEventListener('wme-ready', scheduleRun, { once: true });
    window.setTimeout(scheduleRun, 2000);
    window.setTimeout(scheduleRun, 6000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', onDomReady);
  } else {
    onDomReady();
  }
})();
