package com.connor.mymap.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────
// MyMap 브랜드 팔레트 (남색 → 깊은 청록 → 청록)
// ──────────────────────────────────────────────
val BrandNavy = Color(0xFF0A2540)
val BrandDeep = Color(0xFF0E6E8C)
val BrandTeal = Color(0xFF16C9A6)

/** 아이콘·그래픽과 동일한 브랜드 그라데이션. 헤더·버튼·배경 등에 재사용. */
val BrandGradient = Brush.linearGradient(listOf(BrandNavy, BrandDeep, BrandTeal))

/** 진행 중 경로/현재 위치에 쓰는 트랙 블루 (지도 위 대비용). */
val TrackBlue = Color(0xFF1F6FEB)

// ── Light color roles ──────────────────────────
val LightPrimary = Color(0xFF0F8A7E)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFB6EFE5)
val LightOnPrimaryContainer = Color(0xFF00201B)
val LightSecondary = Color(0xFF0E6E8C)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFC2E9F5)
val LightOnSecondaryContainer = Color(0xFF001F29)
val LightTertiary = Color(0xFF35608A)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightBackground = Color(0xFFF5F7F9)
val LightOnBackground = Color(0xFF16242E)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF16242E)
val LightSurfaceVariant = Color(0xFFE6ECEF)
val LightOnSurfaceVariant = Color(0xFF46586A)
val LightOutline = Color(0xFF8B98A2)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)

// ── Dark color roles ───────────────────────────
val DarkPrimary = Color(0xFF5FDCC8)
val DarkOnPrimary = Color(0xFF003730)
val DarkPrimaryContainer = Color(0xFF005047)
val DarkOnPrimaryContainer = Color(0xFFB6EFE5)
val DarkSecondary = Color(0xFF7FD3EE)
val DarkOnSecondary = Color(0xFF003544)
val DarkSecondaryContainer = Color(0xFF004D61)
val DarkOnSecondaryContainer = Color(0xFFC2E9F5)
val DarkTertiary = Color(0xFFA1C9F8)
val DarkOnTertiary = Color(0xFF003258)
val DarkBackground = Color(0xFF0B1A24)
val DarkOnBackground = Color(0xFFDCE4E9)
val DarkSurface = Color(0xFF0F232F)
val DarkOnSurface = Color(0xFFDCE4E9)
val DarkSurfaceVariant = Color(0xFF324049)
val DarkOnSurfaceVariant = Color(0xFFB7C4CC)
val DarkOutline = Color(0xFF6B7C88)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
