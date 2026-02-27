# KoreaStockDash v1.1 Android

## Flavors
- `nofcmDebug` (default): Firebase 없이 100% 빌드/실행
- `fcmDebug`: `app/google-services.json` 존재 시 FCM 동작

## Run (USB 실폰)
```bash
adb reverse tcp:8000 tcp:8000
./gradlew :app:installNofcmDebug
```

## Key Screens
- 장전(게이트 ON/OFF + Top10 + 장투 + 공유)
- 장후(성적요약 + 개선 3개 + 공유)
- 알림(푸시/앱내 히스토리)
- 설정(서버주소, 게이트 N)

## Notes
- 서버 실패 시 Room 캐시 표시
- 공유는 `ACTION_SEND` Sharesheet(카톡 포함)
- 주문/자동매매 기능 없음
