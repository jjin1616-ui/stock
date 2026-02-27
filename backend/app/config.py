from __future__ import annotations

import os
from dataclasses import dataclass


def _data_dir() -> str:
    return os.getenv("STOCK_DATA_DIR") or os.getenv("DATA_DIR") or "/var/lib/stock-backend"


def _database_url(data_dir: str) -> str:
    return os.getenv("DATABASE_URL") or f"sqlite:////{data_dir}/korea_stock_dash.db"


_DEFAULT_DATA_DIR = _data_dir()


@dataclass(frozen=True)
class Settings:
    app_tz: str = os.getenv("APP_TZ", "Asia/Seoul")
    data_dir: str = _DEFAULT_DATA_DIR
    database_url: str = _database_url(_DEFAULT_DATA_DIR)
    gate_lookback_trading_days: int = int(os.getenv("GATE_LOOKBACK_TRADING_DAYS", "20"))
    min_value_krw: float = float(os.getenv("MIN_VALUE_KRW", "5000000000"))
    min_trades: int = int(os.getenv("MIN_TRADES", "3"))
    long_min_value_krw: float = float(os.getenv("LONG_MIN_VALUE_KRW", "2000000000"))
    gate_universe_top_m: int = int(os.getenv("GATE_UNIVERSE_TOP_M", "200"))
    gate_min_trades_per_day: int = int(os.getenv("GATE_MIN_TRADES_PER_DAY", "10"))
    gate_threshold: float = float(os.getenv("GATE_THRESHOLD", "0.0"))
    gate_quantile: float | None = (
        float(os.getenv("GATE_QUANTILE")) if os.getenv("GATE_QUANTILE") not in (None, "", "none", "None") else None
    )
    firebase_admin_json: str = os.getenv("FIREBASE_ADMIN_JSON", "")
    krx_api_key: str = os.getenv("KRX_API_KEY", "")
    krx_endpoint_kospi: str = os.getenv("KRX_ENDPOINT_KOSPI", "stk_bydd_trd")
    krx_endpoint_kosdaq: str = os.getenv("KRX_ENDPOINT_KOSDAQ", "ksq_bydd_trd")
    krx_endpoint_konex: str = os.getenv("KRX_ENDPOINT_KONEX", "knx_bydd_trd")
    # "pykrx" (default) or "krx_per_day"
    krx_chart_mode: str = os.getenv("KRX_CHART_MODE", "pykrx")
    # Pre-warm / cache priming (precompute) for better UX.
    prewarm_enabled: bool = os.getenv("PREWARM_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    prewarm_premarket_hour: int = int(os.getenv("PREWARM_PREMARKET_HOUR", "8"))
    prewarm_premarket_minute: int = int(os.getenv("PREWARM_PREMARKET_MINUTE", "20"))
    prewarm_daytrade_limit: int = int(os.getenv("PREWARM_DAYTRADE_LIMIT", "10"))
    prewarm_longterm_limit: int = int(os.getenv("PREWARM_LONGTERM_LIMIT", "8"))
    auth_pepper: str = os.getenv("AUTH_PEPPER", "change-me-please")
    auth_token_ttl_hours: int = int(os.getenv("AUTH_TOKEN_TTL_HOURS", "168"))
    auth_refresh_token_ttl_hours: int = int(os.getenv("AUTH_REFRESH_TOKEN_TTL_HOURS", "720"))
    auth_lock_minutes: int = int(os.getenv("AUTH_LOCK_MINUTES", "15"))
    auth_max_failed: int = int(os.getenv("AUTH_MAX_FAILED", "5"))
    auth_ip_max_failed: int = int(os.getenv("AUTH_IP_MAX_FAILED", "20"))
    auth_ip_window_seconds: int = int(os.getenv("AUTH_IP_WINDOW_SECONDS", "300"))
    apk_dir: str = os.getenv("APK_DIR", "/var/www/stock/apk")
    ticker_tags_enabled: bool = os.getenv("TICKER_TAGS_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    ticker_tags_csv_path: str = os.getenv("TICKER_TAGS_CSV_PATH", os.path.join(_DEFAULT_DATA_DIR, "ticker_tags.csv"))
    ticker_tags_refresh_hour: int = int(os.getenv("TICKER_TAGS_REFRESH_HOUR", "6"))
    ticker_tags_refresh_minute: int = int(os.getenv("TICKER_TAGS_REFRESH_MINUTE", "0"))
    # Movers (급등주) semantics: avoid showing trivial +0~1% moves as "급등".
    movers_min_chg_pct: float = float(os.getenv("MOVERS_MIN_CHG_PCT", "3.0"))
    # SEC EDGAR requires an identifying User-Agent for fair-access.
    sec_user_agent: str = os.getenv("SEC_USER_AGENT", "MStockResearch/1.0 (contact: ops@example.com)")
    sec_timeout_sec: int = int(os.getenv("SEC_TIMEOUT_SEC", "12"))
    # US insider cache behavior (seconds).
    sec_cache_ttl_sec: int = int(os.getenv("SEC_CACHE_TTL_SEC", "600"))
    sec_cache_stale_sec: int = int(os.getenv("SEC_CACHE_STALE_SEC", "21600"))
    # Persisted cache reuse from reports table (survives process restart).
    sec_cache_db_enabled: bool = os.getenv("SEC_CACHE_DB_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    # GitHub adapter enrichment (sec-edgar-api).
    sec_github_enrich_enabled: bool = os.getenv("SEC_GITHUB_ENRICH_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    sec_github_enrich_cik_limit: int = int(os.getenv("SEC_GITHUB_ENRICH_CIK_LIMIT", "20"))
    sec_github_enrich_max_per_cik: int = int(os.getenv("SEC_GITHUB_ENRICH_MAX_PER_CIK", "3"))
    # SEC prewarm job (background cache fill for faster /market/us-insiders).
    sec_prewarm_enabled: bool = os.getenv("SEC_PREWARM_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    sec_prewarm_interval_min: int = int(os.getenv("SEC_PREWARM_INTERVAL_MIN", "20"))
    sec_prewarm_target_count: int = int(os.getenv("SEC_PREWARM_TARGET_COUNT", "10"))
    sec_prewarm_trading_days: int = int(os.getenv("SEC_PREWARM_TRADING_DAYS", "10"))
    sec_prewarm_expand_days: int = int(os.getenv("SEC_PREWARM_EXPAND_DAYS", "20"))
    sec_prewarm_max_candidates: int = int(os.getenv("SEC_PREWARM_MAX_CANDIDATES", "120"))

    # --- News (Hybrid: OpenDART + RSS + external ingest) ---
    news_enable_dart: bool = os.getenv("NEWS_ENABLE_DART", "1").lower() in ("1", "true", "yes", "on")
    news_enable_rss: bool = os.getenv("NEWS_ENABLE_RSS", "1").lower() in ("1", "true", "yes", "on")
    opendart_api_key: str = os.getenv("OPENDART_API_KEY", "")
    news_fetch_interval_s: int = int(os.getenv("NEWS_FETCH_INTERVAL_S", "300"))
    news_max_items_per_feed: int = int(os.getenv("NEWS_MAX_ITEMS_PER_FEED", "50"))
    news_default_tz: str = os.getenv("NEWS_DEFAULT_TZ", os.getenv("APP_TZ", "Asia/Seoul"))
    # Drop clearly unrelated RSS articles (society/sports/entertainment/noise).
    news_rss_market_relevance_filter: bool = os.getenv("NEWS_RSS_MARKET_RELEVANCE_FILTER", "1").lower() in ("1", "true", "yes", "on")
    # JSON list (either list[str url] or list[{"id": str, "url": str}]).
    news_rss_feeds: str = os.getenv("NEWS_RSS_FEEDS", "")
    # On ticker-specific MISS, supplement from Naver news search HTML.
    news_enable_naver_search_fallback: bool = os.getenv("NEWS_ENABLE_NAVER_SEARCH_FALLBACK", "1").lower() in ("1", "true", "yes", "on")
    # On ticker-specific MISS, supplement from Naver Finance discussion board.
    news_enable_naver_finance_community_fallback: bool = os.getenv("NEWS_ENABLE_NAVER_FINANCE_COMMUNITY_FALLBACK", "1").lower() in ("1", "true", "yes", "on")
    news_naver_finance_community_pages: int = int(os.getenv("NEWS_NAVER_FINANCE_COMMUNITY_PAGES", "2"))
    # Optional shared secret for /api/news/ingest (X-INGEST-TOKEN).
    news_ingest_token: str = os.getenv("NEWS_INGEST_TOKEN", "")
    # --- Auto-trade (KIS broker) ---
    kis_base_url_prod: str = os.getenv("KIS_BASE_URL_PROD", "https://openapi.koreainvestment.com:9443")
    kis_base_url_demo: str = os.getenv("KIS_BASE_URL_DEMO", "https://openapivts.koreainvestment.com:29443")
    kis_app_key_prod: str = os.getenv("KIS_APP_KEY_PROD", "")
    kis_app_secret_prod: str = os.getenv("KIS_APP_SECRET_PROD", "")
    kis_app_key_demo: str = os.getenv("KIS_APP_KEY_DEMO", "")
    kis_app_secret_demo: str = os.getenv("KIS_APP_SECRET_DEMO", "")
    kis_account_no: str = os.getenv("KIS_ACCOUNT_NO", "")
    kis_account_product_code: str = os.getenv("KIS_ACCOUNT_PRODUCT_CODE", "01")
    kis_trading_enabled: bool = os.getenv("KIS_TRADING_ENABLED", "false").lower() in ("1", "true", "yes", "on")
    credentials_crypto_secret: str = os.getenv("CREDENTIALS_CRYPTO_SECRET", os.getenv("AUTH_PEPPER", "change-me-please"))
    kis_order_timeout_sec: int = int(os.getenv("KIS_ORDER_TIMEOUT_SEC", "8"))
    autotrade_market_open_hour: int = int(os.getenv("AUTOTRADE_MARKET_OPEN_HOUR", "9"))
    autotrade_market_open_minute: int = int(os.getenv("AUTOTRADE_MARKET_OPEN_MINUTE", "0"))
    autotrade_market_close_hour: int = int(os.getenv("AUTOTRADE_MARKET_CLOSE_HOUR", "15"))
    autotrade_market_close_minute: int = int(os.getenv("AUTOTRADE_MARKET_CLOSE_MINUTE", "30"))
    autotrade_reservation_poll_sec: int = int(os.getenv("AUTOTRADE_RESERVATION_POLL_SEC", "20"))
    autotrade_reservation_batch_size: int = int(os.getenv("AUTOTRADE_RESERVATION_BATCH_SIZE", "30"))
    autotrade_engine_enabled: bool = os.getenv("AUTOTRADE_ENGINE_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    autotrade_engine_poll_sec: int = int(os.getenv("AUTOTRADE_ENGINE_POLL_SEC", "20"))
    autotrade_engine_exit_poll_sec: int = int(os.getenv("AUTOTRADE_ENGINE_EXIT_POLL_SEC", "5"))
    autotrade_engine_entry_poll_sec: int = int(os.getenv("AUTOTRADE_ENGINE_ENTRY_POLL_SEC", os.getenv("AUTOTRADE_ENGINE_POLL_SEC", "20")))
    autotrade_engine_batch_size: int = int(os.getenv("AUTOTRADE_ENGINE_BATCH_SIZE", "50"))
    autotrade_engine_user_cooldown_sec: int = int(os.getenv("AUTOTRADE_ENGINE_USER_COOLDOWN_SEC", "8"))
    autotrade_engine_exit_cooldown_sec: int = int(os.getenv("AUTOTRADE_ENGINE_EXIT_COOLDOWN_SEC", "3"))
    autotrade_engine_entry_cooldown_sec: int = int(os.getenv("AUTOTRADE_ENGINE_ENTRY_COOLDOWN_SEC", os.getenv("AUTOTRADE_ENGINE_USER_COOLDOWN_SEC", "8")))
    autotrade_pending_order_guard_sec: int = int(os.getenv("AUTOTRADE_PENDING_ORDER_GUARD_SEC", "300"))
    autotrade_push_enabled: bool = os.getenv("AUTOTRADE_PUSH_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    autotrade_push_failure_cooldown_sec: int = int(os.getenv("AUTOTRADE_PUSH_FAILURE_COOLDOWN_SEC", "600"))
    autotrade_push_success_cooldown_sec: int = int(os.getenv("AUTOTRADE_PUSH_SUCCESS_COOLDOWN_SEC", "120"))


settings = Settings()
