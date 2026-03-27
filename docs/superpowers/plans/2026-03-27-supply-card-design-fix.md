# 투자자 수급 카드 디자인 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** design-critique 검토 결과를 반영해 투자자 수급 카드의 색상 대비(WCAG AA), 가독성, 레이블 직관성을 개선한다.

**Architecture:** HomeScreen.kt 단일 파일 내 5개 지점을 수정한다. 새 파일 생성 없음. UpColor 상수 1곳, DivergingBarRows 2곳(금액 텍스트, 기준선), FlowSparklines3Col 1곳, InvestorFlowCard 1곳(동적 헤더).

**Tech Stack:** Android Kotlin, Jetpack Compose, java.time (coreLibraryDesugaring)

---

## 파일 맵

| 파일 | 수정 지점 | 라인 |
|---|---|---|
| `app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt` | `UpColor` 상수 | ~76 |
| 동일 | `DivergingBarRows` — 금액 fontSize, width | ~797-804 |
| 동일 | `DivergingBarRows` — 기준선 color, strokeWidth | ~790-795 |
| 동일 | `FlowSparklines3Col` — 금액 fontSize | ~833-838 |
| 동일 | `InvestorFlowCard` — 오늘 헤더 동적 표시 | ~718-719 |

---

## Task 1: UpColor 대비 수정 (WCAG AA 미달 해결)

**Files:**
- Modify: `app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt:76`

**배경:** `#E95A68` (대비비 ~3.8:1)은 WCAG AA 4.5:1 미달. `#D32F2F`는 ~5.1:1로 AA 통과.

- [ ] **Step 1: UpColor 수정**

`HomeScreen.kt` 76번째 줄:
```kotlin
// 변경 전
private val UpColor = Color(0xFFE95A68)

// 변경 후
private val UpColor = Color(0xFFD32F2F)
```

- [ ] **Step 2: 빌드 확인**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/james.king/AndroidStudioProjects/stock
JAVA_HOME="$JAVA_HOME" ./gradlew assembleDebug 2>&1 | grep -E "^e:|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
cd /Users/james.king/AndroidStudioProjects/stock
git add app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt
git commit -m "fix: UpColor #E95A68 → #D32F2F (WCAG AA 대비 4.5:1 확보)"
```

---

## Task 2: 금액 텍스트 가독성 개선 (fontSize + width)

**Files:**
- Modify: `app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt:797-804`

**배경:** 11sp는 Android 권장 최소(12sp) 미달. 68dp에서 `+5,832억` 7자가 잘릴 수 있음.

- [ ] **Step 1: DivergingBarRows 금액 텍스트 수정**

`HomeScreen.kt`의 `DivergingBarRows` 함수 내 금액 Text 블록:
```kotlin
// 변경 전
Text(
    text = displayText,
    color = barColor,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.End,
    modifier = Modifier.width(68.dp).padding(start = 6.dp),
)

// 변경 후
Text(
    text = displayText,
    color = barColor,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.End,
    modifier = Modifier.width(72.dp).padding(start = 6.dp),
)
```

- [ ] **Step 2: 빌드 확인**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/james.king/AndroidStudioProjects/stock
JAVA_HOME="$JAVA_HOME" ./gradlew assembleDebug 2>&1 | grep -E "^e:|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
cd /Users/james.king/AndroidStudioProjects/stock
git add app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt
git commit -m "fix: 수급 금액 폰트 11sp→12sp, 컨테이너 68dp→72dp (가독성/오버플로 개선)"
```

---

## Task 3: 중앙 기준선 강화

**Files:**
- Modify: `app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt:790-795`

**배경:** 기준선 `#CCCCCC` / 1.5px는 막대 배경 `#F0F1F4`와 대비가 너무 낮아 시각적으로 묻힘. 기준선이 약하면 diverging bar의 핵심인 양수/음수 구분이 흐려짐.

- [ ] **Step 1: 기준선 색상·두께 수정**

`HomeScreen.kt`의 `DivergingBarRows` 함수 내 Canvas 블록, 중앙 기준선 부분:
```kotlin
// 변경 전
drawLine(
    color = Color(0xFFCCCCCC),
    start = Offset(cx, 2f),
    end = Offset(cx, h - 2f),
    strokeWidth = 1.5f,
)

// 변경 후
drawLine(
    color = Color(0xFF999999),
    start = Offset(cx, 2f),
    end = Offset(cx, h - 2f),
    strokeWidth = 2f,
)
```

- [ ] **Step 2: 빌드 확인**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/james.king/AndroidStudioProjects/stock
JAVA_HOME="$JAVA_HOME" ./gradlew assembleDebug 2>&1 | grep -E "^e:|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
cd /Users/james.king/AndroidStudioProjects/stock
git add app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt
git commit -m "fix: diverging bar 중앙 기준선 #CCCCCC→#999999, 1.5px→2px (시인성 개선)"
```

---

## Task 4: 스파크라인 금액 폰트 개선

**Files:**
- Modify: `app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt:833-838`

**배경:** 10sp는 캡션 수준으로 금액 정보를 읽기에 너무 작음. 11sp로 최소 가독성 확보.

- [ ] **Step 1: FlowSparklines3Col 금액 텍스트 수정**

`HomeScreen.kt`의 `FlowSparklines3Col` 함수 내 Row 블록:
```kotlin
// 변경 전
Text(text = label, color = TextMuted, fontSize = 10.sp)
Text(
    text = displayText,
    color = if (isPositive) UpColor else DownColor,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
)

// 변경 후
Text(text = label, color = TextMuted, fontSize = 10.sp)
Text(
    text = displayText,
    color = if (isPositive) UpColor else DownColor,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
)
```

> 주의: 레이블(`label`)은 10sp 유지 — 스파크라인 컬럼이 좁으므로 레이블을 더 작게 두고 금액만 키우는 게 위계상 올바름.

- [ ] **Step 2: 빌드 확인**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/james.king/AndroidStudioProjects/stock
JAVA_HOME="$JAVA_HOME" ./gradlew assembleDebug 2>&1 | grep -E "^e:|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
cd /Users/james.king/AndroidStudioProjects/stock
git add app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt
git commit -m "fix: 스파크라인 금액 폰트 10sp→11sp (최소 가독성 확보)"
```

---

## Task 5: "오늘" 헤더 동적 날짜 표시

**Files:**
- Modify: `app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt:718-719`

**배경:** 장 마감 후에는 `dailyFlow.last()`가 전날 데이터. "오늘"로 고정 표시하면 사용자가 실시간인지 전일인지 알 수 없음. 날짜가 오늘이면 "오늘 (실시간)", 아니면 "MM/dd" 형식으로 표시.

**전제:** `java.time.LocalDate`는 `coreLibraryDesugaringEnabled = true` 로 이미 사용 가능. `HomeScreen.kt`에는 `java.time.LocalDate`, `java.time.ZoneId` import 없으므로 추가 필요.

- [ ] **Step 1: import 추가 확인**

`HomeScreen.kt` 상단 imports 영역에 아래가 없으면 추가:
```kotlin
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
```

> `grep "import java.time" app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt` 로 확인 후 없으면 추가.

- [ ] **Step 2: InvestorFlowCard 헤더 로직 수정**

`HomeScreen.kt`의 `InvestorFlowCard` 함수:
```kotlin
// 변경 전
if (todayFlow != null) {
    FlowSubHeader("오늘")
    DivergingBarRows(...)
}

// 변경 후
if (todayFlow != null) {
    val todayKst = LocalDate.now(ZoneId.of("Asia/Seoul"))
    val flowDate = runCatching { LocalDate.parse(todayFlow.date) }.getOrNull()
    val todayLabel = if (flowDate == todayKst) "오늘 (실시간)" else {
        flowDate?.format(DateTimeFormatter.ofPattern("MM/dd")) ?: todayFlow.date.takeLast(5).replace("-", "/")
    }
    FlowSubHeader(todayLabel)
    DivergingBarRows(
        entries = listOf(
            "외국인" to todayFlow.foreign,
            "기관" to todayFlow.institution,
            "개인" to todayFlow.individual,
        ),
        isValue = isValue,
    )
}
```

- [ ] **Step 3: 빌드 확인**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/james.king/AndroidStudioProjects/stock
JAVA_HOME="$JAVA_HOME" ./gradlew assembleDebug 2>&1 | grep -E "^e:|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
cd /Users/james.king/AndroidStudioProjects/stock
git add app/src/main/java/com/example/stock/ui/screens/HomeScreen.kt
git commit -m "feat: 수급 오늘 헤더 동적 표시 — 오늘이면 '오늘 (실시간)', 아니면 MM/dd"
```

---

## Task 6: 최종 배포 및 검증

**Files:**
- 없음 (빌드+배포 스크립트 실행)

- [ ] **Step 1: 최종 빌드 + EC2 배포**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/james.king/AndroidStudioProjects/stock
JAVA_HOME="$JAVA_HOME" ALLOW_FCM_DISABLED=true HOST=16.176.148.77 KEY_PATH=stock-ec2-key.pem VARIANT=debug scripts/publish_apk_ec2.sh 2>&1 | tail -10
```
Expected 출력 예시:
```
BUILD SUCCESSFUL in 15s
Publishing: app/build/outputs/apk/export/debug/mstock_v3.175(3007XX)_V3_7XX_debug.apk
OK
named:   http://16.176.148.77/apk/james._V3_7XX.apk
```

- [ ] **Step 2: install 페이지 버전 확인**

```bash
curl -s "http://16.176.148.77/apk/install" | grep -o 'V3_[0-9]*' | head -1
```
Expected: 방금 빌드된 버전 번호 (e.g. `V3_717`)

- [ ] **Step 3: GitHub push**

```bash
cd /Users/james.king/AndroidStudioProjects/stock
git log --oneline -5
git push origin main
```

- [ ] **Step 4: 사용자에게 결과 보고**

배포된 버전 번호와 변경 항목 5개를 한글로 요약 보고.

---

## Self-Review

### Spec 커버리지
| 항목 | 태스크 |
|---|---|
| UpColor WCAG AA 미달 | Task 1 ✅ |
| 금액 11sp→12sp | Task 2 ✅ |
| 금액 컨테이너 68dp→72dp | Task 2 ✅ |
| 스파크라인 금액 10sp→11sp | Task 4 ✅ |
| 기준선 색·두께 강화 | Task 3 ✅ |
| "오늘" 동적 레이블 | Task 5 ✅ |
| 배포 검증 | Task 6 ✅ |

### 타입 일관성
- `DivergingBarRows`: `entries: List<Pair<String, Long>>` — Task 2, 3 모두 동일 함수 수정, 충돌 없음
- `FlowSparklines3Col`: `dailyFlow: List<DailyFlow>` — Task 4만 수정, 충돌 없음
- `InvestorFlowCard`: `flow: InvestorFlowSummary` — Task 5만 수정, `todayFlow.date: String` 사용 (DailyFlowItemDto 정의와 일치)
