package io.github.iufb.reach.stickers

import io.github.iufb.reach.chats.StickerInfo

/**
 * Секция сетки панели стикеров: заголовок и стикеры. У секции пака [set] — для заголовка
 * (название) и значка в полосе паков; у недавних и избранных — `null`.
 *
 * Секция трендов ([Kind.Trending]) — один элемент сетки на всю ширину (заголовок с крестиком
 * и строка паков [trending]), стикеров в сетке у неё нет.
 */
data class StickerSection(
    val kind: Kind,
    val stickers: List<StickerInfo>,
    val set: StickerSetInfo? = null,
    val trending: List<StickerSetInfo> = emptyList(),
) {
    enum class Kind { Trending, Recent, Favorites, Set }

    /** Стабильный ключ секции для Lazy-сетки и полосы значков. */
    val key: String
        get() = when (kind) {
            Kind.Trending -> "trending"
            Kind.Recent -> "recent"
            Kind.Favorites -> "favorites"
            Kind.Set -> "set-${set?.id}"
        }
}

/**
 * Секции панели по порядку: «Трендовые» (если [trending] не пуст — см. [trendingShown]),
 * «Недавние», «Избранные», установленные паки в порядке пользователя.
 * Пак берётся полным из [sets], если уже загружен, иначе — обложками из [installed]. Пустые секции
 * не показываются; повторы внутри секции (один файл дважды) убираются — ключи сетки уникальны.
 */
fun stickerSections(
    recent: List<StickerInfo>,
    favorites: List<StickerInfo>,
    installed: List<StickerSetInfo>,
    sets: Map<Long, StickerSetInfo>,
    trending: List<StickerSetInfo> = emptyList(),
): List<StickerSection> = buildList {
    if (trending.isNotEmpty()) add(StickerSection(StickerSection.Kind.Trending, emptyList(), trending = trending.distinctBy { it.id }))
    add(StickerSection(StickerSection.Kind.Recent, recent.distinctBy { it.file.uniqueId }))
    add(StickerSection(StickerSection.Kind.Favorites, favorites.distinctBy { it.file.uniqueId }))
    installed.forEach { listed ->
        val set = sets[listed.id] ?: listed
        add(StickerSection(StickerSection.Kind.Set, set.stickers.distinctBy { it.file.uniqueId }, set))
    }
}.filter { it.stickers.isNotEmpty() || it.kind == StickerSection.Kind.Trending }

/**
 * Показывать ли тренды: есть загруженные паки, и секцию не скрыли крестиком ([hidden]) — или
 * после этого пришли новые, непросмотренные паки.
 */
fun trendingShown(sets: List<StickerSetInfo>, hidden: Boolean): Boolean =
    sets.isNotEmpty() && (!hidden || sets.any { !it.isViewed })

/** Индекс заголовка каждой секции в сетке: заголовок, за ним стикеры секции (у трендов — ни одного). */
fun sectionStarts(sections: List<StickerSection>): List<Int> =
    sections.runningFold(0) { acc, section -> acc + 1 + section.stickers.size }.dropLast(1)

/** Секция, в которой элемент сетки [index]: последняя, чей заголовок не ниже него. */
fun sectionAt(starts: List<Int>, index: Int): Int = starts.indexOfLast { it <= index }.coerceAtLeast(0)
