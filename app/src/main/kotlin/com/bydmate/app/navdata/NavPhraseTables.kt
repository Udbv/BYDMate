package com.bydmate.app.navdata

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Navigator text tables loaded from `assets/navi/phrases/` (one JSON file per language, listed in
 * `languages.json`). This is the "language pack" half of the maneuver parser: the engine in
 * [NavManeuverCodes] / [WazeGuidanceParser] / [WazeAccessibilityReader] is language-agnostic and
 * reads everything language-specific from here.
 *
 * ALL languages are loaded at once, independent of the UI locale: the navigator speaks the head
 * unit's language and drivers mix (UI in English, Waze in Ukrainian). Adding a language means
 * adding `<tag>.json` and listing it, nothing in Kotlin.
 *
 * File shape (arrays are ordered, order = precedence for equal-length matches):
 * ```
 * { "lang": "uk", "sources": ["waze"],
 *   "maneuvers": [ {"code": "UTURN_RIGHT", "phrases": ["розверніться праворуч"]}, ... ],
 *   "numberedExit": "<regex with the exit number as group 1>",      // optional
 *   "exitNouns": ["з'їзд"], "ordinalSuffixes": ["й", "му"],          // optional
 *   "units": {"km": ["км"], "m": ["м"], "mi": [], "ft": [], "hour": ["год"], "minute": ["хв"]} }
 * ```
 * Parsed with a tiny JSON reader so the same code runs in plain JVM unit tests (no org.json stubs).
 */
object NavPhraseTables {
    private const val TAG = "NavPhraseTables"
    const val ASSET_DIR = "navi/phrases"
    const val MANIFEST = "languages.json"

    data class ManeuverEntry(val code: Int, val phrases: List<String>)

    data class LanguageTable(
        val lang: String,
        val maneuvers: List<ManeuverEntry>,
        val numberedExit: Regex?,
        val exitNouns: List<String>,
        val ordinalSuffixes: List<String>,
        val units: Map<String, List<String>>,
    )

    /** Everything the engines need, precompiled once per load. */
    class Compiled internal constructor(val tables: List<LanguageTable>) {
        val languages: List<String> get() = tables.map { it.lang }

        /** (regex, code) in file order; the regex is the literal phrase with letter boundaries. */
        val phrasePatterns: List<Pair<Regex, Int>> = tables.flatMap { t ->
            t.maneuvers.flatMap { m -> m.phrases.map { literalRegex(it) to m.code } }
        }

        /** Numbered-exit regexes in file order; group 1 is the exit number. */
        val numberedExitPatterns: List<Regex> = tables.mapNotNull { it.numberedExit }

        private fun unitAlternation(key: String): String =
            tables.flatMap { it.units[key].orEmpty() }.distinct()
                .sortedByDescending { it.length }   // longest first: "mile" before "mi", "hrs" before "h"
                .joinToString("|") { Regex.escape(it) }

        private fun optional(key: String): String = unitAlternation(key).ifEmpty { "(?!)" }

        // A unit may be followed by punctuation in a complete instruction ("500 m, turn"); reject
        // only another letter so `m` cannot steal the prefix of `mi`/`mile`.
        val distKm: Regex = Regex("""(?<![-+\d.,])(\d+(?:[.,]\d+)?)\s*(?:${optional("km")})(?!\p{L})""", RegexOption.IGNORE_CASE)
        val distM: Regex = Regex("""(?<![-+\d.,])(\d+)\s*(?:${optional("m")})(?!\p{L})""", RegexOption.IGNORE_CASE)
        val distMi: Regex = Regex("""(?<![-+\d.,])(\d+(?:[.,]\d+)?)\s*(?:${optional("mi")})(?!\p{L})""", RegexOption.IGNORE_CASE)
        val distFt: Regex = Regex("""(?<![-+\d.,])(\d+(?:[.,]\d+)?)\s*(?:${optional("ft")})(?!\p{L})""", RegexOption.IGNORE_CASE)
        val etaHourMin: Regex = Regex("""(?<![-+\d])(\d+)\s*(?:${optional("hour")})\s*(\d+)\s*(?:${optional("minute")})(?!\p{L})""", RegexOption.IGNORE_CASE)
        val etaHour: Regex = Regex("""(?<![-+\d])(\d+)\s*(?:${optional("hour")})(?!\p{L})""", RegexOption.IGNORE_CASE)
        val etaMin: Regex = Regex("""(?<![-+\d])(\d+)\s*(?:${optional("minute")})(?!\p{L})""", RegexOption.IGNORE_CASE)

        /** Reader-side quick exit number: "2-й съезд", "на 2-му з'їзді", "2nd exit". */
        val numberedExitQuick: Regex = run {
            val nouns = tables.flatMap { it.exitNouns }.distinct().joinToString("|") { Regex.escape(it) + """\p{L}*""" }
            val ordinals = tables.flatMap { it.ordinalSuffixes }.distinct().sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) }
            Regex("""(?<!\d)(\d{1,2})(?:[-‑ ]?(?:${ordinals.ifEmpty { "(?!)" }}))?\s*(?:${nouns.ifEmpty { "(?!)" }})(?!\p{L})""", RegexOption.IGNORE_CASE)
        }
    }

    @Volatile private var compiled: Compiled = Compiled(emptyList())
    @Volatile var loadedFrom: String = "nothing"
        private set

    val current: Compiled get() = compiled

    /** Android: read the asset directory once (cheap: a few KB). Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (compiled.tables.isNotEmpty()) return
        val result = runCatching {
            val am = context.assets
            val manifest = am.open("$ASSET_DIR/$MANIFEST").use { it.readBytes().toString(Charsets.UTF_8) }
            load(manifest) { tag -> am.open("$ASSET_DIR/$tag.json").use { it.readBytes().toString(Charsets.UTF_8) } }
        }
        result.onSuccess { loadedFrom = "assets" }
            .onFailure { Log.e(TAG, "phrase tables failed to load: ${it.message}") }
    }

    /** JVM tests: the checked-in asset directory. */
    fun loadFromDirectory(dir: File) {
        val manifest = File(dir, MANIFEST).readText(Charsets.UTF_8)
        load(manifest) { tag -> File(dir, "$tag.json").readText(Charsets.UTF_8) }
        loadedFrom = dir.path
    }

    /** Parse everything, then swap atomically: a broken file leaves the previous tables in place. */
    fun load(manifestJson: String, reader: (String) -> String) {
        val manifest = MiniJson.parse(manifestJson) as Map<*, *>
        val languages = (manifest["languages"] as List<*>).map { it as String }
        val tables = languages.map { tag -> parseLanguage(tag, reader(tag)) }
        compiled = Compiled(tables)
        // Self-check in the log: pattern counts plus one probe per language, so a pack that
        // loads but fails to match (escaping, regex dialect, charset) is visible in the field.
        val c = compiled
        val probes = mapOf("en" to "keep right", "ru" to "поверните направо", "uk" to "поверніть ліворуч",
            "cs" to "ostře vlevo", "zh" to "直行")
        val probeSummary = probes.filterKeys { it in c.languages }.entries.joinToString { (lang, text) ->
            "$lang=${NavManeuverCodes.fromInstructionText(text)}"
        }
        Log.i(TAG, "loaded ${tables.size} navigator languages: ${tables.joinToString { it.lang }}; " +
            "phrases=${c.phrasePatterns.size} exitRegexes=${c.numberedExitPatterns.size} probe[$probeSummary]")
    }

    internal fun parseLanguage(tag: String, json: String): LanguageTable {
        val root = MiniJson.parse(json) as Map<*, *>
        val lang = root["lang"] as? String ?: tag
        require(lang == tag) { "$tag.json declares lang=$lang" }
        val maneuvers = (root["maneuvers"] as? List<*>).orEmpty().map { entry ->
            val m = entry as Map<*, *>
            val codeName = m["code"] as String
            val code = NavManeuverCodes.codeFromName(codeName)
                ?: throw IllegalArgumentException("$tag.json: unknown maneuver code $codeName")
            ManeuverEntry(code, (m["phrases"] as List<*>).map { (it as String).lowercase() })
        }
        val units = (root["units"] as? Map<*, *>).orEmpty().entries.associate { (k, v) ->
            (k as String) to (v as List<*>).map { it as String }
        }
        return LanguageTable(
            lang = lang,
            maneuvers = maneuvers,
            numberedExit = (root["numberedExit"] as? String)?.let { Regex(it, RegexOption.IGNORE_CASE) },
            exitNouns = (root["exitNouns"] as? List<*>).orEmpty().map { it as String },
            ordinalSuffixes = (root["ordinalSuffixes"] as? List<*>).orEmpty().map { it as String },
            units = units,
        )
    }

    private fun literalRegex(value: String): Regex =
        Regex("""(?<!\p{L})${Regex.escape(value)}(?!\p{L})""", RegexOption.IGNORE_CASE)

    /** Minimal JSON reader (objects, arrays, strings with escapes, numbers, true/false/null). */
    internal object MiniJson {
        fun parse(text: String): Any? = Parser(text).run {
            val v = value()
            skipWs()
            require(pos == s.length) { "trailing data at $pos" }
            v
        }

        private class Parser(val s: String) {
            var pos = 0

            fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

            fun value(): Any? {
                skipWs()
                require(pos < s.length) { "unexpected end" }
                return when (val c = s[pos]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> str()
                    't' -> lit("true", true)
                    'f' -> lit("false", false)
                    'n' -> lit("null", null)
                    else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("bad char '$c' at $pos")
                }
            }

            private fun lit(word: String, v: Any?): Any? {
                require(s.startsWith(word, pos)) { "bad literal at $pos" }
                pos += word.length
                return v
            }

            private fun num(): Any {
                val start = pos
                if (s[pos] == '-') pos++
                while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
                val t = s.substring(start, pos)
                return t.toLongOrNull() ?: t.toDouble()
            }

            private fun str(): String {
                require(s[pos] == '"'); pos++
                val sb = StringBuilder()
                while (true) {
                    require(pos < s.length) { "unterminated string" }
                    when (val c = s[pos++]) {
                        '"' -> return sb.toString()
                        '\\' -> {
                            when (val e = s[pos++]) {
                                '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                                'b' -> sb.append('\b'); 'f' -> sb.append(''); 'n' -> sb.append('\n')
                                'r' -> sb.append('\r'); 't' -> sb.append('\t')
                                'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                                else -> throw IllegalArgumentException("bad escape \\$e at $pos")
                            }
                        }
                        else -> sb.append(c)
                    }
                }
            }

            private fun arr(): List<Any?> {
                pos++ // [
                val out = ArrayList<Any?>()
                skipWs()
                if (s[pos] == ']') { pos++; return out }
                while (true) {
                    out += value()
                    skipWs()
                    when (s[pos++]) {
                        ',' -> continue
                        ']' -> return out
                        else -> throw IllegalArgumentException("bad array at $pos")
                    }
                }
            }

            private fun obj(): Map<String, Any?> {
                pos++ // {
                val out = LinkedHashMap<String, Any?>()
                skipWs()
                if (s[pos] == '}') { pos++; return out }
                while (true) {
                    skipWs()
                    val k = str()
                    skipWs()
                    require(s[pos++] == ':') { "expected ':' at $pos" }
                    out[k] = value()
                    skipWs()
                    when (s[pos++]) {
                        ',' -> continue
                        '}' -> return out
                        else -> throw IllegalArgumentException("bad object at $pos")
                    }
                }
            }
        }
    }
}
