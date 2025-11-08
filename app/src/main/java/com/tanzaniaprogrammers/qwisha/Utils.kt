package com.tanzaniaprogrammers.qwisha

import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Generate a 5-character alphanumeric ID
fun generateShortId(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..5)
        .map { chars.random() }
        .joinToString("")
}

// Load contacts from device
suspend fun loadContacts(context: Context): List<Contact> = withContext(Dispatchers.IO) {
    val contacts = mutableListOf<Contact>()
    try {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: ""
                val number = it.getString(numberIndex) ?: ""
                if (name.isNotBlank() && number.isNotBlank()) {
                    // Clean phone number (remove spaces, dashes, etc.)
                    val cleanNumber = number.replace(Regex("[^+0-9]"), "")
                    contacts.add(Contact(name, cleanNumber))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    contacts.distinctBy { it.phoneNumber }
}

// Get contact name from phone number
suspend fun getContactName(context: Context, phoneNumber: String): String = withContext(Dispatchers.IO) {
    try {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?"
        val selectionArgs = arrayOf(phoneNumber)

        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val name = cursor.getString(nameIndex)
                if (!name.isNullOrBlank()) {
                    return@withContext name
                }
            }
        }

        // Try with cleaned number (without formatting)
        val cleanNumber = phoneNumber.replace(Regex("[^+0-9]"), "")
        context.contentResolver.query(uri, projection, selection, arrayOf(cleanNumber), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val name = cursor.getString(nameIndex)
                if (!name.isNullOrBlank()) {
                    return@withContext name
                }
            }
        }

        // Try matching by number ending (for cases where country code differs)
        val numberSuffix = cleanNumber.takeLast(9) // Last 9 digits
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIndex) ?: ""
                val cleanStoredNumber = number.replace(Regex("[^+0-9]"), "")
                if (cleanStoredNumber.endsWith(numberSuffix) && numberSuffix.length >= 7) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        return@withContext name
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    phoneNumber // Return phone number if contact not found
}

@RequiresApi(Build.VERSION_CODES.O)
fun formatTime(timestamp: Long): String {
    val now = Instant.now()
    val msgTime = Instant.ofEpochMilli(timestamp)
    val zone = ZoneId.systemDefault()

    val nowDate = now.atZone(zone).toLocalDate()
    val msgDate = msgTime.atZone(zone).toLocalDate()

    return when {
        msgDate == nowDate -> {
            // Today: show time only
            DateTimeFormatter.ofPattern("HH:mm").format(msgTime.atZone(zone))
        }
        msgDate == nowDate.minusDays(1) -> {
            // Yesterday
            "Yesterday"
        }
        msgDate.year == nowDate.year -> {
            // This year: show month and day
            DateTimeFormatter.ofPattern("MMM d").format(msgTime.atZone(zone))
        }
        else -> {
            // Different year: show full date
            DateTimeFormatter.ofPattern("MMM d, yyyy").format(msgTime.atZone(zone))
        }
    }
}

