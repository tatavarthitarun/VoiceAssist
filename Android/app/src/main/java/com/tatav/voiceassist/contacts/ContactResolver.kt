package com.tatav.voiceassist.contacts

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ContactResolver"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }

    data class ContactMatch(
        val displayName: String,
        val phoneNumber: String,
        val score: Int
    )

    sealed class Result {
        data class Found(val name: String, val number: String) : Result()
        data class Ambiguous(val matches: List<ContactMatch>) : Result()
        data class NotFound(val query: String) : Result()
    }

    private var cachedContacts: List<RawContact>? = null
    private var cacheTimestamp: Long = 0L

    fun invalidateCache() {
        cachedContacts = null
        cacheTimestamp = 0L
        Log.d(TAG, "Contact cache invalidated")
    }

    private fun getCachedContacts(): List<RawContact> {
        val now = System.currentTimeMillis()
        if (cachedContacts == null || (now - cacheTimestamp) > CACHE_TTL_MS) {
            cachedContacts = loadContacts()
            cacheTimestamp = now
            Log.d(TAG, "Contact cache refreshed: ${cachedContacts!!.size} contacts")
        }
        return cachedContacts!!
    }

    suspend fun resolve(spokenName: String): Result = withContext(Dispatchers.IO) {
        val query = spokenName.trim().lowercase()
        if (query.isBlank()) return@withContext Result.NotFound(spokenName)

        val contacts = getCachedContacts()
        if (contacts.isEmpty()) {
            Log.w(TAG, "No contacts found on device")
            return@withContext Result.NotFound(spokenName)
        }

        // 1. Exact match (case-insensitive)
        val exact = contacts.filter { it.displayName.lowercase() == query }
        if (exact.size == 1) {
            return@withContext Result.Found(exact[0].displayName, exact[0].phoneNumber)
        }

        // 2. Contains match (spoken name is substring of contact name or vice versa)
        val contains = contacts.filter {
            val name = it.displayName.lowercase()
            name.contains(query) || query.contains(name)
        }.map { ContactMatch(it.displayName, it.phoneNumber, score = 80) }

        if (contains.size == 1) {
            return@withContext Result.Found(contains[0].displayName, contains[0].phoneNumber)
        }

        // 3. Fuzzy match — Levenshtein distance ≤ 2 on each word
        val fuzzy = contacts.mapNotNull { contact ->
            val score = fuzzyScore(query, contact.displayName.lowercase())
            if (score >= 60) ContactMatch(contact.displayName, contact.phoneNumber, score) else null
        }.sortedByDescending { it.score }

        return@withContext when {
            fuzzy.isEmpty() -> Result.NotFound(spokenName)
            fuzzy.size == 1 -> Result.Found(fuzzy[0].displayName, fuzzy[0].phoneNumber)
            // If top match is clearly better, use it
            fuzzy.size >= 2 && fuzzy[0].score - fuzzy[1].score >= 20 ->
                Result.Found(fuzzy[0].displayName, fuzzy[0].phoneNumber)
            else -> Result.Ambiguous(fuzzy.take(3))
        }
    }

    private data class RawContact(val displayName: String, val phoneNumber: String)

    private fun loadContacts(): List<RawContact> {
        val results = mutableListOf<RawContact>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: continue
                    val number = it.getString(numIdx) ?: continue
                    results.add(RawContact(name, number.replace("\\s".toRegex(), "")))
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Contacts permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts", e)
        }
        return results.distinctBy { it.displayName.lowercase() + it.phoneNumber }
    }

    /**
     * Fuzzy scoring: combines word-level Levenshtein with first-name matching.
     * Returns 0-100 score.
     */
    private fun fuzzyScore(query: String, contactName: String): Int {
        val queryWords = query.split("\\s+".toRegex())
        val nameWords = contactName.split("\\s+".toRegex())

        // First name exact match = strong signal
        if (queryWords.first() == nameWords.first()) return 90

        // Check if any query word closely matches any name word
        var bestWordScore = 0
        for (qw in queryWords) {
            for (nw in nameWords) {
                val dist = levenshtein(qw, nw)
                val maxLen = maxOf(qw.length, nw.length)
                if (maxLen == 0) continue
                val wordScore = ((1.0 - dist.toDouble() / maxLen) * 100).toInt()
                bestWordScore = maxOf(bestWordScore, wordScore)
            }
        }
        return bestWordScore
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}
