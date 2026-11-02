package com.bydmate.app.data.roadpoints

/** What the voice says after «лежачий тут»: in Russian, the language of the car's voice. */
object RoadPointPhrases {
    fun answer(result: MarkResult): String = when (result) {
        is MarkResult.Added -> "Отметил: ${name(result.type)}"
        is MarkResult.Passed -> "${name(result.type).replaceFirstChar { it.uppercase() }}, уже отмечен: проезд ${result.passes}"
        MarkResult.NoPosition -> "Нет точной позиции GPS, точка не сохранена"
    }

    private fun name(type: RoadPointType): String = when (type) {
        RoadPointType.SPEED_BUMP -> "лежачий"
        RoadPointType.TRAFFIC_LIGHT -> "светофор"
        RoadPointType.LANES -> "полосы"
        RoadPointType.OTHER -> "точку"
    }
}
