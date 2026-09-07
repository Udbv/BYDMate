# NOA (领航辅助) outside mainland China — research notes

Scope: BYD Tang L EV 2025 (唐L EV), DiLink 150, 天神之眼B / DiPilot 300 (LiDAR), China-market car
imported to Ukraine. Question: can NOA work outside China at all, and what exactly does it depend on.
Web research only, 2026-09-07. Legend: **[FACT]** = primary/press source linked; **[ANECDOTE]** = forum
or owner report; **[INFERENCE]** = my reading of the evidence. Nothing here was verified on the car.

## TL;DR

- 高快领航 (HNOA) and 城市领航 (CNOA) both require an active route in the **built-in customised
  navigation** (高德定制版 on B/A cars; C cars moved from 百度 to 高德 in 2026). "无图" means "no HD map",
  not "no map": the driving domain still consumes the SD navigation route plus lane-level/SD-Pro
  attributes. **[FACT]**
- BYD itself states CNOA coverage "暂不包括港澳台" — the function is scoped to mainland map coverage,
  before any question of geofencing. **[FACT]**
- Only one first-hand owner report found outside China (Tang L in Kazakhstan, drive2): ICC works,
  NOA does not, "из-за отсутствия карты навигационной"; NOA/ICC enabling required completing the
  in-app training while physically in China. **[ANECDOTE]**
- No export-market BYD ships NOA today. BYD says export NOA needs local road testing/maps/regulation
  (Indonesia "2026–2027", Brazil "2027"), and Amap's overseas in-car product (AutoSDK 国际版, HERE data,
  Nov 2025) is a plain navigation SDK with no lane-level/ADAS layer announced. **[FACT]**
- Nav-independent features (ACC/ICC, 车道领航 ICA/LCC, 拨杆变道 ILC, parking) are reported to work
  abroad. **[ANECDOTE]** No reports of anyone spoofing NOA into working outside China were found.

## Glossary (exact Chinese terms)

| Term | Meaning |
|---|---|
| 天神之眼 A/B/C | God's Eye tiers = DiPilot 600 / 300 / 100. Tang L 2025 = 天神之眼B, DiPilot 300, 1 LiDAR, Orin-X |
| 领航辅助 / 智驾领航 | NOA (Navigate on Autopilot) family |
| 高快领航 (HNOA) | highway + urban expressway NOA |
| 城市领航 (CNOA) / 无图城市领航 | city NOA; "无图" = without HD map (高精地图) |
| 城市记忆领航 | memory NOA (learned A→B commute routes) |
| 车位到车位 | parking-spot-to-parking-spot NOA |
| 车道领航 / ICA / LCC | lane-centering cruise (no route needed) |
| 自适应巡航 ACC / ICC | adaptive cruise |
| 拨杆变道 ILC/ALC | stalk-triggered lane change |
| 高德定制版 / 百度定制版 | BYD-customised Amap / Baidu in-car navigation |
| 高精地图 / SD地图 / SD Pro / 轻地图 / LD数据 | HD map / standard nav map / enriched SD map / "light map" / Baidu lane-level data |
| 智驾考试 | in-app ADAS test that must be passed before 领航/泊车 functions are enabled |

## 1. NOA variants, what they need, Tang L OTA timeline

### Variants and what they require

- **[FACT]** 天神之眼A/B support 高快领航 + 城市领航; 天神之眼C supported only 高快领航 in 2025
  (city via later OTA). BYD claims no HD-map dependence ("不依赖高精地图", BEV+Transformer, 端到端).
  Sources: [paultan](https://paultan.org/2025/02/13/byd-unveils-gods-eye-dipilot-adas-suite-to-roll-out-in-all-byd-denza-yangwang-models-sold-in-china/),
  [知乎 A/B/C 全解析](https://zhuanlan.zhihu.com/p/22945639008),
  [BYD 官方 CNOA 全国开通 2024-12-24](https://www.byd.com/cn/news/2024/detail558).
- **[FACT]** CNOA coverage: "可用范围暂不包括港澳台地区" — [IT之家 2024-12-24](https://www.ithome.com/0/819/752.htm).
  First recipients: 仰望U8, 腾势Z9GT, 腾势N7; "该功能将随具体车型OTA陆续推送".
- **[FACT]** NOA needs a destination/route. Activation flow described consistently by BYD-oriented
  guides: set a route in the car navigation → drive onto a supported 高速/快速路 → toggle stalk/button;
  "在使用前需要设置导航路径…将换挡拨杆向下拨动至底部两次". Note on mis-activation when the nav
  position disagrees with the road (under/over an elevated road, 辅路 vs 主路) — i.e. the driving
  domain trusts the nav's map-matched position.
  Sources: [新浪 高速领航辅助 3步激活](https://k.sina.com.cn/article_7879996686_1d5af350e06801ezya.html),
  [汽车之家 领航说明](https://www.autohome.com.cn/ask/18582960.html),
  [autoevex guide](https://autoevex.com/en-us/blogs/smart-tech-tips/how-to-use-byd-gods-eye-noa-complete-driver-assistance-guide-2026)
  ("NOA needs a set route to function", "Enter a destination in the vehicle navigation. Start navigation…").
- **[FACT]** Navigation app is coupled to NOA. When BYD announced that 天神之眼C cars switch from
  百度定制版 to 高德定制版 (2026-04-30), the stated reason was "该功能的开放涉及辅助驾驶功能一系列变更，
  为了大家领航辅助功能的体验保持较高水准" — [IT之家](https://www.ithome.com/0/945/277.htm).
  Baidu had supplied "LD数据" (lane-level, 360万 km) for "天神之眼部分车型" DiLink navigation —
  [证券时报 2025-02-26](https://www.stcn.com/article/detail/1546900.html),
  [凤凰网](https://auto.ifeng.com/c/8hHvahfKr6y).
- **[FACT]** 智驾考试: BYD app (王朝/海洋) has a mandatory ADAS learning/exam covering 车道领航, 高快领航,
  城市领航, 自动泊车, 代客泊车, 遥控泊车 (launched 2025-01-21) —
  [新浪科技](https://finance.sina.com.cn/tech/roll/2025-01-21/doc-ineftrwx7927481.shtml).
- **[FACT]** 城市记忆领航: announced 2025-02-10 for 天神之眼A/B ("A点到B点记忆…一键导航"), "计划在年底前OTA推送" —
  [新浪科技](https://finance.sina.cn/tech/2025-02-10/detail-ineizenz7251854.d.html).
  On 2026-05-29 BYD said 天神之眼C will *skip* 记忆领航 and go straight to 天神之眼4.0 "准城市领航"
  (无图端到端; trial push Sept 2026, full Dec 2026); 天神之眼B became a ¥12,000 option on all models;
  city-NOA accident 兜底 for A/B buyers — [IT之家 2026-05-29](https://www.ithome.com/0/956/870.htm).
  I did not find a source confirming 记忆领航 actually shipped on 唐L. **[not found]**

### Tang L (唐L EV/DM) OTA timeline — what I could confirm

| # | Date | ADAS-relevant content | Source |
|---|---|---|---|
| launch | 2025-04-09 | 全系标配天神之眼B (DiPilot 300); 高快领航 + 城市领航 supported from launch | [新华网](https://www.news.cn/auto/20250411/1312bc3c10984338aba6a0dc5f38b238/c.html) |
| 1st | 2025-07 (planned), reported 2025-08-05 | 15 new items; 车头泊入/车尾泊出, 偏置泊车; 领航/泊车 "能力全面提升" | [IT之家](https://www.ithome.com/0/873/280.htm), [汽车之家论坛](https://club.autohome.com.cn/bbs/thread/3ce95864f41e946a/112299823-1.html) |
| "史上最强" / 4th | announced 2025-11-21 (广州车展), 28 new items | 车位到车位领航辅助 unlocked on 天神之眼B (DiPilot 300); new DiLink 150 UI | [新华网](http://www.news.cn/auto/20251122/bc631582311a4b618d1e48e9b85ffd4c/c.html), [IT之家 汉L/唐L](https://www.ithome.com/0/899/122.htm) |
| 3rd (numbering per IT之家) | 2025-12-18, 24 items | "自车处于P档下可激活城市领航辅助"; 高快领航 ETC 收费站通行; 降级退出时短时紧急避让; 途经点编辑 | [IT之家](https://www.ithome.com/0/906/086.htm) |
| 5th | 2026-06-02, 19 items | steering-wheel custom key → 泊车辅助; long-press to report ADAS event; no new NOA scope | [IT之家](https://www.ithome.com/0/958/610.htm) |

Numbering in Chinese media is inconsistent (Nov 2025 "OTA进阶" vs "第3次" in Dec 2025); dates are what matter.
**[INFERENCE]** Every OTA extends NOA *within* the same map/nav framework; none adds a non-China map source.

## 2. Reports of NOA outside mainland China

- **[ANECDOTE — first-hand, verified at source]** drive2.ru, BYD Tang L thread, user *Bombich00*
  (car bought in China, now in Kazakhstan; SIM inactive, functions via Bluetooth/master account):
  "Активировал ICC и NOA… Автопарковка, удаленный выезд из парковки, все работает." Then, asked
  whether ICC/NOA must be activated in China: "Там необходимо пройти обучение через ПО с телефона
  будучи находясь на территории Китая. Icc работает, noa не работает из-за отсутствия карты
  навигационной." Another commenter (*sanya-q50*, Song Plus DM-i) lists Tang L's LiDAR as
  "бесполезен за пределами Китая". [drive2 comments](https://www.drive2.ru/r/byd/tang_l/710623986832118138/comments)
  (page returns 403 to bots; fetched with a browser UA).
- **[ANECDOTE — dealer marketing, low weight]** Russian importer blog claims parallel-import Song Plus /
  Qin Plus / Seal with God's Eye are "полностью русифицирована и работает" — no mention of NOA
  specifically. [byd-auto-russia.ru](https://byd-auto-russia.ru/blog/avtopilot-ot-byd-gods-eye-uzhe-ustanovlen-v-2-mln-mashin)
- **[FACT — adjacent]** Hong Kong/Macau/Taiwan are explicitly outside CNOA coverage (see §1). A HK
  BYD Seal owner reports the in-car Amap has no English in HK — [GeoExpat](https://geoexpat.com/forum/333/thread366997.html).
- **[FACT — analogue, Geely]** Zeekr 9X driven out of China was digitally locked on entering
  Kazakhstan (screen, GPS nav, charge port) by location geofencing; Zeekr sent an unlock code.
  Not BYD, but shows Chinese OEMs do implement border geofences —
  [motor16](https://www.motor16.com/las-ultimas-noticias/geofencing-coches-chinos-zeekr-bloqueo/),
  [知乎 discussion](https://www.zhihu.com/question/2063532260934907357).
  **[not found]** any report of BYD geofencing/locking ADAS on export; BYD "远程锁车" reports concern
  unpaid export invoices, not NOA ([新浪](https://www.sina.cn/news/detail/5270532637330828.html)).
- **[FACT — analogue, XPeng]** XPeng says NGP abroad "must rely on map data and navigation maneuvers"
  and adopted Google Maps in-vehicle Map Data Services for overseas NGP —
  [xpeng.com](https://www.xpeng.com/news/019f6acdf9b69f64a5748a029c460043). An unsourced blog
  claims China-spec XNGP is blocked abroad by HD-map tiles/geofence/cloud checks
  ([alibaba blog](https://carinterior.alibaba.com/tips/xpeng-g6-xngp-highway-guide)) — treat as opinion.
- **[not found]** Reddit / YouTube / Telegram / VK posts documenting a China-spec BYD/Denza/Yangwang
  with NOA working (or a specific error text such as "not official export") in Europe/Ukraine/
  LatAm. Ukrainian/Russian BYD channels (e.g. [t.me/just_byd](https://t.me/s/just_byd)) discuss
  firmware, Yandex on the cluster and russification only. Ukrainian shops sell "українізація, SIM,
  карти" services ([garage55](https://garage55.com.ua/proshyvky-vsih-kytajskyh-byd/)) but say nothing
  about NOA.

## 3. Amap (高德) outside mainland China; DiLink offline maps

- **[FACT]** Consumer Amap app gained a global map service (200+ countries, 56 languages) in
  July 2025 — [新浪](https://finance.sina.com.cn/tech/roll/2025-07-19/doc-inffzcmp5341569.shtml),
  [观察者](https://www.guancha.cn/economy/2025_07_11_782634.shtml). Open-platform "世界地图服务" —
  [IT之家](https://www.ithome.com/0/869/240.htm).
- **[FACT]** In-car: 高德地图车机国际版 / **AutoSDK 国际版** released 2025-11-03: 170+ countries,
  19 languages, online + 离线导航, ISA speed-limit broadcast; map data via strategic partnership with
  **HERE**; positioned as "助力中国车企驶向海外", pre-installed on export cars then localised. No
  lane-level / ADAS-map layer mentioned. [新浪科技](https://finance.sina.com.cn/tech/roll/2025-11-03/doc-infwckpp2477276.shtml),
  [凤凰网](https://auto.ifeng.com/c/8o0cJ4f0N2z).
- **[FACT]** BYD DiLink's 高德定制版 offline download covers 分省/全国 packages only (China) —
  [迪友社区](https://www.bydmax.com/apps/24237.html), [迪粉之家](https://www.difans.cn/post_byd_car/4.html).
  **[not found]** any way to add non-China regions to the customised in-car Amap; the international
  AutoSDK is a separate product line sold to OEMs, not a downloadable region.
- **[FACT — regulatory background]** Chinese 测绘 rules require geo-data to stay in China and make
  export of map/ADAS data subject to approval; industry answer is "重感知、轻地图" and foreign map
  suppliers abroad — [腾讯新闻 智驾出海](https://news.qq.com/rain/a/20240226A03WH600),
  [安全内参](https://www.secrss.com/articles/77107).

## 4. Export-market BYDs: does any ship NOA, and on which map?

- **[FACT]** BYD Indonesia (product strategy manager Zheng Cuifang, Feb 2025): God's Eye "in 2026
  or 2027… at least two years of experience in China first… we need time to conduct road tests in
  Indonesia" — [katadata](https://auto.katadata.co.id/car/byd-unveils-cutting-edge-autopilot-indonesia-needs-to-be-patient-18075).
- **[FACT]** BYD Brazil: God's Eye confirmed for **2027** — [byd.com/br](https://www.byd.com/br/BYD-confirma-chegada-ao-Brasil-do-sistema-Gods-Eye-de-direcao-inteligente),
  [Correio Braziliense](https://www.correiobraziliense.com.br/economia/2026/05/7431136-byd-anuncia-o-sistema-olho-de-deus-no-brasil-para-2027.html).
- **[FACT]** Australia Sealion 8 (= Tang L DM, launched Jan 2026): reviews list AEB, LKA, BSD,
  driver monitoring etc.; no LiDAR, no NOA — [drive.com.au](https://www.drive.com.au/reviews/2026-byd-sealion-8-review-australian-first-drive/),
  [Wikipedia Tang L](https://en.wikipedia.org/wiki/BYD_Tang_L) (export names Sealion 8 / Atto 8; LiDAR China-only).
- **[FACT]** Europe: BYD EU Atto 3 page lists BSD, LKA, ICC, RCTA/RCTB only; navigation via
  CarPlay/Android Auto "HERE Maps… available in limited markets" —
  [byd.com/eu](https://www.byd.com/eu/electric-cars/atto3). UK Sealion 7 reviews: navigation via
  phone projection, no native NOA ([Carwow](https://www.carwow.co.uk/byd/sealion-7)). German press:
  city functions "could be unlocked via OTA once approved", timing unknown
  ([shop4ev](https://www.shop4ev.com/en/blogs/news/byd-gods-eye-autonomes-fahren)). BYD UK's "urban
  NOA damage coverage" release describes a China policy
  ([bydukmedia](https://bydukmedia.com/en/news-articles/byd-becomes-world%E2%80%99s-first-automaker-to-provide-full-damage-coverage-for-both-intelligent-parking-and-urban-noa.html)).
- **[FACT]** India Atto 3 uses **Mappls (MapmyIndia)** for built-in nav — [about.mappls.com](https://about.mappls.com/automotive/byd-atto-3/).
- **[FACT]** Middle East: BYD in talks with UAE on autonomous driving R&D (Dec 2025); no NOA on sale
  — [The National](https://www.thenationalnews.com/future/technology/2025/12/11/byd-in-talks-with-uae-authorities-on-driverless-cars-and-research-centre/).
- **[INFERENCE]** Export BYDs use per-market nav suppliers (HERE via phone projection in EU, Mappls in
  India, likely Amap-international/HERE going forward) and none of them currently feeds a NOA stack.
  The first export NOA (Brazil/Indonesia 2027) will presumably be a separately validated build.

## 5. How DiPilot NOA consumes navigation data (understanding only)

- **[FACT — industry analysis]** "无图" NOA still needs: the navigation route, SD/SD-Pro map
  attributes ("车道数、车道属性变化点、车道宽度…帮助 BEV 算法来建图"), and map-matched ego position;
  the hardest part is 规控 (planning), which needs lane/topology data —
  [42号车库](https://www.42how.com/en/article/11541),
  [佐思 "轻地图"](https://www.zhihu.com/question/572105821/answer/3315699822),
  [电子工程专辑 「无图」NOA玩的是文字游戏?](https://www.eet-china.com/mp/a327178.html).
- **[FACT]** BYD's own evidence of coupling: the Baidu LD-data supply for DiLink navigation on
  天神之眼 cars, and the Baidu→Amap switch justified by 领航辅助 quality (§1). Amap's AutoSDK 750
  advertises 车道级导航 5.0 / HQ Live MAP for ADAS warning use —
  [IT之家](https://www.ithome.com/0/776/111.htm).
- **[not found]** any public teardown of the DiLink↔ADAS domain link (SOME/IP signal names, ODD region
  tables) for DiPilot 300, and no write-up of anyone spoofing GPS/route data to enable NOA abroad.
  Note BYDMate's own HUD work shows the cluster accepts external turn data, but that is the *display*
  path, not the ADAS route feed.
- **[INFERENCE]** Minimum chain for NOA to arm: (a) built-in customised nav has a route, (b) nav
  map-matches the car onto a road with known class/lane attributes, (c) ADAS domain receives route +
  road attributes and the road is within ODD (高速/快速路 or covered city road), (d) account-level
  enablement (智驾考试 passed, feature flags). In Ukraine (a)–(c) fail because the customised Amap has
  no road graph there. Whether an additional MCC/geofence check exists is undetermined — no evidence
  either way; the Kazakhstan owner's experience (ICC works, NOA "no map") is consistent with a pure
  map-coverage failure.

## 6. Features that do not depend on navigation

- **[FACT]** BYD's own function ladder: 自适应巡航 (no route) → 车道领航 (ICA/LCC, keeps lane; no route)
  → 高快领航 / 城市领航 (route required) — [汽车之家 汉论坛](https://club.autohome.com.cn/bbs/thread/5789061cceebe5a4/111189345-1.html).
  Export spec sheets list ICC/LKA/ILCA/BSD/RCTB as the non-NOA set
  ([BitAuto Sealion 7 DiPilot 100](https://www.bitauto.com/global/news/100194547304.html)).
- **[ANECDOTE]** Kazakhstan Tang L: ICC, auto-parking and remote park-out work (drive2, §2).
  Russia Song Plus EV owners describe lane-centering as working but "дерганый"/self-disabling at
  times — [drive2](https://www.drive2.ru/l/688800880044022828/).
- **[INFERENCE]** Expect on the Ukrainian Tang L: ACC/ICC, 车道领航 (ICA), 拨杆变道 (ILC), AEB, parking
  (泊车/代客/遥控) to function; traffic-sign speed recognition may misread non-Chinese signage
  (Euro NCAP noted sign-reading issues on export BYDs). Not expected: 高快领航, 城市领航, 记忆领航,
  车位到车位, ETC-gate passing, HUD lane-level guidance from Amap.

## 7. Practical implications for this car (inference)

1. NOA will not become available by installing another navigation app: the ADAS domain listens to
   the built-in customised Amap, and the international AutoSDK is not user-installable.
2. Even a hypothetical route feed would hit ODD/road-attribute checks built on China map data;
   BYD's own statements (Indonesia, Brazil) say export NOA needs local validation, not just a map.
3. Nothing found suggests a SIM/MCC or border geofence on BYD ADAS; the observed behaviour abroad is
   simply "no navigation map → NOA greyed out". If a message like "not official export" ever appears,
   it was not documented anywhere I could find.
4. The realistic ceiling is ICA/LCC + ILC + ACC, which owner reports say do work outside China.

## Sources not found / dead ends

- Baidu Baike 天神之眼 and several sina/drive.com.au/evseekers pages returned 403 to the fetcher.
- No Reddit/YouTube/Telegram/VK thread found that documents NOA state on a grey-import BYD in
  Ukraine, Europe or Latin America; no DiPilot protocol teardown; no spoofing write-up.
