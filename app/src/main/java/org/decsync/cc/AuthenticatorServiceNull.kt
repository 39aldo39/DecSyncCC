/**
 * DecSyncCC - AuthenticatorServiceNull.kt
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

package org.decsync.cc

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

abstract class AuthenticatorServiceNull : Service() {
    override fun onBind(intent: Intent): IBinder = AccountAuthenticator(this).iBinder

    class AccountAuthenticator(mContext: Context) : AbstractAccountAuthenticator(mContext) {
        override fun addAccount(response: AccountAuthenticatorResponse, accountType: String, authTokenType: String, requiredFeatures: Array<String>, options: Bundle): Bundle? = null
        override fun editProperties(response: AccountAuthenticatorResponse, accountType: String): Bundle? = null
        override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, bundle: Bundle): Bundle? = null
        override fun getAuthToken(response: AccountAuthenticatorResponse, account: Account, s: String, bundle: Bundle): Bundle? = null
        override fun getAuthTokenLabel(s: String): String? = null
        override fun updateCredentials(response: AccountAuthenticatorResponse, account: Account, s: String, bundle: Bundle): Bundle? = null
        override fun hasFeatures(response: AccountAuthenticatorResponse, account: Account, strings: Array<String>): Bundle? = null
    }
}
