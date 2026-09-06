# BYDMate localization framework — design (2026-09-06)

Problem: six UI locales live in `res/values*/strings.xml` (1216 keys each), but ~1046 lines in 45 Kotlin
files still carry language-dependent literals (Cyrillic inside string literals, comments excluded), and
the language choice is read from eight places with two different defaults. Adding a language today means
touching parsers, prompts, view models and the settings picker by hand.

## 1. Categories of language-dependent text

| Cat | What | Evidence | Verdict |
|---|---|---|---|
| (a) UI strings | HUD running line `"мин"`; import/diagnostic status; language labels; automation journal texts; notification/overlay titles | `hud/HudPushLoop.kt:126`; `ui/settings/SettingsViewModel.kt:700-723`; `ui/settings/SettingsScreen.kt:2902-2906` (`langCodes`/`langLabels`); `data/automation/ActionDispatcher.kt:324-353`; `agent/AgentTools.kt:1144`; `ui/automation/AutomationViewModel.kt:482` | Move to `res/values` |
| (b) LLM prompts, tool schemas, agent-facing text | `SYSTEM_PROMPT` ("отвечай по-русски"), RU date line, persona, ~81 tool descriptions, ~96 JSON error strings, weather-code names, `Cmd.ru` | `agent/AgentOrchestrator.kt:126,241-244`; `voice/AgentPersona.kt:19-31,57-63`; `agent/AgentTools.kt`; `agent/WeatherClient.kt:104+`; `agent/AgentCommandCatalog.kt:15-17` | Prompt bundle per language |
| (c) Recognition lexicon | action/device/number words, RU-only heuristics | `voice/VoiceLexicon.kt:3,13-118`; `voice/NluParser.kt:109,195`; `voice/VoiceStemmer.kt` | Per-language Kotlin object; RU-only ASR is a hard constraint (`README.md:263`, `VoiceController.kt:223,238`, `voice/TtsVoiceCatalog.kt:41-66`) |
| (d) Third-party INPUT parsers | Yandex RU phrase tables, Waze EN/RU/CS/ZH/UK patterns, unit/ETA regexes, Yandex res-id -> RU phrase | `navdata/NavManeuverCodes.kt`; `navdata/NavGuidance.kt:17-20`; `navdata/WazeGuidanceParser.kt:20-34`; `media/NaviRichPostProcessor.kt:14-18,88-92`; `media/NaviNotificationParser.kt:115-131` | NOT tied to UI language; all languages loaded at once |
| (e) Formatting | 48 `SimpleDateFormat(`, 39 `Locale.US`, 11 `Locale.getDefault()`, `"%.1f km"` at `ui/dashboard/DashboardScreen.kt:166` | Resources with args + locale from `AppLanguage` |
| (f) External API language | `language=ru` at `agent/WeatherClient.kt:84`; `data/remote/InsightsManager.kt:72-73`; `UserError("город не найден")` `:91` | Derive from `AppLanguage` |

Language selection sites (to collapse): `MainActivity.kt:46` (default "ru"), `util/LocaleContext.kt:21`,
`SettingsViewModel.kt:255`, `VoiceController.kt:198`, `InsightsManager.kt:73`, `WidgetController.kt:670`,
`AutomationViewModel.kt:52-53` (default "en"!), `data/local/LocaleBootstrap.kt:8-9`, plus `VoiceModule.kt:149-152`.

## 2. Architecture

### 2.1 `AppLanguage` — single source of truth
```kotlin
enum class AppLanguage(val tag: String, val nativeLabel: String,
    val voice: VoiceLang?, val promptLang: String, val apiLang: String) {
  RU("ru", "Русский", VoiceLang.RU, "ru", "ru"), EN(...),
  BE("be", "Беларуская", VoiceLang.RU, "ru", "ru"),
  UK("uk", "Українська", VoiceLang.RU, "ru", "uk"), PL, PT, ZH;
  companion object { val DEFAULT = RU; fun fromTag(t: String?): AppLanguage }
}
```
`LocalePreferences.current(): AppLanguage` replaces every `"ru"` comparison; the settings picker iterates
`AppLanguage.entries`; `voice`/`promptLang`/`apiLang` replace the ad-hoc mappings in `VoiceController`,
`AutomationViewModel`, `WeatherClient`, `InsightsManager`. Native labels stay in code on purpose (each
label is shown in its own language).

### 2.2 (a)/(e): Android resources
`getString`/`stringResource` with positional args and `<plurals>` (reuse `widget_duration_minutes`,
`strings.xml:591`). `HudPushLoop` gets a tiny injected `HudText` interface so it stays context-free and
its JVM test (`HudPushLoopTest.kt:196`) keeps working. Off-Activity code uses `appLocalizedContext()`
(`util/LocaleContext.kt:20`).

### 2.3 (b)/(c)/(d): options compared

| Criterion | Kotlin object per language | JSON in `assets/i18n/<lang>/` | `string-array` resources |
|---|---|---|---|
| Type safety | best (enum keys) | string keys, validated by a JVM loader test | weak, index-based |
| JVM unit tests | direct | `File` loader, precedent `CommandTranslatorTest.kt:35-37` | needs Robolectric |
| Runtime cost | zero | one parse at startup (org.json only; no kotlinx-serialization) | resource lookup |
| Translator-friendly | no | yes (Weblate/Crowdin) | flat text only, bad for patterns |
| Follows UI locale | n/a | no (good for (d)) | yes (wrong for (d)) |

Recommendation:
- (d) parsers: keep `NavManeuverCodes` as the engine, move phrase -> code tables to
  `assets/navi/phrases/<lang>.json` (`{"gaode": 15, "phrases": [...]}`), loaded all at once through a
  `NavPhraseTables` interface (JVM tests load from `File`). Merge the unit/ETA regexes of
  `NavGuidance.kt`, `WazeGuidanceParser.kt`, `NaviRichPostProcessor.kt` into one `NavUnitLexicon` built
  from the union of all language tables. `NaviNotificationParser.kt:115-131` should map res-ids to Gaode
  codes directly; `gaodePhrase()` output (`AgentTools.kt:1511`) becomes a category-(b) string.
- (b) prompts: `assets/prompts/<promptLang>/` text files with `{{placeholders}}` behind a `PromptBundle`
  interface; tool descriptions/errors as `agent_strings.json` keyed by id; JVM parity test across languages.
- (c) lexicon: stays Kotlin, selected through `AppLanguage.voice`.

## 3. Migration phases

| Phase | Scope | Size |
|---|---|---|
| 0 | `AppLanguage` + `LocalePreferences.current()`; replace 8 selection sites; unify default; add `uk` entry + `values-uk/`; `HudPushLoop` "мин" via `HudText`; CI guard (section 4) | ~12 files, +1 res dir |
| 1 | (a) leftovers: `SettingsViewModel` statuses, `ActionDispatcher` results, `AutomationViewModel` templates, `AgentTools.kt:1144`, `UpdateChecker`, `BackupManager`, `EnergyDataReader` | ~10 files, ~120 strings |
| 2 | (e)/(f): `DashboardScreen.kt:166`, `SimpleDateFormat(..., Locale("ru"))`, `WeatherClient.kt:84`, `InsightsManager` | ~15 files |
| 3 | (d) table extraction + `NavUnitLexicon` + Yandex `uk` file | 5 files, ~200 phrases |
| 4 | (b) prompt bundle (`SYSTEM_PROMPT`, persona, 81 descriptions, 96 errors, weather names, `Cmd.ru` -> `Cmd.descKey`) | 5 files, ~250 strings |

## 4. CI guard
No detekt/ktlint (`ci.yml:29-34` runs `testDebugUnitTest` + `lintDebug`). Add a JVM test
`i18n/HardcodedCyrillicGuardTest` that walks `app/src/main/kotlin`, strips comments, flags `\p{Cyrillic}`
inside string literals and compares with `app/src/test/resources/i18n-allowlist.txt` (`path:count`,
counts may only decrease). Add a key-parity test across all `values-*/strings.xml`, and Android Lint
`HardcodedText` for XML.

## 5. Never translate / risks
Never: DiLink Chinese protocol strings (`CommandTranslator.kt:46-49`, `VoiceCommandSpec.kt:19-24`,
`ActionDispatcher.kt:65,81-84`, `AutomationViewModel.kt:75-183` first arg, `Cmd.chinese`), Yandex res-ids
`notification_*_sdl`, parser tables (d), `Cmd.id`, prefs keys, package names, `Log.*` text. HUD f10 is UTF-8
(`HudProtobufBuilder.kt:73`) but glyph coverage on the glass for non-Cyrillic/CJK is unverified.

Risks: stale `applicationContext` locale in overlays/widgets (`WidgetController.relocale`, `:663`) and
foreground-service notifications; Compose recomposition via `LocalConfiguration` (`MainActivity.kt:106-113`),
strings computed in ViewModels must stay resource ids; Robolectric tests set `LocalePreferences`, not
`Locale.setDefault` (`WithAppLocaleTest.kt:26`); `Locale.setDefault` at `MainActivity.kt:56,92` affects
`SimpleDateFormat`/`String.format` app-wide (audit the 39 `Locale.US`); `SYSTEM_PROMPT` change invalidates
the provider prompt cache once; no RTL target yet; `values/` being Russian hides missing keys (parity test).

See also `language-packs.md` (pluggable language-pack contract and workflows).
