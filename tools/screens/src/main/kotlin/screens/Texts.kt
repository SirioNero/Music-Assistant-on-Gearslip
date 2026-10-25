package screens

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Le scritte dell'app dai veri strings.xml, con le regole dei plurali di ogni lingua. */
object Texts {
    private val res = System.getProperty("xonos.res") ?: "../../app/src/main/res"
    private var strings = emptyMap<String, String>()
    private var plurals = emptyMap<String, Map<String, String>>()

    /** La lingua delle scritte, come la cartella delle risorse: "it", "en", "zh"... */
    var language = ""
        set(value) {
            field = value
            val s = mutableMapOf<String, String>()
            val p = mutableMapOf<String, Map<String, String>>()
            load(File("$res/values/strings.xml"), s, p)
            if (value != "en") load(File("$res/values-$value/strings.xml"), s, p)
            strings = s
            plurals = p
        }

    fun string(id: Int): String = strings.getValue(name(id))

    fun plural(id: Int, count: Int): String {
        val forms = plurals.getValue(name(id))
        return forms[quantity(count)] ?: forms.getValue("other")
    }

    private fun name(id: Int) = RESOURCE_NAMES[id] ?: error("Risorsa sconosciuta: $id")

    private fun quantity(n: Int): String = when (language) {
        "ru" -> when {
            n % 10 == 1 && n % 100 != 11 -> "one"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
            else -> "many"
        }
        "zh" -> "other"
        "fr" -> if (n == 0 || n == 1) "one" else "other"
        else -> if (n == 1) "one" else "other"
    }

    private fun load(file: File, strings: MutableMap<String, String>, plurals: MutableMap<String, Map<String, String>>) {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = unescape(e.textContent)
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[e.getAttribute("name")] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
    }

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
}
