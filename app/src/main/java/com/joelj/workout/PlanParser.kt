package com.joelj.workout

/** Conservative, local parser for weekday headings and weekly running tables. Never executes PDF content. */
object PlanParser {
    private val days = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
    private val heading = Regex("^(${days.joinToString("|")})(?:\\s*,?\\s+(20\\d{2}-\\d{2}-\\d{2}|\\d{1,2}\\s+[A-Za-z]+(?:\\s+20\\d{2})?|[A-Za-z]+\\s+\\d{1,2}(?:,?\\s+20\\d{2})?))?\\s*[—–:-]\\s*(.+)$", RegexOption.IGNORE_CASE)
    private data class Section(val day:Int,val title:String,val body:MutableList<String>,val date:java.time.LocalDate?)
    private val bullet = Regex("^[\\s•●▪·\\u007f\\u0000-\\u0008]+")
    private val interval = Regex("^(\\d+)\\s+(\\d+\\s*[×x]\\s*\\d+(?:[.,]\\d+)?\\s*(?:km|m)|5\\s*K\\s*time trial)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val timer = Regex("(\\d{1,2}:\\d{2})\\s*/\\s*(\\d{1,2}:\\d{2})\\s*/\\s*(\\d{1,2}:\\d{2})\\s*/\\s*(\\d+)")
    private val distanceRow = Regex("^(\\d+)\\s+(\\d+(?:[.,]\\d+)?(?:\\s*[–-]\\s*\\d+(?:[.,]\\d+)?)?\\s*km)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val footer = Regex("(?i)^(?:.*training plan.*page\\s*\\d+|page\\s*\\d+)$")

    fun parse(raw: String, sourceName: String): Plan {
        require(raw.trim().length >= 25) { "Too little readable workout text was found. Try a clearer scan or another PDF." }
        if(Regex("(?im)^(?:This\\s+)?Week\\s*\\d*\\s*\\(").containsMatchIn(raw)||raw.lines().none{heading.matches(it.trim())}) {
            FlexiblePlanParser.parseOrNull(raw,sourceName)?.let{return it}
        }
        val lines = raw.replace('\u00a0', ' ').lines().map { it.trim().replace(Regex("[ \\t]+"), " ") }
            .filter { it.isNotEmpty() && !footer.matches(it) }
        val detectedYear=CalendarMapper.detect(raw,sourceName,1).from.year
        val sections = mutableListOf<Section>()
        for (line in lines) {
            val h = heading.matchEntire(line)
            if (h != null) {
                val date=h.groupValues[2].takeIf{it.isNotBlank()}?.let{CalendarMapper.parseDate(it,detectedYear)}
                sections += Section(date?.dayOfWeek?.value?:days.indexOf(h.groupValues[1].uppercase())+1,h.groupValues[3],mutableListOf(),date)
            } else sections.lastOrNull()?.body?.add(line.replace(bullet, ""))
        }
        require(sections.isNotEmpty()) { "No workout schedule could be identified. Try a clearer PDF with readable dates or weekday labels. Your existing plan has not been replaced." }
        val sessions = mutableListOf<Session>()
        val warnings = mutableListOf("Extraction is best effort. Compare each session with the original PDF before activating.")
        for ((day, originalTitle, allBody,date) in sections) {
            val title = originalTitle.substringBefore(" + INTERVAL APP", originalTitle).trim().lowercase().replaceFirstChar(Char::titlecase)
            // Global progression/benchmark material is not a session prescription.
            val body = allBody.takeWhile { !it.startsWith("Calisthenics Progression", true) && !it.startsWith("End-of-", true) }
            fun paragraph(prefix: String): String? {
                val index=body.indexOfFirst { it.startsWith(prefix,true) }
                if(index<0)return null
                val continuation=body.drop(index+1).takeWhile { !Regex("^[A-Za-z][A-Za-z -]*:").containsMatchIn(it) && !it.endsWith("execution rules",true) && !it.startsWith("Week ",true) }
                return (listOf(body[index])+continuation).joinToString(" ")
            }
            val kind = when {
                Regex("(?i)rest|recovery day").containsMatchIn(title) -> Kind.REST
                Regex("(?i)run|interval|time trial").containsMatchIn(title) -> Kind.RUN
                else -> Kind.STRENGTH
            }
            if (kind == Kind.RUN) {
                val intervals = body.mapNotNull(interval::matchEntire)
                val distances = body.mapNotNull(distanceRow::matchEntire)
                if (intervals.isNotEmpty()) {
                    for (row in intervals) {
                        val week = row.groupValues[1].toInt()
                        val workout = row.groupValues[2]
                        val rest = row.groupValues[3]
                        val timing = timer.find(rest)
                        val target = if (timing != null) rest.substring(0, timing.range.first).trim() else rest.substringBefore("No interval", rest).trim()
                        val total = Regex("[≈~]\\s*\\d+(?:[.,]\\d+)?(?:[–-]\\d+(?:[.,]\\d+)?)?\\s*km").find(rest)?.value
                        val facts = mutableListOf<Metric>()
                        val detail = mutableListOf<String>()
                        if (timing != null) {
                            val (prep, round, recovery, count) = timing.destructured
                            facts += Metric("Prepare", prep); facts += Metric("Recovery", recovery)
                            detail += "Workout: $workout; target $target."
                            detail += "Interval timer: preparation $prep / round $round / recovery $recovery / $count rounds."
                            val introIndex=body.indexOfFirst { it.startsWith("The interval app controls",true) }
                            if(introIndex>=0)detail += body.drop(introIndex).takeWhile { !it.equals("Week",true) && !it.startsWith("Week ",true) }.joinToString(" ")
                            // Only attach execution instructions that are actually present in the PDF.
                            paragraph("Warm-up:")?.let(detail::add)
                            paragraph("Cooldown:")?.let { detail += it; facts += Metric("Cool down", it.substringAfter(':').trim().substringBefore(" easy")) }
                            paragraph("Strava:")?.let(detail::add)
                            paragraph("Route:")?.let(detail::add)
                            if (week == 1) paragraph("Rep pacing:")?.let(detail::add)
                            facts += Metric("Timer round",round); facts += Metric("Rounds",count)
                            if(total!=null)facts += Metric("Est. total",total)
                            if (total != null) detail += "Estimated total: $total"
                        } else {
                            detail += "Workout: $workout; target $target."
                            detail += "No interval timer; use Strava/GPS."
                            if (total != null) detail += "Estimated total: $total"
                        }
                        sessions += Session(day = day, week = week, kind = kind, title = workout, target = target, details = detail, metrics = facts,date=date?.plusWeeks((week-1).toLong()))
                    }
                } else if (distances.isNotEmpty()) {
                    for (row in distances) {
                        val rest = row.groupValues[3]
                        val hydration = Regex("\\d+\\s*ml.*", RegexOption.IGNORE_CASE).find(rest)
                        val pace = hydration?.let { rest.substring(0, it.range.first).trim() } ?: rest
                        val distance = row.groupValues[2]
                        val detail = mutableListOf("Distance: $distance", "Target: $pace")
                        val metrics = mutableListOf(Metric("Distance", distance), Metric("Target", pace))
                        if (hydration != null) { detail += "Hydration: ${hydration.value}"; metrics += Metric("Hydration", hydration.value) }
                        val week=row.groupValues[1].toInt()
                        sessions += Session(day = day, week = week, kind = kind, title = title, target = "$distance · $pace", details = detail, metrics = metrics,date=date?.plusWeeks((week-1).toLong()))
                    }
                } else {
                    val content = body.filter { !it.equals("Week", true) }
                    sessions += Session(day = day, kind = kind, title = title, target = content.firstOrNull().orEmpty(), details = content,date=date)
                    warnings += "${days[day - 1].lowercase().replaceFirstChar(Char::titlecase)}: running details were kept as text. Review distance, pace, and interval steps."
                }
            } else {
                val detail = body.filter { it.isNotBlank() }
                if (kind == Kind.STRENGTH && detail.isEmpty()) warnings += "$title: no exercise prescriptions were detected; add details before activating."
                val count = detail.count { it.contains(':') && !it.startsWith("Rule", true) }
                sessions += Session(day = day, kind = kind, title = title, target = if (kind == Kind.REST) "No training scheduled" else "$count movements", details = detail,date=date)
            }
        }
        if (sessions.none { it.day == 7 } && Regex("(?i)Sunday\\s+(?:Complete\\s+)?Rest").containsMatchIn(raw)) {
            sessions += Session(day = 7, kind = Kind.REST, title = "Complete rest", target = "No training scheduled")
        }
        if (raw.contains("44:48") && raw.contains("02:30")) warnings += "Week 1: 44:48 includes six recovery blocks. The prose goes from rep six to cooldown. Confirm whether the final recovery is intended; the app does not resolve this automatically."
        val missing = (1..7).filter { d -> sessions.none { it.day == d } }
        if (missing.isNotEmpty()) warnings += "No sessions detected for ${missing.joinToString { days[it - 1].lowercase() }}. These days will show ‘No session scheduled’."
        val weeks = maxOf(1, sessions.maxOfOrNull { it.week } ?: 1)
        require(weeks <= 52 && sessions.size <= 400) { "This plan is too large. Import at most 52 weeks and 400 sessions." }
        require(sessions.any { it.kind != Kind.REST }) { "No training sessions were detected." }
        val detected=CalendarMapper.detect(raw,sourceName,weeks)
        val explicit=sessions.mapNotNull{it.date}
        val calendar=if(explicit.isNotEmpty()&&!detected.monthly)detected.copy(from=explicit.min(),through=explicit.max()) else detected
        val trainingWeeks=if(explicit.isNotEmpty()||(sessions.all{it.week==0}&&calendar.basis!=DateBasis.UNDATED_INFERRED))maxOf(weeks,java.time.temporal.ChronoUnit.WEEKS.between(calendar.anchor,calendar.through).toInt()+1) else weeks
        return Plan(sourceName.removeSuffix(".pdf").replace('_', ' '), trainingWeeks, sessions, warnings.distinct(),calendar.anchor,calendar)
    }
}

