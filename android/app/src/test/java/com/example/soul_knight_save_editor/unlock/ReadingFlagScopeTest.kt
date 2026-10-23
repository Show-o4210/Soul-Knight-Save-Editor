package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

class ReadingFlagScopeTest {
    private fun mapping(json: String, encoded: Boolean = true): String {
        val text = if (encoded) URLEncoder.encode(json, "UTF-8") else json
        return "<string name=\"AccountSaveSettingsIds\">${text.replace("&", "&amp;").replace("<", "&lt;")}</string>"
    }
    private fun flags(scope: String) = listOf("OpenRijTest", "OpenNewtonJsonTest").joinToString("") {
        "<int name=\"${if (scope.isEmpty()) it else "${it}_$scope"}\" value=\"1\"/>"
    }
    private fun xml(body: String) = "<map>$body</map>".toByteArray()
    private fun value(bytes: ByteArray, key: String) = UnlockEngine.prefs(bytes).getValue(key).getAttribute("value")
    private fun remainsUnchanged(bytes: ByteArray, account: String = "42") {
        val result = UnlockEngine.localReadingFlags(bytes, account)
        assertArrayEquals(bytes, result.first)
        assertTrue("An unresolved scope must have an explanation", result.second.isNotEmpty())
    }

    @Test fun explicitChannelMappingChangesOnlyTheChillyUidScope() {
        val original = xml(mapping("""{"42":"700","99":"900"}""") + flags("700") + flags("900") + flags("42") + flags(""))
        val (edited, changes) = UnlockEngine.localReadingFlags(original, "42")
        assertEquals("0", value(edited, "OpenRijTest_700"))
        assertEquals("0", value(edited, "OpenNewtonJsonTest_700"))
        for (scope in listOf("900", "42")) {
            assertEquals("1", value(edited, "OpenRijTest_$scope"))
            assertEquals("1", value(edited, "OpenNewtonJsonTest_$scope"))
        }
        assertEquals("1", value(edited, "OpenRijTest"))
        assertEquals(2, changes.size)
        assertTrue(UnlockEngine.localReadingFlags(edited, "42").second.isEmpty())
        assertEquals(UnlockEngine.prefs(original).keys, UnlockEngine.prefs(edited).keys)
    }

    @Test fun rawJsonAndNativeAliasesCanShareAnExplicitChillyUidScope() {
        val original = xml(mapping("""{"42":"700","99":"700","123":"900"}""", encoded = false) + flags("700") + flags("900"))
        for (account in listOf("42", "99")) {
            val edited = UnlockEngine.localReadingFlags(original, account).first
            assertEquals("0", value(edited, "OpenRijTest_700"))
            assertEquals("1", value(edited, "OpenRijTest_900"))
        }
    }

    @Test fun numericAndHexLegacyAccountsStillUseOnlyTheirExactScope() {
        for (account in listOf("42", "abcdef0123456789")) {
            val edited = UnlockEngine.localReadingFlags(xml(flags(account) + flags("900")), account).first
            assertEquals("0", value(edited, "OpenRijTest_$account"))
            assertEquals("1", value(edited, "OpenRijTest_900"))
        }
    }

    @Test fun oneForeignFlagAndLastLoginUidAreNotOwnershipEvidence() {
        remainsUnchanged(xml(flags("700") + "<string name=\"last_chilly_uid\">700</string><string name=\"42_c0_unlock\">true</string>"))
    }

    @Test fun missingMappingEntryDoesNotFallBackToAStaleAccountSuffix() {
        remainsUnchanged(xml(mapping("""{"99":"900"}""") + flags("42") + flags("900")))
    }

    @Test fun malformedOrUnsupportedMappingPreservesAllExistingScopes() {
        for (bad in listOf("%", "[]", "null", """{"42":700}""", """{"42":"0"}""", """{"42":"not-a-uid"}""")) {
            remainsUnchanged(xml(mapping(bad, encoded = false) + flags("42") + flags("700")))
        }
        remainsUnchanged(xml("<int name=\"AccountSaveSettingsIds\" value=\"700\"/>" + flags("42")))
    }

    @Test fun duplicateCurrentAccountKeysIncludingEscapedAliasesAreRejected() {
        for (bad in listOf("""{"42":"700","42":"701"}""", """{"4\u0032":"700","42":"701"}""")) {
            remainsUnchanged(xml(mapping(bad) + flags("700") + flags("701")))
        }
    }

    @Test fun unrelatedDuplicateAccountDoesNotInvalidateTheSelectedBinding() {
        val original = xml(mapping("""{"99":"800","99":"900","42":"700"}""") + flags("700") + flags("900"))
        val edited = UnlockEngine.localReadingFlags(original, "42").first
        assertEquals("0", value(edited, "OpenRijTest_700"))
        assertEquals("1", value(edited, "OpenRijTest_900"))
    }

    @Test fun explicitDefaultAccountOnlyChangesUnscopedLegacyKeys() {
        val original = xml(mapping("""{"42":"700"}""") + flags("") + flags("700"))
        val edited = UnlockEngine.localReadingFlags(original, "").first
        assertEquals("0", value(edited, "OpenRijTest"))
        assertEquals("1", value(edited, "OpenRijTest_700"))
    }

    @Test fun unknownForeignFlagTypeDoesNotBlockTheBoundScope() {
        val original = xml(mapping("""{"42":"700"}""") + flags("700") + "<string name=\"OpenRijTest_900\">unknown</string>")
        val edited = UnlockEngine.localReadingFlags(original, "42").first
        assertEquals("0", value(edited, "OpenRijTest_700"))
        assertEquals("unknown", UnlockEngine.prefs(edited).getValue("OpenRijTest_900").textContent)
    }

    @Test fun unknownBoundFlagValueStillRejectsBeforeWriting() {
        val original = xml(mapping("""{"42":"700"}""") + "<int name=\"OpenRijTest_700\" value=\"9\"/>")
        assertThrows(IllegalArgumentException::class.java) { UnlockEngine.localReadingFlags(original, "42") }
    }

    @Test fun missingFlagsAreNotCreatedAndXmlWithoutFlagsHasNoWarning() {
        val original = xml(mapping("""{"42":"700"}""") + "<string name=\"keep\">unchanged</string>")
        val result = UnlockEngine.localReadingFlags(original, "42")
        assertArrayEquals(original, result.first)
        assertTrue(result.second.isEmpty())
        val withoutFlags = xml(mapping("[]", encoded = false))
        assertTrue(UnlockEngine.localReadingFlags(withoutFlags, "42").second.isEmpty())
    }

    @Test fun completeXmlRestoreUsesExplicitOriginalScopesWithoutMappingThemAgain() {
        val original = xml(mapping("""{"42":"700","99":"900"}""") + flags("42") + flags("700") + flags("900") + flags(""))
        val scopes = ReadingFlagScope.rawScopes(UnlockEngine.prefs(original))
        val edited = UnlockEngine.localReadingFlagScopes(original, scopes).first
        for (scope in scopes) {
            val suffix = if (scope.isEmpty()) "" else "_$scope"
            assertEquals("0", value(edited, "OpenRijTest$suffix"))
            assertEquals("0", value(edited, "OpenNewtonJsonTest$suffix"))
        }
    }

    @Test fun mappedAccountDirectoryUsesFileAccountsAndDistinguishesMissingFromInvalidMaps() {
        val known = UnlockEngine.prefs(xml(mapping("""{"42":"700","99":"700"}""") + flags("700")))
        assertEquals(listOf("42", "99"), ReadingFlagScope.mappedAccounts(known))
        assertNull(ReadingFlagScope.mappedAccounts(UnlockEngine.prefs(xml(flags("700")))))
        assertEquals(emptyList<String>(), ReadingFlagScope.mappedAccounts(UnlockEngine.prefs(xml(mapping("[]", encoded = false) + flags("700")))))
        val duplicate = UnlockEngine.prefs(xml(mapping("""{"42":"700","42":"701","99":"900"}""")))
        assertEquals(listOf("99"), ReadingFlagScope.mappedAccounts(duplicate))
    }

    @Test fun readingModeUsesTheBoundRijSwitchAndLeavesNewtonUntouched() {
        for ((rij, expected) in listOf("0" to ReadingFlagScope.Mode.LEGACY, "1" to ReadingFlagScope.Mode.MODERN)) {
            val bytes = xml(mapping("""{"42":"700"}""") +
                "<int name=\"OpenRijTest_700\" value=\"$rij\"/><int name=\"OpenNewtonJsonTest_700\" value=\"1\"/>" + flags("42"))
            val nodes = UnlockEngine.prefs(bytes)
            assertEquals(expected, ReadingFlagScope.readingMode(nodes, "42"))
            assertEquals("1", nodes.getValue("OpenNewtonJsonTest_700").getAttribute("value"))
            assertEquals(rij, nodes.getValue("OpenRijTest_700").getAttribute("value"))
            assertEquals("1", nodes.getValue("OpenRijTest_42").getAttribute("value"))
        }
        val missingNewton = UnlockEngine.prefs(xml(mapping("""{"42":"700"}""") + "<int name=\"OpenRijTest_700\" value=\"1\"/>"))
        assertEquals(ReadingFlagScope.Mode.MODERN, ReadingFlagScope.readingMode(missingNewton, "42"))
        assertFalse("OpenNewtonJsonTest_700" in missingNewton)
    }

    @Test fun unknownReadingModeNeverGuessesFromAnotherScopeOrMalformedFlags() {
        val unknown = listOf(
            xml(flags("700")),
            xml(mapping("""{"42":"700"}""") + "<int name=\"OpenNewtonJsonTest_700\" value=\"1\"/>"),
            xml(mapping("""{"42":"700"}""") + "<int name=\"OpenRijTest_700\" value=\"2\"/>"),
            xml(mapping("""{"42":"700"}""") + "<string name=\"OpenRijTest_700\">1</string>"),
            xml(mapping("""{"42":"700","42":"701"}""") + flags("700") + flags("701"))
        )
        unknown.forEach { assertEquals(ReadingFlagScope.Mode.UNKNOWN, ReadingFlagScope.readingMode(UnlockEngine.prefs(it), "42")) }
        assertEquals(ReadingFlagScope.Mode.LEGACY, ReadingFlagScope.readingMode(UnlockEngine.prefs(xml("<int name=\"OpenRijTest_42\" value=\"0\"/>")), "42"))
        assertEquals(ReadingFlagScope.Mode.MODERN, ReadingFlagScope.readingMode(UnlockEngine.prefs(xml(flags(""))), ""))
    }

    @Test fun currentBiliPrivateBaselineBindsToTheObservedFlagSuffixWithoutPrintingIds() {
        val path = System.getenv("BILI_XML_BASELINE")
        assumeTrue("Optional local Bilibili XML baseline is not configured", !path.isNullOrBlank())
        val bytes = File(requireNotNull(path)).readBytes()
        val nodes = UnlockEngine.prefs(bytes)
        val accounts = UnlockEngine.accounts(bytes)
        assertTrue("Expected one observed file account in the private fixture", accounts.size == 1)
        val binding = ReadingFlagScope.bind(nodes, accounts.single())
        val matches = binding.scope != null && binding.scope in ReadingFlagScope.rawScopes(nodes)
        val count = (Json.parseToJsonElement(URLDecoder.decode(nodes.getValue("AccountSaveSettingsIds").textContent, "UTF-8")) as JsonObject).size
        println("ReadingFlagScope private baseline: bind_succeeds=${binding.scope != null}, matches_actual_flag_suffix=$matches, maps_count=$count")
        assertTrue("Explicit native scope binding must succeed", binding.scope != null)
        assertTrue("Binding must match the actual observed flag scope", matches)
        val edited = UnlockEngine.localReadingFlags(bytes, accounts.single()).first
        val after = UnlockEngine.prefs(edited)
        for (scope in ReadingFlagScope.rawScopes(nodes)) for (flag in listOf("OpenRijTest", "OpenNewtonJsonTest")) {
            val key = if (scope.isEmpty()) flag else "${flag}_$scope"
            val before = nodes[key] ?: continue
            if (scope == binding.scope) assertTrue("The bound flag must be zero", after.getValue(key).getAttribute("value") == "0")
            else assertTrue("Unrelated scopes must stay unchanged", before.getAttribute("value") == after.getValue(key).getAttribute("value"))
        }
    }
}
