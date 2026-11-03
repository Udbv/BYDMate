// ==UserScript==
// @name         BYDMate points for WME
// @namespace    https://github.com/Udbv/BYDMate
// @version      1.0.0
// @description  Shows the road points exported by BYDMate («Мої точки», GeoJSON) as a read-only layer and list in Waze Map Editor.
// @match        https://www.waze.com/editor*
// @match        https://www.waze.com/*/editor*
// @match        https://beta.waze.com/editor*
// @match        https://beta.waze.com/*/editor*
// @exclude      https://www.waze.com/user/editor*
// @grant        none
// @run-at       document-end
// ==/UserScript==

/*
 * A read-only helper: it never creates, edits or saves anything in WME. It only reads a GeoJSON file
 * the editor picks (the «GeoJSON для WME» file BYDMate sends to Telegram), draws the points on a
 * layer of its own and lists them with «go to» buttons. Every change to the map stays the editor's
 * own decision, made with WME's own tools.
 *
 * WME APIs used, in order of preference:
 *  - WME SDK (window.SDK_INITIALIZED, window.getWmeSdk): Sidebar.registerScriptTab,
 *    Map.addLayer / addFeatureToLayer / removeAllFeaturesFromLayer / setMapCenter,
 *    LayerSwitcher.addLayerCheckbox, State.isReady, Events.once({eventName: 'wme-ready'}).
 *  - Fallback without the SDK: W.userscripts.registerSidebarTab / waitForElementConnected,
 *    W.map (addLayer, getProjectionObject, setCenter) and OpenLayers 2 (Layer.Vector, Feature.Vector,
 *    Geometry.Point, StyleMap). Read-only calls only: W.model and W.selectionManager are not touched.
 */
(function bydmatePoints() {
  'use strict';

  const SCRIPT_ID = 'bydmate-points';
  const SCRIPT_NAME = 'BYDMate points';
  // Long enough for a slow WME load, short enough that a missed event does not leave the tab absent.
  const WME_READY_TIMEOUT_MS = 60000;
  const LAYER = 'BYDMate points';
  const GO_TO_ZOOM = 19;

  const TYPE_LABELS = {
    SPEED_BUMP: 'Лежачий',
    TRAFFIC_LIGHT: 'Світлофор',
    LANES: 'Смуги',
    OTHER: 'Інше',
  };
  const STATUS_LABELS = {
    CANDIDATE: 'не перевірено',
    CONFIRMED: 'підтверджено',
    IN_WAZE: 'вже у Waze',
  };
  const STATUS_COLORS = { CANDIDATE: '#fb923c', CONFIRMED: '#4ade80', IN_WAZE: '#60a5fa' };
  // Eight-way arrows: a text label shows the course even where the renderer ignores rotation.
  const ARROWS = ['↑', '↗', '→', '↘', '↓', '↙', '←', '↖'];

  /** Turns the file's features into plain points; anything malformed is skipped, not trusted. */
  function parsePoints(text) {
    const json = JSON.parse(text);
    const features = json && Array.isArray(json.features) ? json.features : [];
    const points = [];
    features.forEach((f, i) => {
      const c = f && f.geometry && f.geometry.type === 'Point' ? f.geometry.coordinates : null;
      if (!Array.isArray(c) || c.length < 2) return;
      const lon = Number(c[0]);
      const lat = Number(c[1]);
      if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
      const p = f.properties || {};
      // Rejected points never leave the car, but an older or hand-edited file may still hold some.
      if (p.status === 'REJECTED') return;
      const heading = p.heading === null || p.heading === undefined ? null : Number(p.heading);
      points.push({
        id: i + 1,
        lat,
        lon,
        type: String(p.type || 'OTHER'),
        heading: Number.isFinite(heading) ? heading : null,
        passes: Number(p.passes) || 1,
        status: String(p.status || 'CANDIDATE'),
        date: p.date ? String(p.date) : '',
        note: p.note ? String(p.note) : '',
      });
    });
    return points;
  }

  function arrow(heading) {
    if (heading === null) return '';
    return ARROWS[Math.round((((heading % 360) + 360) % 360) / 45) % 8];
  }

  function label(p) {
    const type = TYPE_LABELS[p.type] || p.type;
    return `${type} ×${p.passes} ${arrow(p.heading)}`.trim();
  }

  function describe(p) {
    const status = STATUS_LABELS[p.status] || p.status;
    return [status, p.date, p.note].filter(Boolean).join(' · ');
  }

  // Style keys are OpenLayers 2 symbolizer keys, which the SDK's styleRules accept as well.
  const FIXED_STYLE = {
    fillOpacity: 0.9,
    strokeColor: '#111827',
    strokeWidth: 2,
    fontColor: '#ffffff',
    fontSize: '12px',
    fontWeight: 'bold',
    labelOutlineColor: '#111827',
    labelOutlineWidth: 3,
    labelYOffset: -18,
  };
  const VARYING = ['graphicName', 'rotation', 'pointRadius', 'fillColor', 'label'];

  /** A triangle turned along the course when the car knew it, a dot otherwise; colour by status. */
  function styleFor(p) {
    const withCourse = p.heading !== null;
    return Object.assign({}, FIXED_STYLE, {
      graphicName: withCourse ? 'triangle' : 'circle',
      rotation: withCourse ? p.heading : 0,
      pointRadius: withCourse ? 9 : 7,
      fillColor: STATUS_COLORS[p.status] || STATUS_COLORS.CANDIDATE,
      label: label(p),
    });
  }

  /** The map side through the WME SDK. */
  function sdkMap(sdk) {
    // The SDK styles features by rules, not per feature: a template ${name} reads styleContext[name],
    // so each varying key gets a getter that looks the feature's own style up by id.
    let byId = new Map();
    const ctx = {};
    VARYING.forEach((key) => {
      ctx[key] = ({ feature }) => (byId.get(feature.id) || {})[key];
    });
    sdk.Map.addLayer({ layerName: LAYER, styleContext: ctx, styleRules: sdkStyleRules() });
    try {
      sdk.LayerSwitcher.addLayerCheckbox({ name: LAYER });
    } catch (e) {
      console.warn(SCRIPT_NAME, 'no layer checkbox', e);
    }
    return {
      show(points) {
        sdk.Map.removeAllFeaturesFromLayer({ layerName: LAYER });
        byId = new Map(points.map((p) => [p.id, styleFor(p)]));
        points.forEach((p) => sdk.Map.addFeatureToLayer({
          layerName: LAYER,
          feature: { type: 'Feature', id: p.id, geometry: { type: 'Point', coordinates: [p.lon, p.lat] }, properties: {} },
        }));
      },
      goTo(p) {
        sdk.Map.setMapCenter({ lonLat: { lon: p.lon, lat: p.lat }, zoomLevel: GO_TO_ZOOM });
      },
    };
  }

  function sdkStyleRules() {
    const style = Object.assign({}, FIXED_STYLE);
    VARYING.forEach((key) => {
      style[key] = '${' + key + '}';
    });
    return [{ style }];
  }

  /** The map side without the SDK: an OpenLayers 2 vector layer on W.map. */
  function olMap() {
    const OL = window.OpenLayers;
    const map = window.W.map;
    const layer = new OL.Layer.Vector(LAYER, { displayInLayerSwitcher: true, uniqueName: SCRIPT_ID });
    map.addLayer(layer);
    const toMap = (p) => new OL.LonLat(p.lon, p.lat).transform(new OL.Projection('EPSG:4326'), map.getProjectionObject());
    return {
      show(points) {
        layer.removeAllFeatures();
        layer.addFeatures(points.map((p) => {
          const at = toMap(p);
          // A per-feature style: no shared rules to keep in sync with the data.
          return new OL.Feature.Vector(new OL.Geometry.Point(at.lon, at.lat), { id: p.id }, styleFor(p));
        }));
      },
      goTo(p) {
        const olMapObj = typeof map.getOLMap === 'function' ? map.getOLMap() : map;
        olMapObj.setCenter(toMap(p), GO_TO_ZOOM);
      },
    };
  }

  /** The side panel: the file picker, a summary and one row per point with a «go to» button. */
  function buildPanel(tabLabel, tabPane, mapSide) {
    tabLabel.textContent = 'BYDMate';
    tabLabel.title = SCRIPT_NAME;
    const root = document.createElement('div');
    root.style.padding = '8px';
    const title = document.createElement('h4');
    title.textContent = 'Точки BYDMate';
    const hint = document.createElement('p');
    hint.textContent = 'Файл GeoJSON з BYDMate («GeoJSON для WME»). Скрипт лише показує точки, нічого не змінює і не зберігає.';
    hint.style.fontSize = '12px';
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.geojson,.json,application/geo+json,application/json';
    const status = document.createElement('p');
    status.style.fontSize = '12px';
    const list = document.createElement('div');
    root.append(title, hint, input, status, list);
    tabPane.appendChild(root);

    input.addEventListener('change', () => {
      const file = input.files && input.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        let points;
        try {
          points = parsePoints(String(reader.result));
        } catch (e) {
          status.textContent = `Не вдалося прочитати файл: ${e.message}`;
          return;
        }
        mapSide.show(points);
        status.textContent = `Точок: ${points.length}`;
        renderList(list, points, mapSide);
      };
      reader.readAsText(file);
    });
  }

  function renderList(list, points, mapSide) {
    list.replaceChildren();
    points.forEach((p) => {
      const row = document.createElement('div');
      row.style.cssText = 'display:flex;align-items:center;gap:6px;padding:4px 0;border-bottom:1px solid #ddd;';
      const dot = document.createElement('span');
      dot.style.cssText = `width:10px;height:10px;border-radius:50%;flex:none;background:${STATUS_COLORS[p.status] || STATUS_COLORS.CANDIDATE};`;
      const text = document.createElement('div');
      text.style.cssText = 'flex:1;font-size:12px;';
      const head = document.createElement('b');
      head.textContent = label(p);
      const sub = document.createElement('div');
      sub.textContent = describe(p);
      text.append(head, sub);
      const go = document.createElement('button');
      go.type = 'button';
      go.className = 'btn btn-default btn-xs';
      go.textContent = 'Перейти';
      go.addEventListener('click', () => mapSide.goTo(p));
      row.append(dot, text, go);
      list.appendChild(row);
    });
  }

  async function startWithSdk() {
    const sdk = window.getWmeSdk({ scriptId: SCRIPT_ID, scriptName: SCRIPT_NAME });
    // wme-ready fires once; when the script is injected after it (late Tampermonkey injection on a
    // cached reload) waiting for it would hang forever, so only wait while WME is still loading,
    // and give up after a while so the W.map fallback still gets its chance.
    const ready = sdk.State && typeof sdk.State.isReady === 'function' && sdk.State.isReady();
    if (!ready) {
      await Promise.race([
        sdk.Events.once({ eventName: 'wme-ready' }),
        new Promise((_, reject) => setTimeout(() => reject(new Error('wme-ready timeout')), WME_READY_TIMEOUT_MS)),
      ]);
    }
    const { tabLabel, tabPane } = await sdk.Sidebar.registerScriptTab();
    buildPanel(tabLabel, tabPane, sdkMap(sdk));
  }

  async function startWithW() {
    const W = window.W;
    const { tabLabel, tabPane } = W.userscripts.registerSidebarTab(SCRIPT_ID);
    await W.userscripts.waitForElementConnected(tabPane);
    buildPanel(tabLabel, tabPane, olMap());
  }

  function startLegacy() {
    if (window.W && window.W.userscripts && window.W.userscripts.state && window.W.userscripts.state.isReady) {
      startWithW().catch((e) => console.error(SCRIPT_NAME, e));
    } else {
      document.addEventListener('wme-ready', () => startWithW().catch((e) => console.error(SCRIPT_NAME, e)), { once: true });
    }
  }

  // Outside a browser (a node check of the parser) there is no WME to start in.
  if (typeof window === 'undefined') {
    if (typeof module !== 'undefined' && module.exports) module.exports = { parsePoints, label, arrow, describe };
    return;
  }

  if (window.SDK_INITIALIZED && typeof window.SDK_INITIALIZED.then === 'function') {
    window.SDK_INITIALIZED
      .then(startWithSdk)
      .catch((e) => {
        console.warn(SCRIPT_NAME, 'SDK failed, falling back to W.map', e);
        startLegacy();
      });
  } else {
    startLegacy();
  }
})();
