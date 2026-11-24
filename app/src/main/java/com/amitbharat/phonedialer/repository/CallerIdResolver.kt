package com.amitbharat.phonedialer.repository

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

enum class CallerSource {
    SAVED,
    GOVT,
    TRUECALLER,
    NONE
}

data class ResolvedCallerInfo(
    val name: String?,
    val isSaved: Boolean,
    val source: CallerSource
)

object CallerIdResolver {

    private val callerIdCache = ConcurrentHashMap<String, String>()
    private const val PREF_NAME = "caller_id_cache_prefs"

    fun extractDigits(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            if (c in '0'..'9') sb.append(c)
        }
        return sb.toString()
    }

    fun extractCleanNumber(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            if (c in '0'..'9' || c == '+') sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Resolves caller name according to strict priority hierarchy:
     * 1. Saved Contact: If number is saved in the user's phone, return saved name.
     *    Govt CNAP and Truecaller MUST NOT overwrite saved names.
     * 2. Govt CNAP: If not saved, and govt/carrier CNAP name is provided, return govt name.
     * 3. Truecaller / Caller ID: If not saved and no govt name, check Truecaller.
     */
    fun resolveCallerName(
        context: Context,
        rawNumber: String,
        govtName: String? = null
    ): ResolvedCallerInfo {
        if (rawNumber.isBlank()) {
            return ResolvedCallerInfo(null, false, CallerSource.NONE)
        }

        val digits = extractDigits(rawNumber)
        val clean = extractCleanNumber(rawNumber)
        val last10 = if (digits.length >= 10) digits.takeLast(10) else digits

        if (digits.length < 3) {
            return ResolvedCallerInfo(null, false, CallerSource.NONE)
        }

        // STEP 1: Check SAVED CONTACTS
        val cachedContacts = ContactsRepository.getCachedContacts()
        val matchedSaved = cachedContacts.find { c ->
            c.numbers.any { num ->
                val cClean = extractCleanNumber(num)
                val cDigits = extractDigits(num)
                val cLast10 = if (cDigits.length >= 10) cDigits.takeLast(10) else cDigits
                (clean.isNotBlank() && cClean == clean) ||
                (last10.isNotBlank() && cLast10 == last10)
            }
        }

        if (matchedSaved != null && matchedSaved.name.isNotBlank()) {
            return ResolvedCallerInfo(
                name = matchedSaved.name.trim(),
                isSaved = true,
                source = CallerSource.SAVED
            )
        }

        // Fallback check against ContactsContract.PhoneLookup
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(rawNumber))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        val savedName = cursor.getString(nameIdx)
                        if (!savedName.isNullOrBlank()) {
                            return ResolvedCallerInfo(
                                name = savedName.trim(),
                                isSaved = true,
                                source = CallerSource.SAVED
                            )
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        // STEP 2: NOT SAVED -> Check GOVERNMENT POLICY / CNAP NAME
        val cleanGovtName = govtName?.trim()
        val isGovtValid = !cleanGovtName.isNullOrBlank() &&
                cleanGovtName != rawNumber &&
                cleanGovtName != clean &&
                !cleanGovtName.equals("Unknown", ignoreCase = true) &&
                !cleanGovtName.equals("Private Number", ignoreCase = true) &&
                !cleanGovtName.equals("Private", ignoreCase = true)

        if (isGovtValid) {
            cacheCallerId(context, clean, cleanGovtName!!)
            if (last10.isNotBlank()) cacheCallerId(context, last10, cleanGovtName)
            return ResolvedCallerInfo(
                name = cleanGovtName,
                isSaved = false,
                source = CallerSource.GOVT
            )
        }

        // STEP 3: NOT SAVED & NO GOVT NAME -> Check TRUECALLER / CALLER ID LOOKUP
        val truecallerName = lookupTruecaller(context, clean, last10)
        if (!truecallerName.isNullOrBlank()) {
            return ResolvedCallerInfo(
                name = truecallerName,
                isSaved = false,
                source = CallerSource.TRUECALLER
            )
        }

        return ResolvedCallerInfo(
            name = null,
            isSaved = false,
            source = CallerSource.NONE
        )
    }

    fun lookupTruecaller(context: Context, cleanNumber: String, last10: String): String? {
        val keys = listOfNotNull(
            last10.ifBlank { null },
            cleanNumber.ifBlank { null }
        )
        if (keys.isEmpty()) return null

        // 1. Check in-memory cache
        for (k in keys) {
            callerIdCache[k]?.let { return it }
        }

        // 2. Check SharedPreferences persistent cache
        try {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            for (k in keys) {
                val cached = sp.getString(k, null)
                if (!cached.isNullOrBlank()) {
                    callerIdCache[k] = cached
                    return cached
                }
            }
        } catch (ignored: Exception) {}

        val key = if (last10.isNotBlank()) last10 else cleanNumber

        // 3. Check Truecaller ContentProvider on device (com.truecaller)
        try {
            val searchUris = listOf(
                Uri.parse("content://com.truecaller/search/$key"),
                Uri.parse("content://com.truecaller/search/$cleanNumber"),
                Uri.parse("content://com.truecaller.search/$key")
            )
            for (uri in searchUris) {
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            for (col in listOf("name", "display_name", "data1", "contact_name", "tc_name")) {
                                val idx = cursor.getColumnIndex(col)
                                if (idx >= 0) {
                                    val valName = cursor.getString(idx)
                                    if (!valName.isNullOrBlank() && valName != key && valName != cleanNumber) {
                                        cacheCallerId(context, key, valName.trim())
                                        return valName.trim()
                                    }
                                }
                            }
                        }
                    }
                } catch (ignored: Exception) {}
            }
        } catch (ignored: Exception) {}

        // 4. Check RawContacts with account_type "com.truecaller" in ContactsContract
        try {
            val rawUri = ContactsContract.Data.CONTENT_URI
            val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?"
            val args = arrayOf("%$key%", "com.truecaller")
            context.contentResolver.query(
                rawUri,
                arrayOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME),
                selection,
                args,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) {
                        cacheCallerId(context, key, name.trim())
                        return name.trim()
                    }
                }
            }
        } catch (ignored: Exception) {}

        for (k in keys) {
            callerIdCache[k] = ""
        }
        return null
    }

    fun cacheCallerId(context: Context, numberKey: String, name: String) {
        val trimmedName = name.trim()
        if (numberKey.isBlank() || trimmedName.isBlank()) return
        val clean = extractCleanNumber(numberKey)
        val digits = extractDigits(numberKey)
        val last10 = if (digits.length >= 10) digits.takeLast(10) else digits

        callerIdCache[numberKey] = trimmedName
        if (clean.isNotBlank()) callerIdCache[clean] = trimmedName
        if (last10.isNotBlank()) callerIdCache[last10] = trimmedName

        try {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val editor = sp.edit()
            editor.putString(numberKey, trimmedName)
            if (clean.isNotBlank()) editor.putString(clean, trimmedName)
            if (last10.isNotBlank()) editor.putString(last10, trimmedName)
            editor.apply()
        } catch (ignored: Exception) {}
    }
}
