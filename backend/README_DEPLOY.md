# EC2 배포 가이드 (Ubuntu 22.04)

## 가정
- 백엔드 경로: `/Users/james.king/AndroidStudioProjects/stock/backend`
- 서비스명: `stock-backend`
- Uvicorn 내부 바인딩: `127.0.0.1:8000` (외부 비공개)

## 1) 로컬에서 실행
```bash
cd /Users/james.king/AndroidStudioProjects/stock/backend
chmod +x scripts/deploy_ec2.sh scripts/ec2_bootstrap.sh
chmod 400 /mnt/data/stock-ec2-key.pem  # 없으면 본인 pem 경로 사용
./scripts/deploy_ec2.sh <EC2_HOST> /mnt/data/stock-ec2-key.pem ubuntu
```

도메인이 있을 때:
```bash
DOMAIN=api.yourdomain.com ./scripts/deploy_ec2.sh <EC2_HOST> /mnt/data/stock-ec2-key.pem ubuntu
```

> `deploy_ec2.sh`는 인자 없이 실행하면 EC2_HOST/키/도메인을 자동 탐색 시도합니다.

## 2) 자동 수행 내용
- rsync 업로드 (불필요 파일 제외)
- EC2 패키지 설치 (`python3-venv`, `nginx`, `certbot` 등)
- `.venv` 생성 + `requirements.txt` 설치
- `stock-backend.service` 생성/재시작
- Nginx reverse proxy 설정 (`/etc/nginx/sites-available/stock-api`)
- `/health` 검증 (`127.0.0.1:8000`, `127.0.0.1`)
- DOMAIN 설정 시 certbot HTTPS 시도

## 3) 운영 확인 명령
```bash
ssh -i <KEY> ubuntu@<EC2_HOST> 'sudo systemctl status stock-backend --no-pager'
ssh -i <KEY> ubuntu@<EC2_HOST> 'curl -sS http://127.0.0.1:8000/health'
ssh -i <KEY> ubuntu@<EC2_HOST> 'curl -sS http://127.0.0.1/health'
```

## 4) 장애 대응
- 서비스 로그:
```bash
sudo journalctl -u stock-backend -n 200 --no-pager
```
- Nginx 로그:
```bash
/var/log/nginx/access.log
/var/log/nginx/error.log
```
- Nginx 설정 점검:
```bash
sudo nginx -t && sudo systemctl reload nginx
```

## 5) 보안그룹 운영 기준
- 허용: `22`(내 IP만), `80`, `443`
- 차단: `8000` 외부 미공개

## APP_MODULE 오버라이드
FastAPI 엔트리포인트 자동 탐색이 실패하면 아래처럼 지정:
```bash
APP_MODULE=app.main:app ./scripts/deploy_ec2.sh <EC2_HOST> <SSH_KEY> ubuntu
```

자동매매/주문/브로커/계좌 기능은 본 배포에 포함하지 않습니다.
