package com.joelj.workout

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class Kind { STRENGTH, RUN, REST, OTHER }
data class Metric(val label: String, val value: String)
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val day: Int,
    val week: Int = 0,
    val kind: Kind,
    val title: String,
    val target: String = "",
    val details: List<String> = emptyList(),
    val metrics: List<Metric> = emptyList(),
    val date: LocalDate? = null,
    val sourcePages:List<Int> = emptyList()
) {
    val exercises get() = details.filter { it.contains(':') && Regex("(?i)(×|\\bx\\b|sets|reps|sec)").containsMatchIn(it) && !it.startsWith("Rule", true) && !it.startsWith("Session notes:",true) && !it.startsWith("Library note:",true) }
    fun toJson() = JSONObject().put("id", id).put("day", day).put("week", week).put("kind", kind.name)
        .put("title", title).put("target", target).put("details", JSONArray(details))
        .put("metrics", JSONArray(metrics.map { JSONObject().put("label", it.label).put("value", it.value) })).put("date",date?.toString()).put("sourcePages",JSONArray(sourcePages))
    companion object {
        fun fromJson(j: JSONObject) = Session(j.getString("id"), j.getInt("day"), j.optInt("week"), Kind.valueOf(j.getString("kind")),
            j.getString("title"), j.optString("target"), j.getJSONArray("details").strings(),
            j.getJSONArray("metrics").objects().map { Metric(it.getString("label"), it.getString("value")) },j.optString("date").takeIf{it.isNotBlank()&&it!="null"}?.let(LocalDate::parse),j.optJSONArray("sourcePages")?.let{a->(0 until a.length()).map{a.getInt(it)}}?:emptyList())
    }
}
data class Plan(val title: String, val weeks: Int, val sessions: List<Session>, val warnings: List<String>, val start: LocalDate? = null,val calendar:CalendarMapping?=null) {
    val anchor get()=calendar?.anchor?:start
    val firstDate get()=calendar?.from?:start
    val lastDate get()=calendar?.through?:start?.plusWeeks(weeks.toLong())?.minusDays(1)
    fun weekAt(date: LocalDate): Int = anchor?.let { Math.floorDiv(ChronoUnit.DAYS.between(it, date), 7).toInt() + 1 } ?: 0
    fun stateAt(date:LocalDate):PlanDayState {
        val first=firstDate;val last=lastDate
        if(first==null||date<first)return PlanDayState.UPCOMING
        if(last!=null&&date>last)return PlanDayState.FINISHED
        if(sessions.any{it.date==date})return PlanDayState.ACTIVE
        if(calendar!=null&&sessions.any{it.date==null&&it.week==0&&it.day==date.dayOfWeek.value})return PlanDayState.ACTIVE
        if(weekAt(date) !in 1..weeks)return PlanDayState.UNSPECIFIED
        return PlanDayState.ACTIVE
    }
    fun on(date: LocalDate): List<Session> {
        if(stateAt(date)!=PlanDayState.ACTIVE)return emptyList()
        val week = weekAt(date)
        return sessions.filter { it.date==date || (it.date==null&&it.day == date.dayOfWeek.value && ((it.week==0&&(calendar!=null||week in 1..weeks)) || (week in 1..weeks&&it.week == week))) }
    }
    fun nextAfter(date: LocalDate): Pair<LocalDate, Session>? {
        val first = firstDate ?: return null
        val end = lastDate ?: return null
        var next = maxOf(date.plusDays(1),first)
        while (next<=end) {
            on(next).firstOrNull { it.kind != Kind.REST }?.let { return next to it }
            next = next.plusDays(1)
        }
        return null
    }
    fun toJson() = JSONObject().put("schema", 2).put("title", title).put("weeks", weeks)
        .put("sessions", JSONArray(sessions.map { it.toJson() })).put("warnings", JSONArray(warnings)).put("start", start?.toString()).put("calendar",calendar?.toJson())
    companion object {
        fun fromJson(j: JSONObject): Plan {
            require(j.getInt("schema") in 1..2) { "Unsupported plan version" }
            return Plan(j.getString("title"), j.getInt("weeks"), j.getJSONArray("sessions").objects().map(Session::fromJson),
                j.getJSONArray("warnings").strings(), j.optString("start").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse),j.optJSONObject("calendar")?.let(CalendarMapping::fromJson))
        }
    }
}
internal fun JSONArray.strings() = (0 until length()).map { getString(it) }
internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

