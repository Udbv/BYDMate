# BYDMate points для Waze Map Editor

Юзерскрипт `bydmate-points.user.js` показує в Waze Map Editor (WME) точки, які водій позначив у BYDMate
(«Мої точки»: лежачі поліцейські, світлофори, смуги): окремим шаром на мапі та списком у бічній панелі.

Скрипт **лише показує**. Він нічого не створює, не редагує і не зберігає у WME: кожну правку на мапі
редактор робить сам, звичайними інструментами WME, після власної перевірки.

## Звідки взяти файл

1. На машині: сторінка «Мапа» → панель «Мої точки».
2. Переглянути точки: натиснути на маркер → «Підтвердити» або «Відхилити». Відхилені точки в експорт не
   потрапляють, а нова позначка на тому самому місці (і в тому самому напрямку) їх не відновлює.
3. «Експорт» надсилає в Telegram-бот власника два файли: текстовий список із посиланнями на WME та
   `bydmate-points.geojson` («GeoJSON для WME»). Скрипту потрібен саме `.geojson`.

## Встановлення

1. Встановіть розширення [Tampermonkey](https://www.tampermonkey.net/) (Chrome, Edge, Firefox).
2. У Chrome / Edge увімкніть для розширень «Режим розробника» (або дозвіл «Allow User Scripts» у
   налаштуваннях Tampermonkey) — без цього нові версії Chrome не запускають юзерскрипти.
3. Tampermonkey → «Створити новий скрипт», видаліть шаблон, вставте вміст `bydmate-points.user.js`,
   збережіть (Ctrl+S). Або відкрийте «сирий» файл зі сховища — Tampermonkey сам запропонує встановлення.
4. Відкрийте або оновіть https://www.waze.com/uk/editor .

## Користування

1. У бічній панелі WME з'явиться вкладка **BYDMate**.
2. «Вибрати файл» → `bydmate-points.geojson`.
3. На мапі з'являться точки шару «BYDMate points»:
   - колір — статус: помаранчевий «не перевірено», зелений «підтверджено», синій «вже у Waze»;
   - трикутник повернутий за напрямком руху машини в момент позначки; коло — напрямок невідомий;
   - підпис — тип, кількість проїздів (`×3`) і стрілка напрямку.
4. У списку під файлом кнопка «Перейти» центрує мапу на точці (масштаб 19).
5. Шар можна сховати перемикачем шарів WME. Файл читається лише в браузері, нікуди не надсилається;
   після перезавантаження сторінки його треба вибрати знову.

## Що скрипт використовує з WME

- Якщо є **WME SDK** (`window.SDK_INITIALIZED`, `window.getWmeSdk`):
  `Sidebar.registerScriptTab`, `Map.addLayer` / `addFeatureToLayer` / `removeAllFeaturesFromLayer` /
  `setMapCenter`, `LayerSwitcher.addLayerCheckbox`, `Events.once({ eventName: 'wme-ready' })`.
- Якщо SDK немає або він не запустився — старий шлях: `W.userscripts.registerSidebarTab`,
  `W.userscripts.waitForElementConnected`, `W.map` та OpenLayers 2 (`Layer.Vector`, `Feature.Vector`).

Обидва шляхи лише читають: `W.model`, `W.selectionManager` і дії збереження скрипт не чіпає.

## Перевірка без WME

Розбір файлу і підписи не залежать від WME, їх можна перевірити в Node.js:

```js
const s = require('./bydmate-points.user.js');
s.parsePoints(require('fs').readFileSync('bydmate-points.geojson', 'utf8'));
```
