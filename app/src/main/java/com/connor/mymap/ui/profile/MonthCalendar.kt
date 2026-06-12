package com.connor.mymap.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.connor.mymap.domain.model.TrackingSession
import com.connor.mymap.util.Formats
import java.util.Calendar

/**
 * 달력에서 다루는 '하루'. 시각/분은 무시하고 연·월·일만 가진다.
 * month1은 1~12(1-based). rememberSaveable 직렬화를 위해 toInt()/fromInt()를 제공한다.
 */
data class CalendarDay(
    val year: Int,
    val month1: Int,
    val day: Int
) : Comparable<CalendarDay> {
    fun toInt(): Int = year * 10000 + month1 * 100 + day
    override fun compareTo(other: CalendarDay): Int = toInt().compareTo(other.toInt())

    companion object {
        fun fromInt(v: Int): CalendarDay = CalendarDay(v / 10000, (v / 100) % 100, v % 100)

        fun fromMillis(ms: Long): CalendarDay {
            val c = Calendar.getInstance().apply { timeInMillis = ms }
            return CalendarDay(
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH)
            )
        }

        fun today(): CalendarDay = fromMillis(System.currentTimeMillis())
    }
}

enum class CalendarSelectionMode { Single, Range }

/** 세션을 시작 시각 기준 '하루' 단위로 묶는다. */
fun List<TrackingSession>.bucketByDay(): Map<CalendarDay, List<TrackingSession>> =
    groupBy { CalendarDay.fromMillis(it.startedAtMillis) }

/**
 * 월 달력 격자.
 * - 기록 있는 날: 숫자 아래 점 마커
 * - 오늘: 외곽 링
 * - 단일 선택일 / 기간 시작·종료: primary 채움 원
 * - 기간 사이: 옅은 primary tint 원
 * 좌우 스와이프 또는 ←/→ 버튼으로 월 이동.
 */
@Composable
fun MonthCalendar(
    year: Int,
    month1: Int,
    today: CalendarDay,
    daysWithRecords: Set<CalendarDay>,
    selectionMode: CalendarSelectionMode,
    selectedDay: CalendarDay?,
    rangeStart: CalendarDay?,
    rangeEnd: CalendarDay?,
    onDayClick: (CalendarDay) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cells = remember(year, month1) { buildMonthCells(year, month1) }
    val weeks = remember(cells) { cells.chunked(7) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(year, month1) {
                val threshold = 56.dp.toPx()
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (dx <= -threshold) onNextMonth()
                        else if (dx >= threshold) onPrevMonth()
                    },
                    onHorizontalDrag = { _, amount -> dx += amount }
                )
            }
    ) {
        // 헤더: ← 2026년 6월 →
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 달")
            }
            Text(
                text = Formats.monthYear(year, month1),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNextMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 달")
            }
        }

        // 요일 라벨 (locale 기준, 일=빨강·토=파랑)
        val weekdayHeaders = remember { Formats.weekdayHeaders() }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            weekdayHeaders.forEach { (label, dow) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = weekdayColorForDow(dow),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    if (day == null) {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        DayCell(
                            day = day,
                            isToday = day == today,
                            hasRecord = day in daysWithRecords,
                            selectionMode = selectionMode,
                            selectedDay = selectedDay,
                            rangeStart = rangeStart,
                            rangeEnd = rangeEnd,
                            onClick = { onDayClick(day) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(
    day: CalendarDay,
    isToday: Boolean,
    hasRecord: Boolean,
    selectionMode: CalendarSelectionMode,
    selectedDay: CalendarDay?,
    rangeStart: CalendarDay?,
    rangeEnd: CalendarDay?,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    val isEndpoint = when (selectionMode) {
        CalendarSelectionMode.Single -> selectedDay != null && day == selectedDay
        CalendarSelectionMode.Range -> day == rangeStart || day == rangeEnd
    }
    val inRange = selectionMode == CalendarSelectionMode.Range &&
        rangeStart != null && rangeEnd != null &&
        day > minOf(rangeStart, rangeEnd) && day < maxOf(rangeStart, rangeEnd)

    val fillColor = when {
        isEndpoint -> scheme.primary
        inRange -> scheme.primary.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val textColor = when {
        isEndpoint -> scheme.onPrimary
        inRange -> scheme.primary
        hasRecord -> scheme.onSurface
        else -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val dotColor = if (isEndpoint) scheme.onPrimary else scheme.primary

    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // 채움 원 (선택/기간)
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(fillColor)
                .then(
                    if (isToday && !isEndpoint)
                        Modifier.border(1.5.dp, scheme.primary, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${day.day}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isEndpoint || isToday) FontWeight.Bold else FontWeight.Normal,
                    color = textColor
                )
                // 기록 마커 점
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (hasRecord) dotColor else Color.Transparent)
                )
            }
        }
    }
}

/** [단일] / [기간] 알약형 토글. */
@Composable
fun CalendarModeToggle(
    mode: CalendarSelectionMode,
    onChange: (CalendarSelectionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
    ) {
        ModeChip("단일", mode == CalendarSelectionMode.Single) {
            onChange(CalendarSelectionMode.Single)
        }
        ModeChip("기간", mode == CalendarSelectionMode.Range) {
            onChange(CalendarSelectionMode.Range)
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 달력 아래 한 줄 월 요약: "이번 달 N회 · 총 X.X km". */
@Composable
fun MonthSummary(count: Int, distanceText: String, modifier: Modifier = Modifier) {
    Text(
        text = if (count == 0) "이번 달 기록 없음" else "이번 달 ${count}회 · 총 $distanceText",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** "6월 12일 (금) · 오늘" / "Jun 12 (Fri) · Today" 형태의 날짜 헤더 라벨(locale). */
fun dayHeaderLabel(day: CalendarDay, today: CalendarDay): String =
    Formats.dayHeader(day.toMillis(), today.toMillis())

fun rangeHeaderLabel(start: CalendarDay, end: CalendarDay): String =
    Formats.rangeLabel(start.toMillis(), end.toMillis())

// ---- 내부 헬퍼 ----

private fun buildMonthCells(year: Int, month1: Int): List<CalendarDay?> {
    val cal = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month1 - 1)
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val firstDow = cal.get(Calendar.DAY_OF_WEEK) // 1=일요일
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val weekStart = Formats.firstDayOfWeek() // locale 주 시작 요일
    val leading = (firstDow - weekStart + 7) % 7

    return buildList {
        repeat(leading) { add(null) }
        for (d in 1..daysInMonth) add(CalendarDay(year, month1, d))
        while (size % 7 != 0) add(null)
    }
}

private fun CalendarDay.toMillis(): Long =
    Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month1 - 1)
        set(Calendar.DAY_OF_MONTH, day)
    }.timeInMillis

@Composable
private fun weekdayColorForDow(dow: Int): Color = when (dow) {
    Calendar.SUNDAY -> Color(0xFFD9534F) // 일요일 빨강
    Calendar.SATURDAY -> Color(0xFF1F6FEB) // 토요일 파랑
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
