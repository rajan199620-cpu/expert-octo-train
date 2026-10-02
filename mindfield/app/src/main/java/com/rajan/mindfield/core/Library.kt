package com.rajan.mindfield.core

/**
 * The concept library, parsed from assets/concepts.txt.
 *
 * The file is written grouped by category, best "hook" first within each. The daily order deals
 * the categories round-robin (see [ROTATION]) so consecutive days never sit in one corner of
 * psychology, which is also interleaving, a reliable aid to learning to tell concepts apart.
 */
class Library(val all: List<Concept>) {
    private val byId = all.associateBy { it.id }

    operator fun get(id: String): Concept? = byId[id]

    fun contains(id: String) = id in byId

    val size: Int get() = all.size

    companion object {
        /** Day 1 is the frequency illusion: the effect this whole app runs on. */
        val ROTATION = listOf(
            Category.MEMORY, Category.SELF, Category.THINKING, Category.INFLUENCE, Category.FEELINGS,
            Category.CONNECTION, Category.DECISIONS, Category.HABITS, Category.GROUPS,
        )

        /** Parses and orders the library, or throws listing every problem in the file. */
        fun parse(text: String): Library {
            val parsed = ConceptParser.parse(text)
            require(parsed.errors.isEmpty()) { "concepts.txt has problems:\n" + parsed.errors.joinToString("\n") }
            return Library(interleave(parsed.concepts))
        }

        fun interleave(concepts: List<Concept>): List<Concept> {
            val queues = ROTATION.associateWith { c -> ArrayDeque(concepts.filter { it.category == c }) }
            val ordered = ArrayList<Concept>(concepts.size)
            while (queues.values.any { it.isNotEmpty() }) {
                for (c in ROTATION) queues.getValue(c).removeFirstOrNull()?.let { ordered += it }
            }
            return ordered.mapIndexed { i, c -> c.copy(number = i + 1) }
        }
    }
}

/**
 * Reads the plain-text library format:
 *
 * ```
 * ## concept-id
 * title: Anchoring
 * category: thinking
 * predict: The question?
 * - a wrong option
 * * the right option
 * ```
 *
 * A line that isn't `key: value` or an option continues the previous field, so long text can wrap.
 * Lines starting with a single `#` are comments.
 */
object ConceptParser {
    data class Result(val concepts: List<Concept>, val errors: List<String>)

    private val KEYS = setOf(
        "title", "aka", "category", "evidence", "hook", "what", "study", "proof", "spot", "use", "guard",
        "predict", "scenario", "source", "related",
    )
    private val REQUIRED = KEYS - setOf("aka", "related")
    private val FIELD = Regex("""^([a-z_]+):\s?(.*)$""")
    private val ID = Regex("""^[a-z0-9]+(-[a-z0-9]+)*$""")

    fun parse(text: String): Result {
        val concepts = ArrayList<Concept>()
        val errors = ArrayList<String>()
        var id: String? = null
        var startLine = 0
        var fields = LinkedHashMap<String, String>()
        var options = ArrayList<Pair<String, Boolean>>()
        var current: String? = null

        fun finish() {
            val cid = id ?: return
            build(cid, startLine, fields, options, errors)?.let { concepts += it }
        }

        text.lines().forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            val n = index + 1
            when {
                line.startsWith("## ") -> {
                    finish()
                    id = line.removePrefix("## ").trim()
                    startLine = n
                    fields = LinkedHashMap()
                    options = ArrayList()
                    current = null
                    if (!ID.matches(id!!)) errors += "line $n: bad id '$id' (lowercase words joined by hyphens)"
                }
                line.startsWith("#") -> Unit
                line.isBlank() -> current = null
                id == null -> errors += "line $n: text before the first '## id'"
                line.startsWith("- ") || line.startsWith("* ") -> {
                    if (fields["predict"] == null) errors += "line $n: option outside a predict question in '$id'"
                    options += Typography.smarten(line.substring(2).trim()) to line.startsWith("*")
                    current = OPTION
                }
                else -> {
                    val m = FIELD.find(line)
                    if (m != null && m.groupValues[1] in KEYS) {
                        val key = m.groupValues[1]
                        if (key in fields) errors += "line $n: '$key' given twice in '$id'"
                        fields[key] = Typography.smarten(m.groupValues[2].trim())
                        current = key
                    } else if (m != null && !line.startsWith(" ")) {
                        errors += "line $n: unknown field '${m.groupValues[1]}' in '$id'"
                    } else {
                        val key = current
                        when {
                            key == null -> errors += "line $n: stray text in '$id'"
                            key == OPTION -> {
                                val (t, right) = options.removeAt(options.lastIndex)
                                options += "$t ${Typography.smarten(line.trim())}" to right
                            }
                            else -> fields[key] = (fields.getValue(key) + " " + Typography.smarten(line.trim())).trim()
                        }
                    }
                }
            }
        }
        finish()

        concepts.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { errors += "duplicate id '$it'" }
        val ids = concepts.map { it.id }.toSet()
        for (c in concepts) for (r in c.related) {
            if (r !in ids) errors += "'${c.id}' is related to unknown id '$r'"
            if (r == c.id) errors += "'${c.id}' is related to itself"
        }
        return Result(concepts, errors)
    }

    private const val OPTION = "\u0000option"

    private fun build(
        id: String,
        line: Int,
        f: Map<String, String>,
        options: List<Pair<String, Boolean>>,
        errors: MutableList<String>,
    ): Concept? {
        val before = errors.size
        for (key in REQUIRED) if (f[key].isNullOrBlank()) errors += "'$id' (line $line) is missing '$key'"
        val category = f["category"]?.let { Category.of(it) }
        if (f["category"] != null && category == null) errors += "'$id' has unknown category '${f["category"]}'"
        val evidence = f["evidence"]?.let { Evidence.of(it) }
        if (f["evidence"] != null && evidence == null) errors += "'$id' has unknown evidence '${f["evidence"]}'"
        if (options.size !in 2..4) errors += "'$id' needs 2 to 4 predict options, has ${options.size}"
        if (options.count { it.second } != 1) errors += "'$id' needs exactly one right option (*)"
        if (errors.size > before || category == null || evidence == null) return null
        return Concept(
            id = id,
            number = 0,
            title = f.getValue("title"),
            aka = f["aka"]?.takeIf { it.isNotBlank() },
            category = category,
            evidence = evidence,
            hook = f.getValue("hook"),
            what = f.getValue("what"),
            study = f.getValue("study"),
            proof = f.getValue("proof"),
            spot = f.getValue("spot"),
            use = f.getValue("use"),
            guard = f.getValue("guard"),
            predict = Prediction(f.getValue("predict"), options.map { it.first }, options.indexOfFirst { it.second }),
            scenario = f.getValue("scenario"),
            source = f.getValue("source"),
            related = f["related"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}
