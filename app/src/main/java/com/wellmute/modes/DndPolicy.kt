package com.wellmute.modes

import android.service.notification.ZenPolicy
import androidx.room.ColumnInfo

/** Who can still call or message during Do Not Disturb. */
enum class Senders(val zenPolicyType: Int) {
    ANYONE(ZenPolicy.PEOPLE_TYPE_ANYONE),
    CONTACTS(ZenPolicy.PEOPLE_TYPE_CONTACTS),
    STARRED(ZenPolicy.PEOPLE_TYPE_STARRED),
    NOBODY(ZenPolicy.PEOPLE_TYPE_NONE),
    ;

    companion object {
        fun fromZenPolicy(type: Int): Senders? = entries.find { it.zenPolicyType == type }
    }
}

/** Who can reach you and which sounds still play while a Do Not Disturb mode is on. Shared by Bedtime and schedules. */
data class DndPolicy(
    val callsFrom: Senders = Senders.STARRED,
    val repeatCallers: Boolean = true,
    val messagesFrom: Senders = Senders.STARRED,
    val priorityConversations: Boolean = true,
    val alarms: Boolean = true,
    val media: Boolean = false,
    val touchSounds: Boolean = false,
    val reminders: Boolean = false,
    val events: Boolean = false,
    // How notifications that are silenced still show (Android's "Display options for filtered notifications").
    /** Screen off: don't light up the screen for them. */
    @ColumnInfo(defaultValue = "1") val dontTurnOnScreen: Boolean = true,
    /** Screen off: don't show them on the always-on display. */
    @ColumnInfo(defaultValue = "1") val dontWake: Boolean = true,
    /** Screen on: no dots on app icons. */
    @ColumnInfo(defaultValue = "0") val hideBadges: Boolean = false,
    /** Screen on: no icons in the status bar. Implied by [hideFromShade]. */
    @ColumnInfo(defaultValue = "0") val hideStatusBarIcons: Boolean = false,
    /** Screen on: no pop-up banner. Implied by [hideFromShade]. */
    @ColumnInfo(defaultValue = "1") val dontPop: Boolean = true,
    /** Screen on: not in the pull-down shade at all. */
    @ColumnInfo(defaultValue = "0") val hideFromShade: Boolean = false,
) {
    /** Hidden from the shade means no status bar icon and no pop-up either, as in Android. */
    val effectiveHideStatusBarIcons: Boolean get() = hideStatusBarIcons || hideFromShade
    val effectiveDontPop: Boolean get() = dontPop || hideFromShade

    fun toZenPolicy(): ZenPolicy = ZenPolicy.Builder()
        .showFullScreenIntent(!dontTurnOnScreen)
        .showLights(!dontTurnOnScreen)
        .showInAmbientDisplay(!dontWake)
        .showBadges(!hideBadges)
        .showStatusBarIcons(!effectiveHideStatusBarIcons)
        .showPeeking(!effectiveDontPop)
        .showInNotificationList(!hideFromShade)
        .allowCalls(callsFrom.zenPolicyType)
        .allowRepeatCallers(repeatCallers)
        .allowMessages(messagesFrom.zenPolicyType)
        .allowConversations(if (priorityConversations) ZenPolicy.CONVERSATION_SENDERS_IMPORTANT else ZenPolicy.CONVERSATION_SENDERS_NONE)
        .allowAlarms(alarms)
        .allowMedia(media)
        .allowSystem(touchSounds)
        .allowReminders(reminders)
        .allowEvents(events)
        .build()

    /** This policy with anything the user changed in Android's own Modes screen; unset values keep ours. */
    fun updatedFrom(policy: ZenPolicy?): DndPolicy {
        if (policy == null) return this
        fun allows(state: Int, fallback: Boolean) = when (state) {
            ZenPolicy.STATE_ALLOW -> true
            ZenPolicy.STATE_DISALLOW -> false
            else -> fallback
        }
        return DndPolicy(
            callsFrom = Senders.fromZenPolicy(policy.priorityCallSenders) ?: callsFrom,
            repeatCallers = allows(policy.priorityCategoryRepeatCallers, repeatCallers),
            messagesFrom = Senders.fromZenPolicy(policy.priorityMessageSenders) ?: messagesFrom,
            priorityConversations = when (policy.priorityConversationSenders) {
                ZenPolicy.CONVERSATION_SENDERS_IMPORTANT -> true
                ZenPolicy.CONVERSATION_SENDERS_NONE -> false
                else -> priorityConversations
            },
            alarms = allows(policy.priorityCategoryAlarms, alarms),
            media = allows(policy.priorityCategoryMedia, media),
            touchSounds = allows(policy.priorityCategorySystem, touchSounds),
            reminders = allows(policy.priorityCategoryReminders, reminders),
            events = allows(policy.priorityCategoryEvents, events),
            // Display options: "allowed" to show means not hidden.
            dontTurnOnScreen = !allows(policy.visualEffectFullScreenIntent, !dontTurnOnScreen),
            dontWake = !allows(policy.visualEffectAmbient, !dontWake),
            hideBadges = !allows(policy.visualEffectBadge, !hideBadges),
            hideStatusBarIcons = !allows(policy.visualEffectStatusBar, !hideStatusBarIcons),
            dontPop = !allows(policy.visualEffectPeek, !dontPop),
            hideFromShade = !allows(policy.visualEffectNotificationList, !hideFromShade),
        )
    }
}
