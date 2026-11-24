package com.amitbharat.phonedialer.repository

import android.content.ContentResolver
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import com.amitbharat.phonedialer.database.AppDatabase
import com.amitbharat.phonedialer.database.entity.CallLogEntity
import com.amitbharat.phonedialer.database.entity.SpeedDialEntity
import com.amitbharat.phonedialer.model.CallLogItem
import com.amitbharat.phonedialer.model.CallType
import com.amitbharat.phonedialer.model.SpeedDialItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class CallLogRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val callLogDao = db.callLogDao()
    private val speedDialDao = db.speedDialDao()

    companion object {
        @Volatile
        private var cachedCallLogs: List<CallLogItem>? = null
        @Volatile
        private var lastLogFetchTime: Long = 0L
        @Volatile
        private var cachedRecordingsMap: Map<String, String>? = null
        @Volatile
        private var lastRecordingsScanTime: Long = 0L
        @Volatile
        var currentDaysLimit: Int = 15

        private val _callLogsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<CallLogItem>>(emptyList())

        fun getCachedCallLogs(): List<CallLogItem> = cachedCallLogs ?: emptyList()

        fun updateCachedCallLogs(logs: List<CallLogItem>) {
            cachedCallLogs = logs
            lastLogFetchTime = System.currentTimeMillis()
            _callLogsFlow.value = logs
        }
    }

    fun getCachedCallLogs(): List<CallLogItem> = cachedCallLogs ?: emptyList()

    private fun extractDigits(raw: String): String {
        val len = raw.length
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val c = raw[i]
            if (c in '0'..'9') sb.append(c)
        }
        return sb.toString()
    }

    private fun extractCleanNumber(raw: String): String {
        val len = raw.length
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val c = raw[i]
            if (c in '0'..'9' || c == '+') sb.append(c)
        }
        return sb.toString()
    }

    private fun deduplicateLogs(logs: List<CallLogItem>): List<CallLogItem> {
        val seen = HashSet<String>(logs.size)
        val result = ArrayList<CallLogItem>(logs.size)
        for (i in logs.indices) {
            val item = logs[i]
            val digits = extractDigits(item.number)
            if (digits.length < 3) continue
            val cleanNum = extractCleanNumber(item.number)
            val key = "${cleanNum}_${item.timestamp}_${item.callType.name}_${item.duration}"
            if (seen.add(key)) {
                result.add(item)
            }
        }
        result.sortByDescending { it.timestamp }
        return result
    }

    fun getAllCallLogs(forceRefresh: Boolean = false): Flow<List<CallLogItem>> = flow {
        val now = System.currentTimeMillis()
        val cached = cachedCallLogs
        if (!cached.isNullOrEmpty()) {
            emit(cached)
            if (!forceRefresh && (now - lastLogFetchTime < 30_000)) {
                _callLogsFlow.collect { updatedList ->
                    if (updatedList.isNotEmpty()) emit(updatedList)
                }
                return@flow
            }
        } else {
            // Stage 1: Ultra-fast fetch of top 100 call logs (0ms perceived startup!)
            try {
                val fastLogs = deduplicateLogs(fetchDeviceCallLogsDirectly(daysLimit = null, maxCount = 100, skipRecordings = true))
                if (fastLogs.isNotEmpty()) {
                    updateCachedCallLogs(fastLogs)
                    emit(fastLogs)
                }
            } catch (ignored: Exception) {}
        }

        // Stage 2: Query full recent call logs in background
        val recentLogs = deduplicateLogs(fetchDeviceCallLogsDirectly(daysLimit = null, maxCount = 500, skipRecordings = false))
        if (recentLogs.isNotEmpty()) {
            updateCachedCallLogs(recentLogs)
            emit(recentLogs)
        }

        _callLogsFlow.collect { updatedList ->
            if (updatedList.isNotEmpty()) {
                emit(updatedList)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun loadMoreCallLogs(batchCount: Int = 100): List<CallLogItem> = withContext(Dispatchers.IO) {
        val updated = deduplicateLogs(fetchDeviceCallLogsDirectly(daysLimit = null, maxCount = (cachedCallLogs?.size ?: 100) + batchCount))
        if (updated.isNotEmpty()) {
            updateCachedCallLogs(updated)
        }
        updated
    }

    suspend fun addCallLog(item: CallLogItem): Long = withContext(Dispatchers.IO) {
        val entity = CallLogEntity(
            number = item.number,
            name = item.name,
            callType = item.callType.name,
            timestamp = item.timestamp,
            duration = item.duration,
            simSlot = item.simSlot,
            recordingPath = item.recordingPath,
            notes = item.notes
        )
        callLogDao.insertCallLog(entity)
    }

    suspend fun deleteCallLog(id: Long) = withContext(Dispatchers.IO) {
        callLogDao.deleteCallLog(id)
    }

    suspend fun clearCallLogs() = withContext(Dispatchers.IO) {
        callLogDao.clearCallLogs()
    }

    fun fetchDeviceCallLogsDirectly(
        daysLimit: Int? = null,
        maxCount: Int? = null,
        skipRecordings: Boolean = false
    ): List<CallLogItem> {
        val result = mutableListOf<CallLogItem>()
        try {
            // Build saved contact number indices and name map for O(1) instant lookup
            val savedIndex = getSavedContactIndex()

            // Skip file scanning during fast initial load
            val recordingsMap = if (skipRecordings || (maxCount != null && maxCount <= 50)) emptyMap() else getRecordingsMap()

            val resolver: ContentResolver = context.contentResolver
            val (selection, selectionArgs) = if (daysLimit != null) {
                val minDate = System.currentTimeMillis() - (daysLimit.toLong() * 24L * 60L * 60L * 1000L)
                Pair("${CallLog.Calls.DATE} >= ?", arrayOf(minDate.toString()))
            } else {
                Pair(null, null)
            }

            // Always use safe sort order without LIMIT keyword (LIMIT in sortOrder causes crashes on Android 10+)
            val sortOrder = "${CallLog.Calls.DATE} DESC"

            val extendedProjection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                "cnap_name",
                "asserted_display_name"
            )

            val standardProjection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            val cursor = try {
                resolver.query(
                    CallLog.Calls.CONTENT_URI,
                    extendedProjection,
                    selection,
                    selectionArgs,
                    sortOrder
                )
            } catch (e: Exception) {
                resolver.query(
                    CallLog.Calls.CONTENT_URI,
                    standardProjection,
                    selection,
                    selectionArgs,
                    sortOrder
                )
            }

            cursor?.use {
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val cnapIdx = it.getColumnIndex("cnap_name")
                val assertedIdx = it.getColumnIndex("asserted_display_name")

                class TempCallRecord(
                    val id: Long,
                    val number: String,
                    val cleanNum: String,
                    val last10: String,
                    val rawName: String?,
                    val cnapName: String?,
                    val assertedName: String?,
                    val callType: CallType,
                    val date: Long,
                    val duration: Long
                )

                val tempRecords = ArrayList<TempCallRecord>()
                val discoveredGovtNames = HashMap<String, String>()

                while (it.moveToNext()) {
                    if (maxCount != null && tempRecords.size >= maxCount) {
                        break
                    }
                    val number = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""
                    if (number.isBlank()) continue

                    val digitsOnly = extractDigits(number)
                    if (digitsOnly.length < 3) continue

                    val cleanNum = extractCleanNumber(number)
                    val last10 = if (digitsOnly.length >= 10) digitsOnly.takeLast(10) else digitsOnly

                    val id = if (idIdx >= 0) it.getLong(idIdx) else 0L
                    val rawName = if (nameIdx >= 0) it.getString(nameIdx) else null
                    val cnapName = if (cnapIdx >= 0) it.getString(cnapIdx) else null
                    val assertedName = if (assertedIdx >= 0) it.getString(assertedIdx) else null
                    val rawType = if (typeIdx >= 0) it.getInt(typeIdx) else CallLog.Calls.OUTGOING_TYPE
                    val date = if (dateIdx >= 0) it.getLong(dateIdx) else System.currentTimeMillis()
                    val duration = if (durIdx >= 0) it.getLong(durIdx) else 0L

                    val callType = when (rawType) {
                        CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
                        CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
                        CallLog.Calls.BLOCKED_TYPE -> CallType.BLOCKED
                        else -> null
                    }
                    if (callType == null) continue

                    // Check if CNAP or asserted name has a valid government / carrier name
                    val candGovtName = listOfNotNull(cnapName, assertedName, rawName).firstOrNull { cand ->
                        val trimmed = cand.trim()
                        trimmed.isNotBlank() &&
                        trimmed != number &&
                        trimmed != cleanNum &&
                        !trimmed.equals("Unknown", ignoreCase = true) &&
                        !trimmed.equals("Private Number", ignoreCase = true) &&
                        !trimmed.equals("Private", ignoreCase = true)
                    }?.trim()

                    if (!candGovtName.isNullOrBlank()) {
                        if (cleanNum.isNotBlank()) discoveredGovtNames[cleanNum] = candGovtName
                        if (last10.isNotBlank()) discoveredGovtNames[last10] = candGovtName
                        CallerIdResolver.cacheCallerId(context, cleanNum, candGovtName)
                        if (last10.isNotBlank()) CallerIdResolver.cacheCallerId(context, last10, candGovtName)
                    }

                    tempRecords.add(
                        TempCallRecord(
                            id = id,
                            number = number,
                            cleanNum = cleanNum,
                            last10 = last10,
                            rawName = rawName,
                            cnapName = cnapName,
                            assertedName = assertedName,
                            callType = callType,
                            date = date,
                            duration = duration
                        )
                    )
                }

                for (record in tempRecords) {
                    val cleanNum = record.cleanNum
                    val last10 = record.last10
                    val number = record.number

                    val savedName = savedIndex.nameMap[cleanNum] ?: if (last10.isNotBlank()) savedIndex.nameMap[last10] else null
                    val isSaved = savedName != null || savedIndex.exactSet.contains(cleanNum) || 
                                  (last10.isNotBlank() && savedIndex.last10Set.contains(last10))

                    // Priority Hierarchy:
                    // 1. Saved Contact: If saved in phonebook, ALWAYS use the saved name!
                    // 2. Unsaved:
                    //    a. Govt CNAP name (from row, discovered across other calls of same number, or cached)
                    //    b. Truecaller / Persistent caller ID
                    val resolvedName = if (isSaved) {
                        savedName ?: record.rawName
                    } else {
                        val govtName = discoveredGovtNames[cleanNum]
                            ?: (if (last10.isNotBlank()) discoveredGovtNames[last10] else null)
                            ?: listOfNotNull(record.cnapName, record.assertedName, record.rawName).firstOrNull { cand ->
                                val trimmed = cand.trim()
                                trimmed.isNotBlank() &&
                                trimmed != number &&
                                trimmed != cleanNum &&
                                !trimmed.equals("Unknown", ignoreCase = true) &&
                                !trimmed.equals("Private Number", ignoreCase = true) &&
                                !trimmed.equals("Private", ignoreCase = true)
                            }?.trim()

                        govtName ?: CallerIdResolver.lookupTruecaller(context, cleanNum, last10)
                    }

                    val recordingPath = recordingsMap[cleanNum]

                    result.add(
                        CallLogItem(
                            id = record.id,
                            number = number,
                            name = if (!resolvedName.isNullOrBlank()) resolvedName else null,
                            callType = record.callType,
                            timestamp = record.date,
                            duration = record.duration,
                            simSlot = 0,
                            recordingPath = recordingPath,
                            isSavedContact = isSaved
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private data class SavedContactIndex(
        val exactSet: Set<String>,
        val last10Set: Set<String>,
        val nameMap: Map<String, String>
    )

    private fun getSavedContactIndex(): SavedContactIndex {
        val exactSet = HashSet<String>()
        val last10Set = HashSet<String>()
        val nameMap = HashMap<String, String>()

        // First check if cached contacts are already in memory to avoid querying ContactsProvider
        val cached = ContactsRepository.getCachedContacts()
        if (cached.isNotEmpty()) {
            for (contact in cached) {
                val cName = contact.name.trim()
                for (num in contact.numbers) {
                    val clean = extractCleanNumber(num)
                    val digits = extractDigits(num)
                    if (clean.isNotBlank()) {
                        exactSet.add(clean)
                        nameMap[clean] = cName
                    }
                    if (digits.length >= 10) {
                        val l10 = digits.takeLast(10)
                        last10Set.add(l10)
                        nameMap[l10] = cName
                    } else if (digits.isNotBlank()) {
                        last10Set.add(digits)
                        nameMap[digits] = cName
                    }
                }
            }
            return SavedContactIndex(exactSet, last10Set, nameMap)
        }

        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null,
                null,
                null
            )
            cursor?.use {
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (it.moveToNext()) {
                    val raw = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""
                    val cName = if (nameIdx >= 0) it.getString(nameIdx)?.trim() ?: "" else ""
                    val clean = extractCleanNumber(raw)
                    val digits = extractDigits(raw)
                    if (clean.isNotBlank()) {
                        exactSet.add(clean)
                        if (cName.isNotBlank()) nameMap[clean] = cName
                    }
                    if (digits.length >= 10) {
                        val l10 = digits.takeLast(10)
                        last10Set.add(l10)
                        if (cName.isNotBlank()) nameMap[l10] = cName
                    } else if (digits.isNotBlank()) {
                        last10Set.add(digits)
                        if (cName.isNotBlank()) nameMap[digits] = cName
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return SavedContactIndex(exactSet, last10Set, nameMap)
    }

    private fun getRecordingsMap(): Map<String, String> {
        val now = System.currentTimeMillis()
        val cached = cachedRecordingsMap
        if (cached != null && (now - lastRecordingsScanTime < 60_000)) {
            return cached
        }
        val map = HashMap<String, String>()
        try {
            val candidateDirs = listOf(
                File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_RECORDINGS), "CallRecordings"),
                File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "CallRecordings"),
                File(context.getExternalFilesDir(null), "CallRecordings"),
                File(context.getExternalFilesDir(null), "Recordings")
            )

            candidateDirs.forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        val name = file.name
                        if ((name.startsWith("Call_") || name.startsWith("REC_")) &&
                            (name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".mp3") || name.endsWith(".mp4"))
                        ) {
                            val parts = name.split("_")
                            for (part in parts) {
                                val clean = part.replace(Regex("[^0-9+]"), "")
                                if (clean.length >= 6) {
                                    map[clean] = file.absolutePath
                                    if (clean.length >= 10) {
                                        map[clean.takeLast(10)] = file.absolutePath
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        cachedRecordingsMap = map
        lastRecordingsScanTime = now
        return map
    }

    suspend fun syncDeviceCallLogs() = withContext(Dispatchers.IO) {
        val freshLogs = deduplicateLogs(fetchDeviceCallLogsDirectly(daysLimit = null, skipRecordings = false))
        if (freshLogs.isNotEmpty()) {
            updateCachedCallLogs(freshLogs)
        }
        callLogDao.clearUnrecordedCallLogs()
    }

    // Speed Dial
    fun getSpeedDials(): Flow<List<SpeedDialItem>> {
        return speedDialDao.getSpeedDials().map { list ->
            list.map { SpeedDialItem(digit = it.digit, name = it.name, number = it.number, photoUri = it.photoUri) }
        }
    }

    suspend fun setSpeedDial(item: SpeedDialItem) = withContext(Dispatchers.IO) {
        speedDialDao.setSpeedDial(
            SpeedDialEntity(digit = item.digit, name = item.name, number = item.number, photoUri = item.photoUri)
        )
    }

    suspend fun deleteSpeedDial(digit: Int) = withContext(Dispatchers.IO) {
        speedDialDao.deleteSpeedDial(digit)
    }

    private fun CallLogEntity.toModel(): CallLogItem {
        val type = try {
            CallType.valueOf(callType)
        } catch (e: Exception) {
            CallType.OUTGOING
        }
        return CallLogItem(
            id = id,
            number = number,
            name = name,
            callType = type,
            timestamp = timestamp,
            duration = duration,
            simSlot = simSlot,
            recordingPath = recordingPath,
            notes = notes
        )
    }
}
