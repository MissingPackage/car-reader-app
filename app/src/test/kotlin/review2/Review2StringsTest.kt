package review2

import io.carreader.app.R
import io.carreader.app.ui.CoreText
import io.carreader.app.ui.TextRes
import io.carreader.brands.vag.VagEcus
import io.carreader.core.obd2.StandardPids
import io.carreader.core.uds.FailureTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class Review2StringsTest {
    private fun load(path: String): Map<String, String> {
        val f = listOf(File(path), File("app/$path")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent
        }
    }
    private val en by lazy { load("src/main/res/values/strings.xml") }
    private fun nameOf(id: Int): String = R.string::class.java.fields.first { f -> f.getInt(null) == id }.name
    private fun enText(res: TextRes): String {
        var s = en.getValue(nameOf(res.id))
        res.args.forEachIndexed { i, a -> s = s.replace("%${i + 1}\$s", if (a is TextRes) enText(a) else a.toString()) }
        return s.replace("\\'", "'")
    }

    @Test fun englishFailureTypeTextEqualsCore() {
        val bad = (0..0xFF).mapNotNull { v ->
            val core = FailureTypes.describe(v) ?: return@mapNotNull null
            val app = enText(CoreText.failureTypeRes(v)!!)
            if (app != core) "0x%02X core='%s' app='%s'".format(v, core, app) else null
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun englishEcuNamesEqualCore() {
        val bad = VagEcus.all.mapNotNull { e ->
            val app = enText(CoreText.ecuNameRes(e.code, e.name)!!)
            if (app != e.name) "${e.code} core='${e.name}' app='$app'" else null
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun englishPidNamesCloseToCore() {
        // Reported, not asserted strictly: names may be shortened on purpose.
        StandardPids.all.forEach { p ->
            val app = enText(CoreText.pidNameRes(p.key)!!)
            if (!app.equals(p.name, ignoreCase = true)) println("PID ${p.key} core='${p.name}' app='$app'")
        }
    }
}
