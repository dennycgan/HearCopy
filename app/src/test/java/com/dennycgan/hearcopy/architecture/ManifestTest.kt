package com.dennycgan.hearcopy.architecture

import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Source manifests: backup and cleartext locked down, permissions within
 * the approved V1 allowlist, labels from string resources (ADR-001).
 * The merged manifest is not checked: libraries add entries we do not
 * control (for example androidx.core's DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION).
 */
class ManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val mainManifest = "app/src/main/AndroidManifest.xml"

    private val allowedPermissions = setOf(
        "android.permission.INTERNET",
        "android.permission.RECORD_AUDIO",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "android.permission.POST_NOTIFICATIONS",
    )

    private val manifests = Repo.existingFiles.filter {
        it.startsWith("app/src/") && it.endsWith("/AndroidManifest.xml")
    }

    @Test
    fun `main application disables backup and sets data extraction rules`() {
        val application = application(mainManifest)
        val violations = buildList {
            if (application == null) {
                add("$mainManifest has no <application> element")
            } else {
                val backup = application.getAttributeNS(androidNs, "allowBackup")
                if (backup != "false") add("android:allowBackup is '$backup', expected 'false'")
                val rules = application.getAttributeNS(androidNs, "dataExtractionRules")
                if (rules != "@xml/data_extraction_rules") {
                    add("android:dataExtractionRules is '$rules', expected '@xml/data_extraction_rules'")
                }
                if (!Repo.file("app/src/main/res/xml/data_extraction_rules.xml").isFile) {
                    add("app/src/main/res/xml/data_extraction_rules.xml is missing")
                }
            }
        }
        assertNoViolations("Backup must be disabled:", violations)
    }

    @Test
    fun `no manifest allows cleartext traffic`() {
        val violations = manifests.flatMap { path ->
            val application = application(path) ?: return@flatMap emptyList()
            buildList {
                val cleartext = application.getAttributeNS(androidNs, "usesCleartextTraffic")
                if (path == mainManifest && cleartext != "false") {
                    add("$path: android:usesCleartextTraffic is '$cleartext', expected 'false'")
                }
                if (path != mainManifest && cleartext.isNotEmpty() && cleartext != "false") {
                    add("$path: android:usesCleartextTraffic is '$cleartext'")
                }
                if (application.hasAttributeNS(androidNs, "networkSecurityConfig")) {
                    add("$path: android:networkSecurityConfig is not allowed")
                }
                if (path != mainManifest && application.getAttributeNS(androidNs, "allowBackup") == "true") {
                    add("$path: android:allowBackup=\"true\" overrides the main manifest")
                }
            }
        }
        assertNoViolations("Cleartext traffic must stay disabled:", violations)
    }

    @Test
    fun `permissions match the allowlist`() {
        val violations = manifests.flatMap { path ->
            val document = parse(path)
            listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
                document.getElementsByTagName(tag).elements()
                    .map { it.getAttributeNS(androidNs, "name") }
                    .filter { it !in allowedPermissions }
                    .map { "$path: <$tag> $it (not allowlisted)" }
            }
        }
        assertNoViolations("Manifest permissions must be allowlisted:", violations)
    }

    @Test
    fun `labels are string resources`() {
        val violations = manifests.flatMap { path ->
            parse(path).getElementsByTagName("*").elements()
                .filter { it.hasAttributeNS(androidNs, "label") }
                .map { it.tagName to it.getAttributeNS(androidNs, "label") }
                .filterNot { (_, label) -> label.startsWith("@string/") }
                .map { (tag, label) -> "$path: <$tag> android:label=\"$label\"" }
        }
        assertNoViolations("android:label must be an @string/ reference:", violations)
    }

    private fun parse(path: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(Repo.file(path))

    private fun application(path: String): Element? =
        parse(path).getElementsByTagName("application").elements().firstOrNull()

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).map { item(it) }.filterIsInstance<Element>()
}
