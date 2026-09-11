package com.bydmate.app.hud

import android.util.Log
import java.text.Normalizer

/**
 * Latinises the text the instrument panel is asked to draw (port of openbyd 2.4.3's
 * `com.sr.openbyd.utils.HudTextSanitizer`).
 *
 * The cluster's next-street field is rendered by the car's own firmware with a Chinese font set.
 * Cyrillic reaches it as bytes and comes back as boxes or as hanzi, whichever way the firmware
 * guesses the charset. openbyd's answer — field-proven on BYD clusters — is not to fight the
 * font: transliterate to ASCII before the write, and leave CJK alone so a Chinese-market map
 * still reads correctly.
 *
 * The work is per character, exactly as in the donor: ICU's `Any-Latin; Latin-ASCII` is applied
 * to one character at a time, CJK ranges pass through untouched, and anything ICU cannot do
 * falls back to NFD + combining-mark stripping. Per-character is slower than transliterating the
 * whole string, but it is what the donor does and it keeps one bad character from taking the
 * whole street name down with it.
 *
 * [transliterate] is injectable so unit tests can drive both paths deterministically: on a plain
 * JVM the android.icu class is not there and the default is null, i.e. the fallback path.
 */
class HudTextSanitizer(
    private val transliterate: ((String) -> String)? = icuTransliterator(),
) {

    /** null / blank → "", CJK kept, everything else Latinised, result trimmed (donor order). */
    fun sanitize(text: String?): String {
        if (text == null || text.isBlank()) return ""
        val icu = transliterate
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (isCjk(ch)) {
                sb.append(ch)
                continue
            }
            val one = ch.toString()
            sb.append(
                if (icu == null) fallback(one)
                else runCatching { icu(one) }.getOrElse { fallback(one) }
            )
        }
        return sb.toString().trim()
    }

    companion object {
        private const val TAG = "HudTextSanitizer"

        /** Shared instance; building the ICU transliterator is not free. */
        val DEFAULT: HudTextSanitizer by lazy { HudTextSanitizer() }

        /**
         * CJK Unified Ideographs, Extension A and the Compatibility block — the three ranges the
         * donor lets through. A Chinese street name is already drawable by the cluster's font;
         * romanising it would make it worse, not better.
         */
        fun isCjk(c: Char): Boolean =
            c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF

        private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")

        /**
         * No-ICU path: decompose, drop the accents, and spell ñ out by hand (it decomposes, but
         * the donor names it explicitly and a missing letter on the glass is worse than a
         * redundant rule). Scripts with no Latin decomposition — Cyrillic, Greek, Arabic — come
         * out unchanged here; only the ICU path can romanise those.
         */
        fun fallback(text: String): String =
            COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
                .replace("ñ", "n")
                .replace("Ñ", "N")

        /**
         * The ICU transliterator, or null when this runtime has no android.icu (plain JVM unit
         * tests, or a firmware that stripped it). Null means every character takes [fallback].
         */
        fun icuTransliterator(): ((String) -> String)? = runCatching {
            val t = android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII")
            val step: (String) -> String = { s -> t.transliterate(s) }
            step
        }.getOrElse {
            Log.w(TAG, "ICU Transliterator unavailable, using the NFD fallback: ${it.message}")
            null
        }
    }
}
