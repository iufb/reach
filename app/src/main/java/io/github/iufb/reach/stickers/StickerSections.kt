package io.github.iufb.reach.stickers

import io.github.iufb.reach.chats.StickerInfo

/**
 * Секция сетки панели стикеров: заголовок и стикеры. У секции пака [set] — для заголовка
 * (название) и значка в полосе паков; у недавних и избранных — `null`.
 */
data class StickerSection(
    val kind: Kind,
    val stickers: List<StickerInfo>,
    val set: StickerSetInfo? = null,
) {
    enum class Kind { Recent, Favorites, Set }

    /** Стабильный ключ секции для Lazy-сетки и полосы значков. */
    val key: String
        get() = when (kind) {
            Kind.Recent -> "recent"
            Kind.Favorites -> "favorites"
            Kind.Set -> "set-${set?.id}"
        }
}

/**
 * Секции панели по порядку: «Недавние», «Избранные», установленные паки в порядке пользователя.
 * Пак берётся полным из [sets], если уже загружен, иначе — обложками из [installed]. Пустые секции
 * не показываются; повторы внутри секции (один файл дважды) убираются — ключи сетки уникальны.
 */
fun stickerSections(
    recent: List<StickerInfo>,
    favorites: List<StickerInfo>,
    installed: List<StickerSetInfo>,
    sets: Map<Long, StickerSetInfo>,
): List<StickerSection> = buildList {
    add(StickerSection(StickerSection.Kind.Recent, recent.distinctBy { it.file.uniqueId }))
    add(StickerSection(StickerSection.Kind.Favorites, favorites.distinctBy { it.file.uniqueId }))
    installed.forEach { listed ->
        val set = sets[listed.id] ?: listed
        add(StickerSection(StickerSection.Kind.Set, set.stickers.distinctBy { it.file.uniqueId }, set))
    }
}.filter { it.stickers.isNotEmpty() }

/** Индекс заголовка каждой секции в сетке: заголовок, за ним стикеры секции. */
fun sectionStarts(sections: List<StickerSection>): List<Int> =
    sections.runningFold(0) { acc, section -> acc + 1 + section.stickers.size }.dropLast(1)

/** Секция, в которой элемент сетки [index]: последняя, чей заголовок не ниже него. */
fun sectionAt(starts: List<Int>, index: Int): Int = starts.indexOfLast { it <= index }.coerceAtLeast(0)
