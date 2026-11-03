package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsVoiceCatalogTest {

    @Test fun `catalog lists six Russian and three Ukrainian voices with dmitri first as default`() {
        assertEquals(listOf("dmitri", "alena", "ruslan", "irina", "mark", "sofia", "tetiana_uk", "mykyta_uk", "lada_uk"),
            TtsVoiceCatalog.ALL.map { it.id })
    }

    @Test fun `piper voices use the k2-fsa release url pattern`() {
        listOf("dmitri", "ruslan", "irina").forEach { id ->
            val v = TtsVoiceCatalog.byId(id)
            assertEquals(TtsVoiceEngine.PIPER, v.engine)
            assertEquals(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-$id-medium-int8.tar.bz2",
                v.url)
        }
    }

    @Test fun `supertonic voices share one archive and differ by speaker`() {
        val mark = TtsVoiceCatalog.byId("mark")
        val sofia = TtsVoiceCatalog.byId("sofia")
        assertEquals(TtsVoiceEngine.SUPERTONIC, mark.engine)
        assertEquals(mark.url, sofia.url)
        assertEquals(mark.modelDirId, sofia.modelDirId)
        assertEquals(7, mark.speakerId)
        assertEquals(3, sofia.speakerId)
        assertEquals(TtsGender.MALE, mark.gender)
        assertEquals(TtsGender.FEMALE, sofia.gender)
    }

    @Test fun `unknown and retired ids fall back to dmitri`() {
        assertEquals("dmitri", TtsVoiceCatalog.byId("artem").id)
        assertEquals("dmitri", TtsVoiceCatalog.byId("garbage").id)
    }

    @Test fun `counterpart swaps gender within engine and falls back across engines`() {
        assertEquals("irina", TtsVoiceCatalog.counterpart(TtsVoiceCatalog.byId("dmitri")).id)
        assertEquals("sofia", TtsVoiceCatalog.counterpart(TtsVoiceCatalog.byId("mark")).id)
        // alena is the only VITS_MULTI voice left: counterpart falls back to the default male
        assertEquals("dmitri", TtsVoiceCatalog.counterpart(TtsVoiceCatalog.byId("alena")).id)
        // a Ukrainian voice swaps to a Ukrainian one
        assertEquals("tetiana_uk", TtsVoiceCatalog.counterpart(TtsVoiceCatalog.byId("mykyta_uk")).id)
        assertEquals("mykyta_uk", TtsVoiceCatalog.counterpart(TtsVoiceCatalog.byId("lada_uk")).id)
    }

    @Test fun `Ukrainian voices speak Ukrainian, the multispeaker pair shares one archive`() {
        val t = TtsVoiceCatalog.byId("tetiana_uk")
        val m = TtsVoiceCatalog.byId("mykyta_uk")
        assertEquals(TtsLang.UK, t.lang)
        assertEquals(t.modelDirId, m.modelDirId)
        assertEquals(listOf(2, 1), listOf(t.speakerId, m.speakerId))
        assertEquals(TtsLang.RU, TtsVoiceCatalog.byId("dmitri").lang)
    }
}
