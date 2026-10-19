package com.fabiosilva.ecosdeeldra.save

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sentinela de anúncios: os IDs reais e o aparelho de teste do dono ficam como estão. */
class AdConfigTest {
    private val gradle: String by lazy {
        listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() }.readText()
    }

    @Test
    fun realAdMobIdsAndTestDeviceStay() {
        assertTrue(gradle.contains("ca-app-pub-6598765502914364~3458194932"))
        assertTrue(gradle.contains("ca-app-pub-6598765502914364/5788844807"))
        assertTrue(gradle.contains("ca-app-pub-6598765502914364/6741641853"))
        assertTrue(gradle.contains("E812F36934272DE8BFD5D96B7E64BE83"))
    }

    @Test
    fun googleDemoIdsNeverAppear() = assertFalse(gradle.contains("3940256099942544"))

    @Test
    fun adIdsAreNotOverriddenPerBuildType() =
        assertFalse(Regex("""debug\s*\{[^}]*ADMOB""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(gradle))
}
