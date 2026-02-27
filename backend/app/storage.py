from __future__ import annotations

from contextlib import contextmanager
import logging
import os

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from app.config import settings

logger = logging.getLogger("stock.storage")


def _ensure_sqlite_dir() -> None:
    if not settings.database_url.startswith("sqlite"):
        return
    raw = settings.database_url.replace("sqlite:///", "/", 1).replace("sqlite:////", "/", 1)
    db_path = raw if raw.startswith("/") else os.path.abspath(raw)
    db_dir = os.path.dirname(db_path)
    if db_dir and not os.path.exists(db_dir):
        os.makedirs(db_dir, exist_ok=True)


_ensure_sqlite_dir()
engine = create_engine(
    settings.database_url,
    future=True,
    connect_args={"check_same_thread": False} if settings.database_url.startswith("sqlite") else {},
)
# FastAPI 요청 처리에서 ORM 인스턴스를 세션 밖에서 참조하는 경우가 많으므로
# commit 시 자동 만료(expire_on_commit=True 기본값)를 꺼서 DetachedInstanceError를 방지한다.
SessionLocal = sessionmaker(
    bind=engine,
    autoflush=False,
    autocommit=False,
    expire_on_commit=False,
    future=True,
)


class Base(DeclarativeBase):
    pass


def init_db() -> None:
    from app import models

    # Safety-first default: never drop runtime DB unless explicitly requested.
    if settings.database_url.startswith("sqlite") and os.getenv("RESET_DB_ON_START", "false").lower() == "true":
        Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)


def ensure_schema() -> None:
    if not settings.database_url.startswith("sqlite"):
        return
    from sqlalchemy import text

    with engine.connect() as conn:
        # ensure strategy_settings table
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS strategy_settings (
                  id INTEGER PRIMARY KEY,
                  algo_version TEXT NOT NULL DEFAULT 'V2',
                  risk_preset TEXT NOT NULL DEFAULT 'ADAPTIVE',
                  use_custom_weights INTEGER NOT NULL DEFAULT 0,
                  w_ta REAL,
                  w_re REAL,
                  w_rs REAL,
                  theme_cap INTEGER NOT NULL DEFAULT 2,
                  max_gap_pct REAL NOT NULL DEFAULT 0.0,
                  gate_threshold REAL NOT NULL DEFAULT 0.0,
                  gate_quantile REAL,
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        strategy_cols = [r[1] for r in conn.execute(text("PRAGMA table_info(strategy_settings)")).fetchall()]
        if "algo_version" not in strategy_cols:
            conn.execute(text("ALTER TABLE strategy_settings ADD COLUMN algo_version TEXT NOT NULL DEFAULT 'V2'"))
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS users (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_code TEXT NOT NULL,
                  role TEXT NOT NULL DEFAULT 'USER',
                  status TEXT NOT NULL DEFAULT 'active',
                  password_hash TEXT NOT NULL,
                  force_password_change INTEGER NOT NULL DEFAULT 1,
                  invite_status TEXT NOT NULL DEFAULT 'CREATED',
                  expires_at TEXT,
                  memo TEXT,
                  name TEXT,
                  phone TEXT,
                  failed_attempts INTEGER NOT NULL DEFAULT 0,
                  locked_until TEXT,
                  last_login_at TEXT,
                  device_binding_enabled INTEGER NOT NULL DEFAULT 0,
                  bound_device_id TEXT,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS session_tokens (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  token_hash TEXT NOT NULL,
                  issued_at TEXT NOT NULL,
                  expires_at TEXT NOT NULL,
                  revoked_at TEXT,
                  device_id TEXT,
                  app_version TEXT,
                  ip TEXT,
                  user_agent TEXT
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS refresh_tokens (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  session_id INTEGER,
                  token_hash TEXT NOT NULL,
                  issued_at TEXT NOT NULL,
                  expires_at TEXT NOT NULL,
                  revoked_at TEXT,
                  device_id TEXT,
                  app_version TEXT,
                  ip TEXT,
                  user_agent TEXT
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS user_menu_permissions (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  menu_daytrade INTEGER NOT NULL DEFAULT 1,
                  menu_autotrade INTEGER NOT NULL DEFAULT 1,
                  menu_holdings INTEGER NOT NULL DEFAULT 1,
                  menu_supply INTEGER NOT NULL DEFAULT 1,
                  menu_movers INTEGER NOT NULL DEFAULT 1,
                  menu_us INTEGER NOT NULL DEFAULT 1,
                  menu_news INTEGER NOT NULL DEFAULT 1,
                  menu_longterm INTEGER NOT NULL DEFAULT 1,
                  menu_papers INTEGER NOT NULL DEFAULT 1,
                  menu_eod INTEGER NOT NULL DEFAULT 1,
                  menu_alerts INTEGER NOT NULL DEFAULT 1,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS login_events (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  ts TEXT NOT NULL,
                  user_id INTEGER,
                  user_code TEXT,
                  result TEXT NOT NULL,
                  reason_code TEXT NOT NULL,
                  ip TEXT,
                  device_id TEXT,
                  app_version TEXT
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS admin_audit_logs (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  ts TEXT NOT NULL,
                  admin_user_id INTEGER NOT NULL,
                  action TEXT NOT NULL,
                  detail_json TEXT
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS ticker_tags (
                  ticker TEXT PRIMARY KEY,
                  tags_json TEXT NOT NULL DEFAULT '[]',
                  source TEXT NOT NULL DEFAULT 'manual',
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS user_favorites (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  ticker TEXT NOT NULL,
                  name TEXT,
                  baseline_price REAL NOT NULL,
                  favorited_at TEXT NOT NULL,
                  source_tab TEXT,
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS user_broker_credentials (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  use_user_credentials INTEGER NOT NULL DEFAULT 0,
                  app_key_demo_enc TEXT,
                  app_secret_demo_enc TEXT,
                  app_key_prod_enc TEXT,
                  app_secret_prod_enc TEXT,
                  account_no_demo_enc TEXT,
                  account_no_prod_enc TEXT,
                  account_no_enc TEXT,
                  account_product_code_demo TEXT NOT NULL DEFAULT '01',
                  account_product_code_prod TEXT NOT NULL DEFAULT '01',
                  account_product_code TEXT NOT NULL DEFAULT '01',
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS autotrade_settings (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  enabled INTEGER NOT NULL DEFAULT 0,
                  environment TEXT NOT NULL DEFAULT 'paper',
                  include_daytrade INTEGER NOT NULL DEFAULT 1,
                  include_movers INTEGER NOT NULL DEFAULT 1,
                  include_supply INTEGER NOT NULL DEFAULT 1,
                  include_papers INTEGER NOT NULL DEFAULT 1,
                  include_longterm INTEGER NOT NULL DEFAULT 1,
                  include_favorites INTEGER NOT NULL DEFAULT 1,
                  order_budget_krw REAL NOT NULL DEFAULT 200000.0,
                  max_orders_per_run INTEGER NOT NULL DEFAULT 5,
                  max_daily_loss_pct REAL NOT NULL DEFAULT 3.0,
                  seed_krw REAL NOT NULL DEFAULT 10000000.0,
                  take_profit_pct REAL NOT NULL DEFAULT 7.0,
                  stop_loss_pct REAL NOT NULL DEFAULT 5.0,
                  stoploss_reentry_policy TEXT NOT NULL DEFAULT 'cooldown',
                  stoploss_reentry_cooldown_min INTEGER NOT NULL DEFAULT 30,
                  takeprofit_reentry_policy TEXT NOT NULL DEFAULT 'cooldown',
                  takeprofit_reentry_cooldown_min INTEGER NOT NULL DEFAULT 30,
                  allow_market_order INTEGER NOT NULL DEFAULT 0,
                  offhours_reservation_enabled INTEGER NOT NULL DEFAULT 1,
                  offhours_reservation_mode TEXT NOT NULL DEFAULT 'auto',
                  offhours_confirm_timeout_min INTEGER NOT NULL DEFAULT 3,
                  offhours_confirm_timeout_action TEXT NOT NULL DEFAULT 'cancel',
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS autotrade_orders (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  run_id TEXT NOT NULL,
                  source_tab TEXT NOT NULL,
                  ticker TEXT NOT NULL,
                  name TEXT,
                  side TEXT NOT NULL DEFAULT 'BUY',
                  qty INTEGER NOT NULL DEFAULT 0,
                  requested_price REAL NOT NULL DEFAULT 0.0,
                  filled_price REAL,
                  current_price REAL,
                  pnl_pct REAL,
                  status TEXT NOT NULL DEFAULT 'SKIPPED',
                  broker_order_no TEXT,
                  reason TEXT,
                  metadata_json TEXT,
                  requested_at TEXT NOT NULL,
                  filled_at TEXT
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS autotrade_symbol_rules (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  ticker TEXT NOT NULL,
                  name TEXT,
                  take_profit_pct REAL NOT NULL DEFAULT 7.0,
                  stop_loss_pct REAL NOT NULL DEFAULT 5.0,
                  enabled INTEGER NOT NULL DEFAULT 1,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS autotrade_daily_metrics (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  ymd TEXT NOT NULL,
                  orders_total INTEGER NOT NULL DEFAULT 0,
                  filled_total INTEGER NOT NULL DEFAULT 0,
                  buy_amount_krw REAL NOT NULL DEFAULT 0.0,
                  eval_amount_krw REAL NOT NULL DEFAULT 0.0,
                  realized_pnl_krw REAL NOT NULL DEFAULT 0.0,
                  unrealized_pnl_krw REAL NOT NULL DEFAULT 0.0,
                  roi_pct REAL NOT NULL DEFAULT 0.0,
                  win_rate REAL NOT NULL DEFAULT 0.0,
                  mdd_pct REAL NOT NULL DEFAULT 0.0,
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS autotrade_reentry_blocks (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  environment TEXT NOT NULL DEFAULT 'demo',
                  ticker TEXT NOT NULL,
                  trigger_reason TEXT NOT NULL,
                  is_active INTEGER NOT NULL DEFAULT 1,
                  blocked_at TEXT NOT NULL,
                  released_at TEXT,
                  note TEXT
                )
                """
            )
        )
        conn.execute(
            text(
                "CREATE INDEX IF NOT EXISTS idx_autotrade_reentry_blocks_user "
                "ON autotrade_reentry_blocks(user_id, environment, trigger_reason, is_active)"
            )
        )
        conn.execute(
            text(
                "CREATE INDEX IF NOT EXISTS idx_autotrade_reentry_blocks_user_ticker "
                "ON autotrade_reentry_blocks(user_id, ticker, environment, is_active)"
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS autotrade_reservations (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  environment TEXT NOT NULL DEFAULT 'demo',
                  mode TEXT NOT NULL DEFAULT 'auto',
                  status TEXT NOT NULL DEFAULT 'QUEUED',
                  requested_at TEXT NOT NULL,
                  execute_at TEXT NOT NULL,
                  confirm_deadline_at TEXT,
                  timeout_action TEXT NOT NULL DEFAULT 'cancel',
                  payload_json TEXT,
                  reason_code TEXT,
                  reason_message TEXT,
                  result_run_id TEXT,
                  result_json TEXT,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_user_code ON users(user_code)"))
        conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_refresh_tokens_token_hash ON refresh_tokens(token_hash)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_refresh_tokens_session_id ON refresh_tokens(session_id)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at ON refresh_tokens(expires_at)"))
        conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_user_favorites_user_ticker ON user_favorites(user_id, ticker)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_user_favorites_user_id ON user_favorites(user_id)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_user_favorites_favorited_at ON user_favorites(favorited_at)"))
        conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_user_broker_credentials_user ON user_broker_credentials(user_id)"))
        conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_user_menu_permissions_user ON user_menu_permissions(user_id)"))
        conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_autotrade_settings_user ON autotrade_settings(user_id)"))
        conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_autotrade_symbol_rule_user_ticker ON autotrade_symbol_rules(user_id, ticker)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_autotrade_symbol_rule_user ON autotrade_symbol_rules(user_id)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_autotrade_orders_user_requested ON autotrade_orders(user_id, requested_at)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_autotrade_orders_user_ticker ON autotrade_orders(user_id, ticker)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_autotrade_reservations_user_requested ON autotrade_reservations(user_id, requested_at)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_autotrade_reservations_status_execute ON autotrade_reservations(status, execute_at)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_autotrade_reservations_result_run_id ON autotrade_reservations(result_run_id)"))
        conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_autotrade_daily_user_ymd ON autotrade_daily_metrics(user_id, ymd)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS idx_autotrade_daily_ymd ON autotrade_daily_metrics(ymd)"))
        broker_cols = [r[1] for r in conn.execute(text("PRAGMA table_info(user_broker_credentials)")).fetchall()]
        if "use_user_credentials" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN use_user_credentials INTEGER NOT NULL DEFAULT 0"))
        if "app_key_demo_enc" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN app_key_demo_enc TEXT"))
        if "app_secret_demo_enc" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN app_secret_demo_enc TEXT"))
        if "app_key_prod_enc" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN app_key_prod_enc TEXT"))
        if "app_secret_prod_enc" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN app_secret_prod_enc TEXT"))
        if "account_no_demo_enc" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN account_no_demo_enc TEXT"))
        if "account_no_prod_enc" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN account_no_prod_enc TEXT"))
        if "account_no_enc" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN account_no_enc TEXT"))
        if "account_product_code_demo" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN account_product_code_demo TEXT NOT NULL DEFAULT '01'"))
        if "account_product_code_prod" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN account_product_code_prod TEXT NOT NULL DEFAULT '01'"))
        if "account_product_code" not in broker_cols:
            conn.execute(text("ALTER TABLE user_broker_credentials ADD COLUMN account_product_code TEXT NOT NULL DEFAULT '01'"))
        menu_cols = [r[1] for r in conn.execute(text("PRAGMA table_info(user_menu_permissions)")).fetchall()]
        if "menu_daytrade" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_daytrade INTEGER NOT NULL DEFAULT 1"))
        if "menu_autotrade" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_autotrade INTEGER NOT NULL DEFAULT 1"))
        if "menu_holdings" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_holdings INTEGER NOT NULL DEFAULT 1"))
        if "menu_supply" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_supply INTEGER NOT NULL DEFAULT 1"))
        if "menu_movers" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_movers INTEGER NOT NULL DEFAULT 1"))
        if "menu_us" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_us INTEGER NOT NULL DEFAULT 1"))
        if "menu_news" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_news INTEGER NOT NULL DEFAULT 1"))
        if "menu_longterm" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_longterm INTEGER NOT NULL DEFAULT 1"))
        if "menu_papers" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_papers INTEGER NOT NULL DEFAULT 1"))
        if "menu_eod" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_eod INTEGER NOT NULL DEFAULT 1"))
        if "menu_alerts" not in menu_cols:
            conn.execute(text("ALTER TABLE user_menu_permissions ADD COLUMN menu_alerts INTEGER NOT NULL DEFAULT 1"))
        autotrade_cols = [r[1] for r in conn.execute(text("PRAGMA table_info(autotrade_settings)")).fetchall()]
        if "include_longterm" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN include_longterm INTEGER NOT NULL DEFAULT 1"))
        if "include_supply" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN include_supply INTEGER NOT NULL DEFAULT 1"))
        if "seed_krw" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN seed_krw REAL NOT NULL DEFAULT 10000000.0"))
        if "take_profit_pct" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN take_profit_pct REAL NOT NULL DEFAULT 7.0"))
        if "stop_loss_pct" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN stop_loss_pct REAL NOT NULL DEFAULT 5.0"))
        if "stoploss_reentry_policy" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN stoploss_reentry_policy TEXT NOT NULL DEFAULT 'cooldown'"))
        if "stoploss_reentry_cooldown_min" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN stoploss_reentry_cooldown_min INTEGER NOT NULL DEFAULT 30"))
        if "takeprofit_reentry_policy" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN takeprofit_reentry_policy TEXT NOT NULL DEFAULT 'cooldown'"))
        if "takeprofit_reentry_cooldown_min" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN takeprofit_reentry_cooldown_min INTEGER NOT NULL DEFAULT 30"))
        if "offhours_reservation_enabled" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN offhours_reservation_enabled INTEGER NOT NULL DEFAULT 1"))
        if "offhours_reservation_mode" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN offhours_reservation_mode TEXT NOT NULL DEFAULT 'auto'"))
        if "offhours_confirm_timeout_min" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN offhours_confirm_timeout_min INTEGER NOT NULL DEFAULT 3"))
        if "offhours_confirm_timeout_action" not in autotrade_cols:
            conn.execute(text("ALTER TABLE autotrade_settings ADD COLUMN offhours_confirm_timeout_action TEXT NOT NULL DEFAULT 'cancel'"))
        reentry_cols = [r[1] for r in conn.execute(text("PRAGMA table_info(autotrade_reentry_blocks)")).fetchall()]
        if reentry_cols:
            if "environment" not in reentry_cols:
                conn.execute(text("ALTER TABLE autotrade_reentry_blocks ADD COLUMN environment TEXT NOT NULL DEFAULT 'demo'"))
            if "trigger_reason" not in reentry_cols:
                conn.execute(text("ALTER TABLE autotrade_reentry_blocks ADD COLUMN trigger_reason TEXT NOT NULL DEFAULT 'STOP_LOSS'"))
            if "is_active" not in reentry_cols:
                conn.execute(text("ALTER TABLE autotrade_reentry_blocks ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1"))
            if "blocked_at" not in reentry_cols:
                conn.execute(text("ALTER TABLE autotrade_reentry_blocks ADD COLUMN blocked_at TEXT"))
            if "released_at" not in reentry_cols:
                conn.execute(text("ALTER TABLE autotrade_reentry_blocks ADD COLUMN released_at TEXT"))
            if "note" not in reentry_cols:
                conn.execute(text("ALTER TABLE autotrade_reentry_blocks ADD COLUMN note TEXT"))
            conn.execute(
                text(
                    "CREATE INDEX IF NOT EXISTS idx_autotrade_reentry_blocks_user "
                    "ON autotrade_reentry_blocks(user_id, environment, trigger_reason, is_active)"
                )
            )
            conn.execute(
                text(
                    "CREATE INDEX IF NOT EXISTS idx_autotrade_reentry_blocks_user_ticker "
                    "ON autotrade_reentry_blocks(user_id, ticker, environment, is_active)"
                )
            )
        symbol_rule_cols = [r[1] for r in conn.execute(text("PRAGMA table_info(autotrade_symbol_rules)")).fetchall()]
        if symbol_rule_cols:
            if "name" not in symbol_rule_cols:
                conn.execute(text("ALTER TABLE autotrade_symbol_rules ADD COLUMN name TEXT"))
            if "take_profit_pct" not in symbol_rule_cols:
                conn.execute(text("ALTER TABLE autotrade_symbol_rules ADD COLUMN take_profit_pct REAL NOT NULL DEFAULT 7.0"))
            if "stop_loss_pct" not in symbol_rule_cols:
                conn.execute(text("ALTER TABLE autotrade_symbol_rules ADD COLUMN stop_loss_pct REAL NOT NULL DEFAULT 5.0"))
            if "enabled" not in symbol_rule_cols:
                conn.execute(text("ALTER TABLE autotrade_symbol_rules ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1"))
        # add cache_key column if missing
        cols = [r[1] for r in conn.execute(text("PRAGMA table_info(reports)")).fetchall()]
        if "cache_key" not in cols:
            conn.execute(text("ALTER TABLE reports ADD COLUMN cache_key TEXT"))
        # Legacy compatibility:
        # Older DBs were created with UNIQUE(date,type). New logic needs cache-keyed rows.
        # If legacy unique index is detected, rebuild reports table with UNIQUE(date,type,cache_key).
        idx_rows = conn.execute(text("PRAGMA index_list(reports)")).fetchall()
        has_legacy_unique = False
        has_cache_unique = False
        for idx in idx_rows:
            # PRAGMA index_list columns: seq, name, unique, origin, partial
            idx_name = str(idx[1])
            is_unique = bool(idx[2])
            if not is_unique:
                continue
            cols_i = [str(c[2]) for c in conn.execute(text(f"PRAGMA index_info('{idx_name}')")).fetchall()]
            if cols_i == ["date", "type"]:
                has_legacy_unique = True
            if cols_i == ["date", "type", "cache_key"]:
                has_cache_unique = True

        if has_legacy_unique and not has_cache_unique:
            logger.warning("Migrating reports table: UNIQUE(date,type) -> UNIQUE(date,type,cache_key)")
            conn.execute(text("ALTER TABLE reports RENAME TO reports_legacy"))
            conn.execute(
                text(
                    """
                    CREATE TABLE reports (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      date TEXT NOT NULL,
                      type TEXT NOT NULL,
                      cache_key TEXT NOT NULL,
                      payload_json TEXT NOT NULL,
                      generated_at TEXT NOT NULL,
                      UNIQUE(date, type, cache_key)
                    )
                    """
                )
            )
            conn.execute(
                text(
                    """
                    INSERT INTO reports (id, date, type, cache_key, payload_json, generated_at)
                    SELECT
                      id,
                      date,
                      type,
                      CASE
                        WHEN cache_key IS NULL OR TRIM(cache_key) = ''
                          THEN (type || ':' || date || ':legacy:' || id)
                        ELSE cache_key
                      END AS cache_key_norm,
                      payload_json,
                      generated_at
                    FROM reports_legacy
                    """
                )
            )
            conn.execute(text("DROP TABLE reports_legacy"))
            conn.execute(text("CREATE INDEX IF NOT EXISTS ix_reports_date ON reports(date)"))
            conn.execute(text("CREATE INDEX IF NOT EXISTS ix_reports_type ON reports(type)"))
            conn.execute(text("CREATE INDEX IF NOT EXISTS ix_reports_cache_key ON reports(cache_key)"))
        conn.commit()


@contextmanager
def session_scope() -> Session:
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        logger.exception("DB session failed; rolled back")
        raise
    finally:
        session.close()
