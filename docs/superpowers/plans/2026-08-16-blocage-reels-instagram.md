# Blocage des Reels Instagram — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une app Android qui détecte l'onglet Reels et la page Explore dans l'app Instagram officielle, déclenche un retour arrière, et compte les tentatives.

**Architecture:** Un `AccessibilityService` observe les événements d'Instagram, traduit l'arbre de vues en un instantané neutre, et délègue la décision à un classifieur pur piloté par des règles JSON à trois paliers de fiabilité. Une machine à états sépare la détection de l'action, pour éviter la boucle de retours arrière. Toute la logique décisionnelle est du Kotlin sans dépendance Android, donc testable sur JVM.

**Tech Stack:** Kotlin 2.1, Gradle multi-module, Jetpack Compose, Room, DataStore, kotlinx.serialization, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-16-blocage-reels-instagram-design.md`

## Global Constraints

- Package applicatif : `com.insta.reelsoff`
- Package cible observé : `com.instagram.android`
- `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`, JVM target 17
- Versions (dans `gradle/libs.versions.toml`) : AGP `8.7.3`, Kotlin `2.1.0`, KSP `2.1.0-1.0.29`, Room `2.6.1`, kotlinx-serialization `1.7.3`, DataStore `1.1.1`, Compose BOM `2024.12.01`, activity-compose `1.9.3`, lifecycle `2.8.7`, core-ktx `1.15.0`. Si Gradle refuse une version, bumper au plus proche disponible et le noter dans le message de commit.
- Le module `:detection` ne doit contenir **aucun** import `android.*`. C'est la contrainte structurante du projet.
- Les textes d'interface sont en français. Le code, les noms de symboles et les messages de commit sont en anglais.
- Chaque message de commit se termine par la ligne `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>` (omise des commandes ci-dessous pour la lisibilité).
- Aucune donnée ne quitte l'appareil. Aucune dépendance réseau n'est ajoutée au projet, jamais.
- Champs collectés depuis l'arbre de vues, et rien d'autre : `viewIdResourceName`, `contentDescription`, `className`, `isSelected`, `isClickable`, `boundsInScreen`. Ne jamais lire `text`.

---

### Task 1: Squelette Gradle et types de l'instantané

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `.gitignore`
- Create: `detection/build.gradle.kts`
- Create: `detection/src/main/kotlin/com/insta/detection/Surface.kt`
- Create: `detection/src/main/kotlin/com/insta/detection/ScreenSnapshot.kt`
- Test: `detection/src/test/kotlin/com/insta/detection/ScreenSnapshotTest.kt`

**Interfaces:**
- Consumes: rien
- Produces: `Surface` (enum `REELS`, `EXPLORE`, `OTHER`), `Bounds(left: Int, top: Int, right: Int, bottom: Int)`, `NodeSummary`, `ScreenSnapshot`, et le format JSON des instantanés utilisé par les fixtures et par le mode capture.

- [ ] **Step 1: Écrire le test qui échoue**

`detection/src/test/kotlin/com/insta/detection/ScreenSnapshotTest.kt`

```kotlin
package com.insta.detection

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenSnapshotTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes a snapshot written by the capture tool`() {
        val raw = """
            {
              "packageName": "com.instagram.android",
              "capturedAtMillis": 1723800000000,
              "nodes": [
                {
                  "index": 0,
                  "parentIndex": -1,
                  "depth": 0,
                  "indexInParent": 0,
                  "viewId": "com.instagram.android:id/root",
                  "contentDescription": null,
                  "className": "android.widget.FrameLayout",
                  "isSelected": false,
                  "isClickable": false,
                  "bounds": { "left": 0, "top": 0, "right": 1080, "bottom": 2400 }
                }
              ]
            }
        """.trimIndent()

        val snapshot = json.decodeFromString<ScreenSnapshot>(raw)

        assertEquals("com.instagram.android", snapshot.packageName)
        assertEquals(1, snapshot.nodes.size)
        assertEquals("com.instagram.android:id/root", snapshot.nodes[0].viewId)
        assertEquals(-1, snapshot.nodes[0].parentIndex)
        assertEquals(2400, snapshot.nodes[0].bounds.bottom)
    }

    @Test
    fun `round trips through json`() {
        val original = ScreenSnapshot(
            packageName = "com.instagram.android",
            capturedAtMillis = 42L,
            nodes = listOf(
                NodeSummary(
                    index = 0,
                    parentIndex = -1,
                    depth = 0,
                    indexInParent = 0,
                    viewId = null,
                    contentDescription = "Réels",
                    className = "android.widget.ImageView",
                    isSelected = true,
                    isClickable = true,
                    bounds = Bounds(0, 2200, 216, 2400),
                ),
            ),
        )

        val decoded = json.decodeFromString<ScreenSnapshot>(json.encodeToString(original))

        assertEquals(original, decoded)
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :detection:test`
Expected: échec de compilation — `Unresolved reference: ScreenSnapshot`

- [ ] **Step 3: Créer les fichiers de build**

`settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "reelsoff"
include(":detection")
```

`gradle/libs.versions.toml`

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
ksp = "2.1.0-1.0.29"
serialization = "1.7.3"
room = "2.6.1"
datastore = "1.1.1"
composeBom = "2024.12.01"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
coreKtx = "1.15.0"
junit = "4.13.2"
androidxTestRunner = "1.6.2"
androidxTestExt = "1.2.1"

[libraries]
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-material3 = { module = "androidx.compose.material3:material3" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
junit = { module = "junit:junit", version.ref = "junit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExt" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

`build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

`gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2048m
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
```

`.gitignore`

```gitignore
.gradle/
build/
local.properties
*.iml
.idea/
captures/
```

`detection/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
```

- [ ] **Step 4: Écrire les types**

`detection/src/main/kotlin/com/insta/detection/Surface.kt`

```kotlin
package com.insta.detection

/** A screen of the Instagram app, as far as this app cares about it. */
enum class Surface {
    REELS,
    EXPLORE,
    OTHER,
}
```

`detection/src/main/kotlin/com/insta/detection/ScreenSnapshot.kt`

```kotlin
package com.insta.detection

import kotlinx.serialization.Serializable

@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * One node of the observed view tree, flattened.
 *
 * Deliberately excludes node text: the classifier never needs it, so it is
 * never collected.
 */
@Serializable
data class NodeSummary(
    val index: Int,
    val parentIndex: Int,
    val depth: Int,
    val indexInParent: Int,
    val viewId: String?,
    val contentDescription: String?,
    val className: String?,
    val isSelected: Boolean,
    val isClickable: Boolean,
    val bounds: Bounds,
)

/** A whole view tree, flattened into a parent-indexed list. */
@Serializable
data class ScreenSnapshot(
    val packageName: String,
    val capturedAtMillis: Long,
    val nodes: List<NodeSummary>,
)
```

- [ ] **Step 5: Lancer les tests pour vérifier qu'ils passent**

Run: `./gradlew :detection:test`
Expected: PASS, 2 tests

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ .gitignore detection/
git commit -m "feat: gradle skeleton and screen snapshot types"
```

---

### Task 2: Format de règles et parseur tolérant aux pannes

**Files:**
- Create: `detection/src/main/kotlin/com/insta/detection/Rules.kt`
- Create: `detection/src/main/kotlin/com/insta/detection/RuleSetParser.kt`
- Test: `detection/src/test/kotlin/com/insta/detection/RuleSetParserTest.kt`

**Interfaces:**
- Consumes: `Surface` (Task 1)
- Produces: `Tier` (enum `HIGH`, `MEDIUM`, `LOW`), `SignalType` (enum `VIEW_ID`, `CONTENT_DESCRIPTION`, `NAV_BAR_INDEX`), `Signal`, `SurfaceRules`, `RuleSet(version: Int, surfaces: Map<Surface, SurfaceRules>)`, et `RuleSetParser.parse(raw: String): ParseResult` renvoyant `ParseResult.Success(ruleSet)` ou `ParseResult.Failure(message)`.

Le parseur ne lève jamais d'exception. Le fichier de règles est destiné à être édité à la main sur l'appareil : il sera cassé un jour, et ce jour-là l'app doit se replier proprement, pas planter.

- [ ] **Step 1: Écrire les tests qui échouent**

`detection/src/test/kotlin/com/insta/detection/RuleSetParserTest.kt`

```kotlin
package com.insta.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetParserTest {

    private fun failureMessage(raw: String): String {
        val result = RuleSetParser.parse(raw)
        assertTrue("expected a failure, got $result", result is ParseResult.Failure)
        return (result as ParseResult.Failure).message
    }

    @Test
    fun `parses a well formed rule set`() {
        val raw = """
            {
              "version": 1,
              "surfaces": {
                "REELS": {
                  "signals": [
                    { "tier": "HIGH", "type": "VIEW_ID",
                      "value": "com.instagram.android:id/clips_tab", "requireSelected": true },
                    { "tier": "MEDIUM", "type": "CONTENT_DESCRIPTION",
                      "anyOf": ["Reels", "Réels"], "requireSelected": true },
                    { "tier": "LOW", "type": "NAV_BAR_INDEX",
                      "value": "2", "requireSelected": true }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Success)
        val ruleSet = (result as ParseResult.Success).ruleSet
        assertEquals(1, ruleSet.version)
        val signals = ruleSet.surfaces.getValue(Surface.REELS).signals
        assertEquals(3, signals.size)
        assertEquals(Tier.HIGH, signals[0].tier)
        assertEquals(SignalType.VIEW_ID, signals[0].type)
        assertEquals(listOf("Reels", "Réels"), signals[1].anyOf)
        assertEquals("2", signals[2].value)
    }

    @Test
    fun `requireSelected defaults to true`() {
        val raw = """
            { "version": 1, "surfaces": { "EXPLORE": { "signals": [
              { "tier": "HIGH", "type": "VIEW_ID", "value": "x" }
            ] } } }
        """.trimIndent()

        val result = RuleSetParser.parse(raw) as ParseResult.Success

        assertTrue(result.ruleSet.surfaces.getValue(Surface.EXPLORE).signals[0].requireSelected)
    }

    @Test
    fun `rejects malformed json instead of throwing`() {
        assertTrue(failureMessage("{ this is not json").contains("malformed", ignoreCase = true))
    }

    @Test
    fun `rejects an unknown surface name`() {
        val raw = """
            { "version": 1, "surfaces": { "STORIES": { "signals": [] } } }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("STORIES"))
    }

    @Test
    fun `rejects a view id signal with no value`() {
        val raw = """
            { "version": 1, "surfaces": { "REELS": { "signals": [
              { "tier": "HIGH", "type": "VIEW_ID" }
            ] } } }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("VIEW_ID"))
    }

    @Test
    fun `rejects a content description signal with an empty anyOf`() {
        val raw = """
            { "version": 1, "surfaces": { "REELS": { "signals": [
              { "tier": "MEDIUM", "type": "CONTENT_DESCRIPTION", "anyOf": [] }
            ] } } }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("CONTENT_DESCRIPTION"))
    }

    @Test
    fun `rejects a nav bar index that is not a number`() {
        val raw = """
            { "version": 1, "surfaces": { "REELS": { "signals": [
              { "tier": "LOW", "type": "NAV_BAR_INDEX", "value": "middle" }
            ] } } }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("NAV_BAR_INDEX"))
    }

    @Test
    fun `rejects the OTHER surface as a rule target`() {
        val raw = """
            { "version": 1, "surfaces": { "OTHER": { "signals": [] } } }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("OTHER"))
    }
}
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./gradlew :detection:test --tests '*RuleSetParserTest*'`
Expected: échec de compilation — `Unresolved reference: RuleSetParser`

- [ ] **Step 3: Écrire les types de règles**

`detection/src/main/kotlin/com/insta/detection/Rules.kt`

```kotlin
package com.insta.detection

import kotlinx.serialization.Serializable

/** How much a signal can be trusted when Instagram changes its view tree. */
enum class Tier {
    HIGH,
    MEDIUM,
    LOW,
}

enum class SignalType {
    /** Exact match on a resource id. Breaks when Instagram renames it. */
    VIEW_ID,

    /** Match on the accessibility label. Depends on the device language. */
    CONTENT_DESCRIPTION,

    /** Position within the bottom navigation bar, located geometrically. */
    NAV_BAR_INDEX,
}

@Serializable
data class Signal(
    val tier: Tier,
    val type: SignalType,
    val value: String? = null,
    val anyOf: List<String> = emptyList(),
    val requireSelected: Boolean = true,
)

@Serializable
data class SurfaceRules(val signals: List<Signal>)

data class RuleSet(
    val version: Int,
    val surfaces: Map<Surface, SurfaceRules>,
)
```

- [ ] **Step 4: Écrire le parseur**

`detection/src/main/kotlin/com/insta/detection/RuleSetParser.kt`

```kotlin
package com.insta.detection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface ParseResult {
    data class Success(val ruleSet: RuleSet) : ParseResult
    data class Failure(val message: String) : ParseResult
}

@Serializable
private data class RawRuleSet(
    val version: Int,
    val surfaces: Map<String, SurfaceRules>,
)

/**
 * Reads the rules file. Never throws: a hand-edited rules file will be broken
 * one day, and that day the app must degrade, not crash.
 */
object RuleSetParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): ParseResult {
        val decoded = try {
            json.decodeFromString<RawRuleSet>(raw)
        } catch (e: Exception) {
            return ParseResult.Failure("malformed rules file: ${e.message}")
        }

        val surfaces = mutableMapOf<Surface, SurfaceRules>()
        for ((name, rules) in decoded.surfaces) {
            val surface = Surface.entries.firstOrNull { it.name == name }
                ?: return ParseResult.Failure("unknown surface \"$name\"")
            if (surface == Surface.OTHER) {
                return ParseResult.Failure("surface \"OTHER\" cannot carry rules")
            }
            rules.signals.forEach { signal ->
                validate(signal)?.let { return ParseResult.Failure(it) }
            }
            surfaces[surface] = rules
        }

        return ParseResult.Success(RuleSet(decoded.version, surfaces))
    }

    /** Returns an error message, or null when the signal is usable. */
    private fun validate(signal: Signal): String? = when (signal.type) {
        SignalType.VIEW_ID ->
            if (signal.value.isNullOrBlank()) "VIEW_ID signal needs a non-empty value" else null

        SignalType.CONTENT_DESCRIPTION ->
            if (signal.anyOf.isEmpty()) "CONTENT_DESCRIPTION signal needs a non-empty anyOf" else null

        SignalType.NAV_BAR_INDEX -> {
            val index = signal.value?.toIntOrNull()
            if (index == null || index < 0) "NAV_BAR_INDEX signal needs a non-negative integer value" else null
        }
    }
}
```

- [ ] **Step 5: Lancer les tests pour vérifier qu'ils passent**

Run: `./gradlew :detection:test --tests '*RuleSetParserTest*'`
Expected: PASS, 8 tests

- [ ] **Step 6: Commit**

```bash
git add detection/
git commit -m "feat: rule set format and fault-tolerant parser"
```

---

### Task 3: Classifieur en cascade

C'est le cœur du projet. Le test écrit en premier est celui qui protège le fil d'actualité : le bouton Reels est présent dans la barre de navigation sur **tous** les écrans, y compris le feed. Confondre sa présence avec sa sélection ferait sortir l'app du fil d'actualité en boucle.

**Files:**
- Create: `detection/src/main/kotlin/com/insta/detection/Classification.kt`
- Create: `detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt`
- Create: `detection/src/test/kotlin/com/insta/detection/TestNodes.kt`
- Test: `detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt`

**Interfaces:**
- Consumes: `ScreenSnapshot`, `NodeSummary`, `Bounds`, `Surface` (Task 1) ; `RuleSet`, `Signal`, `Tier`, `SignalType` (Task 2)
- Produces: `Classification(surface: Surface, tier: Tier?)` — `tier` est `null` quand et seulement quand `surface == Surface.OTHER` — et `ScreenClassifier(ruleSet: RuleSet).classify(snapshot: ScreenSnapshot): Classification`

- [ ] **Step 1: Écrire les constructeurs de test**

`detection/src/test/kotlin/com/insta/detection/TestNodes.kt`

```kotlin
package com.insta.detection

fun node(
    index: Int,
    parentIndex: Int = -1,
    depth: Int = 0,
    indexInParent: Int = 0,
    viewId: String? = null,
    contentDescription: String? = null,
    className: String? = "android.widget.FrameLayout",
    isSelected: Boolean = false,
    isClickable: Boolean = false,
    bounds: Bounds = Bounds(0, 0, 1080, 200),
) = NodeSummary(
    index = index,
    parentIndex = parentIndex,
    depth = depth,
    indexInParent = indexInParent,
    viewId = viewId,
    contentDescription = contentDescription,
    className = className,
    isSelected = isSelected,
    isClickable = isClickable,
    bounds = bounds,
)

fun snapshot(nodes: List<NodeSummary>) =
    ScreenSnapshot("com.instagram.android", 0L, nodes)

/**
 * A five-tab bottom bar sitting at the bottom of a 2400px-tall screen,
 * plus some content above it so the bar is not the only container.
 *
 * Node 0 is the content container, node 1 its child, node 2 the bar itself,
 * nodes 3..7 the five tabs.
 */
fun screenWithNavBar(
    selectedTab: Int,
    tabViewIds: List<String?> = List(5) { null },
    tabDescriptions: List<String?> = List(5) { null },
): List<NodeSummary> {
    val content = listOf(
        node(index = 0, parentIndex = -1, depth = 0, bounds = Bounds(0, 0, 1080, 2400)),
        node(index = 1, parentIndex = 0, depth = 1, indexInParent = 0, bounds = Bounds(0, 0, 1080, 2200)),
        node(index = 2, parentIndex = 0, depth = 1, indexInParent = 1, bounds = Bounds(0, 2200, 1080, 2400)),
    )
    val tabs = (0 until 5).map { tab ->
        node(
            index = 3 + tab,
            parentIndex = 2,
            depth = 2,
            indexInParent = tab,
            viewId = tabViewIds[tab],
            contentDescription = tabDescriptions[tab],
            isSelected = tab == selectedTab,
            isClickable = true,
            bounds = Bounds(216 * tab, 2200, 216 * (tab + 1), 2400),
        )
    }
    return content + tabs
}

val TEST_RULES = RuleSet(
    version = 1,
    surfaces = mapOf(
        Surface.REELS to SurfaceRules(
            listOf(
                Signal(Tier.HIGH, SignalType.VIEW_ID, value = "com.instagram.android:id/clips_tab"),
                Signal(Tier.MEDIUM, SignalType.CONTENT_DESCRIPTION, anyOf = listOf("Reels", "Réels")),
                Signal(Tier.LOW, SignalType.NAV_BAR_INDEX, value = "2"),
            ),
        ),
        Surface.EXPLORE to SurfaceRules(
            listOf(
                Signal(Tier.HIGH, SignalType.VIEW_ID, value = "com.instagram.android:id/search_tab"),
                Signal(Tier.MEDIUM, SignalType.CONTENT_DESCRIPTION, anyOf = listOf("Search and explore", "Recherche et exploration")),
                Signal(Tier.LOW, SignalType.NAV_BAR_INDEX, value = "1"),
            ),
        ),
    ),
)
```

- [ ] **Step 2: Écrire les tests qui échouent**

`detection/src/test/kotlin/com/insta/detection/ScreenClassifierTest.kt`

```kotlin
package com.insta.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenClassifierTest {

    private val classifier = ScreenClassifier(TEST_RULES)

    private val allTabViewIds = listOf(
        "com.instagram.android:id/feed_tab",
        "com.instagram.android:id/search_tab",
        "com.instagram.android:id/clips_tab",
        "com.instagram.android:id/creation_tab",
        "com.instagram.android:id/profile_tab",
    )

    private val allTabDescriptions =
        listOf("Home", "Search and explore", "Reels", "Create", "Profile")

    /**
     * The critical test. The Reels tab button exists on the feed too; only its
     * selected state means anything. Getting this wrong bounces the user out of
     * their own feed.
     */
    @Test
    fun `feed is OTHER even though the reels tab is present`() {
        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 0, tabViewIds = allTabViewIds, tabDescriptions = allTabDescriptions)),
        )

        assertEquals(Surface.OTHER, result.surface)
        assertNull(result.tier)
    }

    @Test
    fun `selected reels tab is REELS at the high tier`() {
        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 2, tabViewIds = allTabViewIds, tabDescriptions = allTabDescriptions)),
        )

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `selected search tab is EXPLORE at the high tier`() {
        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 1, tabViewIds = allTabViewIds, tabDescriptions = allTabDescriptions)),
        )

        assertEquals(Surface.EXPLORE, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `falls back to the medium tier when view ids are gone`() {
        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 2, tabDescriptions = allTabDescriptions)),
        )

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.MEDIUM, result.tier)
    }

    @Test
    fun `falls back to the low tier when view ids and labels are gone`() {
        val result = classifier.classify(snapshot(screenWithNavBar(selectedTab = 2)))

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.LOW, result.tier)
    }

    @Test
    fun `content description match is case insensitive and accepts the french label`() {
        val french = listOf("Accueil", "Recherche et exploration", "Réels", "Créer", "Profil")

        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 2, tabDescriptions = french)),
        )

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.MEDIUM, result.tier)
    }

    /** A screen with no bottom bar at all — a full-screen story viewer, say. */
    @Test
    fun `screen without a nav bar is OTHER`() {
        val result = classifier.classify(
            snapshot(
                listOf(
                    node(index = 0, bounds = Bounds(0, 0, 1080, 2400)),
                    node(index = 1, parentIndex = 0, depth = 1, bounds = Bounds(0, 0, 1080, 2400)),
                ),
            ),
        )

        assertEquals(Surface.OTHER, result.surface)
    }

    @Test
    fun `empty snapshot is OTHER`() {
        assertEquals(Surface.OTHER, classifier.classify(snapshot(emptyList())).surface)
    }

    /**
     * A high-tier match on one surface must beat a low-tier match on another,
     * so tiers are evaluated across all surfaces before moving down.
     */
    @Test
    fun `high tier on explore wins over low tier on reels`() {
        val rules = RuleSet(
            version = 1,
            surfaces = mapOf(
                Surface.REELS to SurfaceRules(listOf(Signal(Tier.LOW, SignalType.NAV_BAR_INDEX, value = "1"))),
                Surface.EXPLORE to SurfaceRules(
                    listOf(Signal(Tier.HIGH, SignalType.VIEW_ID, value = "com.instagram.android:id/search_tab")),
                ),
            ),
        )

        val result = ScreenClassifier(rules).classify(
            snapshot(screenWithNavBar(selectedTab = 1, tabViewIds = allTabViewIds)),
        )

        assertEquals(Surface.EXPLORE, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `requireSelected false matches on mere presence`() {
        val rules = RuleSet(
            version = 1,
            surfaces = mapOf(
                Surface.REELS to SurfaceRules(
                    listOf(
                        Signal(
                            Tier.HIGH,
                            SignalType.VIEW_ID,
                            value = "com.instagram.android:id/clips_viewer_video_container",
                            requireSelected = false,
                        ),
                    ),
                ),
            ),
        )

        val result = ScreenClassifier(rules).classify(
            snapshot(
                listOf(
                    node(index = 0),
                    node(
                        index = 1,
                        parentIndex = 0,
                        depth = 1,
                        viewId = "com.instagram.android:id/clips_viewer_video_container",
                    ),
                ),
            ),
        )

        assertEquals(Surface.REELS, result.surface)
    }

    /** The bar is picked geometrically, so a higher row of buttons must not win. */
    @Test
    fun `nav bar detection picks the lowest row of clickable siblings`() {
        val decoy = (0 until 5).map { tab ->
            node(
                index = 8 + tab,
                parentIndex = 1,
                depth = 2,
                indexInParent = tab,
                isSelected = tab == 2,
                isClickable = true,
                bounds = Bounds(216 * tab, 300, 216 * (tab + 1), 500),
            )
        }

        val result = classifier.classify(snapshot(screenWithNavBar(selectedTab = 0) + decoy))

        assertEquals(Surface.OTHER, result.surface)
    }
}
```

- [ ] **Step 3: Lancer les tests pour vérifier qu'ils échouent**

Run: `./gradlew :detection:test --tests '*ScreenClassifierTest*'`
Expected: échec de compilation — `Unresolved reference: ScreenClassifier`

- [ ] **Step 4: Écrire le classifieur**

`detection/src/main/kotlin/com/insta/detection/Classification.kt`

```kotlin
package com.insta.detection

/**
 * The classifier's verdict. [tier] records which trust level answered, so the
 * app can tell the user when detection is running degraded — the only honest
 * signal that Instagram has changed underneath it.
 *
 * [tier] is null if and only if [surface] is [Surface.OTHER].
 */
data class Classification(
    val surface: Surface,
    val tier: Tier?,
) {
    companion object {
        val OTHER = Classification(Surface.OTHER, null)
    }
}
```

`detection/src/main/kotlin/com/insta/detection/ScreenClassifier.kt`

```kotlin
package com.insta.detection

/**
 * Decides which Instagram screen a snapshot shows.
 *
 * Signals are tried most-trusted first, across all surfaces, and the first
 * match wins. When Instagram renames its resource ids the HIGH tier stops
 * answering, the lower tiers keep working, and the reported tier tells the app
 * it is degraded.
 */
class ScreenClassifier(private val ruleSet: RuleSet) {

    fun classify(snapshot: ScreenSnapshot): Classification {
        val navBar by lazy { findNavBar(snapshot) }

        for (tier in Tier.entries) {
            for ((surface, rules) in ruleSet.surfaces) {
                val matched = rules.signals
                    .filter { it.tier == tier }
                    .any { matches(it, snapshot, navBar) }
                if (matched) return Classification(surface, tier)
            }
        }
        return Classification.OTHER
    }

    private fun matches(
        signal: Signal,
        snapshot: ScreenSnapshot,
        navBar: List<NodeSummary>?,
    ): Boolean = when (signal.type) {
        SignalType.VIEW_ID -> snapshot.nodes.any { node ->
            node.viewId == signal.value && node.satisfies(signal)
        }

        SignalType.CONTENT_DESCRIPTION -> snapshot.nodes.any { node ->
            node.contentDescription != null &&
                signal.anyOf.any { it.equals(node.contentDescription, ignoreCase = true) } &&
                node.satisfies(signal)
        }

        SignalType.NAV_BAR_INDEX -> {
            val index = signal.value?.toIntOrNull()
            val tab = if (index == null) null else navBar?.getOrNull(index)
            tab != null && tab.satisfies(signal)
        }
    }

    private fun NodeSummary.satisfies(signal: Signal): Boolean =
        !signal.requireSelected || isSelected

    /**
     * Finds the bottom tab bar geometrically rather than by id, since geometry
     * is the one thing Instagram cannot rename: a row of at least four
     * clickable siblings, sitting lower on screen than any other such row.
     */
    private fun findNavBar(snapshot: ScreenSnapshot): List<NodeSummary>? =
        snapshot.nodes
            .filter { it.parentIndex >= 0 }
            .groupBy { it.parentIndex }
            .values
            .filter { siblings -> siblings.size >= MIN_TABS && siblings.all { it.isClickable } }
            .maxByOrNull { siblings -> siblings.minOf { it.bounds.top } }
            ?.sortedBy { it.indexInParent }

    private companion object {
        const val MIN_TABS = 4
    }
}
```

- [ ] **Step 5: Lancer les tests pour vérifier qu'ils passent**

Run: `./gradlew :detection:test`
Expected: PASS, 21 tests au total sur le module

- [ ] **Step 6: Commit**

```bash
git add detection/
git commit -m "feat: tiered screen classifier with geometric nav bar detection"
```

---

### Task 4: Module app, parcours d'arbre borné

Le parcours prend une interface `NodeLike` plutôt que `AccessibilityNodeInfo`, ce qui permet de tester les bornes sur JVM avec des faux nœuds. L'adaptateur Android reste une fine couche sans logique.

**Files:**
- Create: `app/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/NodeLike.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/TreeWalker.kt`
- Test: `app/src/test/kotlin/com/insta/reelsoff/service/TreeWalkerTest.kt`

**Interfaces:**
- Consumes: `ScreenSnapshot`, `NodeSummary`, `Bounds` (Task 1)
- Produces: interface `NodeLike` (propriétés `viewId`, `contentDescription`, `className`, `isSelected`, `isClickable`, `bounds`, `childCount`, méthode `childAt(i: Int): NodeLike?`) et `TreeWalker(maxDepth: Int = 25, maxNodes: Int = 800).walk(root: NodeLike?, packageName: String, capturedAtMillis: Long): ScreenSnapshot`

- [ ] **Step 1: Écrire les tests qui échouent**

`app/src/test/kotlin/com/insta/reelsoff/service/TreeWalkerTest.kt`

```kotlin
package com.insta.reelsoff.service

import com.insta.detection.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeNode(
    override val viewId: String? = null,
    override val contentDescription: String? = null,
    override val className: String? = "android.view.View",
    override val isSelected: Boolean = false,
    override val isClickable: Boolean = false,
    override val bounds: Bounds = Bounds(0, 0, 100, 100),
    private val children: List<FakeNode> = emptyList(),
) : NodeLike {
    override val childCount: Int get() = children.size
    override fun childAt(index: Int): NodeLike? = children.getOrNull(index)
}

/** A chain of [length] nodes, each the single child of the previous one. */
private fun chain(length: Int): FakeNode =
    (1..length).fold(FakeNode()) { acc, _ -> FakeNode(children = listOf(acc)) }

/** A node with [count] leaf children. */
private fun fanOut(count: Int): FakeNode =
    FakeNode(children = List(count) { FakeNode(viewId = "leaf-$it") })

class TreeWalkerTest {

    private val walker = TreeWalker()

    @Test
    fun `null root yields an empty snapshot`() {
        val snapshot = walker.walk(null, "com.instagram.android", 7L)

        assertEquals("com.instagram.android", snapshot.packageName)
        assertEquals(7L, snapshot.capturedAtMillis)
        assertTrue(snapshot.nodes.isEmpty())
    }

    @Test
    fun `copies every collected field`() {
        val root = FakeNode(
            viewId = "com.instagram.android:id/clips_tab",
            contentDescription = "Réels",
            className = "android.widget.ImageView",
            isSelected = true,
            isClickable = true,
            bounds = Bounds(1, 2, 3, 4),
        )

        val node = walker.walk(root, "com.instagram.android", 0L).nodes.single()

        assertEquals("com.instagram.android:id/clips_tab", node.viewId)
        assertEquals("Réels", node.contentDescription)
        assertEquals("android.widget.ImageView", node.className)
        assertTrue(node.isSelected)
        assertTrue(node.isClickable)
        assertEquals(Bounds(1, 2, 3, 4), node.bounds)
    }

    @Test
    fun `records parent index depth and position`() {
        val root = FakeNode(
            children = listOf(
                FakeNode(viewId = "a"),
                FakeNode(viewId = "b", children = listOf(FakeNode(viewId = "b1"))),
            ),
        )

        val nodes = walker.walk(root, "com.instagram.android", 0L).nodes
        val b1 = nodes.single { it.viewId == "b1" }
        val b = nodes.single { it.viewId == "b" }

        assertEquals(-1, nodes[0].parentIndex)
        assertEquals(0, nodes[0].depth)
        assertEquals(b.index, b1.parentIndex)
        assertEquals(2, b1.depth)
        assertEquals(1, b.indexInParent)
        assertEquals(0, b1.indexInParent)
    }

    @Test
    fun `index equals position in the flat list`() {
        val nodes = walker.walk(fanOut(5), "com.instagram.android", 0L).nodes

        nodes.forEachIndexed { position, node -> assertEquals(position, node.index) }
    }

    @Test
    fun `stops descending past the depth cap`() {
        val nodes = TreeWalker(maxDepth = 3).walk(chain(50), "com.instagram.android", 0L).nodes

        assertEquals(3, nodes.size)
        assertEquals(2, nodes.maxOf { it.depth })
    }

    @Test
    fun `stops collecting past the node cap`() {
        val nodes = TreeWalker(maxNodes = 10).walk(fanOut(500), "com.instagram.android", 0L).nodes

        assertEquals(10, nodes.size)
    }

    @Test
    fun `skips null children without losing the siblings after them`() {
        val root = object : NodeLike {
            override val viewId: String? = "root"
            override val contentDescription: String? = null
            override val className: String? = "android.view.View"
            override val isSelected: Boolean = false
            override val isClickable: Boolean = false
            override val bounds: Bounds = Bounds(0, 0, 0, 0)
            override val childCount: Int = 3
            override fun childAt(index: Int): NodeLike? =
                if (index == 1) null else FakeNode(viewId = "child-$index")
        }

        val nodes = walker.walk(root, "com.instagram.android", 0L).nodes

        assertEquals(listOf("root", "child-0", "child-2"), nodes.map { it.viewId })
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :app:testDebugUnitTest`
Expected: échec — le projet `:app` n'existe pas encore

- [ ] **Step 3: Créer le module app**

Ajouter à `settings.gradle.kts`, après `include(":detection")` :

```kotlin
include(":app")
```

`app/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.insta.reelsoff"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.insta.reelsoff"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }
}

dependencies {
    implementation(project(":detection"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
}
```

`app/src/main/res/values/strings.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Sans Reels</string>
    <string name="accessibility_service_description">Détecte l\'onglet Reels et la page Explore dans Instagram, et revient en arrière. Ne lit aucun texte et n\'envoie rien sur le réseau.</string>
</resources>
```

`app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar" />

</manifest>
```

- [ ] **Step 4: Écrire `NodeLike` et `TreeWalker`**

`app/src/main/kotlin/com/insta/reelsoff/service/NodeLike.kt`

```kotlin
package com.insta.reelsoff.service

import com.insta.detection.Bounds

/**
 * The slice of AccessibilityNodeInfo this app reads. Exists so the walking
 * logic can be tested on the JVM with fakes, and so the set of collected
 * fields is visible in one place — note the absence of `text`.
 */
interface NodeLike {
    val viewId: String?
    val contentDescription: String?
    val className: String?
    val isSelected: Boolean
    val isClickable: Boolean
    val bounds: Bounds
    val childCount: Int
    fun childAt(index: Int): NodeLike?
}
```

`app/src/main/kotlin/com/insta/reelsoff/service/TreeWalker.kt`

```kotlin
package com.insta.reelsoff.service

import com.insta.detection.NodeSummary
import com.insta.detection.ScreenSnapshot

/**
 * Flattens a view tree into a snapshot, breadth-first.
 *
 * Both caps exist because this runs on the main thread on every qualifying
 * accessibility event: a pathological tree must cost a bounded amount of work,
 * not an unbounded one.
 */
class TreeWalker(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
) {

    fun walk(root: NodeLike?, packageName: String, capturedAtMillis: Long): ScreenSnapshot {
        val nodes = mutableListOf<NodeSummary>()
        if (root != null) {
            val queue = ArrayDeque<Pending>()
            queue.add(Pending(root, parentIndex = -1, depth = 0, indexInParent = 0))

            while (queue.isNotEmpty() && nodes.size < maxNodes) {
                val pending = queue.removeFirst()
                val index = nodes.size
                nodes.add(pending.toSummary(index))

                if (pending.depth + 1 < maxDepth) {
                    for (childPosition in 0 until pending.node.childCount) {
                        val child = pending.node.childAt(childPosition) ?: continue
                        queue.add(
                            Pending(
                                node = child,
                                parentIndex = index,
                                depth = pending.depth + 1,
                                indexInParent = childPosition,
                            ),
                        )
                    }
                }
            }
        }
        return ScreenSnapshot(packageName, capturedAtMillis, nodes)
    }

    private class Pending(
        val node: NodeLike,
        val parentIndex: Int,
        val depth: Int,
        val indexInParent: Int,
    ) {
        fun toSummary(index: Int) = NodeSummary(
            index = index,
            parentIndex = parentIndex,
            depth = depth,
            indexInParent = indexInParent,
            viewId = node.viewId,
            contentDescription = node.contentDescription,
            className = node.className,
            isSelected = node.isSelected,
            isClickable = node.isClickable,
            bounds = node.bounds,
        )
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 25
        const val DEFAULT_MAX_NODES = 800
    }
}
```

Note sur `indexInParent` : c'est la position dans la liste des enfants du parent, y compris les enfants nuls sautés. C'est voulu — l'index de l'onglet Reels dans la barre reste correct même si un frère est illisible.

- [ ] **Step 5: Lancer les tests pour vérifier qu'ils passent**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 7 tests

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts app/
git commit -m "feat: android module and bounded view tree walker"
```

---

### Task 5: Machine à états du blocage

Sépare « quel écran est affiché » de « que faire ». Sans elle, un `BACK` qui ne quitte pas l'écran produit une boucle qui rend le téléphone inutilisable.

**Files:**
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/Clock.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/Blocker.kt`
- Test: `app/src/test/kotlin/com/insta/reelsoff/service/BlockerTest.kt`

**Interfaces:**
- Consumes: `Classification`, `Surface`, `Tier` (Tasks 1 et 3)
- Produces: interface `Clock { fun nowMillis(): Long }`, `SystemClock` (implémentation réelle), la constante `NEVER` partagée par le paquet `service`, `BlockAction` (enum `NONE`, `BACK`, `HOME`), `BlockDecision(action: BlockAction, recordEpisode: Boolean, tier: Tier?)`, `BlockerConfig`, et `Blocker(clock: Clock, config: BlockerConfig = BlockerConfig()).decide(classification: Classification, blockedSurfaces: Set<Surface>): BlockDecision`

Règles encodées :
- une surface non bloquée réinitialise le compteur de retours consécutifs ;
- après une action, période morte de 600 ms pendant laquelle rien n'est décidé ;
- trois retours arrière en trois secondes déclenchent `HOME` ;
- `HOME` ne peut pas se déclencher plus d'une fois par 30 secondes ; pendant ce plafond la décision est `NONE`, pour éviter de reprendre la boucle qu'on vient de fuir ;
- un épisode compté par tranche : deux secondes sans détection bloquée ferment l'épisode courant.

- [ ] **Step 1: Écrire les tests qui échouent**

`app/src/test/kotlin/com/insta/reelsoff/service/BlockerTest.kt`

```kotlin
package com.insta.reelsoff.service

import com.insta.detection.Classification
import com.insta.detection.Surface
import com.insta.detection.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
    fun advance(millis: Long) { now += millis }
}

class BlockerTest {

    private val clock = FakeClock()
    private val blocker = Blocker(clock)
    private val blocked = setOf(Surface.REELS, Surface.EXPLORE)

    private val reels = Classification(Surface.REELS, Tier.HIGH)
    private val explore = Classification(Surface.EXPLORE, Tier.HIGH)
    private val other = Classification.OTHER

    @Test
    fun `does nothing on a non blocked surface`() {
        val decision = blocker.decide(other, blocked)

        assertEquals(BlockAction.NONE, decision.action)
        assertFalse(decision.recordEpisode)
    }

    @Test
    fun `does nothing when the surface is blocked but disabled in settings`() {
        val decision = blocker.decide(reels, setOf(Surface.EXPLORE))

        assertEquals(BlockAction.NONE, decision.action)
        assertFalse(decision.recordEpisode)
    }

    @Test
    fun `presses back and records an episode on first detection`() {
        val decision = blocker.decide(reels, blocked)

        assertEquals(BlockAction.BACK, decision.action)
        assertTrue(decision.recordEpisode)
        assertEquals(Tier.HIGH, decision.tier)
    }

    @Test
    fun `stays quiet during the cooldown`() {
        blocker.decide(reels, blocked)
        clock.advance(300)

        val decision = blocker.decide(reels, blocked)

        assertEquals(BlockAction.NONE, decision.action)
        assertFalse(decision.recordEpisode)
    }

    @Test
    fun `presses back again once the cooldown has passed`() {
        blocker.decide(reels, blocked)
        clock.advance(700)

        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `a burst of back presses counts as one episode`() {
        assertTrue(blocker.decide(reels, blocked).recordEpisode)
        clock.advance(700)
        assertFalse(blocker.decide(reels, blocked).recordEpisode)
        clock.advance(700)
        assertFalse(blocker.decide(reels, blocked).recordEpisode)
    }

    @Test
    fun `a fresh attempt after the episode gap counts again`() {
        assertTrue(blocker.decide(reels, blocked).recordEpisode)
        clock.advance(5_000)

        assertTrue(blocker.decide(reels, blocked).recordEpisode)
    }

    @Test
    fun `escalates to home after three failed back presses`() {
        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
        clock.advance(700)
        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
        clock.advance(700)

        assertEquals(BlockAction.HOME, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `does not escalate when back presses are spread beyond the window`() {
        blocker.decide(reels, blocked)
        clock.advance(4_000)
        blocker.decide(reels, blocked)
        clock.advance(4_000)

        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `landing on a non blocked surface resets the escalation counter`() {
        blocker.decide(reels, blocked)
        clock.advance(700)
        blocker.decide(reels, blocked)
        clock.advance(700)
        blocker.decide(other, blocked)
        clock.advance(700)

        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `home is rate limited to once every thirty seconds`() {
        fun escalate() {
            repeat(3) {
                blocker.decide(reels, blocked)
                clock.advance(700)
            }
        }

        escalate()
        clock.advance(2_000)
        blocker.decide(other, blocked)
        clock.advance(700)

        blocker.decide(reels, blocked)
        clock.advance(700)
        blocker.decide(reels, blocked)
        clock.advance(700)
        val secondEscalation = blocker.decide(reels, blocked)

        assertEquals(BlockAction.NONE, secondEscalation.action)
    }

    @Test
    fun `home becomes available again after the rate limit expires`() {
        repeat(3) {
            blocker.decide(reels, blocked)
            clock.advance(700)
        }
        clock.advance(31_000)
        blocker.decide(other, blocked)
        clock.advance(700)

        blocker.decide(reels, blocked)
        clock.advance(700)
        blocker.decide(reels, blocked)
        clock.advance(700)

        assertEquals(BlockAction.HOME, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `reports the tier that fired so the ui can flag degraded detection`() {
        val decision = blocker.decide(Classification(Surface.EXPLORE, Tier.LOW), blocked)

        assertEquals(Tier.LOW, decision.tier)
        assertEquals(BlockAction.BACK, decision.action)
    }

    @Test
    fun `explore and reels are tracked by the same escalation counter`() {
        blocker.decide(reels, blocked)
        clock.advance(700)
        blocker.decide(explore, blocked)
        clock.advance(700)

        assertEquals(BlockAction.HOME, blocker.decide(reels, blocked).action)
    }
}
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./gradlew :app:testDebugUnitTest --tests '*BlockerTest*'`
Expected: échec de compilation — `Unresolved reference: Blocker`

- [ ] **Step 3: Écrire l'horloge**

`app/src/main/kotlin/com/insta/reelsoff/service/Clock.kt`

```kotlin
package com.insta.reelsoff.service

/** Injected so the blocker's timing rules can be tested without waiting. */
interface Clock {
    fun nowMillis(): Long
}

/**
 * Uses elapsed real time rather than wall clock: the blocker reasons about
 * intervals, and a clock change must not confuse it.
 */
object SystemClock : Clock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

/**
 * "Long ago" sentinel, used by every timing rule in this package.
 *
 * Deliberately not Long.MIN_VALUE: `now - Long.MIN_VALUE` overflows back to a
 * negative number, which would make every "has enough time passed" check fail
 * on the very first call — no first block, no first event, no first capture.
 */
internal const val NEVER: Long = Long.MIN_VALUE / 4
```

- [ ] **Step 4: Écrire la machine à états**

`app/src/main/kotlin/com/insta/reelsoff/service/Blocker.kt`

```kotlin
package com.insta.reelsoff.service

import com.insta.detection.Classification
import com.insta.detection.Surface
import com.insta.detection.Tier

enum class BlockAction {
    NONE,
    BACK,
    HOME,
}

data class BlockDecision(
    val action: BlockAction,
    val recordEpisode: Boolean,
    val tier: Tier?,
) {
    companion object {
        val IDLE = BlockDecision(BlockAction.NONE, recordEpisode = false, tier = null)
    }
}

data class BlockerConfig(
    /** Quiet window after an action, so one action is not counted many times. */
    val cooldownMillis: Long = 600,
    /** Back presses within [escalationWindowMillis] before falling back to HOME. */
    val escalateAfterBacks: Int = 3,
    val escalationWindowMillis: Long = 3_000,
    /** A misfiring detector must be annoying, not device-locking. */
    val homeRateLimitMillis: Long = 30_000,
    /** Silence longer than this closes the current episode. */
    val episodeGapMillis: Long = 2_000,
)

/**
 * Turns a stream of classifications into actions.
 *
 * A back press is not guaranteed to leave the screen — Explore can be the root
 * of the stack, and Instagram can relaunch straight onto Reels. Pressing back
 * blindly on every detection would loop forever, so escalation to HOME is the
 * way out, and the rate limit on HOME is the way out of *that*.
 */
class Blocker(
    private val clock: Clock,
    private val config: BlockerConfig = BlockerConfig(),
) {

    private var lastActionAtMillis = NEVER
    private var lastBlockedAtMillis = NEVER
    private var lastHomeAtMillis = NEVER
    private var escalationWindowStartMillis = NEVER
    private var consecutiveBacks = 0

    fun decide(classification: Classification, blockedSurfaces: Set<Surface>): BlockDecision {
        val now = clock.nowMillis()
        val surface = classification.surface

        if (surface == Surface.OTHER || surface !in blockedSurfaces) {
            consecutiveBacks = 0
            escalationWindowStartMillis = NEVER
            return BlockDecision.IDLE
        }

        if (now - lastActionAtMillis < config.cooldownMillis) return BlockDecision.IDLE

        val recordEpisode = now - lastBlockedAtMillis > config.episodeGapMillis
        lastBlockedAtMillis = now
        lastActionAtMillis = now

        if (now - escalationWindowStartMillis > config.escalationWindowMillis) {
            escalationWindowStartMillis = now
            consecutiveBacks = 0
        }
        consecutiveBacks++

        val action = when {
            consecutiveBacks < config.escalateAfterBacks -> BlockAction.BACK
            now - lastHomeAtMillis >= config.homeRateLimitMillis -> {
                lastHomeAtMillis = now
                consecutiveBacks = 0
                escalationWindowStartMillis = NEVER
                BlockAction.HOME
            }
            // Rate limited: staying quiet beats re-entering the loop we just left.
            else -> BlockAction.NONE
        }

        return BlockDecision(action, recordEpisode, classification.tier)
    }
}
```

- [ ] **Step 5: Lancer les tests pour vérifier qu'ils passent**

Run: `./gradlew :app:testDebugUnitTest --tests '*BlockerTest*'`
Expected: PASS, 14 tests

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: blocker state machine with cooldown and home escalation"
```

---

### Task 6: Service d'accessibilité et mode capture

Premier jalon vérifiable sur appareil. À la fin de cette tâche, l'app ne bloque encore rien : elle sait seulement capturer des arbres de vues. C'est l'étape qui rend la Task 7 déterministe au lieu de tâtonnante.

**Files:**
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/EventThrottle.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/AccessibilityNodeLike.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/CaptureSession.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/InstagramWatcherService.kt`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`
- Create: `app/src/main/kotlin/com/insta/reelsoff/ui/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/com/insta/reelsoff/service/EventThrottleTest.kt`
- Test: `app/src/test/kotlin/com/insta/reelsoff/service/CaptureSessionTest.kt`

**Interfaces:**
- Consumes: `NodeLike`, `TreeWalker` (Task 4) ; `Clock` (Task 5) ; `ScreenSnapshot` (Task 1)
- Produces: `EventThrottle(clock, minIntervalMillis = 200).shouldProcess(): Boolean`, `AccessibilityNodeLike(node: AccessibilityNodeInfo) : NodeLike`, `CaptureSession(clock, durationMillis = 60_000, intervalMillis = 3_000)` avec `start()`, `shouldCapture(): Boolean`, `isActive(): Boolean`, et l'action `com.insta.reelsoff.START_CAPTURE` que `MainActivity` diffuse et que le service reçoit.

- [ ] **Step 1: Écrire les tests qui échouent**

`app/src/test/kotlin/com/insta/reelsoff/service/EventThrottleTest.kt`

```kotlin
package com.insta.reelsoff.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class TickClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}

class EventThrottleTest {

    @Test
    fun `lets the first event through`() {
        assertTrue(EventThrottle(TickClock()).shouldProcess())
    }

    @Test
    fun `drops events inside the interval`() {
        val clock = TickClock()
        val throttle = EventThrottle(clock)

        throttle.shouldProcess()
        clock.now = 199

        assertFalse(throttle.shouldProcess())
    }

    @Test
    fun `lets an event through once the interval has elapsed`() {
        val clock = TickClock()
        val throttle = EventThrottle(clock)

        throttle.shouldProcess()
        clock.now = 200

        assertTrue(throttle.shouldProcess())
    }

    @Test
    fun `a dropped event does not restart the interval`() {
        val clock = TickClock()
        val throttle = EventThrottle(clock)

        throttle.shouldProcess()
        clock.now = 150
        throttle.shouldProcess()
        clock.now = 210

        assertTrue(throttle.shouldProcess())
    }
}
```

`app/src/test/kotlin/com/insta/reelsoff/service/CaptureSessionTest.kt`

```kotlin
package com.insta.reelsoff.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class StepClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}

class CaptureSessionTest {

    @Test
    fun `is inactive until started`() {
        val session = CaptureSession(StepClock())

        assertFalse(session.isActive())
        assertFalse(session.shouldCapture())
    }

    @Test
    fun `captures immediately when started`() {
        val session = CaptureSession(StepClock()).apply { start() }

        assertTrue(session.isActive())
        assertTrue(session.shouldCapture())
    }

    @Test
    fun `captures at most once per interval`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { start() }

        session.shouldCapture()
        clock.now = 2_999
        assertFalse(session.shouldCapture())
        clock.now = 3_000
        assertTrue(session.shouldCapture())
    }

    @Test
    fun `goes inactive after the duration`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { start() }

        clock.now = 60_001

        assertFalse(session.isActive())
        assertFalse(session.shouldCapture())
    }

    @Test
    fun `restarting extends the window`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { start() }

        clock.now = 60_001
        session.start()

        assertTrue(session.isActive())
        assertTrue(session.shouldCapture())
    }
}
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./gradlew :app:testDebugUnitTest --tests '*EventThrottleTest*' --tests '*CaptureSessionTest*'`
Expected: échec de compilation — `Unresolved reference: EventThrottle`

- [ ] **Step 3: Écrire l'étranglement et la session de capture**

`app/src/main/kotlin/com/insta/reelsoff/service/EventThrottle.kt`

```kotlin
package com.insta.reelsoff.service

/**
 * Caps how often the tree gets walked. TYPE_WINDOW_CONTENT_CHANGED fires
 * continuously while scrolling; walking on every one of them would burn the
 * battery for no extra information.
 */
class EventThrottle(
    private val clock: Clock,
    private val minIntervalMillis: Long = 200,
) {
    private var lastProcessedAtMillis = NEVER

    fun shouldProcess(): Boolean {
        val now = clock.nowMillis()
        if (now - lastProcessedAtMillis < minIntervalMillis) return false
        lastProcessedAtMillis = now
        return true
    }
}
```

`NEVER` est défini dans `Clock.kt` (Task 5) et partagé par tout le paquet.

`app/src/main/kotlin/com/insta/reelsoff/service/CaptureSession.kt`

```kotlin
package com.insta.reelsoff.service

/**
 * A timed window during which the service dumps view trees to disk.
 *
 * Time-based rather than button-based because the user cannot press a button
 * in this app while Instagram is in the foreground, and pulling down the
 * notification shade would change the active window — capturing the shade
 * instead of Instagram.
 */
class CaptureSession(
    private val clock: Clock,
    private val durationMillis: Long = 60_000,
    private val intervalMillis: Long = 3_000,
) {
    private var startedAtMillis = NEVER
    private var lastCaptureAtMillis = NEVER

    fun start() {
        startedAtMillis = clock.nowMillis()
        lastCaptureAtMillis = NEVER
    }

    fun isActive(): Boolean = clock.nowMillis() - startedAtMillis <= durationMillis

    fun shouldCapture(): Boolean {
        if (!isActive()) return false
        val now = clock.nowMillis()
        if (now - lastCaptureAtMillis < intervalMillis) return false
        lastCaptureAtMillis = now
        return true
    }
}
```

- [ ] **Step 4: Lancer les tests pour vérifier qu'ils passent**

Run: `./gradlew :app:testDebugUnitTest --tests '*EventThrottleTest*' --tests '*CaptureSessionTest*'`
Expected: PASS, 9 tests

- [ ] **Step 5: Écrire l'adaptateur Android**

`app/src/main/kotlin/com/insta/reelsoff/service/AccessibilityNodeLike.kt`

```kotlin
package com.insta.reelsoff.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.insta.detection.Bounds

/**
 * Thin adapter, deliberately logic-free: everything worth testing lives in
 * [TreeWalker], which knows nothing about Android.
 */
class AccessibilityNodeLike(private val node: AccessibilityNodeInfo) : NodeLike {

    override val viewId: String? get() = node.viewIdResourceName
    override val contentDescription: String? get() = node.contentDescription?.toString()
    override val className: String? get() = node.className?.toString()
    override val isSelected: Boolean get() = node.isSelected
    override val isClickable: Boolean get() = node.isClickable

    override val bounds: Bounds
        get() {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return Bounds(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val childCount: Int get() = node.childCount

    override fun childAt(index: Int): NodeLike? =
        node.getChild(index)?.let(::AccessibilityNodeLike)
}
```

- [ ] **Step 6: Écrire la configuration du service**

`app/src/main/res/xml/accessibility_service_config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100"
    android:packageNames="com.instagram.android" />
```

`flagReportViewIds` est obligatoire : sans lui `viewIdResourceName` est toujours `null` et le palier HIGH ne répondrait jamais. `android:packageNames` fait filtrer Android en amont — le service ne reçoit aucun événement des autres apps.

- [ ] **Step 7: Écrire le service**

`app/src/main/kotlin/com/insta/reelsoff/service/InstagramWatcherService.kt`

```kotlin
package com.insta.reelsoff.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.insta.detection.ScreenSnapshot
import kotlinx.serialization.json.Json
import java.io.File

class InstagramWatcherService : AccessibilityService() {

    private val clock: Clock = SystemClock
    private val walker = TreeWalker()
    private val throttle = EventThrottle(clock)
    private val captureSession = CaptureSession(clock)
    private val json = Json { prettyPrint = true }

    private var captureIndex = 0

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            captureSession.start()
            Log.i(TAG, "capture session started")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(
            captureReceiver,
            IntentFilter(ACTION_START_CAPTURE),
            Context.RECEIVER_NOT_EXPORTED,
        )
        Log.i(TAG, "service connected")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(captureReceiver) }
        super.onDestroy()
    }

    /**
     * Everything is wrapped: an exception escaping this callback crashes the
     * service, and Android may then disable it for good — leaving the user
     * believing they are still protected.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handle(event)
        } catch (e: Throwable) {
            Log.e(TAG, "event handling failed", e)
        }
    }

    override fun onInterrupt() = Unit

    private fun handle(event: AccessibilityEvent?) {
        if (event?.packageName != INSTAGRAM_PACKAGE) return
        if (!captureSession.isActive() && !throttle.shouldProcess()) return

        // Frequently null during screen transitions. Nothing to do but skip.
        val root = rootInActiveWindow ?: return
        val snapshot = walker.walk(
            root = AccessibilityNodeLike(root),
            packageName = INSTAGRAM_PACKAGE,
            capturedAtMillis = System.currentTimeMillis(),
        )

        if (captureSession.shouldCapture()) writeCapture(snapshot)
    }

    private fun writeCapture(snapshot: ScreenSnapshot) {
        val directory = File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        val file = File(directory, "snapshot-%03d.json".format(captureIndex++))
        file.writeText(json.encodeToString(snapshot))
        Log.i(TAG, "wrote ${file.absolutePath} (${snapshot.nodes.size} nodes)")
    }

    companion object {
        const val ACTION_START_CAPTURE = "com.insta.reelsoff.START_CAPTURE"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val TAG = "ReelsOff"
    }
}
```

- [ ] **Step 8: Écrire l'écran minimal**

Ajouter à `app/src/main/res/values/strings.xml`, avant `</resources>` :

```xml
    <string name="open_accessibility_settings">Ouvrir les réglages d\'accessibilité</string>
    <string name="start_capture">Capturer 60 secondes</string>
    <string name="capture_hint">Appuie, puis bascule vers Instagram et navigue : fil, Reels, Explore, profil, messages. Un instantané est enregistré toutes les 3 secondes.</string>
```

`app/src/main/kotlin/com/insta/reelsoff/ui/MainActivity.kt`

```kotlin
package com.insta.reelsoff.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.insta.reelsoff.R
import com.insta.reelsoff.service.InstagramWatcherService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CaptureScreen()
                }
            }
        }
    }
}

@Composable
private fun CaptureScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text(stringResource(R.string.open_accessibility_settings))
        }

        Text(stringResource(R.string.capture_hint), style = MaterialTheme.typography.bodyMedium)

        Button(onClick = {
            context.sendBroadcast(
                Intent(InstagramWatcherService.ACTION_START_CAPTURE)
                    .setPackage(context.packageName),
            )
        }) {
            Text(stringResource(R.string.start_capture))
        }
    }
}
```

- [ ] **Step 9: Déclarer le service et l'activité**

Remplacer le bloc `<application>` de `app/src/main/AndroidManifest.xml` par :

```xml
    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.InstagramWatcherService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

    </application>
```

`android:exported="true"` est requis pour que le système puisse se lier au service ; `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` fait que seul le système en est capable.

- [ ] **Step 10: Vérifier que tout compile et que les tests passent**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, tous les tests JVM au vert

- [ ] **Step 11: Commit**

```bash
git add app/
git commit -m "feat: accessibility service with throttling and timed capture mode"
```

---

### Task 7: Capture réelle, fixtures et calibrage des règles

La seule tâche à étapes manuelles. Elle produit la vérité de terrain sur laquelle repose tout le reste. **Aucune valeur d'identifiant ne doit être devinée** : tout vient des captures.

**Files:**
- Create: `detection/src/test/resources/fixtures/feed.json`
- Create: `detection/src/test/resources/fixtures/reels.json`
- Create: `detection/src/test/resources/fixtures/explore.json`
- Create: `detection/src/test/resources/fixtures/profile.json`
- Create: `detection/src/test/resources/fixtures/direct.json`
- Create: `app/src/main/assets/rules.json`
- Test: `detection/src/test/kotlin/com/insta/detection/RealFixtureTest.kt`

**Interfaces:**
- Consumes: `ScreenClassifier`, `RuleSetParser` (Tasks 2 et 3) ; le format d'instantané et le mode capture (Tasks 1 et 6)
- Produces: `app/src/main/assets/rules.json` — le jeu de règles réel, calibré, chargé par l'app en Task 8 ; et les fixtures réelles qui protègent les tâches suivantes contre les régressions.

- [ ] **Step 1: Installer et activer**

```bash
./gradlew :app:installDebug
```

Sur le téléphone : Réglages → Accessibilité → Sans Reels → activer. Android interdit d'activer un service d'accessibilité par programme ; cette bascule est manuelle et se fait une fois.

- [ ] **Step 2: Capturer**

Ouvrir l'app, appuyer sur « Capturer 60 secondes », puis basculer vers Instagram et visiter dans cet ordre, environ 8 secondes chacun : fil d'actualité, onglet Explore, onglet Reels, retour au fil, profil, messages.

- [ ] **Step 3: Récupérer les captures**

```bash
adb pull /sdcard/Android/data/com.insta.reelsoff/files/captures ./captures
ls captures
```
Expected: une vingtaine de fichiers `snapshot-NNN.json`

- [ ] **Step 4: Identifier les écrans et renommer les fixtures**

Pour chaque fichier, repérer la barre du bas et l'onglet sélectionné :

```bash
for f in captures/*.json; do
  echo "== $f"
  python3 -c "
import json,sys
nodes=json.load(open('$f'))['nodes']
by_parent={}
for n in nodes:
    by_parent.setdefault(n['parentIndex'],[]).append(n)
bars=[g for g in by_parent.values() if len(g)>=4 and all(x['isClickable'] for x in g)]
if not bars: print('  no nav bar'); sys.exit()
bar=max(bars,key=lambda g:min(x['bounds']['top'] for x in g))
for c in sorted(bar,key=lambda x:x['indexInParent']):
    print('  %d %-55s %-28s selected=%s' % (
        c['indexInParent'], c['viewId'], c['contentDescription'], c['isSelected']))
"
done
```

Choisir un fichier représentatif par écran et le copier :

```bash
mkdir -p detection/src/test/resources/fixtures
cp captures/snapshot-XXX.json detection/src/test/resources/fixtures/feed.json
cp captures/snapshot-XXX.json detection/src/test/resources/fixtures/reels.json
cp captures/snapshot-XXX.json detection/src/test/resources/fixtures/explore.json
cp captures/snapshot-XXX.json detection/src/test/resources/fixtures/profile.json
cp captures/snapshot-XXX.json detection/src/test/resources/fixtures/direct.json
```

Si un écran manque, recapturer. Ne pas continuer avec une fixture approximative : c'est elle qui définira le comportement.

- [ ] **Step 5: Écrire le jeu de règles réel**

`app/src/main/assets/rules.json` — remplacer chaque valeur ci-dessous par ce que la sortie de l'étape 4 a montré. `NAV_BAR_INDEX` prend l'`indexInParent` observé pour l'onglet concerné, pas une position supposée.

```json
{
  "version": 1,
  "surfaces": {
    "REELS": {
      "signals": [
        { "tier": "HIGH", "type": "VIEW_ID",
          "value": "REMPLACER_PAR_LE_VIEWID_OBSERVE", "requireSelected": true },
        { "tier": "MEDIUM", "type": "CONTENT_DESCRIPTION",
          "anyOf": ["REMPLACER_PAR_LE_LIBELLE_OBSERVE"], "requireSelected": true },
        { "tier": "LOW", "type": "NAV_BAR_INDEX",
          "value": "REMPLACER_PAR_L_INDEX_OBSERVE", "requireSelected": true }
      ]
    },
    "EXPLORE": {
      "signals": [
        { "tier": "HIGH", "type": "VIEW_ID",
          "value": "REMPLACER_PAR_LE_VIEWID_OBSERVE", "requireSelected": true },
        { "tier": "MEDIUM", "type": "CONTENT_DESCRIPTION",
          "anyOf": ["REMPLACER_PAR_LE_LIBELLE_OBSERVE"], "requireSelected": true },
        { "tier": "LOW", "type": "NAV_BAR_INDEX",
          "value": "REMPLACER_PAR_L_INDEX_OBSERVE", "requireSelected": true }
      ]
    }
  }
}
```

Aucun marqueur `REMPLACER_` ne doit subsister à la fin de cette tâche.

- [ ] **Step 6: Écrire le test sur fixtures réelles**

`detection/src/test/kotlin/com/insta/detection/RealFixtureTest.kt`

```kotlin
package com.insta.detection

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the shipped rules against view trees actually captured from Instagram.
 * These fixtures are the project's ground truth; the synthetic tests in
 * ScreenClassifierTest cover the logic, these cover reality.
 */
class RealFixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing resource $path" }
            .bufferedReader().readText()

    private fun fixture(name: String): ScreenSnapshot =
        json.decodeFromString(resource("/fixtures/$name.json"))

    private val ruleSet: RuleSet =
        when (val result = RuleSetParser.parse(resource("/rules.json"))) {
            is ParseResult.Success -> result.ruleSet
            is ParseResult.Failure -> error("shipped rules are invalid: ${result.message}")
        }

    private val classifier = ScreenClassifier(ruleSet)

    @Test
    fun `shipped rules contain no leftover placeholders`() {
        assertTrue(
            "rules.json still contains a REMPLACER_ placeholder",
            !resource("/rules.json").contains("REMPLACER_"),
        )
    }

    @Test
    fun `feed is not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("feed")).surface)
    }

    @Test
    fun `profile is not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("profile")).surface)
    }

    @Test
    fun `direct messages are not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("direct")).surface)
    }

    @Test
    fun `reels tab is detected at the high tier`() {
        val result = classifier.classify(fixture("reels"))

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `explore tab is detected at the high tier`() {
        val result = classifier.classify(fixture("explore"))

        assertEquals(Surface.EXPLORE, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    /** Proves the fallback works on real trees, not just synthetic ones. */
    @Test
    fun `reels is still detected when view ids are stripped`() {
        val stripped = fixture("reels").let { snapshot ->
            snapshot.copy(nodes = snapshot.nodes.map { it.copy(viewId = null) })
        }

        val result = classifier.classify(stripped)

        assertEquals(Surface.REELS, result.surface)
        assertNotEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `reels is still detected when view ids and labels are stripped`() {
        val stripped = fixture("reels").let { snapshot ->
            snapshot.copy(
                nodes = snapshot.nodes.map { it.copy(viewId = null, contentDescription = null) },
            )
        }

        val result = classifier.classify(stripped)

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.LOW, result.tier)
    }

    @Test
    fun `stripped feed is still not blocked`() {
        val stripped = fixture("feed").let { snapshot ->
            snapshot.copy(
                nodes = snapshot.nodes.map { it.copy(viewId = null, contentDescription = null) },
            )
        }

        assertEquals(Surface.OTHER, classifier.classify(stripped).surface)
    }
}
```

- [ ] **Step 7: Partager le fichier de règles avec les tests**

Le test lit `/rules.json` depuis les ressources de test de `:detection`, alors que le fichier livré vit dans `app/src/main/assets/`. Un seul fichier, deux consommateurs : ajouter à `detection/build.gradle.kts`, après le bloc `dependencies` :

```kotlin
sourceSets {
    named("test") {
        resources.srcDir(rootProject.file("app/src/main/assets"))
    }
}
```

- [ ] **Step 8: Lancer les tests**

Run: `./gradlew :detection:test --tests '*RealFixtureTest*'`
Expected: PASS, 9 tests

En cas d'échec sur `feed is not blocked`, c'est `requireSelected` qui est en cause, ou un `VIEW_ID` présent hors de la barre du bas. Corriger `rules.json`, pas le test.

- [ ] **Step 9: Commit**

```bash
git add detection/ app/src/main/assets/rules.json
git commit -m "feat: real instagram fixtures and calibrated rule set"
```

---

### Task 8: Chargement des règles et câblage du blocage

À la fin de cette tâche, l'app bloque réellement.

**Files:**
- Create: `app/src/main/kotlin/com/insta/reelsoff/service/RuleSetLoader.kt`
- Modify: `app/src/main/kotlin/com/insta/reelsoff/service/InstagramWatcherService.kt`
- Test: `app/src/androidTest/kotlin/com/insta/reelsoff/service/RuleSetLoaderTest.kt`

**Interfaces:**
- Consumes: `RuleSetParser`, `RuleSet` (Task 2) ; `ScreenClassifier` (Task 3) ; `Blocker`, `BlockAction` (Task 5)
- Produces: `RuleSetLoader(context).load(): LoadedRules` avec `LoadedRules(ruleSet: RuleSet, source: RuleSource, error: String?)` et `RuleSource` (enum `OVERRIDE`, `BUNDLED`)

Chargement : `filesDir/rules.json` s'il existe et se parse, sinon `assets/rules.json`. Un fichier de surcharge invalide n'empêche jamais l'app de démarrer.

- [ ] **Step 1: Écrire le test qui échoue**

`app/src/androidTest/kotlin/com/insta/reelsoff/service/RuleSetLoaderTest.kt`

```kotlin
package com.insta.reelsoff.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta.detection.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuleSetLoaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val override = File(context.filesDir, "rules.json")

    @After
    fun tearDown() {
        override.delete()
    }

    @Test
    fun `falls back to the bundled rules when no override exists`() {
        override.delete()

        val loaded = RuleSetLoader(context).load()

        assertEquals(RuleSource.BUNDLED, loaded.source)
        assertNull(loaded.error)
        assertTrue(loaded.ruleSet.surfaces.containsKey(Surface.REELS))
        assertTrue(loaded.ruleSet.surfaces.containsKey(Surface.EXPLORE))
    }

    @Test
    fun `prefers a valid override file`() {
        override.writeText(
            """
            { "version": 99, "surfaces": { "REELS": { "signals": [
              { "tier": "HIGH", "type": "VIEW_ID", "value": "override-marker" }
            ] } } }
            """.trimIndent(),
        )

        val loaded = RuleSetLoader(context).load()

        assertEquals(RuleSource.OVERRIDE, loaded.source)
        assertEquals(99, loaded.ruleSet.version)
    }

    @Test
    fun `falls back and reports the error when the override is broken`() {
        override.writeText("{ not json at all")

        val loaded = RuleSetLoader(context).load()

        assertEquals(RuleSource.BUNDLED, loaded.source)
        assertNotNull(loaded.error)
        assertTrue(loaded.ruleSet.surfaces.containsKey(Surface.REELS))
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*RuleSetLoaderTest*'`
Expected: échec de compilation — `Unresolved reference: RuleSetLoader`

- [ ] **Step 3: Écrire le chargeur**

`app/src/main/kotlin/com/insta/reelsoff/service/RuleSetLoader.kt`

```kotlin
package com.insta.reelsoff.service

import android.content.Context
import com.insta.detection.ParseResult
import com.insta.detection.RuleSet
import com.insta.detection.RuleSetParser
import java.io.File

enum class RuleSource {
    /** A hand-edited file in app storage, so detection can be repaired on device. */
    OVERRIDE,
    BUNDLED,
}

data class LoadedRules(
    val ruleSet: RuleSet,
    val source: RuleSource,
    /** Why the override was rejected, when it was. */
    val error: String?,
)

class RuleSetLoader(private val context: Context) {

    fun load(): LoadedRules {
        val override = File(context.filesDir, FILE_NAME)
        if (override.exists()) {
            when (val result = runCatching { RuleSetParser.parse(override.readText()) }.getOrNull()) {
                is ParseResult.Success -> return LoadedRules(result.ruleSet, RuleSource.OVERRIDE, null)
                is ParseResult.Failure -> return bundled(result.message)
                null -> return bundled("override file could not be read")
            }
        }
        return bundled(null)
    }

    private fun bundled(error: String?): LoadedRules {
        val raw = context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
        return when (val result = RuleSetParser.parse(raw)) {
            is ParseResult.Success -> LoadedRules(result.ruleSet, RuleSource.BUNDLED, error)
            // Caught by RealFixtureTest before shipping; if it happens here the build is broken.
            is ParseResult.Failure -> error("bundled rules are invalid: ${result.message}")
        }
    }

    private companion object {
        const val FILE_NAME = "rules.json"
    }
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*RuleSetLoaderTest*'`
Expected: PASS, 3 tests

- [ ] **Step 5: Câbler le blocage dans le service**

Dans `InstagramWatcherService.kt`, ajouter les imports :

```kotlin
import com.insta.detection.ScreenClassifier
import com.insta.detection.Surface
```

Ajouter les champs, après `private val json = Json { prettyPrint = true }` :

```kotlin
    private val blocker = Blocker(clock)
    private lateinit var classifier: ScreenClassifier
```

Dans `onServiceConnected()`, avant le `Log.i` final :

```kotlin
        val loaded = RuleSetLoader(this).load()
        classifier = ScreenClassifier(loaded.ruleSet)
        Log.i(TAG, "rules loaded from ${loaded.source}${loaded.error?.let { " ($it)" } ?: ""}")
```

Remplacer la fin de `handle(...)`, à partir de `if (captureSession.shouldCapture())` :

```kotlin
        if (captureSession.shouldCapture()) writeCapture(snapshot)

        val classification = classifier.classify(snapshot)
        val decision = blocker.decide(classification, BLOCKED_SURFACES)

        when (decision.action) {
            BlockAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            BlockAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            BlockAction.NONE -> Unit
        }

        if (decision.recordEpisode) {
            Log.i(TAG, "blocked ${classification.surface} via ${decision.tier}")
        }
```

Ajouter au `companion object` :

```kotlin
        private val BLOCKED_SURFACES = setOf(Surface.REELS, Surface.EXPLORE)
```

`BLOCKED_SURFACES` devient réglable en Task 10.

- [ ] **Step 6: Installer et vérifier sur appareil**

```bash
./gradlew :app:installDebug
adb logcat -c && adb logcat -s ReelsOff
```

Ouvrir Instagram et vérifier, dans cet ordre :
1. le fil d'actualité reste accessible, et défile normalement — si l'app t'en éjecte, arrêter et corriger `rules.json` ;
2. appuyer sur l'onglet Reels renvoie immédiatement en arrière ;
3. appuyer sur Explore renvoie immédiatement en arrière ;
4. le journal affiche `blocked REELS via HIGH` une seule fois par tentative, pas trois ;
5. aucune boucle de retours arrière : le téléphone reste utilisable.

- [ ] **Step 7: Commit**

```bash
git add app/
git commit -m "feat: load rules and act on classifications"
```

---

### Task 9: Journal des blocages

**Files:**
- Create: `app/src/main/kotlin/com/insta/reelsoff/data/BlockEvent.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/data/BlockEventDao.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/data/AppDatabase.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/data/DailyCount.kt`
- Modify: `app/src/main/kotlin/com/insta/reelsoff/service/InstagramWatcherService.kt`
- Test: `app/src/androidTest/kotlin/com/insta/reelsoff/data/BlockEventDaoTest.kt`
- Test: `app/src/test/kotlin/com/insta/reelsoff/data/DailyCountTest.kt`

**Interfaces:**
- Consumes: `Surface`, `Tier` (Tasks 1 et 2)
- Produces: entité `BlockEvent(id, epochMillis, surface, ruleTier)`, `BlockEventDao` avec `suspend fun insert(event: BlockEvent)` et `fun observeSince(sinceMillis: Long): Flow<List<BlockEvent>>`, `AppDatabase.get(context): AppDatabase`, et `dailyCounts(events: List<BlockEvent>, zone: ZoneId, today: LocalDate, days: Int): List<DailyCount>` avec `DailyCount(date: LocalDate, reels: Int, explore: Int)` et sa propriété dérivée `total`

L'agrégation par jour se fait en Kotlin, pas en SQL : le découpage en journées locales dépend du fuseau, et `java.time` le fait correctement là où une expression SQLite s'y casserait les dents.

- [ ] **Step 1: Écrire le test d'agrégation qui échoue**

`app/src/test/kotlin/com/insta/reelsoff/data/DailyCountTest.kt`

```kotlin
package com.insta.reelsoff.data

import com.insta.detection.Surface
import com.insta.detection.Tier
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyCountTest {

    private val paris: ZoneId = ZoneId.of("Europe/Paris")
    private val today: LocalDate = LocalDate.of(2026, 8, 16)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(date.atTime(hour, minute), paris).toInstant().toEpochMilli()

    private fun event(millis: Long, surface: Surface) = BlockEvent(
        epochMillis = millis,
        surface = surface.name,
        ruleTier = Tier.HIGH.name,
    )

    @Test
    fun `returns one entry per day even when a day is empty`() {
        val counts = dailyCounts(emptyList(), paris, today, days = 14)

        assertEquals(14, counts.size)
        assertEquals(today.minusDays(13), counts.first().date)
        assertEquals(today, counts.last().date)
        assertEquals(0, counts.last().reels)
    }

    @Test
    fun `counts each surface separately`() {
        val events = listOf(
            event(at(today, 9), Surface.REELS),
            event(at(today, 10), Surface.REELS),
            event(at(today, 11), Surface.EXPLORE),
        )

        val last = dailyCounts(events, paris, today, days = 14).last()

        assertEquals(2, last.reels)
        assertEquals(1, last.explore)
    }

    @Test
    fun `buckets by local day not by utc day`() {
        // 00:30 Paris on the 16th is 22:30 UTC on the 15th.
        val events = listOf(event(at(today, 0, 30), Surface.REELS))

        val counts = dailyCounts(events, paris, today, days = 14)

        assertEquals(1, counts.last().reels)
        assertEquals(0, counts[counts.size - 2].reels)
    }

    @Test
    fun `ignores events older than the window`() {
        val events = listOf(event(at(today.minusDays(20), 12), Surface.REELS))

        assertEquals(0, dailyCounts(events, paris, today, days = 14).sumOf { it.reels })
    }

    @Test
    fun `ignores an unknown surface name without crashing`() {
        val events = listOf(BlockEvent(epochMillis = at(today, 9), surface = "STORIES", ruleTier = "HIGH"))

        val last = dailyCounts(events, paris, today, days = 14).last()

        assertEquals(0, last.reels)
        assertEquals(0, last.explore)
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :app:testDebugUnitTest --tests '*DailyCountTest*'`
Expected: échec de compilation — `Unresolved reference: BlockEvent`

- [ ] **Step 3: Écrire l'entité, l'agrégation, le DAO et la base**

`app/src/main/kotlin/com/insta/reelsoff/data/BlockEvent.kt`

```kotlin
package com.insta.reelsoff.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One blocking episode — not one back press. A burst of back presses on a
 * single attempt is one row, so the log measures the user's reflex rather than
 * the state machine's behaviour.
 *
 * [ruleTier] is stored so degraded detection is visible from the log alone.
 */
@Entity(tableName = "block_event")
data class BlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMillis: Long,
    val surface: String,
    val ruleTier: String,
)
```

`app/src/main/kotlin/com/insta/reelsoff/data/DailyCount.kt`

```kotlin
package com.insta.reelsoff.data

import com.insta.detection.Surface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyCount(
    val date: LocalDate,
    val reels: Int,
    val explore: Int,
) {
    val total: Int get() = reels + explore
}

/**
 * Buckets events into local days, oldest first, including empty days so the
 * chart keeps a stable width.
 *
 * Done in Kotlin rather than SQL because local-day boundaries depend on the
 * time zone, which java.time handles and a SQLite date expression does not.
 */
fun dailyCounts(
    events: List<BlockEvent>,
    zone: ZoneId,
    today: LocalDate,
    days: Int,
): List<DailyCount> {
    val firstDay = today.minusDays((days - 1).toLong())
    val reels = mutableMapOf<LocalDate, Int>()
    val explore = mutableMapOf<LocalDate, Int>()

    for (event in events) {
        val date = Instant.ofEpochMilli(event.epochMillis).atZone(zone).toLocalDate()
        if (date < firstDay || date > today) continue
        when (event.surface) {
            Surface.REELS.name -> reels.merge(date, 1, Int::plus)
            Surface.EXPLORE.name -> explore.merge(date, 1, Int::plus)
            else -> Unit
        }
    }

    return (0 until days).map { offset ->
        val date = firstDay.plusDays(offset.toLong())
        DailyCount(date, reels[date] ?: 0, explore[date] ?: 0)
    }
}
```

`app/src/main/kotlin/com/insta/reelsoff/data/BlockEventDao.kt`

```kotlin
package com.insta.reelsoff.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockEventDao {

    @Insert
    suspend fun insert(event: BlockEvent)

    @Query("SELECT * FROM block_event WHERE epochMillis >= :sinceMillis ORDER BY epochMillis")
    fun observeSince(sinceMillis: Long): Flow<List<BlockEvent>>

    @Query("SELECT * FROM block_event WHERE epochMillis >= :sinceMillis ORDER BY epochMillis")
    suspend fun since(sinceMillis: Long): List<BlockEvent>
}
```

`app/src/main/kotlin/com/insta/reelsoff/data/AppDatabase.kt`

```kotlin
package com.insta.reelsoff.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BlockEvent::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockEventDao(): BlockEventDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "reelsoff.db",
            ).build().also { instance = it }
        }
    }
}
```

- [ ] **Step 4: Lancer le test d'agrégation pour vérifier qu'il passe**

Run: `./gradlew :app:testDebugUnitTest --tests '*DailyCountTest*'`
Expected: PASS, 5 tests

- [ ] **Step 5: Écrire le test du DAO**

`app/src/androidTest/kotlin/com/insta/reelsoff/data/BlockEventDaoTest.kt`

```kotlin
package com.insta.reelsoff.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockEventDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: BlockEventDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.blockEventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `inserts and reads back in chronological order`() = runBlocking {
        dao.insert(BlockEvent(epochMillis = 300, surface = "REELS", ruleTier = "HIGH"))
        dao.insert(BlockEvent(epochMillis = 100, surface = "EXPLORE", ruleTier = "LOW"))

        val events = dao.since(0)

        assertEquals(listOf(100L, 300L), events.map { it.epochMillis })
        assertEquals("EXPLORE", events[0].surface)
        assertEquals("LOW", events[0].ruleTier)
    }

    @Test
    fun `filters out events before the cutoff`() = runBlocking {
        dao.insert(BlockEvent(epochMillis = 100, surface = "REELS", ruleTier = "HIGH"))
        dao.insert(BlockEvent(epochMillis = 500, surface = "REELS", ruleTier = "HIGH"))

        assertEquals(1, dao.since(200).size)
    }

    @Test
    fun `flow emits the current contents`() = runBlocking {
        dao.insert(BlockEvent(epochMillis = 100, surface = "REELS", ruleTier = "HIGH"))

        assertEquals(1, dao.observeSince(0).first().size)
    }
}
```

- [ ] **Step 6: Lancer le test du DAO**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*BlockEventDaoTest*'`
Expected: PASS, 3 tests

- [ ] **Step 7: Écrire dans la base depuis le service**

Dans `InstagramWatcherService.kt`, ajouter les imports :

```kotlin
import com.insta.reelsoff.data.AppDatabase
import com.insta.reelsoff.data.BlockEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
```

Ajouter le champ, à côté de `blocker` :

```kotlin
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

Dans `onDestroy()`, avant `super.onDestroy()` :

```kotlin
        scope.cancel()
```

Remplacer le bloc `if (decision.recordEpisode) { Log.i(...) }` par :

```kotlin
        if (decision.recordEpisode) {
            val event = BlockEvent(
                epochMillis = System.currentTimeMillis(),
                surface = classification.surface.name,
                ruleTier = decision.tier?.name ?: "UNKNOWN",
            )
            // Off the main thread: onAccessibilityEvent runs on it, and a disk
            // write in the hot path would show up as jank in Instagram itself.
            scope.launch {
                runCatching { AppDatabase.get(applicationContext).blockEventDao().insert(event) }
                    .onFailure { Log.e(TAG, "could not record episode", it) }
            }
            Log.i(TAG, "blocked ${classification.surface} via ${decision.tier}")
        }
```

- [ ] **Step 8: Vérifier que tout compile**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add app/
git commit -m "feat: record blocking episodes to room"
```

---

### Task 10: Réglages par surface

**Files:**
- Create: `app/src/main/kotlin/com/insta/reelsoff/data/SettingsStore.kt`
- Modify: `app/src/main/kotlin/com/insta/reelsoff/service/InstagramWatcherService.kt`
- Test: `app/src/androidTest/kotlin/com/insta/reelsoff/data/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: `Surface` (Task 1)
- Produces: `SettingsStore(context)` avec `val settings: Flow<BlockSettings>`, `suspend fun setBlockReels(enabled: Boolean)`, `suspend fun setBlockExplore(enabled: Boolean)`, et `BlockSettings(blockReels: Boolean = true, blockExplore: Boolean = true)` exposant `val blockedSurfaces: Set<Surface>`

- [ ] **Step 1: Écrire le test qui échoue**

`app/src/androidTest/kotlin/com/insta/reelsoff/data/SettingsStoreTest.kt`

```kotlin
package com.insta.reelsoff.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta.detection.Surface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SettingsStore(context)

    @Before
    fun reset() = runBlocking {
        store.setBlockReels(true)
        store.setBlockExplore(true)
    }

    @Test
    fun `both surfaces are blocked by default`() = runBlocking {
        val settings = store.settings.first()

        assertTrue(settings.blockReels)
        assertTrue(settings.blockExplore)
        assertEquals(setOf(Surface.REELS, Surface.EXPLORE), settings.blockedSurfaces)
    }

    @Test
    fun `disabling explore leaves reels blocked`() = runBlocking {
        store.setBlockExplore(false)

        val settings = store.settings.first()

        assertTrue(settings.blockReels)
        assertFalse(settings.blockExplore)
        assertEquals(setOf(Surface.REELS), settings.blockedSurfaces)
    }

    @Test
    fun `disabling both yields an empty set`() = runBlocking {
        store.setBlockReels(false)
        store.setBlockExplore(false)

        assertTrue(store.settings.first().blockedSurfaces.isEmpty())
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*SettingsStoreTest*'`
Expected: échec de compilation — `Unresolved reference: SettingsStore`

- [ ] **Step 3: Écrire le magasin de réglages**

`app/src/main/kotlin/com/insta/reelsoff/data/SettingsStore.kt`

```kotlin
package com.insta.reelsoff.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.insta.detection.Surface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class BlockSettings(
    val blockReels: Boolean = true,
    val blockExplore: Boolean = true,
) {
    val blockedSurfaces: Set<Surface>
        get() = buildSet {
            if (blockReels) add(Surface.REELS)
            if (blockExplore) add(Surface.EXPLORE)
        }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    val settings: Flow<BlockSettings> = context.dataStore.data.map { preferences ->
        BlockSettings(
            blockReels = preferences[BLOCK_REELS] ?: true,
            blockExplore = preferences[BLOCK_EXPLORE] ?: true,
        )
    }

    suspend fun setBlockReels(enabled: Boolean) {
        context.dataStore.edit { it[BLOCK_REELS] = enabled }
    }

    suspend fun setBlockExplore(enabled: Boolean) {
        context.dataStore.edit { it[BLOCK_EXPLORE] = enabled }
    }

    private companion object {
        val BLOCK_REELS = booleanPreferencesKey("block_reels")
        val BLOCK_EXPLORE = booleanPreferencesKey("block_explore")
    }
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*SettingsStoreTest*'`
Expected: PASS, 3 tests

- [ ] **Step 5: Faire suivre les réglages au service**

Dans `InstagramWatcherService.kt`, ajouter les imports :

```kotlin
import com.insta.reelsoff.data.BlockSettings
import com.insta.reelsoff.data.SettingsStore
import kotlinx.coroutines.flow.collectLatest
```

Ajouter le champ :

```kotlin
    @Volatile
    private var settings = BlockSettings()
```

Dans `onServiceConnected()`, après le chargement des règles :

```kotlin
        scope.launch {
            SettingsStore(applicationContext).settings.collectLatest { settings = it }
        }
```

Remplacer `blocker.decide(classification, BLOCKED_SURFACES)` par :

```kotlin
        val decision = blocker.decide(classification, settings.blockedSurfaces)
```

Supprimer la constante `BLOCKED_SURFACES` du `companion object`, et l'import `com.insta.detection.Surface` s'il n'est plus utilisé.

- [ ] **Step 6: Vérifier que tout compile**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/
git commit -m "feat: per-surface blocking settings"
```

---

### Task 11: Écran d'accueil

**Files:**
- Create: `app/src/main/kotlin/com/insta/reelsoff/ui/ServiceStatus.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/ui/HomeViewModel.kt`
- Create: `app/src/main/kotlin/com/insta/reelsoff/ui/HomeScreen.kt`
- Modify: `app/src/main/kotlin/com/insta/reelsoff/ui/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/com/insta/reelsoff/ui/DegradedDetectionTest.kt`

**Interfaces:**
- Consumes: `dailyCounts`, `DailyCount`, `BlockEvent`, `BlockEventDao`, `AppDatabase` (Task 9) ; `SettingsStore`, `BlockSettings` (Task 10) ; `Tier` (Task 2)
- Produces: `isServiceEnabled(context): Boolean`, `isDegraded(events: List<BlockEvent>): Boolean`, `HomeUiState`, `HomeViewModel`, `HomeScreen`

- [ ] **Step 1: Écrire le test de détection dégradée qui échoue**

`app/src/test/kotlin/com/insta/reelsoff/ui/DegradedDetectionTest.kt`

```kotlin
package com.insta.reelsoff.ui

import com.insta.detection.Tier
import com.insta.reelsoff.data.BlockEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DegradedDetectionTest {

    private fun event(tier: Tier) =
        BlockEvent(epochMillis = 0, surface = "REELS", ruleTier = tier.name)

    /**
     * Silence is not evidence of breakage: zero blocks may be exactly what
     * success looks like. Only the reported tier is an honest signal.
     */
    @Test
    fun `no events is not degraded`() {
        assertFalse(isDegraded(emptyList()))
    }

    @Test
    fun `high tier events are not degraded`() {
        assertFalse(isDegraded(listOf(event(Tier.HIGH), event(Tier.HIGH))))
    }

    @Test
    fun `a single high tier event among low ones is not degraded`() {
        assertFalse(isDegraded(listOf(event(Tier.LOW), event(Tier.HIGH), event(Tier.LOW))))
    }

    @Test
    fun `only low tier events is degraded`() {
        assertTrue(isDegraded(listOf(event(Tier.LOW), event(Tier.LOW))))
    }

    @Test
    fun `only medium tier events is degraded`() {
        assertTrue(isDegraded(listOf(event(Tier.MEDIUM))))
    }

    @Test
    fun `an unparseable tier is treated as degraded`() {
        assertTrue(isDegraded(listOf(BlockEvent(epochMillis = 0, surface = "REELS", ruleTier = "UNKNOWN"))))
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :app:testDebugUnitTest --tests '*DegradedDetectionTest*'`
Expected: échec de compilation — `Unresolved reference: isDegraded`

- [ ] **Step 3: Écrire l'état du service et la détection dégradée**

`app/src/main/kotlin/com/insta/reelsoff/ui/ServiceStatus.kt`

```kotlin
package com.insta.reelsoff.ui

import android.content.Context
import android.provider.Settings
import com.insta.detection.Tier
import com.insta.reelsoff.data.BlockEvent
import com.insta.reelsoff.service.InstagramWatcherService

/**
 * Reads the real state from the system rather than tracking it ourselves:
 * manufacturer task killers stop accessibility services without telling the
 * app, and a blocker that is silently off is worse than no blocker at all.
 */
fun isServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val name = "${context.packageName}/${InstagramWatcherService::class.java.name}"
    return enabled.split(':').any { it.equals(name, ignoreCase = true) }
}

/**
 * True when every recent block came from a fallback tier — the one honest
 * sign that Instagram changed and the rules need repair.
 */
fun isDegraded(events: List<BlockEvent>): Boolean =
    events.isNotEmpty() && events.none { it.ruleTier == Tier.HIGH.name }
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew :app:testDebugUnitTest --tests '*DegradedDetectionTest*'`
Expected: PASS, 6 tests

- [ ] **Step 5: Écrire le ViewModel**

`app/src/main/kotlin/com/insta/reelsoff/ui/HomeViewModel.kt`

```kotlin
package com.insta.reelsoff.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.insta.reelsoff.data.AppDatabase
import com.insta.reelsoff.data.BlockSettings
import com.insta.reelsoff.data.DailyCount
import com.insta.reelsoff.data.SettingsStore
import com.insta.reelsoff.data.dailyCounts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

const val HISTORY_DAYS = 14

data class HomeUiState(
    val serviceEnabled: Boolean = false,
    val settings: BlockSettings = BlockSettings(),
    val history: List<DailyCount> = emptyList(),
    val degraded: Boolean = false,
) {
    val todayReels: Int get() = history.lastOrNull()?.reels ?: 0
    val todayExplore: Int get() = history.lastOrNull()?.explore ?: 0
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val dao = AppDatabase.get(application).blockEventDao()
    private val serviceEnabled = MutableStateFlow(false)

    /** Called from onResume: the user leaves the app to flip the system toggle. */
    fun refreshServiceStatus() {
        serviceEnabled.value = isServiceEnabled(getApplication())
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val historySinceMillis: Long
        get() = LocalDate.now(zone)
            .minusDays((HISTORY_DAYS - 1).toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    val uiState: StateFlow<HomeUiState> = combine(
        serviceEnabled,
        settingsStore.settings,
        dao.observeSince(historySinceMillis),
    ) { enabled, settings, events ->
        HomeUiState(
            serviceEnabled = enabled,
            settings = settings,
            history = dailyCounts(events, zone, LocalDate.now(zone), HISTORY_DAYS),
            degraded = isDegraded(events),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setBlockReels(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBlockReels(enabled) }
    }

    fun setBlockExplore(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBlockExplore(enabled) }
    }
}
```

- [ ] **Step 6: Ajouter les textes**

Remplacer `app/src/main/res/values/strings.xml` par :

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Sans Reels</string>
    <string name="accessibility_service_description">Détecte l\'onglet Reels et la page Explore dans Instagram, et revient en arrière. Ne lit aucun texte et n\'envoie rien sur le réseau.</string>
    <string name="open_accessibility_settings">Ouvrir les réglages d\'accessibilité</string>
    <string name="start_capture">Capturer 60 secondes</string>
    <string name="capture_hint">Appuie, puis bascule vers Instagram et navigue : fil, Reels, Explore, profil, messages. Un instantané est enregistré toutes les 3 secondes.</string>
    <string name="service_on">Service actif</string>
    <string name="service_off">Service inactif — rien n\'est bloqué</string>
    <string name="battery_hint">Si le service se désactive tout seul, exempte l\'app de l\'optimisation de batterie dans les réglages Android.</string>
    <string name="today">Aujourd\'hui</string>
    <string name="reels">Reels</string>
    <string name="explore">Explore</string>
    <string name="block_reels">Bloquer l\'onglet Reels</string>
    <string name="block_explore">Bloquer Explore</string>
    <string name="history_title">14 derniers jours</string>
    <string name="degraded_warning">Détection dégradée : Instagram a probablement changé. Vérifie rules.json.</string>
</resources>
```

- [ ] **Step 7: Écrire l'écran**

`app/src/main/kotlin/com/insta/reelsoff/ui/HomeScreen.kt`

```kotlin
package com.insta.reelsoff.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.insta.reelsoff.R
import com.insta.reelsoff.data.DailyCount

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onStartCapture: () -> Unit,
    onBlockReelsChanged: (Boolean) -> Unit,
    onBlockExploreChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ServiceCard(state.serviceEnabled, onOpenAccessibilitySettings)

        if (state.degraded) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.degraded_warning),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        TodayCard(state.todayReels, state.todayExplore)

        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleMedium)
        History(state.history)

        SwitchRow(
            label = stringResource(R.string.block_reels),
            checked = state.settings.blockReels,
            onCheckedChange = onBlockReelsChanged,
        )
        SwitchRow(
            label = stringResource(R.string.block_explore),
            checked = state.settings.blockExplore,
            onCheckedChange = onBlockExploreChanged,
        )

        Button(onClick = onStartCapture, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.start_capture))
        }
        Text(stringResource(R.string.capture_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ServiceCard(enabled: Boolean, onOpenSettings: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(if (enabled) R.string.service_on else R.string.service_off),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!enabled) {
                Button(onClick = onOpenSettings) {
                    Text(stringResource(R.string.open_accessibility_settings))
                }
            }
            Text(stringResource(R.string.battery_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TodayCard(reels: Int, explore: Int) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.today), style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.reels)} : $reels")
            Text("${stringResource(R.string.explore)} : $explore")
        }
    }
}

@Composable
private fun History(history: List<DailyCount>) {
    val maximum = (history.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)

    Row(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        history.forEach { day ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((100.dp * day.total / maximum).coerceAtLeast(2.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(220.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
```

- [ ] **Step 8: Brancher l'activité**

Remplacer `app/src/main/kotlin/com/insta/reelsoff/ui/MainActivity.kt` par :

```kotlin
package com.insta.reelsoff.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insta.reelsoff.service.InstagramWatcherService

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    HomeScreen(
                        state = state,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onStartCapture = {
                            sendBroadcast(
                                Intent(InstagramWatcherService.ACTION_START_CAPTURE)
                                    .setPackage(packageName),
                            )
                        },
                        onBlockReelsChanged = viewModel::setBlockReels,
                        onBlockExploreChanged = viewModel::setBlockExplore,
                    )
                }
            }
        }
    }

    /** The accessibility toggle lives in system settings, so re-read on return. */
    override fun onResume() {
        super.onResume()
        viewModel.refreshServiceStatus()
    }
}
```

Ajouter la dépendance manquante à `app/build.gradle.kts`, dans le bloc `dependencies` :

```kotlin
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
```

- [ ] **Step 9: Vérifier que tout compile et que les tests passent**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add app/
git commit -m "feat: home screen with counters, history and per-surface switches"
```

---

### Task 12: Recette sur appareil

Aucun test unitaire ne prouve qu'Instagram se comporte comme une fixture d'il y a trois semaines. Cette tâche est la seule preuve que le projet marche.

**Files:**
- Create: `docs/superpowers/plans/2026-08-16-recette.md`

**Interfaces:**
- Consumes: l'app complète (Tasks 1 à 11)
- Produits: un compte rendu de recette daté, avec la version d'Instagram testée

- [ ] **Step 1: Installer une version propre**

```bash
adb uninstall com.insta.reelsoff || true
./gradlew :app:installDebug
adb shell dumpsys package com.instagram.android | grep versionName
```

Noter la version d'Instagram : c'est elle que les fixtures décrivent.

- [ ] **Step 2: Dérouler la recette**

Activer le service, puis vérifier chaque point et consigner le résultat :

1. Le fil d'actualité s'ouvre et défile normalement pendant deux minutes, sans aucune éjection. **Bloquant si échoue.**
2. Appuyer sur l'onglet Reels renvoie au fil en moins d'une seconde.
3. Appuyer sur Explore renvoie au fil.
4. Le profil, les messages et les stories restent accessibles.
5. Ouvrir Reels dix fois d'affilée n'enferme jamais le téléphone dans une boucle.
6. L'écran d'accueil affiche le bon compte du jour, et une seule unité par tentative.
7. Désactiver l'interrupteur Explore : Explore redevient accessible, Reels reste bloqué.
8. Redémarrer le téléphone, rouvrir Instagram : le blocage fonctionne toujours.
9. Tuer Instagram et le relancer directement sur l'onglet Reels : retour arrière puis, si l'écran persiste, sortie vers l'accueil — jamais de boucle.
10. Laisser le téléphone tourner une journée normale, puis vérifier que l'écran d'accueil indique toujours « Service actif ».

- [ ] **Step 3: Consigner les résultats**

Créer `docs/superpowers/plans/2026-08-16-recette.md` avec la version d'Instagram testée, la date, et le résultat de chacun des dix points. Pour chaque échec : ce qui a été observé, et si la cause est `rules.json` ou le code.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: device acceptance run"
```

---

## Après la recette

Si le point 1 échoue, la cause est presque toujours `requireSelected` ou un `VIEW_ID` présent hors de la barre du bas. Corriger `app/src/main/assets/rules.json`, ajouter la fixture fautive à `detection/src/test/resources/fixtures/`, et écrire le test qui échoue avant de corriger.

Quand une mise à jour d'Instagram cassera la détection — l'app l'annoncera par le bandeau dégradé — la réparation suit le même chemin : capturer, ajouter la fixture, ajuster `rules.json`. Le code n'a pas à changer. C'est précisément ce que le pilotage par données achète.

Le robot de résumé des Reels est un projet distinct, avec son propre cycle de conception. Le journal `block_event` fournira sa mesure de départ.
