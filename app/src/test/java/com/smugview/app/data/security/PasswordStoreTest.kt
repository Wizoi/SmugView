package com.smugview.app.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Step 6-10 (R-25, Q5 (a)): a password is never written in plain text. If the phone's secure storage can't be opened a typed
 * password works until the app closes, from memory; passwords an older version left in the plain fallback file move into the
 * secure store the next time it opens, and only then is the plain file deleted.
 *
 * The secure store is a seam: `factory` stands for `EncryptedSharedPreferences.create` (which needs the Android Keystore, absent
 * here). A factory that throws is a phone whose Keystore is broken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PasswordStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val secret = "hunter2-Zebra"

    private val brokenKeystore: (Context) -> SharedPreferences = { throw java.security.GeneralSecurityException("Keystore broken") }

    /** The one file a working "secure" store writes to in these tests. */
    private fun workingKeystore(): (Context) -> SharedPreferences = { it.getSharedPreferences("test_secure_store", Context.MODE_PRIVATE) }

    private fun store(factory: (Context) -> SharedPreferences) = EncryptedPasswordStore(context, factory)

    private fun prefsDir() = File(context.applicationInfo.dataDir, "shared_prefs")

    /** Every SharedPreferences file under the app's data directory, with its text. */
    private fun allPrefsFiles(): Map<String, String> =
        (prefsDir().listFiles() ?: emptyArray()).associate { it.name to it.readText() }

    private fun plain(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun seedPlain(name: String, vararg entries: Pair<String, String>) {
        val e = plain(name).edit()
        entries.forEach { (k, v) -> e.putString(k, v) }
        assertTrue(e.commit())
    }

    private fun plainFileHasEntries(name: String): Boolean {
        val f = File(prefsDir(), "$name.xml")
        return f.exists() && plain(name).all.isNotEmpty()
    }

    // ---- R-25: nothing plain is ever written ----

    @Test fun keystoreBroken_aSaveWritesNothingToAnySharedPreferencesFile_andThePasswordWorksThisSession() {
        val s = store(brokenKeystore)

        s.savePassword("rootNode", secret)

        assertEquals(secret, s.getPassword("rootNode"))
        assertEquals(mapOf("rootNode" to secret), s.all())
        assertEquals(setOf("rootNode"), s.unlockedKeys.value)
        assertTrue("the prompt must be able to tell the user it won't be kept", s.keptOnlyThisSession.value)
        val leaked = allPrefsFiles().filterValues { it.contains(secret) }
        assertTrue("a plain file holds the password: ${leaked.keys}", leaked.isEmpty())
        assertFalse("no fallback file may even be created", File(prefsDir(), "smugview_passwords_fallback.xml").exists())
    }

    @Test fun keystoreBroken_removeAndRevisionStillWorkInMemory() {
        val s = store(brokenKeystore)
        val r0 = s.revision.value
        s.savePassword("a", "one-secret")
        s.savePassword("b", "two-secret")
        s.remove("a")

        assertNull(s.getPassword("a"))
        assertEquals("two-secret", s.getPassword("b"))
        assertEquals(setOf("b"), s.unlockedKeys.value)
        assertEquals(r0 + 3, s.revision.value)
    }

    @Test fun keystoreWorks_aSaveGoesToTheSecureStore_andKeptOnlyThisSessionIsFalse() {
        val s = store(workingKeystore())
        s.savePassword("rootNode", secret)

        assertFalse(s.keptOnlyThisSession.value)
        assertEquals(secret, s.getPassword("rootNode"))
        assertEquals(secret, plain("test_secure_store").getString("rootNode", null))
    }

    @Test fun revisionGoesUpWhenTheSameKeyGetsANewPassword_whichTheKeySetDoesNotShow() {
        val s = store(workingKeystore())
        s.savePassword("rootNode", "oldsecret1")
        val keysBefore = s.unlockedKeys.value
        val revisionBefore = s.revision.value

        s.savePassword("rootNode", "newsecret2")

        assertEquals(keysBefore, s.unlockedKeys.value)
        assertEquals(revisionBefore + 1, s.revision.value)
    }

    // ---- Q5 (a): plain fallback entries move into the secure store, then the plain file goes ----

    @Test fun fallbackEntries_workingKeystore_areMoved_andTheFallbackFileIsEmpty() {
        seedPlain("smugview_passwords_fallback", "familyRoot" to secret, "other" to "second-secret")

        val s = store(workingKeystore())

        assertEquals(secret, s.getPassword("familyRoot"))
        assertEquals("second-secret", s.getPassword("other"))
        assertEquals(secret, plain("test_secure_store").getString("familyRoot", null))
        assertFalse("the plain file must be gone", plainFileHasEntries("smugview_passwords_fallback"))
        assertFalse(File(prefsDir(), "smugview_passwords_fallback.xml").exists())
        assertEquals(setOf("familyRoot", "other"), s.unlockedKeys.value)
    }

    @Test fun legacyPlaintextFile_workingKeystore_isMovedToo() {
        seedPlain("smugview_passwords", "oldNode" to secret)

        val s = store(workingKeystore())

        assertEquals(secret, s.getPassword("oldNode"))
        assertFalse(File(prefsDir(), "smugview_passwords.xml").exists())
    }

    @Test fun fallbackEntries_keystoreStillBroken_workThisSession_theOldFileIsDeletedAtOnce_andNothingNewIsWritten() {
        seedPlain("smugview_passwords_fallback", "familyRoot" to secret)

        val s = store(brokenKeystore)

        assertEquals("a saved password keeps working for this session", secret, s.getPassword("familyRoot"))
        s.savePassword("newRoot", "brand-new-secret")
        assertEquals("brand-new-secret", s.getPassword("newRoot"))
        val after = allPrefsFiles()
        assertFalse("the plain file is deleted at once, not left holding the password", File(prefsDir(), "smugview_passwords_fallback.xml").exists())
        assertTrue("no plain file holds either password", after.values.none { it.contains(secret) || it.contains("brand-new-secret") })
        assertTrue("the grey line says it won't be kept", s.keptOnlyThisSession.value)
        assertEquals(setOf("familyRoot", "newRoot"), s.unlockedKeys.value)
    }

    @Test fun fallbackEntries_keystoreStillBroken_theNextStartAsksAgain_andNothingStaleIsLeftBehind() {
        seedPlain("smugview_passwords_fallback", "familyRoot" to secret)
        store(brokenKeystore).getPassword("familyRoot") // the start that deleted the plain file

        val nextStart = store(brokenKeystore)

        assertNull("owner decision: the user is asked for it again", nextStart.getPassword("familyRoot"))
        assertTrue(nextStart.all().isEmpty())
        assertTrue(nextStart.keptOnlyThisSession.value)
        assertTrue(allPrefsFiles().values.none { it.contains(secret) })
    }

    @Test fun bothPlainFiles_keystoreStillBroken_areDeleted_andTheNewerFallbackValueWinsInMemory() {
        seedPlain("smugview_passwords", "oldNode" to "legacy-secret", "shared" to "legacy-value")
        seedPlain("smugview_passwords_fallback", "shared" to "fallback-value")

        val s = store(brokenKeystore)

        assertEquals("legacy-secret", s.getPassword("oldNode"))
        assertEquals("fallback-value", s.getPassword("shared"))
        assertFalse(File(prefsDir(), "smugview_passwords.xml").exists())
        assertFalse(File(prefsDir(), "smugview_passwords_fallback.xml").exists())
    }

    @Test fun emptyFallbackFile_keystoreStillBroken_isRemovedToo() {
        seedPlain("smugview_passwords_fallback", "x" to "y")
        plain("smugview_passwords_fallback").edit().clear().commit()

        val s = store(brokenKeystore)

        assertTrue(s.all().isEmpty())
        assertFalse(File(prefsDir(), "smugview_passwords_fallback.xml").exists())
        assertTrue(s.keptOnlyThisSession.value)
    }

    @Test fun halfMigrated_thenTheKeystoreBreaks_theMovedPasswordIsStillInTheSecureStoreWhenItComesBack() {
        // Start 1 copied the entry into the secure store but was killed before it deleted the plain file.
        plain("test_secure_store").edit().putString("familyRoot", secret).commit()
        seedPlain("smugview_passwords_fallback", "familyRoot" to secret)

        val broken = store(brokenKeystore) // start 2: the Keystore is down; the plain file goes at once
        assertEquals(secret, broken.getPassword("familyRoot"))
        assertFalse(File(prefsDir(), "smugview_passwords_fallback.xml").exists())

        val recovered = store(workingKeystore()) // start 3: the Keystore is back
        assertEquals("a password that WAS moved is never lost", secret, recovered.getPassword("familyRoot"))
    }

    @Test fun halfMigrated_aNewerSecureValueIsNotOverwrittenByTheStalePlainOne() {
        // The copy finished, the delete did not, and the user then changed the password (the secure store has the newer one).
        plain("test_secure_store").edit().putString("familyRoot", "newer-secret").commit()
        seedPlain("smugview_passwords_fallback", "familyRoot" to "stale-secret", "onlyInPlain" to "plain-only-secret")

        val s = store(workingKeystore())

        assertEquals("newer-secret", s.getPassword("familyRoot"))
        assertEquals("newer-secret", plain("test_secure_store").getString("familyRoot", null))
        assertEquals("an entry the secure store does not have yet is still moved", "plain-only-secret", plain("test_secure_store").getString("onlyInPlain", null))
        assertFalse(plainFileHasEntries("smugview_passwords_fallback"))
    }

    @Test fun migrationKilledMidway_theTargetCouldNotBeWritten_keepsTheFallbackEntries_andTheNextOpenFinishesIt() {
        seedPlain("smugview_passwords_fallback", "familyRoot" to secret)
        val failingEditor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        Mockito.`when`(failingEditor.commit()).thenReturn(false)
        val failingTarget = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(failingTarget.edit()).thenReturn(failingEditor)
        Mockito.`when`(failingTarget.all).thenReturn(emptyMap<String, Any>())

        val first = store { failingTarget }
        first.getPassword("familyRoot") // opens the store, which runs the migration

        assertTrue("the entries must still be on the phone: the secure store did not take them", plainFileHasEntries("smugview_passwords_fallback"))
        assertEquals("and they work this session", secret, first.getPassword("familyRoot"))

        val second = store(workingKeystore())
        assertEquals(secret, second.getPassword("familyRoot"))
        assertEquals(secret, plain("test_secure_store").getString("familyRoot", null))
        assertFalse(plainFileHasEntries("smugview_passwords_fallback"))
    }

    @Test fun migrationTargetThrowsWhileWriting_keepsTheFallbackEntries_andTheStoreDoesNotCrash() {
        seedPlain("smugview_passwords_fallback", "familyRoot" to secret)
        val throwingEditor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        Mockito.`when`(throwingEditor.commit()).thenThrow(IllegalStateException("disk full"))
        val throwingTarget = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(throwingTarget.edit()).thenReturn(throwingEditor)
        Mockito.`when`(throwingTarget.all).thenReturn(emptyMap<String, Any>())

        val s = store { throwingTarget }

        assertEquals(secret, s.getPassword("familyRoot"))
        assertTrue(plainFileHasEntries("smugview_passwords_fallback"))
    }

    @Test fun migrationKilledAfterTheCopyButBeforeTheDelete_isRepeatedHarmlessly() {
        // The secure store already holds the entry (the copy finished) and the plain file is still there (the delete did not).
        plain("test_secure_store").edit().putString("familyRoot", secret).commit()
        seedPlain("smugview_passwords_fallback", "familyRoot" to secret)

        val s = store(workingKeystore())

        assertEquals(secret, s.getPassword("familyRoot"))
        assertEquals(mapOf("familyRoot" to secret), plain("test_secure_store").all)
        assertFalse(plainFileHasEntries("smugview_passwords_fallback"))
    }

    @Test fun emptyFallbackFile_isHarmless_andIsRemoved() {
        seedPlain("smugview_passwords_fallback", "x" to "y")
        plain("smugview_passwords_fallback").edit().clear().commit() // the file exists and holds nothing

        val s = store(workingKeystore())

        assertTrue(s.all().isEmpty())
        assertEquals(emptySet<String>(), s.unlockedKeys.value)
        assertFalse(plainFileHasEntries("smugview_passwords_fallback"))
    }

    @Test fun noFallbackFile_nothingIsCreated() {
        val s = store(workingKeystore())
        s.all()

        assertFalse(File(prefsDir(), "smugview_passwords_fallback.xml").exists())
        assertFalse(File(prefsDir(), "smugview_passwords.xml").exists())
    }
}
