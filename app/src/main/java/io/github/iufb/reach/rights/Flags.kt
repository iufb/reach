package io.github.iufb.reach.rights

import androidx.annotation.StringRes
import io.github.iufb.reach.R
import org.drinkless.tdlib.TdApi

/**
 * Права описаны данными, а не вёрсткой: один экран флагов на группы и каналы (PRODUCT.md §4.6).
 * [inGroup] / [inChannel] — где флаг имеет смысл; [forumOnly] — только в форумах.
 */
internal class AdminFlag(
    @StringRes val label: Int,
    val get: (TdApi.ChatAdministratorRights) -> Boolean,
    val set: (TdApi.ChatAdministratorRights, Boolean) -> Unit,
    val inGroup: Boolean = true,
    val inChannel: Boolean = true,
    val forumOnly: Boolean = false,
    /** Значение для нового админа. */
    val default: Boolean = true,
)

internal val ADMIN_FLAGS = listOf(
    AdminFlag(R.string.right_change_info, { it.canChangeInfo }, { r, v -> r.canChangeInfo = v }),
    AdminFlag(R.string.right_post_messages, { it.canPostMessages }, { r, v -> r.canPostMessages = v }, inGroup = false),
    AdminFlag(R.string.right_edit_messages, { it.canEditMessages }, { r, v -> r.canEditMessages = v }, inGroup = false),
    AdminFlag(R.string.right_delete_messages, { it.canDeleteMessages }, { r, v -> r.canDeleteMessages = v }),
    AdminFlag(R.string.right_restrict_members, { it.canRestrictMembers }, { r, v -> r.canRestrictMembers = v }, inChannel = false),
    AdminFlag(R.string.right_invite_users, { it.canInviteUsers }, { r, v -> r.canInviteUsers = v }),
    AdminFlag(R.string.right_pin_messages, { it.canPinMessages }, { r, v -> r.canPinMessages = v }, inChannel = false),
    AdminFlag(R.string.right_manage_topics, { it.canManageTopics }, { r, v -> r.canManageTopics = v }, inChannel = false, forumOnly = true),
    AdminFlag(R.string.right_manage_video_chats, { it.canManageVideoChats }, { r, v -> r.canManageVideoChats = v }),
    AdminFlag(R.string.right_post_stories, { it.canPostStories }, { r, v -> r.canPostStories = v }, inGroup = false, default = false),
    AdminFlag(R.string.right_edit_stories, { it.canEditStories }, { r, v -> r.canEditStories = v }, inGroup = false, default = false),
    AdminFlag(R.string.right_delete_stories, { it.canDeleteStories }, { r, v -> r.canDeleteStories = v }, inGroup = false, default = false),
    AdminFlag(R.string.right_promote_members, { it.canPromoteMembers }, { r, v -> r.canPromoteMembers = v }, default = false),
    AdminFlag(R.string.right_anonymous, { it.isAnonymous }, { r, v -> r.isAnonymous = v }, inChannel = false, default = false),
)

internal fun emptyAdminRights() = TdApi.ChatAdministratorRights(
    true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
)

/** Разрешения участников: общие для группы и для ограничения одного участника. */
internal class PermissionFlag(
    @StringRes val label: Int,
    val get: (TdApi.ChatPermissions) -> Boolean,
    val set: (TdApi.ChatPermissions, Boolean) -> Unit,
    val forumOnly: Boolean = false,
)

internal val PERMISSION_FLAGS = listOf(
    PermissionFlag(R.string.perm_send_text, { it.canSendBasicMessages }, { p, v -> p.canSendBasicMessages = v }),
    PermissionFlag(R.string.perm_send_photos, { it.canSendPhotos }, { p, v -> p.canSendPhotos = v }),
    PermissionFlag(R.string.perm_send_videos, { it.canSendVideos }, { p, v -> p.canSendVideos = v }),
    PermissionFlag(R.string.perm_send_audios, { it.canSendAudios }, { p, v -> p.canSendAudios = v }),
    PermissionFlag(R.string.perm_send_documents, { it.canSendDocuments }, { p, v -> p.canSendDocuments = v }),
    PermissionFlag(R.string.perm_send_voice, { it.canSendVoiceNotes }, { p, v -> p.canSendVoiceNotes = v }),
    PermissionFlag(R.string.perm_send_video_notes, { it.canSendVideoNotes }, { p, v -> p.canSendVideoNotes = v }),
    PermissionFlag(R.string.perm_send_stickers, { it.canSendOtherMessages }, { p, v -> p.canSendOtherMessages = v }),
    PermissionFlag(R.string.perm_send_polls, { it.canSendPolls }, { p, v -> p.canSendPolls = v }),
    PermissionFlag(R.string.perm_link_previews, { it.canAddLinkPreviews }, { p, v -> p.canAddLinkPreviews = v }),
    PermissionFlag(R.string.perm_react, { it.canReactToMessages }, { p, v -> p.canReactToMessages = v }),
    PermissionFlag(R.string.perm_invite, { it.canInviteUsers }, { p, v -> p.canInviteUsers = v }),
    PermissionFlag(R.string.perm_pin, { it.canPinMessages }, { p, v -> p.canPinMessages = v }),
    PermissionFlag(R.string.perm_change_info, { it.canChangeInfo }, { p, v -> p.canChangeInfo = v }),
    PermissionFlag(R.string.perm_create_topics, { it.canCreateTopics }, { p, v -> p.canCreateTopics = v }, forumOnly = true),
)

internal fun emptyPermissions() = TdApi.ChatPermissions(
    false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
)
