package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/** Карточка ссылки: сайт, заголовок, описание и картинка, если есть. */
data class LinkPreview(
    val url: String,
    val siteName: String,
    val title: String,
    val description: RichText,
    val photo: TdFile?,
)

/** Пустая карточка (ни сайта, ни заголовка, ни описания, ни картинки) не показывается. */
internal fun TdApi.LinkPreview.toModel(): LinkPreview? {
    val photo = when (val t = type) {
        is TdApi.LinkPreviewTypeArticle -> t.photo
        is TdApi.LinkPreviewTypePhoto -> t.photo
        else -> null
    }
    val preview = LinkPreview(
        url = url,
        siteName = siteName,
        title = title,
        description = description.toRichText(),
        photo = photo?.bestSize()?.photo?.toTdFile(),
    )
    val empty = siteName.isBlank() && title.isBlank() && preview.description.isBlank() && preview.photo == null
    return preview.takeUnless { empty }
}

/** Размер не меньше [targetWidth] и при этом самый маленький из таких; иначе самый большой. */
internal fun TdApi.Photo.bestSize(targetWidth: Int = 640): TdApi.PhotoSize? =
    sizes.filter { it.width >= targetWidth }.minByOrNull { it.width } ?: sizes.maxByOrNull { it.width }
