package com.wellmute.contacts

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** A contact marked as a favourite (starred) in the Contacts app. */
data class StarredContact(val id: Long, val name: String, val uri: Uri)

/**
 * The starred contacts that Do Not Disturb's "Starred contacts only" lets through. Wellmute only
 * reads them, to show who that is; starring and unstarring happen in the Contacts app. Nothing
 * is stored or sent anywhere.
 */
object StarredContacts {

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Sorted by name; empty without permission. Reads only ids and names. */
    fun read(context: Context): List<StarredContact> {
        if (!canRead(context)) return emptyList()
        val projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        val contacts = mutableListOf<StarredContact>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                "${ContactsContract.Contacts.STARRED} = 1",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    contacts += StarredContact(id, name, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id))
                }
            }
        }
        return contacts
    }
}
