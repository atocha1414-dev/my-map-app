package com.connor.mymap.util

import android.text.format.DateUtils
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 글로벌 확장 대비: 거리/속도/날짜/달력 표기를 한곳에서 locale·단위계에 맞춰 포맷한다.
 *
 * 정책
 * - 거리/속도 단위계 기본값은 locale 국가에서 추론(미국·라이베리아·미얀마 = imperial, 그 외 metric).
 *   추후 설정(미터/마일 선택)이 생기면 [UnitSystem]을 인자로 넘기면 된다.
 * - 날짜·시간은 숫자 패턴을 고정해 어느 locale에서도 모호하지 않게 표기한다(연-월-일).
 * - 요일/월 이름, 주 시작 요일, 오늘/어제는 locale API로 현지화한다.
 *   (한국 locale에서는 기존과 동일하게 렌더된다.)
 */
enum class UnitSystem { METRIC, IMPERIAL }

object Formats {

    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084
    private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

    fun defaultUnitSystem(locale: Locale = Locale.getDefault()): UnitSystem =
        when (locale.country.uppercase(Locale.ROOT)) {
            "US", "LR", "MM" -> UnitSystem.IMPERIAL
            else -> UnitSystem.METRIC
        }

    /** 거리(미터)를 단위계·locale에 맞춰 표기. */
    fun distance(
        meters: Float,
        system: UnitSystem = defaultUnitSystem(),
        locale: Locale = Locale.getDefault()
    ): String = when (system) {
        UnitSystem.METRIC ->
            if (meters >= 1_000f) String.format(locale, "%.2f km", meters / 1_000f)
            else String.format(locale, "%d m", meters.toInt())
        UnitSystem.IMPERIAL -> {
            val miles = meters / METERS_PER_MILE
            if (miles >= 0.1) String.format(locale, "%.2f mi", miles)
            else String.format(locale, "%d ft", (meters * FEET_PER_METER).toInt())
        }
    }

    /** 속도(m/s)를 단위계·locale에 맞춰 표기. */
    fun speed(
        metersPerSecond: Float,
        system: UnitSystem = defaultUnitSystem(),
        locale: Locale = Locale.getDefault()
    ): String = when (system) {
        UnitSystem.METRIC -> String.format(locale, "%.1f km/h", metersPerSecond * 3.6f)
        UnitSystem.IMPERIAL -> String.format(locale, "%.1f mph", metersPerSecond * 2.236936f)
    }

    /** 경과 시간(숫자만). */
    fun duration(millis: Long, locale: Locale = Locale.getDefault()): String {
        val s = millis / 1_000L
        val h = s / 3_600L
        val m = (s % 3_600L) / 60L
        val sec = s % 60L
        return if (h > 0L) String.format(locale, "%d:%02d:%02d", h, m, sec)
        else String.format(locale, "%02d:%02d", m, sec)
    }

    /** 날짜+시간 (연-월-일 24시, 어느 locale에서도 동일·명확). */
    fun dateTime(millis: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("yyyy.MM.dd HH:mm", locale).format(Date(millis))

    // ───────── 달력용 (locale 현지화) ─────────

    /** 1=일요일 … 7=토요일. */
    fun firstDayOfWeek(locale: Locale = Locale.getDefault()): Int =
        Calendar.getInstance(locale).firstDayOfWeek

    /** firstDayOfWeek부터 시작하는 요일 7개의 (짧은 이름, 요일번호) 목록. */
    fun weekdayHeaders(locale: Locale = Locale.getDefault()): List<Pair<String, Int>> {
        val short = DateFormatSymbols(locale).shortWeekdays // [0]="" , 1=일 … 7=토
        val start = firstDayOfWeek(locale)
        return (0 until 7).map { i ->
            val dow = ((start - 1 + i) % 7) + 1
            short[dow] to dow
        }
    }

    /** "2026년 6월" / "June 2026" (locale). */
    fun monthYear(year: Int, month1: Int, locale: Locale = Locale.getDefault()): String {
        val cal = Calendar.getInstance().apply { clear(); set(year, month1 - 1, 1) }
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMM")
        return SimpleDateFormat(pattern, locale).format(cal.time)
    }

    /** 한 날짜의 머리글: "6월 12일 (금) · 오늘" / "Jun 12 (Fri) · Today". */
    fun dayHeader(dayStartMillis: Long, todayStartMillis: Long, locale: Locale = Locale.getDefault()): String {
        val date = Date(dayStartMillis)
        val mdPattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
        val md = SimpleDateFormat(mdPattern, locale).format(date)
        val weekday = SimpleDateFormat("EEE", locale).format(date)
        val base = "$md ($weekday)"
        val rel = relativeDayWord(dayStartMillis, todayStartMillis, locale)
        return if (rel != null) "$base · $rel" else base
    }

    /** "2026.6.7 ~ 2026.6.12" 형태의 기간 라벨(locale 월/일 순서 반영). */
    fun rangeLabel(startMillis: Long, endMillis: Long, locale: Locale = Locale.getDefault()): String {
        val lo = minOf(startMillis, endMillis)
        val hi = maxOf(startMillis, endMillis)
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd")
        val fmt = SimpleDateFormat(pattern, locale)
        return "${fmt.format(Date(lo))} ~ ${fmt.format(Date(hi))}"
    }

    /** 오늘/어제 단어(현지화), 그 외엔 null. 입력은 자정(day-start) 기준. */
    private fun relativeDayWord(dayStartMillis: Long, todayStartMillis: Long, locale: Locale): String? {
        val diffDays = (todayStartMillis - dayStartMillis) / MS_PER_DAY
        return if (diffDays == 0L || diffDays == 1L) {
            DateUtils.getRelativeTimeSpanString(
                dayStartMillis, todayStartMillis, DateUtils.DAY_IN_MILLIS, 0
            ).toString()
        } else null
    }
}
