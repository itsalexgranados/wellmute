package com.wellmute

/**
 * Web pages Wellmute links to. Opening one hands it to the browser: Wellmute itself has no
 * internet access.
 *
 * The repository is still private; these must point to the public one before release.
 */
object Links {
    private const val REPOSITORY = "https://github.com/itsalexgranados/wellmute"

    const val PRIVACY = "$REPOSITORY/blob/main/docs/PRIVACY.md"

    /** The author's site, from "Made by Alex Granados" in Settings. */
    const val AUTHOR = "https://alexgranados.com"

    /** The donation section of Wellmute's site, from "Support the project" in Settings. */
    const val DONATE = "https://wellmute.com/#donate"
}
