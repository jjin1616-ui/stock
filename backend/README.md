# KoreaStockDash Backend

## API 서버 실행
```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
curl http://127.0.0.1:8000/health
```

## v1.3 리포트 생성 (1회 실행)
```bash
cd backend
python main.py --report_date 2026-02-05 --lookback 20 --risk ADAPTIVE --theme_cap 2 --out results/
```
- 생성 파일: `results/report_premarket.txt`, `results/daytrade_primary10.csv`, `results/daytrade_watch20.csv`, `results/longterm30.csv`, `results/gate_daily.csv`, `results/theme_map.csv`, `results/diagnostics.json`

## AWS EC2 배포 (Ubuntu)
```bash
cd backend
./scripts/deploy_ec2.sh <EC2_PUBLIC_IP_OR_DNS> <SSH_KEY_PATH> [ubuntu]
# 예: ./scripts/deploy_ec2.sh ec2-xx-xx-xx-xx.ap-northeast-2.compute.amazonaws.com ~/.ssh/stock.pem
```
- 보안그룹 인바운드: `8000/tcp` 허용 필요
- 앱 설정 화면의 서버 주소를 `http://<EC2_PUBLIC_IP_OR_DNS>:8000/` 로 변경

## 단타 엔진(v2) 검증 실행
```bash
cd backend
python run.py --end 2026-02-04 --lookback_days 120 --min_value 5e9 --out results/
# 다중 구간 비교: --lookbacks 60,120
```

## 결과 파일
- `results/kpi_grid.csv`
- `results/top10_daily.csv`
- `results/trade_log.csv`
- `results/gate_daily.csv`
- `results/summary.txt`

## 실시간 시세(읽기 전용)
- `GET /quotes/realtime?tickers=005930,000660`
- 우선순위: NAVER 실시간 조회 → 실패 시 FDR 일봉 fallback

자동매매/주문/계좌/브로커 실행 기능은 포함하지 않습니다.
