# KoreaStockDash v1.1

## Backend
```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## Android (USB 실폰)
```bash
adb reverse tcp:8000 tcp:8000
./gradlew :app:installNofcmDebug
```
