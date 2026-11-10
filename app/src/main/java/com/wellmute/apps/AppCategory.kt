package com.wellmute.apps

import android.content.pm.ApplicationInfo
import androidx.annotation.StringRes
import com.wellmute.R

/**
 * A few broad kinds of app, for splitting screen time in charts. Kept to five so the colours and
 * the legend stay readable.
 */
enum class AppCategory(@StringRes val label: Int) {
    SOCIAL(R.string.category_social),
    ENTERTAINMENT(R.string.category_entertainment),
    GAMES(R.string.category_games),
    PRODUCTIVITY(R.string.category_productivity),
    OTHER(R.string.category_other),
    ;

    companion object {
        /** The category an app declares for itself in its manifest, if it maps to one of ours. */
        fun fromSystem(category: Int): AppCategory? = when (category) {
            ApplicationInfo.CATEGORY_SOCIAL -> SOCIAL
            ApplicationInfo.CATEGORY_GAME -> GAMES
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> ENTERTAINMENT
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> PRODUCTIVITY
            else -> null
        }
    }
}

/**
 * Common apps that don't declare a category, or declare one that doesn't fit, wherever they were
 * installed from: package names are the same on Google Play, F-Droid, Accrescent or an APK.
 * It ships inside Wellmute and is never looked up online. Anyone can choose differently per app.
 */
internal val KNOWN_CATEGORIES: Map<String, AppCategory> = buildMap {
    fun add(category: AppCategory, vararg packages: String) = packages.forEach { put(it, category) }

    add(
        AppCategory.SOCIAL,
        "com.instagram.android", "com.instagram.barcelona", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.twitter.android", "com.facebook.katana", "com.facebook.lite", "com.facebook.orca", "com.snapchat.android",
        "com.reddit.frontpage", "com.discord", "com.linkedin.android", "com.pinterest", "com.tumblr", "com.bereal.ft",
        "xyz.blueskyweb.app", "ml.docilealligator.infinityforreddit", "org.joinmastodon.android",
        "com.keylesspalace.tusky", "app.fedilab.android", "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "org.thoughtcrime.securesms", "im.molly.app", "im.vector.app", "ch.threema.app", "ch.threema.app.libre",
        "chat.simplex.app", "eu.siacs.conversations", "com.viber.voip", "jp.naver.line.android", "com.tencent.mm",
        "com.google.android.apps.messaging", "com.android.messaging", "org.fossify.messages",
    )
    add(
        AppCategory.ENTERTAINMENT,
        "com.google.android.youtube", "app.revanced.android.youtube", "com.google.android.apps.youtube.kids",
        "org.schabi.newpipe", "org.polymorphicshade.tubular", "com.github.libretube", "com.netflix.mediaclient",
        "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient", "com.wbd.stream", "tv.twitch.android.app",
        "com.crunchyroll.crunchyroid", "com.spotify.music", "com.google.android.apps.youtube.music",
        "deezer.android.app", "com.soundcloud.android", "com.apple.android.music", "com.amazon.mp3",
        "com.audible.application", "au.com.shiftyjelly.pocketcasts", "de.danoeh.antennapod",
        "it.fast4x.rimusic", "app.vitune.android", "com.zionhuang.music", "org.videolan.vlc", "is.xyz.mpv",
        "com.mxtech.videoplayer.ad", "com.stremio.one", "com.plexapp.android", "org.jellyfin.mobile",
        "dev.jdtech.jellyfin", "org.fossify.musicplayer",
    )
    add(
        AppCategory.GAMES,
        "com.supercell.clashofclans", "com.supercell.clashroyale", "com.supercell.brawlstars",
        "com.king.candycrushsaga", "com.mojang.minecraftpe", "com.roblox.client", "com.chess",
        "org.lichess.mobileapp", "com.kiloo.subwaysurf", "com.innersloth.spacemafia",
    )
    add(
        AppCategory.PRODUCTIVITY,
        "com.wellmute",
        "com.google.android.gm", "ch.protonmail.android", "me.proton.android.calendar", "me.proton.android.drive",
        "com.microsoft.office.outlook", "com.microsoft.teams", "com.microsoft.office.word",
        "com.microsoft.office.excel", "com.slack", "notion.id", "md.obsidian", "com.todoist", "com.ticktick.task",
        "com.trello", "com.google.android.apps.docs", "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets", "com.google.android.apps.docs.editors.slides",
        "com.google.android.calendar", "com.google.android.keep", "org.fossify.calendar", "org.fossify.notes",
        "com.nextcloud.client", "net.thunderbird.android", "com.fsck.k9", "com.philkes.notallyx",
        "net.gsantner.markor", "io.anytype.app", "com.dropbox.android", "us.zoom.videomeetings",
    )
}
