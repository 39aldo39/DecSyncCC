/**
 * DecSyncCC - LocalContact.kt
 *
 * Copyright (C) 2018 Aldo Gunsing
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 */

package org.decsync.cc.contacts

import android.content.ContentValues
import android.provider.ContactsContract.RawContacts
import at.bitfire.vcard4android.*
import ezvcard.VCardVersion
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.decsync.cc.Extra
import org.decsync.library.Decsync
import java.io.ByteArrayOutputStream
import java.util.*

@ExperimentalStdlibApi
class LocalContact: AndroidContact {

    // Use fileName as UID and eTag as bookId

    private var uid: String?
        get() = this.fileName
        set(value) { this.fileName = value }

    private var bookId: String?
        get() = this.eTag
        set(value) { this.eTag = value }

    companion object {
        const val COLUMN_LOCAL_UID = COLUMN_FILENAME
        const val COLUMN_LOCAL_BOOKID = COLUMN_ETAG
    }

    constructor(addressBook: AndroidAddressBook<LocalContact, *>, values: ContentValues) : super(addressBook, values)
    constructor(addressBook: AndroidAddressBook<LocalContact,*>, contact: Contact, uid: String, bookId: String) : super(addressBook, contact, uid, bookId)

    fun writeDeleteAction(decsync: Decsync<Extra>) {
        val uid = uid

        if (uid != null) {
            decsync.setEntry(listOf("resources", uid), JsonNull, JsonNull)
        }

        super.delete()
    }

    fun writeUpdateAction(decsync: Decsync<Extra>) {
        val bookId = bookId // Populating the contact overwrites the bookId from the database
        val contact = getContact()
        val uid = uid ?: UUID.randomUUID().toString()
        contact.uid = uid

        val os = ByteArrayOutputStream()
        contact.writeVCard(VCardVersion.V3_0, os) // Evolution does not support version 4.0 well (https://gitlab.gnome.org/GNOME/evolution-data-server/-/issues/8)
        val vcard = os.toString("UTF-8")
        decsync.setEntry(listOf("resources", uid), JsonNull, JsonPrimitive(vcard))

        val values = ContentValues()
        values.put(COLUMN_LOCAL_UID, uid)
        values.put(COLUMN_LOCAL_BOOKID, bookId ?: this.bookId) // If we create a new contact, we need the given bookId
        values.put(RawContacts.DIRTY, 0)
        addressBook.provider!!.update(rawContactSyncURI(), values, null, null)
    }

    object ContactFactory: AndroidContactFactory<LocalContact> {
        override fun fromProvider(addressBook: AndroidAddressBook<LocalContact, *>, values: ContentValues) =
                LocalContact(addressBook, values)
    }

    object GroupFactory: AndroidGroupFactory<AndroidGroup> {
        override fun fromProvider(addressBook: AndroidAddressBook<out AndroidContact, AndroidGroup>, values: ContentValues) =
                AndroidGroup(addressBook, values)
    }

}
