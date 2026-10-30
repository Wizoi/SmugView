package com.smugview.app.data.security

import kotlinx.coroutines.flow.StateFlow

/**
 * Centralized store for SmugMug gallery/node unlock passwords.
 *
 * Replaces the previous scattered plaintext `SharedPreferences("smugview_passwords")` access in
 * both [com.smugview.app.ui.viewmodel.SmugViewModel] and
 * [com.smugview.app.data.repository.SmugMugRepository].
 *
 * [unlockedKeys] exposes the set of keys (nodeIds / albumKeys) that currently have a saved
 * password so UI can observe unlock state reactively instead of polling a blocking predicate.
 *
 * This is an interface so it can be faked in JVM unit tests (the production
 * [EncryptedPasswordStore] requires the Android keystore).
 */
interface PasswordStore {
    val unlockedKeys: StateFlow<Set<String>>

    /**
     * Counts every save and remove, including a save that changes the password under a key that already has one (the key set
     * does not change then, so [unlockedKeys] does not emit). Whoever must react to a changed VALUE collects this (N9).
     */
    val revision: StateFlow<Long>

    /** True when the phone's secure storage could not be opened: passwords then live in memory only, until the app closes (R-25). */
    val keptOnlyThisSession: StateFlow<Boolean>

    fun getPassword(key: String?): String?
    fun savePassword(key: String, value: String)
    fun remove(key: String)
    /** Snapshot of all stored (key -> password) entries. */
    fun all(): Map<String, String>
}
