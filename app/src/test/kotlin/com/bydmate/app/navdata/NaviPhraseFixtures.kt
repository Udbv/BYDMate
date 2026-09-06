package com.bydmate.app.navdata

import java.io.File

/** Loads the checked-in language packs (app/src/main/assets/navi/phrases) into
 *  [NavPhraseTables] for JVM tests, from wherever Gradle runs the test JVM. */
object NaviPhraseFixtures {
    val dir: File by lazy {
        val rel = "app/src/main/assets/navi/phrases"
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, rel).isDirectory }
            ?: error("Cannot find $rel from ${File(".").canonicalPath}")
        File(root, rel)
    }

    @Synchronized
    fun load() {
        if (NavPhraseTables.loadedFrom == dir.path) return
        NavPhraseTables.loadFromDirectory(dir)
    }
}
