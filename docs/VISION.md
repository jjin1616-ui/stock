# KoreaStockDash (Stock) — Vision & Operating Rules

This project is an Android client + FastAPI backend deployed on AWS EC2.
It is a stock dashboard + guarded auto-trading system (paper-first, KIS broker integration).

## Why This Exists
- Fast, consistent “one screen” premarket/daytrade/longterm reporting.
- Strict honesty: no fabricated prices/tickers; show data source explicitly.
- Cache-first backend with predictable performance characteristics.

## Non-Goals (Hard No)
- No client-side secret storage for broker credentials.
- No silent data fabrication when upstream is missing.

## Hard Rules (Single Source: `server_rules/RULES.md`)
- Single package install only (no multiple app variants installed on device).
- Auto-trade allowed only under explicit opt-in + risk guardrails (paper default).
- Backend is cache-first + queue; do not spawn per-request executors.
- UI must show `LIVE/CACHE/FALLBACK` source.
- Server data dir is fixed: `/var/lib/stock-backend`.

## Access Control (v1.1)
Single source: `server_rules/ACCESS_CONTROL_V1_1.md`

Core intent:
- Only invited users can log in.
- Server stores only hashes (no plaintext passwords/tokens).
- If `force_password_change=true`, block main APIs (reports/quotes/chart) until password change completes.
- Password change/reset/block must revoke all sessions immediately.
- Biometric is local token-unlock only (not a server auth substitute).
- Admin actions must be audit-logged.

Invite flow (high level):
1. MASTER creates invite (AUTO or MANUAL).
2. User installs APK, uses `user_code + initial_password` (first login).
3. User updates profile (optional) and MUST change password.
4. After password change, main APIs become accessible.

## Common UI Components (Shared Structure)
Location: `app/src/main/java/com/example/stock/ui/common/`
- `TopBars.kt`: app-wide top bar patterns.
- `BottomBar.kt`: shared bottom navigation (`TossBottomBar`).
- `HeaderComponents.kt`: shared report header card (`ReportHeaderCard`).
- `ReportComponents.kt`: shared list pipeline (`CommonReportList`, `CommonReportItemCard`, refresh logic, risk footer).
- `AuthComponents.kt`: shared auth screen building blocks.

Rule of thumb:
- If it affects list refresh/count, header/footer, or “card language”, it must be implemented in `ui/common` and used across tabs consistently.

## Distribution (APK) + Sharing
Goal:
- Always host the latest APK on the server, with a stable URL for installs.
- Keep versioned APKs for rollback/debugging.

Server URLs (nginx static):
- Latest: `/apk/app-latest.apk`
- Checksum: `/apk/app-latest.apk.sha256`
- Versioned files are also available under `/apk/` via directory listing.

Publish from your workstation:
```bash
scripts/publish_apk_ec2.sh
```

APK naming:
- Exported APKs are saved as:
  - `KoreaStockDash-v<versionName>(<versionCode>)-<APP_BUILD_LABEL>-<buildType>.apk`

Invite sharing:
- In Settings (MASTER), use “초대 공유” to open the Android share sheet (KakaoTalk works via share sheet).
- Default message shares APK link + `user_code`.
- Temp password is intentionally NOT included by default (1-time exposure rule).
