from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.storage import Base


class Report(Base):
    __tablename__ = "reports"
    __table_args__ = (UniqueConstraint("date", "type", "cache_key", name="uq_reports_date_type_cache"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    date: Mapped[date] = mapped_column(Date, index=True, nullable=False)
    type: Mapped[str] = mapped_column(String(20), index=True, nullable=False)  # PREMARKET | EOD
    cache_key: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    payload_json: Mapped[str] = mapped_column(Text, nullable=False)
    generated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class Device(Base):
    __tablename__ = "devices"

    device_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    fcm_token: Mapped[str | None] = mapped_column(Text, nullable=True)
    pref_json: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class Alert(Base):
    __tablename__ = "alerts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    ts: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True, nullable=False)
    type: Mapped[str] = mapped_column(String(20), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    body: Mapped[str] = mapped_column(Text, nullable=False)
    payload_json: Mapped[str] = mapped_column(Text, nullable=False)


class EvalDaily(Base):
    __tablename__ = "eval_daily"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    date: Mapped[date] = mapped_column(Date, unique=True, nullable=False, index=True)
    daily_mean_r: Mapped[float] = mapped_column(nullable=False)
    generated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class EvalMonthly(Base):
    __tablename__ = "eval_monthly"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    end_date: Mapped[date] = mapped_column(Date, unique=True, nullable=False, index=True)
    expectancy_r: Mapped[float] = mapped_column(nullable=False)
    win_rate: Mapped[float] = mapped_column(nullable=False)
    mdd_r: Mapped[float] = mapped_column(nullable=False)
    payload_json: Mapped[str] = mapped_column(Text, nullable=False)
    generated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class StrategySettings(Base):
    __tablename__ = "strategy_settings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    algo_version: Mapped[str] = mapped_column(String(8), nullable=False, default="V2")
    risk_preset: Mapped[str] = mapped_column(String(32), nullable=False, default="ADAPTIVE")
    use_custom_weights: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    w_ta: Mapped[float | None] = mapped_column(Float, nullable=True)
    w_re: Mapped[float | None] = mapped_column(Float, nullable=True)
    w_rs: Mapped[float | None] = mapped_column(Float, nullable=True)
    theme_cap: Mapped[int] = mapped_column(Integer, nullable=False, default=2)
    max_gap_pct: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    gate_threshold: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    gate_quantile: Mapped[float | None] = mapped_column(Float, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class User(Base):
    __tablename__ = "users"
    __table_args__ = (UniqueConstraint("user_code", name="uq_users_user_code"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_code: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    role: Mapped[str] = mapped_column(String(16), nullable=False, default="USER")
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="active")
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    force_password_change: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    invite_status: Mapped[str] = mapped_column(String(32), nullable=False, default="CREATED")
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    memo: Mapped[str | None] = mapped_column(Text, nullable=True)
    name: Mapped[str | None] = mapped_column(String(80), nullable=True)
    phone: Mapped[str | None] = mapped_column(String(32), nullable=True)
    failed_attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    device_binding_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    bound_device_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class UserMenuPermission(Base):
    __tablename__ = "user_menu_permissions"
    __table_args__ = (UniqueConstraint("user_id", name="uq_user_menu_permissions_user"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    menu_daytrade: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    menu_autotrade: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    menu_holdings: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    menu_supply: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    menu_movers: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    menu_us: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    menu_news: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    menu_longterm: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    menu_papers: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    menu_eod: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    menu_alerts: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class SessionToken(Base):
    __tablename__ = "session_tokens"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    token_hash: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    issued_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    device_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    app_version: Mapped[str | None] = mapped_column(String(32), nullable=True)
    ip: Mapped[str | None] = mapped_column(String(64), nullable=True)
    user_agent: Mapped[str | None] = mapped_column(String(255), nullable=True)


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    session_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    token_hash: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    issued_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    device_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    app_version: Mapped[str | None] = mapped_column(String(32), nullable=True)
    ip: Mapped[str | None] = mapped_column(String(64), nullable=True)
    user_agent: Mapped[str | None] = mapped_column(String(255), nullable=True)


class LoginEvent(Base):
    __tablename__ = "login_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    ts: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    user_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    user_code: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    result: Mapped[str] = mapped_column(String(16), nullable=False)
    reason_code: Mapped[str] = mapped_column(String(64), nullable=False)
    ip: Mapped[str | None] = mapped_column(String(64), nullable=True)
    device_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    app_version: Mapped[str | None] = mapped_column(String(32), nullable=True)


class AdminAuditLog(Base):
    __tablename__ = "admin_audit_logs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    ts: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    admin_user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    detail_json: Mapped[str | None] = mapped_column(Text, nullable=True)


class TickerTag(Base):
    __tablename__ = "ticker_tags"

    ticker: Mapped[str] = mapped_column(String(16), primary_key=True)
    tags_json: Mapped[str] = mapped_column(Text, nullable=False, default="[]")
    source: Mapped[str] = mapped_column(String(32), nullable=False, default="manual")
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class UserFavorite(Base):
    __tablename__ = "user_favorites"
    __table_args__ = (UniqueConstraint("user_id", "ticker", name="uq_user_favorites_user_ticker"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    ticker: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    baseline_price: Mapped[float] = mapped_column(Float, nullable=False)
    favorited_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    source_tab: Mapped[str | None] = mapped_column(String(32), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class UserBrokerCredential(Base):
    __tablename__ = "user_broker_credentials"
    __table_args__ = (UniqueConstraint("user_id", name="uq_user_broker_credentials_user"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    use_user_credentials: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    app_key_demo_enc: Mapped[str | None] = mapped_column(Text, nullable=True)
    app_secret_demo_enc: Mapped[str | None] = mapped_column(Text, nullable=True)
    app_key_prod_enc: Mapped[str | None] = mapped_column(Text, nullable=True)
    app_secret_prod_enc: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Legacy shared account fields (kept for backward compatibility).
    account_no_enc: Mapped[str | None] = mapped_column(Text, nullable=True)
    account_product_code: Mapped[str] = mapped_column(String(8), nullable=False, default="01")
    # Preferred env-specific account fields.
    account_no_demo_enc: Mapped[str | None] = mapped_column(Text, nullable=True)
    account_no_prod_enc: Mapped[str | None] = mapped_column(Text, nullable=True)
    account_product_code_demo: Mapped[str] = mapped_column(String(8), nullable=False, default="01")
    account_product_code_prod: Mapped[str] = mapped_column(String(8), nullable=False, default="01")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class AutoTradeSetting(Base):
    __tablename__ = "autotrade_settings"
    __table_args__ = (UniqueConstraint("user_id", name="uq_autotrade_settings_user"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    environment: Mapped[str] = mapped_column(String(16), nullable=False, default="paper")  # paper|demo|prod
    include_daytrade: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    include_movers: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    include_supply: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    include_papers: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    include_longterm: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    include_favorites: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    order_budget_krw: Mapped[float] = mapped_column(Float, nullable=False, default=200000.0)
    max_orders_per_run: Mapped[int] = mapped_column(Integer, nullable=False, default=5)
    max_daily_loss_pct: Mapped[float] = mapped_column(Float, nullable=False, default=3.0)
    seed_krw: Mapped[float] = mapped_column(Float, nullable=False, default=10000000.0)
    take_profit_pct: Mapped[float] = mapped_column(Float, nullable=False, default=7.0)
    stop_loss_pct: Mapped[float] = mapped_column(Float, nullable=False, default=5.0)
    stoploss_reentry_policy: Mapped[str] = mapped_column(String(16), nullable=False, default="cooldown")  # immediate|cooldown|day_block|manual_block
    stoploss_reentry_cooldown_min: Mapped[int] = mapped_column(Integer, nullable=False, default=30)
    takeprofit_reentry_policy: Mapped[str] = mapped_column(String(16), nullable=False, default="cooldown")  # immediate|cooldown|day_block|manual_block
    takeprofit_reentry_cooldown_min: Mapped[int] = mapped_column(Integer, nullable=False, default=30)
    allow_market_order: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    offhours_reservation_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    offhours_reservation_mode: Mapped[str] = mapped_column(String(16), nullable=False, default="auto")  # auto|confirm
    offhours_confirm_timeout_min: Mapped[int] = mapped_column(Integer, nullable=False, default=3)
    offhours_confirm_timeout_action: Mapped[str] = mapped_column(String(16), nullable=False, default="cancel")  # cancel|auto
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class AutoTradeSymbolRule(Base):
    __tablename__ = "autotrade_symbol_rules"
    __table_args__ = (
        UniqueConstraint("user_id", "ticker", name="uq_autotrade_symbol_rule_user_ticker"),
        Index("idx_autotrade_symbol_rule_user", "user_id"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    ticker: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    take_profit_pct: Mapped[float] = mapped_column(Float, nullable=False, default=7.0)
    stop_loss_pct: Mapped[float] = mapped_column(Float, nullable=False, default=5.0)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class AutoTradeOrder(Base):
    __tablename__ = "autotrade_orders"
    __table_args__ = (
        Index("idx_autotrade_orders_user_requested", "user_id", "requested_at"),
        Index("idx_autotrade_orders_user_ticker", "user_id", "ticker"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    run_id: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    source_tab: Mapped[str] = mapped_column(String(32), nullable=False)
    ticker: Mapped[str] = mapped_column(String(16), nullable=False)
    name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    side: Mapped[str] = mapped_column(String(8), nullable=False, default="BUY")
    qty: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    requested_price: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    filled_price: Mapped[float | None] = mapped_column(Float, nullable=True)
    current_price: Mapped[float | None] = mapped_column(Float, nullable=True)
    pnl_pct: Mapped[float | None] = mapped_column(Float, nullable=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="SKIPPED")
    broker_order_no: Mapped[str | None] = mapped_column(String(64), nullable=True)
    reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    metadata_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    requested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    filled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class AutoTradeReservation(Base):
    __tablename__ = "autotrade_reservations"
    __table_args__ = (
        Index("idx_autotrade_reservations_user_requested", "user_id", "requested_at"),
        Index("idx_autotrade_reservations_status_execute", "status", "execute_at"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    environment: Mapped[str] = mapped_column(String(16), nullable=False, default="demo")
    mode: Mapped[str] = mapped_column(String(16), nullable=False, default="auto")  # auto|confirm
    status: Mapped[str] = mapped_column(String(24), nullable=False, default="QUEUED")
    requested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    execute_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    confirm_deadline_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    timeout_action: Mapped[str] = mapped_column(String(16), nullable=False, default="cancel")  # cancel|auto
    payload_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    reason_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    reason_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    result_run_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    result_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class AutoTradeReentryBlock(Base):
    __tablename__ = "autotrade_reentry_blocks"
    __table_args__ = (
        Index("idx_autotrade_reentry_blocks_user", "user_id", "environment", "trigger_reason", "is_active"),
        Index("idx_autotrade_reentry_blocks_user_ticker", "user_id", "ticker", "environment", "is_active"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    environment: Mapped[str] = mapped_column(String(16), nullable=False, default="demo")
    ticker: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    trigger_reason: Mapped[str] = mapped_column(String(32), nullable=False)  # STOP_LOSS|TAKE_PROFIT
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    blocked_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    released_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)


class AutoTradeDailyMetric(Base):
    __tablename__ = "autotrade_daily_metrics"
    __table_args__ = (UniqueConstraint("user_id", "ymd", name="uq_autotrade_daily_user_ymd"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    ymd: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    orders_total: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    filled_total: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    buy_amount_krw: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    eval_amount_krw: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    realized_pnl_krw: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    unrealized_pnl_krw: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    roi_pct: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    win_rate: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    mdd_pct: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class NewsArticle(Base):
    __tablename__ = "news_articles"
    __table_args__ = (
        UniqueConstraint("source", "source_uid", name="uq_news_articles_source_uid"),
        Index("idx_news_articles_theme_published", "theme_key", "published_at"),
        Index("idx_news_articles_source_published", "source", "published_at"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    source: Mapped[str] = mapped_column(String(64), nullable=False, index=True)  # dart|rss feed id|openclaw
    source_uid: Mapped[str] = mapped_column(String(128), nullable=False)  # DART rcept_no / RSS guid / md5(link)
    url: Mapped[str] = mapped_column(Text, nullable=False)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    published_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    ingested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    published_ymd: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    event_type: Mapped[str] = mapped_column(String(32), nullable=False, default="misc", index=True)
    polarity: Mapped[str] = mapped_column(String(16), nullable=False, default="neutral", index=True)
    impact: Mapped[int] = mapped_column(Integer, nullable=False, default=0, index=True)
    theme_key: Mapped[str] = mapped_column(String(32), nullable=False, default="ETC", index=True)
    tickers_json: Mapped[str | None] = mapped_column(Text, nullable=True)  # JSON list


class NewsCluster(Base):
    __tablename__ = "news_clusters"
    __table_args__ = (
        UniqueConstraint("cluster_key", name="uq_news_clusters_key"),
        Index("idx_news_clusters_theme_start", "theme_key", "published_start"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    cluster_key: Mapped[str] = mapped_column(String(64), nullable=False)
    theme_key: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    event_type: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    top_tickers_json: Mapped[str | None] = mapped_column(Text, nullable=True)  # JSON list
    published_start: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    published_end: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    published_ymd: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    article_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)


class NewsEntityMention(Base):
    __tablename__ = "news_entity_mentions"
    __table_args__ = (
        UniqueConstraint("ticker", "article_id", name="uq_news_entity_mentions_ticker_article"),
        Index("idx_news_mentions_ticker_published", "ticker", "published_at"),
        Index("idx_news_mentions_ticker_ymd", "ticker", "published_ymd"),
        Index("idx_news_mentions_event_published", "event_type", "published_at"),
        Index("idx_news_mentions_cluster", "cluster_id"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    ticker: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    article_id: Mapped[int] = mapped_column(Integer, ForeignKey("news_articles.id"), nullable=False, index=True)
    cluster_id: Mapped[int | None] = mapped_column(Integer, ForeignKey("news_clusters.id"), nullable=True, index=True)
    mention_weight: Mapped[float] = mapped_column(Float, nullable=False, default=1.0)
    published_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    published_ymd: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    event_type: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    impact: Mapped[int] = mapped_column(Integer, nullable=False, index=True)


# ─────────────────────────────────────────────────────────────────────────────
# 단타2 (autotrade2) — 기존 autotrade와 독립된 테이블, 롤백 시 삭제만 하면 됨
# ─────────────────────────────────────────────────────────────────────────────


class AutoTrade2Setting(Base):
    """단타2 설정 — 기존 AutoTradeSetting에 부분익절/단계적 손실/강제청산 필드 추가."""
    __tablename__ = "autotrade2_settings"
    __table_args__ = (UniqueConstraint("user_id", name="uq_autotrade2_settings_user"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    environment: Mapped[str] = mapped_column(String(16), nullable=False, default="paper")
    # 소스 ON/OFF
    include_daytrade: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    include_movers: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    include_supply: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    include_papers: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    include_longterm: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    include_favorites: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    # 기본 매매 파라미터
    order_budget_krw: Mapped[float] = mapped_column(Float, nullable=False, default=200000.0)
    max_orders_per_run: Mapped[int] = mapped_column(Integer, nullable=False, default=5)
    seed_krw: Mapped[float] = mapped_column(Float, nullable=False, default=10000000.0)
    take_profit_pct: Mapped[float] = mapped_column(Float, nullable=False, default=7.0)
    stop_loss_pct: Mapped[float] = mapped_column(Float, nullable=False, default=5.0)
    # 리엔트리 정책
    stoploss_reentry_policy: Mapped[str] = mapped_column(String(16), nullable=False, default="cooldown")
    stoploss_reentry_cooldown_min: Mapped[int] = mapped_column(Integer, nullable=False, default=30)
    takeprofit_reentry_policy: Mapped[str] = mapped_column(String(16), nullable=False, default="cooldown")
    takeprofit_reentry_cooldown_min: Mapped[int] = mapped_column(Integer, nullable=False, default=30)
    allow_market_order: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    # 장외 예약
    offhours_reservation_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    offhours_reservation_mode: Mapped[str] = mapped_column(String(16), nullable=False, default="auto")
    offhours_confirm_timeout_min: Mapped[int] = mapped_column(Integer, nullable=False, default=3)
    offhours_confirm_timeout_action: Mapped[str] = mapped_column(String(16), nullable=False, default="cancel")
    # ── 단타2 신규 필드 ──
    # P0-3: 단계적 일일 손실 한계
    max_daily_loss_pct: Mapped[float] = mapped_column(Float, nullable=False, default=3.0)
    daily_loss_throttle_pct: Mapped[float] = mapped_column(Float, nullable=False, default=3.0)
    daily_loss_block_pct: Mapped[float] = mapped_column(Float, nullable=False, default=5.0)
    daily_loss_throttle_ratio: Mapped[float] = mapped_column(Float, nullable=False, default=0.5)
    # P0-1: AVG_FALLBACK 강제 청산
    avg_fallback_max_count: Mapped[int] = mapped_column(Integer, nullable=False, default=3)
    avg_fallback_force_exit: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    # P1-4: 부분 익절
    partial_tp_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    partial_tp_ratio: Mapped[float] = mapped_column(Float, nullable=False, default=0.5)
    partial_tp_pct: Mapped[float] = mapped_column(Float, nullable=False, default=5.0)
    final_tp_pct: Mapped[float] = mapped_column(Float, nullable=False, default=7.0)
    # P1-3: 프리셋
    preset_name: Mapped[str] = mapped_column(String(32), nullable=False, default="balanced")
    # 타임스탬프
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class AutoTrade2Order(Base):
    """단타2 주문 기록."""
    __tablename__ = "autotrade2_orders"
    __table_args__ = (
        Index("idx_autotrade2_orders_user_run", "user_id", "run_id"),
        Index("idx_autotrade2_orders_ticker_status", "ticker", "status"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    run_id: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    source_tab: Mapped[str] = mapped_column(String(32), nullable=False, default="UNKNOWN")
    ticker: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    side: Mapped[str] = mapped_column(String(8), nullable=False)
    qty: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    requested_price: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    filled_price: Mapped[float | None] = mapped_column(Float, nullable=True)
    current_price: Mapped[float | None] = mapped_column(Float, nullable=True)
    pnl_pct: Mapped[float | None] = mapped_column(Float, nullable=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="SKIPPED")
    broker_order_no: Mapped[str | None] = mapped_column(String(64), nullable=True)
    reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    suggestion: Mapped[str | None] = mapped_column(Text, nullable=True)  # P1-1: 사유 suggestion
    metadata_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    requested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    filled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class AutoTrade2DailyMetric(Base):
    """단타2 일일 성과."""
    __tablename__ = "autotrade2_daily_metrics"
    __table_args__ = (UniqueConstraint("user_id", "ymd", name="uq_autotrade2_daily_user_ymd"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    ymd: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    orders_total: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    filled_total: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    buy_amount_krw: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    eval_amount_krw: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    realized_pnl_krw: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    unrealized_pnl_krw: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    roi_pct: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    win_rate: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    mdd_pct: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    # 단타2: 손실 단계 상태 기록
    loss_throttle_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    loss_blocked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class AutoTrade2ReentryBlock(Base):
    """단타2 리엔트리 블락."""
    __tablename__ = "autotrade2_reentry_blocks"
    __table_args__ = (
        Index("idx_autotrade2_reentry_user_ticker", "user_id", "ticker", "environment", "is_active"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    environment: Mapped[str] = mapped_column(String(16), nullable=False, default="demo")
    ticker: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    trigger_reason: Mapped[str] = mapped_column(String(32), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    blocked_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    released_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)


class AutoTrade2AvgFallbackTracker(Base):
    """P0-1: AVG_FALLBACK 누적 카운트 — 3회 초과 시 강제 청산 트리거."""
    __tablename__ = "autotrade2_avg_fallback_tracker"
    __table_args__ = (
        UniqueConstraint("user_id", "ticker", "environment", name="uq_autotrade2_fallback_user_ticker_env"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    ticker: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    environment: Mapped[str] = mapped_column(String(16), nullable=False, default="demo")
    fallback_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    last_fallback_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    force_exited: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)


class AutoTrade2GateHistory(Base):
    """P1-2: Gate ON/OFF 시계열 기록."""
    __tablename__ = "autotrade2_gate_history"
    __table_args__ = (
        UniqueConstraint("ymd", name="uq_autotrade2_gate_ymd"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    ymd: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    gate_metric: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    gate_threshold: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    gate_on: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    regime: Mapped[str | None] = mapped_column(String(32), nullable=True)
    dynamic_threshold: Mapped[float | None] = mapped_column(Float, nullable=True)
    daily_mean_r: Mapped[float | None] = mapped_column(Float, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class AutoTrade2SymbolRule(Base):
    """단타2 심볼별 규칙."""
    __tablename__ = "autotrade2_symbol_rules"
    __table_args__ = (
        UniqueConstraint("user_id", "ticker", name="uq_autotrade2_symbol_rule_user_ticker"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    ticker: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    take_profit_pct: Mapped[float] = mapped_column(Float, nullable=False, default=7.0)
    stop_loss_pct: Mapped[float] = mapped_column(Float, nullable=False, default=5.0)
    partial_tp_enabled: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class CompanyFinancial(Base):
    """Cached DART financial statement data per company per quarter."""
    __tablename__ = "company_financials"
    __table_args__ = (
        UniqueConstraint("corp_code", "bsns_year", "reprt_code", name="uq_cf_corp_year_reprt"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    corp_code: Mapped[str] = mapped_column(String(8), index=True, nullable=False)
    ticker: Mapped[str] = mapped_column(String(6), index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    market: Mapped[str] = mapped_column(String(10), nullable=False)
    sector: Mapped[str] = mapped_column(String(100), nullable=True)
    bsns_year: Mapped[str] = mapped_column(String(4), nullable=False)
    reprt_code: Mapped[str] = mapped_column(String(5), nullable=False)
    revenue: Mapped[int] = mapped_column(Integer, nullable=True)
    operating_profit: Mapped[int] = mapped_column(Integer, nullable=True)
    net_income: Mapped[int] = mapped_column(Integer, nullable=True)
    total_assets: Mapped[int] = mapped_column(Integer, nullable=True)
    total_liabilities: Mapped[int] = mapped_column(Integer, nullable=True)
    total_equity: Mapped[int] = mapped_column(Integer, nullable=True)
    current_assets: Mapped[int] = mapped_column(Integer, nullable=True)
    current_liabilities: Mapped[int] = mapped_column(Integer, nullable=True)
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class CompanyHealthScore(Base):
    """Computed health scores, refreshed after each DART quarterly release."""
    __tablename__ = "company_health_scores"
    __table_args__ = (
        UniqueConstraint("ticker", "computed_date", name="uq_chs_ticker_date"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    ticker: Mapped[str] = mapped_column(String(6), index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    market: Mapped[str] = mapped_column(String(10), nullable=False)
    sector: Mapped[str] = mapped_column(String(100), nullable=True)
    total_score: Mapped[float] = mapped_column(Float, nullable=False)
    profitability: Mapped[float] = mapped_column(Float, nullable=True)
    stability: Mapped[float] = mapped_column(Float, nullable=True)
    growth: Mapped[float] = mapped_column(Float, nullable=True)
    efficiency: Mapped[float] = mapped_column(Float, nullable=True)
    valuation: Mapped[float] = mapped_column(Float, nullable=True)
    grade: Mapped[str] = mapped_column(String(10), nullable=False)
    ai_summary: Mapped[str] = mapped_column(Text, nullable=True)
    ai_positive_points: Mapped[str] = mapped_column(Text, nullable=True)
    ai_risk_points: Mapped[str] = mapped_column(Text, nullable=True)
    ai_health_comment: Mapped[str] = mapped_column(Text, nullable=True)
    revenue: Mapped[int] = mapped_column(Integer, nullable=True)
    debt_ratio: Mapped[float] = mapped_column(Float, nullable=True)
    revenue_growth: Mapped[float] = mapped_column(Float, nullable=True)
    computed_date: Mapped[date] = mapped_column(Date, nullable=False)
