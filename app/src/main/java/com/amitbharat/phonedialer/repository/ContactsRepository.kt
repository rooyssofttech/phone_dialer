package com.amitbharat.phonedialer.repository

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import com.amitbharat.phonedialer.database.AppDatabase
import com.amitbharat.phonedialer.database.entity.ContactEntity
import com.amitbharat.phonedialer.model.Contact
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ContactsRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val contactDao = db.contactDao()
    private val gson = Gson()
    private val type = object : TypeToken<List<String>>() {}.type

    companion object {
        @Volatile
        private var cachedContacts: List<Contact>? = null
        @Volatile
        private var lastFetchTime: Long = 0L

        private val _contactsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<Contact>>(emptyList())

        fun getCachedContacts(): List<Contact> = cachedContacts ?: emptyList()

        fun updateCachedContacts(contacts: List<Contact>) {
            cachedContacts = contacts
            lastFetchTime = System.currentTimeMillis()
            _contactsFlow.value = contacts
        }

        private fun encodeNumbers(numbers: List<String>): String = numbers.joinToString(";")

        private fun extractDigitsAndPlus(raw: String): String {
            val len = raw.length
            val sb = StringBuilder(len)
            for (i in 0 until len) {
                val c = raw[i]
                if (c in '0'..'9' || c == '+') sb.append(c)
            }
            return sb.toString()
        }
    }

    private fun decodeNumbers(str: String): List<String> {
        if (str.isBlank()) return emptyList()
        if (str.startsWith("[")) {
            return try {
                gson.fromJson<List<String>>(str, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        return str.split(";").filter { it.isNotBlank() }
    }

    fun getCachedContacts(): List<Contact> = cachedContacts ?: emptyList()

    private fun deduplicateContacts(contacts: List<Contact>): List<Contact> {
        val seenIds = HashSet<Long>(contacts.size)
        val result = ArrayList<Contact>(contacts.size)
        for (i in contacts.indices) {
            val c = contacts[i]
            val validNumbers = c.numbers.filter { num ->
                var digitCount = 0
                for (j in 0 until num.length) {
                    if (num[j] in '0'..'9') digitCount++
                }
                digitCount >= 3
            }.distinct()
            if (validNumbers.isEmpty()) continue
            if (c.id > 0) {
                if (seenIds.add(c.id)) {
                    result.add(c.copy(numbers = validNumbers))
                }
            } else {
                result.add(c.copy(numbers = validNumbers))
            }
        }
        result.sortBy { it.name.lowercase() }
        return result
    }

    fun getAllContacts(forceRefresh: Boolean = false): Flow<List<Contact>> = flow {
        val now = System.currentTimeMillis()
        val cached = cachedContacts
        if (!cached.isNullOrEmpty()) {
            emit(cached)
            if (!forceRefresh && (now - lastFetchTime < 60_000)) {
                _contactsFlow.collect { updatedList ->
                    if (updatedList.isNotEmpty()) emit(updatedList)
                }
                return@flow
            }
        } else {
            // Instant load from Room database for 0ms initial UI display
            try {
                val roomList = contactDao.getAllContactsDirect()
                if (roomList.isNotEmpty()) {
                    val roomContacts = deduplicateContacts(roomList.map { it.toModel() })
                    updateCachedContacts(roomContacts)
                    emit(roomContacts)
                }
            } catch (ignored: Exception) {}
        }

        // Asynchronously fetch fresh device contacts from content provider
        val deviceContacts = deduplicateContacts(fetchDeviceContactsDirectly(onlyStarred = false))
        updateCachedContacts(deviceContacts)
        emit(deviceContacts)

        // Save fresh contacts to Room in background for next instant 0ms launch
        try {
            val entities = deviceContacts.map { c ->
                ContactEntity(
                    id = if (c.id > 0) c.id else 0,
                    name = c.name,
                    numbersJson = encodeNumbers(c.numbers),
                    photoUri = c.photoUri,
                    isFavorite = c.isFavorite
                )
            }
            contactDao.deleteAllContacts()
            contactDao.insertContacts(entities)
        } catch (ignored: Exception) {}

        // Asynchronously refresh WhatsApp contacts map in background without blocking
        try {
            com.amitbharat.phonedialer.utils.WhatsAppHelper.refreshWhatsAppContacts(context)
        } catch (e: Exception) {}

        _contactsFlow.collect { updatedList ->
            if (updatedList.isNotEmpty()) emit(updatedList)
        }
    }.flowOn(Dispatchers.IO)

    fun getFavoriteContacts(): Flow<List<Contact>> = flow {
        val favorites = deduplicateContacts(fetchDeviceContactsDirectly(onlyStarred = true))
        emit(favorites)

        contactDao.getFavoriteContacts().map { list ->
            if (list.isNotEmpty()) deduplicateContacts(list.map { it.toModel() }) else favorites
        }.collect {
            emit(deduplicateContacts(it))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun addContact(contact: Contact): Long {
        val entity = ContactEntity(
            name = contact.name,
            numbersJson = encodeNumbers(contact.numbers),
            photoUri = contact.photoUri,
            email = contact.email,
            isFavorite = contact.isFavorite,
            notes = contact.notes,
            company = contact.company
        )
        return contactDao.insertContact(entity)
    }

    suspend fun updateContact(contact: Contact) {
        val entity = ContactEntity(
            id = contact.id,
            name = contact.name,
            numbersJson = encodeNumbers(contact.numbers),
            photoUri = contact.photoUri,
            email = contact.email,
            isFavorite = contact.isFavorite,
            notes = contact.notes,
            company = contact.company
        )
        contactDao.insertContact(entity)
    }

    suspend fun deleteContact(contact: Contact) {
        val entity = ContactEntity(
            id = contact.id,
            name = contact.name,
            numbersJson = encodeNumbers(contact.numbers),
            photoUri = contact.photoUri,
            email = contact.email,
            isFavorite = contact.isFavorite
        )
        contactDao.deleteContact(entity)
    }

    fun fetchDeviceContactsDirectly(onlyStarred: Boolean = false): List<Contact> {
        val result = mutableListOf<Contact>()
        try {
            val resolver = context.contentResolver
            val selection = if (onlyStarred) "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1" else null
            val cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                    ContactsContract.CommonDataKinds.Phone.STARRED
                ),
                selection,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )

            cursor?.use {
                val contactMap = LinkedHashMap<Long, MutableList<String>>()
                val photoMap = HashMap<Long, String?>()
                val starMap = HashMap<Long, Boolean>()
                val displayNameMap = HashMap<Long, String>()

                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val starIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)

                while (it.moveToNext()) {
                    val contactId = if (idIdx >= 0) it.getLong(idIdx) else 0L
                    if (contactId <= 0) continue

                    val rawName = if (nameIdx >= 0) it.getString(nameIdx) else null
                    val name = if (!rawName.isNullOrBlank()) rawName.trim() else "Unknown"
                    val number = if (numIdx >= 0) it.getString(numIdx)?.trim() ?: "" else ""
                    val photo = if (photoIdx >= 0) it.getString(photoIdx) else null
                    val isStarred = if (starIdx >= 0) it.getInt(starIdx) == 1 else false

                    var digitCount = 0
                    for (k in 0 until number.length) {
                        if (number[k] in '0'..'9') digitCount++
                    }
                    if (number.isNotBlank() && digitCount >= 3) {
                        contactMap.getOrPut(contactId) { mutableListOf() }.add(number)
                        displayNameMap[contactId] = name
                        if (photo != null && photoMap[contactId] == null) photoMap[contactId] = photo
                        if (isStarred) starMap[contactId] = true
                    }
                }

                contactMap.forEach { (cid, numbers) ->
                    val displayName = displayNameMap[cid] ?: "Unknown"
                    result.add(
                        Contact(
                            id = cid,
                            name = displayName,
                            numbers = numbers.distinct(),
                            photoUri = photoMap[cid],
                            isFavorite = starMap[cid] ?: false
                        )
                    )
                }
                result.sortBy { it.name.lowercase() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    suspend fun syncDeviceContacts() = withContext(Dispatchers.IO) {
        val contacts = fetchDeviceContactsDirectly()
        updateCachedContacts(contacts)
        val entities = contacts.map { c ->
            ContactEntity(
                name = c.name,
                numbersJson = encodeNumbers(c.numbers),
                photoUri = c.photoUri,
                isFavorite = c.isFavorite
            )
        }
        contactDao.deleteAllContacts()
        contactDao.insertContacts(entities)
    }

    private fun ContactEntity.toModel(): Contact {
        val numbers: List<String> = decodeNumbers(numbersJson)
        return Contact(
            id = id,
            name = name,
            numbers = numbers,
            photoUri = photoUri,
            email = email,
            isFavorite = isFavorite,
            notes = notes,
            company = company
        )
    }
}
