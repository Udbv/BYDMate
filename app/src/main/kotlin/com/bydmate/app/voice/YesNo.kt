package com.bydmate.app.voice

/**
 * The answer to a yes/no question by voice (#22: «Лежачий?»), in Russian and Ukrainian; null when
 * the phrase is neither. Only the first word counts: «да, лежачий» is yes, «нет, яма» is no.
 */
object YesNo {
    private val YES = setOf("да", "так", "ага", "угу", "конечно", "верно", "точно", "ну да", "лежачий", "был", "було", "є", "есть")
    private val NO = setOf("нет", "ні", "не", "неа", "нi", "ложная", "яма", "люк", "мимо")

    fun of(phrase: String): Boolean? {
        val words = phrase.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        val first = words.firstOrNull() ?: return null
        return when {
            first in NO -> false
            first in YES -> true
            words.take(2).joinToString(" ") in YES -> true
            else -> null
        }
    }
}
