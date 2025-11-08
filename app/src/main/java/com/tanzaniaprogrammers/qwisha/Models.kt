package com.tanzaniaprogrammers.qwisha

data class ThreadSummary(
    val threadId: String,
    val contactName: String,
    val snippet: String,
    val lastAtEpoch: Long,
    val unread: Int = 0
)

data class Contact(
    val name: String,
    val phoneNumber: String
)

