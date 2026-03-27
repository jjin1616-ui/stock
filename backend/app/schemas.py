from __future__ import annotations

from datetime import date, datetime
from typing import Any, Literal

from pydantic import BaseModel, Field


class HealthResponse(BaseModel):
    ok: bool = True


class DaytradeGate(BaseModel):
    on: bool
    lookback_days: int
    gate_metric: float
    gate_on_days: int
    gate_total_days: int
    reason: list[str]


class DaytradeTopItem(BaseModel):
    ticker: str
    name: str
    market: Literal["KOSPI", "KOSDAQ"]
    theme_id: int | None = None
    tags: list[str] = Field(default_factory=list)
    trigger_buy: float
    target_1: float
    stop_loss: float
    thesis: str


class BuyZone(BaseModel):
    low: float
    high: float


class LongtermItem(BaseModel):
    ticker: str
    name: str
    market: Literal["KOSPI", "KOSDAQ"]
    theme_id: int | None = None
    tags: list[str] = Field(default_factory=list)
    d1_close: float
    buy_zone: BuyZone
    target_12m: float
    stop_loss: float
    thesis: str


class OverlapItem(BaseModel):
    ticker: str
    name: str
    market: Literal["KOSPI", "KOSDAQ"]
    trigger_buy: float
    target_1: float
    stop_loss: float
    thesis: str


class DeltaKpi(BaseModel):
    delta_expectancy_exec: float = 0.0
    delta_mdd_exec: float = 0.0
    delta_theme_hhi: float = 0.0
    delta_cost_impact: float = 0.0


class ThemeItem(BaseModel):
    rank: int
    name: str
    why: str


class ResponseStatus(BaseModel):
    source: Literal["LIVE", "CACHE", "FALLBACK"] = "LIVE"
    queued: bool = False
    message: str | None = None
    cache_key: str | None = None
    settings_hash: str | None = None
    algo_version: str | None = None


class MarketSnapshot(BaseModel):
    kospi_close: float | None = None
    kosdaq_close: float | None = None
    usdkrw_close: float | None = None


class Regime(BaseModel):
    mode: str | None = None
    bullets: list[str] = Field(default_factory=list)
    market_snapshot: MarketSnapshot | None = None


class PremarketResponse(BaseModel):
    date: str
    generated_at: datetime
    status: ResponseStatus = Field(default_factory=ResponseStatus)
    daytrade_gate: DaytradeGate
    daytrade_top: list[DaytradeTopItem]
    daytrade_primary: list[DaytradeTopItem] = Field(default_factory=list)
    daytrade_watch: list[DaytradeTopItem] = Field(default_factory=list)
    daytrade_top10: list[DaytradeTopItem] = Field(default_factory=list)
    longterm: list[LongtermItem]
    longterm_top10: list[LongtermItem] = Field(default_factory=list)
    overlap_bucket: list[OverlapItem] = Field(default_factory=list)
    base_top10: list[DaytradeTopItem] = Field(default_factory=list)
    var7_top10: list[DaytradeTopItem] = Field(default_factory=list)
    delta_kpi: DeltaKpi = Field(default_factory=DeltaKpi)
    delta_explain: list[str] = Field(default_factory=list)
    themes: list[ThemeItem]
    hard_rules: list[str]
    regime: Regime | None = None
    briefing: str | None = None
    market_temperature: dict | None = None


class EodResponse(BaseModel):
    date: str
    generated_at: datetime
    summary: list[str]
    themes_worked: list[dict[str, str]]
    themes_failed: list[dict[str, str]]
    tomorrow_improvements: list[str]


class EvalMonthlyResponse(BaseModel):
    end: str
    trades_total: int
    win_rate: float
    avg_r: float
    expectancy_r: float
    mdd_r: float
    gate_on_days_recent: int
    gate_metric_recent: float
    payload: dict[str, Any] = Field(default_factory=dict)


class DevicePref(BaseModel):
    push_premarket: bool = True
    push_eod: bool = True
    push_triggers: bool = True


class DeviceRegisterRequest(BaseModel):
    device_id: str
    fcm_token: str | None = None
    pref: DevicePref


class AlertHistoryItem(BaseModel):
    ts: datetime
    type: Literal["PREMARKET", "EOD", "TRIGGER", "ADMIN", "UPDATE"]
    title: str
    body: str
    payload: dict[str, Any]


class OkResponse(BaseModel):
    ok: bool = True


class RealtimeQuoteItem(BaseModel):
    ticker: str
    price: float
    prev_close: float
    chg_pct: float
    as_of: datetime
    source: str
    is_live: bool


class RealtimeQuotesResponse(BaseModel):
    as_of: datetime
    items: list[RealtimeQuoteItem]


class StockInvestorDailyItem(BaseModel):
    date: str
    individual_qty: int = 0
    foreign_qty: int = 0
    institution_qty: int = 0
    private_fund_qty: int = 0
    corporate_qty: int = 0
    financial_investment_qty: int = 0
    insurance_qty: int = 0
    trust_qty: int = 0
    pension_qty: int = 0
    bank_qty: int = 0
    etc_finance_qty: int = 0
    other_foreign_qty: int = 0
    total_qty: int = 0
    individual_value: float = 0.0
    foreign_value: float = 0.0
    institution_value: float = 0.0
    private_fund_value: float = 0.0
    corporate_value: float = 0.0
    total_value: float = 0.0


class StockInvestorDailyResponse(BaseModel):
    ticker: str
    name: str | None = None
    as_of: datetime
    source: Literal["LIVE", "CACHE", "FALLBACK"] = "LIVE"
    message: str | None = None
    days: int = 30
    items: list[StockInvestorDailyItem] = Field(default_factory=list)


class StockTrendIntradayItem(BaseModel):
    time: str
    current_price: float
    change_abs: float
    change_pct: float
    volume_delta: int = 0
    cumulative_volume: int = 0
    net_buy_qty_estimate: int = 0
    direction: Literal["UP", "DOWN", "FLAT"] = "FLAT"


class StockTrendIntradayResponse(BaseModel):
    ticker: str
    name: str | None = None
    as_of: datetime
    prev_close: float = 0.0
    source: Literal["LIVE", "CACHE", "FALLBACK"] = "LIVE"
    message: str | None = None
    window_minutes: int = 0
    items: list[StockTrendIntradayItem] = Field(default_factory=list)


class MoverItem(BaseModel):
    ticker: str
    name: str
    market: str
    logo_url: str | None = None
    logo_png_url: str | None = None
    tags: list[str] = Field(default_factory=list)
    rank: int | None = None
    search_ratio: float | None = None
    price: float
    prev_close: float
    chg_pct: float
    as_of: datetime | None = None
    source: str | None = None
    is_live: bool | None = None
    volume: float = 0.0
    value: float = 0.0
    baseline_value: float | None = None
    value_ratio: float | None = None


class MoversResponse(BaseModel):
    as_of: datetime
    bas_dd: str
    ref_bas_dd: str = ""
    period: Literal["1d", "1w", "1m", "3m", "6m", "1y"] = "1d"
    mode: Literal["chg", "chg_down", "value", "volume", "value_ratio", "popular"] = "chg"
    items: list[MoverItem]


class Mover2Item(BaseModel):
    ticker: str
    name: str
    market: str
    logo_url: str | None = None
    logo_png_url: str | None = None
    tags: list[str] = Field(default_factory=list)
    price: float
    prev_close: float
    chg_pct: float
    as_of: datetime | None = None
    source: str | None = None
    is_live: bool | None = None
    volume: float = 0.0
    value: float = 0.0
    baseline_value: float | None = None
    value_ratio: float | None = None
    session: Literal["preopen", "regular", "spike", "closecall", "afterclose", "afterauction"] = "regular"
    flow_source: Literal["SESSION", "REGULAR", "REGULAR_FALLBACK", "MISSING"] = "MISSING"
    metric_name: str
    metric_value: float
    quality: Literal["EXACT", "APPROX", "STALE"] = "APPROX"
    quality_reason: str | None = None
    session_price: float
    basis_price: float
    basis_label: str | None = None
    over_status: str | None = None


class Movers2Response(BaseModel):
    as_of: datetime
    bas_dd: str
    session: Literal["preopen", "regular", "spike", "closecall", "afterclose", "afterauction"] = "regular"
    session_label: str
    active_session: Literal["preopen", "regular", "spike", "closecall", "afterclose", "afterauction", "closed"] = "regular"
    direction: Literal["up", "down"] = "up"
    data_state: Literal["LIVE", "SNAPSHOT", "APPROX"] = "LIVE"
    algo_version: Literal["V1", "V2"] = "V2"
    snapshot_as_of: datetime | None = None
    universe_count: int = 0
    candidate_quotes: int = 0
    session_progress: float = 0.0
    notes: list[str] = Field(default_factory=list)
    items: list[Mover2Item]


class SupplyItem(BaseModel):
    ticker: str
    name: str
    market: str
    logo_url: str | None = None
    logo_png_url: str | None = None
    tags: list[str] = Field(default_factory=list)
    price: float
    prev_close: float
    chg_pct: float
    as_of: datetime | None = None
    source: str | None = None
    is_live: bool | None = None
    volume: float = 0.0
    value: float = 0.0
    baseline_value: float | None = None
    value_ratio: float | None = None
    flow_label: Literal["외국인 주도", "기관 주도", "동반 매수", "개인 역추세"] = "동반 매수"
    confidence: Literal["HIGH", "MID", "LOW"] = "MID"
    investor_source: Literal["LIVE", "CACHE", "FALLBACK"] = "LIVE"
    investor_message: str | None = None
    investor_days: int = 0
    foreign_3d: int = 0
    institution_3d: int = 0
    individual_3d: int = 0
    net_3d: int = 0
    net_5d: int = 0
    buy_streak_days: int = 0
    flow_score: float = 0.0


class DailyFlowItem(BaseModel):
    date: str  # YYYY-MM-DD
    foreign: int = 0
    institution: int = 0
    individual: int = 0


class SupplyResponse(BaseModel):
    as_of: datetime
    bas_dd: str
    source: Literal["LIVE", "CACHE", "FALLBACK"] = "LIVE"
    message: str | None = None
    unit: Literal["value", "qty"] = "value"
    universe_count: int = 0
    candidate_quotes: int = 0
    notes: list[str] = Field(default_factory=list)
    items: list[SupplyItem] = Field(default_factory=list)
    daily_flow: list[DailyFlowItem] = Field(default_factory=list)


class UsInsiderItem(BaseModel):
    ticker: str
    company_name: str
    cik: str
    executive_name: str
    executive_role: Literal["CEO", "CFO", "OFFICER", "DIRECTOR", "TEN_PCT_OWNER", "OTHER"]
    transaction_code: Literal["P", "A", "M", "F"] = "P"
    acquired_disposed_code: Literal["A", "D"] | None = None
    transaction_date: date
    filing_date: date | None = None
    buy_dates: list[date] = Field(default_factory=list)
    buy_date_range: str | None = None
    transaction_count: int
    total_shares: float
    avg_price_usd: float
    total_value_usd: float
    pattern_summary: str | None = None
    signal_score: int = 0
    signal_grade: Literal["STRONG", "MEDIUM", "WATCH"] = "WATCH"
    signal_reason: str | None = None
    repeat_buy_90d: bool = False
    repeat_count_90d: int = 0
    has_10b5_1: bool = False
    accession_no: str
    source_url: str
    notes: list[str] = Field(default_factory=list)


class UsInsiderResponse(BaseModel):
    as_of: datetime
    requested_trading_days: int
    effective_trading_days: int
    expanded_window: bool = False
    selected_transaction_codes: list[str] = Field(default_factory=list)
    target_count: int
    returned_count: int
    candidate_daily_index: int = 0
    candidate_atom: int = 0
    candidate_github: int = 0
    candidate_merged: int = 0
    forms_checked: int = 0
    forms_parsed: int = 0
    parse_errors: int = 0
    purchase_rows_total: int = 0
    purchase_rows_in_requested: int = 0
    purchase_rows_in_expanded: int = 0
    purchase_rows_in_effective: int = 0
    shortage_reason: str | None = None
    items: list[UsInsiderItem]
    notes: list[str] = Field(default_factory=list)


class FavoriteUpsertRequest(BaseModel):
    ticker: str
    name: str | None = None
    baseline_price: float
    favorited_at: datetime | None = None
    source_tab: str | None = None


class FavoriteItem(BaseModel):
    ticker: str
    name: str | None = None
    baseline_price: float
    favorited_at: datetime
    source_tab: str | None = None
    current_price: float | None = None
    change_since_favorite_pct: float | None = None
    as_of: datetime | None = None
    source: str | None = None
    is_live: bool | None = None


class FavoritesResponse(BaseModel):
    items: list[FavoriteItem]


class StockSearchItem(BaseModel):
    ticker: str
    name: str
    market: str
    current_price: float | None = None
    chg_pct: float | None = None


class StockSearchResponse(BaseModel):
    count: int
    items: list[StockSearchItem] = Field(default_factory=list)


class AutoTradeSettingsPayload(BaseModel):
    enabled: bool = False
    environment: Literal["paper", "demo", "prod"] = "paper"
    include_daytrade: bool = True
    include_movers: bool = True
    include_supply: bool = True
    include_papers: bool = True
    include_longterm: bool = True
    include_favorites: bool = True
    order_budget_krw: float = 200000.0
    max_orders_per_run: int = 5
    max_daily_loss_pct: float = 3.0
    seed_krw: float = 10000000.0
    take_profit_pct: float = 7.0
    stop_loss_pct: float = 5.0
    stoploss_reentry_policy: Literal["immediate", "cooldown", "day_block", "manual_block"] = "cooldown"
    stoploss_reentry_cooldown_min: int = 30
    takeprofit_reentry_policy: Literal["immediate", "cooldown", "day_block", "manual_block"] = "cooldown"
    takeprofit_reentry_cooldown_min: int = 30
    allow_market_order: bool = False
    offhours_reservation_enabled: bool = True
    offhours_reservation_mode: Literal["auto", "confirm"] = "auto"
    offhours_confirm_timeout_min: int = 3
    offhours_confirm_timeout_action: Literal["cancel", "auto"] = "cancel"

    @classmethod
    def validate_ranges(cls, v: "AutoTradeSettingsPayload") -> "AutoTradeSettingsPayload":
        if v.order_budget_krw < 10000.0:
            raise ValueError("order_budget_krw out of range")
        if not (1 <= v.max_orders_per_run <= 100):
            raise ValueError("max_orders_per_run out of range")
        if not (0.1 <= v.max_daily_loss_pct <= 50.0):
            raise ValueError("max_daily_loss_pct out of range")
        if not (10000000.0 <= v.seed_krw <= 100000000.0):
            raise ValueError("seed_krw out of range")
        if not (1.0 <= v.take_profit_pct <= 30.0):
            raise ValueError("take_profit_pct out of range")
        if not (0.5 <= v.stop_loss_pct <= 30.0):
            raise ValueError("stop_loss_pct out of range")
        if not (1 <= v.stoploss_reentry_cooldown_min <= 1440):
            raise ValueError("stoploss_reentry_cooldown_min out of range")
        if not (1 <= v.takeprofit_reentry_cooldown_min <= 1440):
            raise ValueError("takeprofit_reentry_cooldown_min out of range")
        if not (1 <= v.offhours_confirm_timeout_min <= 30):
            raise ValueError("offhours_confirm_timeout_min out of range")
        return v


class AutoTradeSettingsResponse(BaseModel):
    settings: AutoTradeSettingsPayload
    updated_at: datetime


class AutoTradeSymbolRuleUpsertPayload(BaseModel):
    ticker: str
    name: str | None = None
    take_profit_pct: float = 7.0
    stop_loss_pct: float = 5.0
    enabled: bool = True


class AutoTradeSymbolRuleItem(BaseModel):
    ticker: str
    name: str | None = None
    take_profit_pct: float
    stop_loss_pct: float
    enabled: bool = True
    updated_at: datetime


class AutoTradeSymbolRulesResponse(BaseModel):
    count: int
    items: list[AutoTradeSymbolRuleItem] = Field(default_factory=list)


class AutoTradeBrokerCredentialPayload(BaseModel):
    use_user_credentials: bool = True
    app_key_demo: str | None = None
    app_secret_demo: str | None = None
    app_key_prod: str | None = None
    app_secret_prod: str | None = None
    # Legacy shared account input (applies to both envs if env-specific values are absent).
    account_no: str | None = None
    account_product_code: str | None = None
    account_no_demo: str | None = None
    account_product_code_demo: str | None = None
    account_no_prod: str | None = None
    account_product_code_prod: str | None = None
    clear_demo: bool = False
    clear_prod: bool = False
    clear_account: bool = False


class AutoTradeBrokerCredentialResponse(BaseModel):
    kis_trading_enabled: bool = False
    use_user_credentials: bool
    has_demo_app_key: bool
    has_demo_app_secret: bool
    has_prod_app_key: bool
    has_prod_app_secret: bool
    has_account_no: bool
    has_demo_account_no: bool = False
    has_prod_account_no: bool = False
    masked_account_no: str | None = None
    masked_demo_account_no: str | None = None
    masked_prod_account_no: str | None = None
    masked_prod_app_key: str | None = None
    masked_prod_app_secret: str | None = None
    account_product_code: str = "01"
    account_product_code_demo: str = "01"
    account_product_code_prod: str = "01"
    demo_ready_user: bool = False
    prod_ready_user: bool = False
    demo_ready_server: bool = False
    prod_ready_server: bool = False
    demo_ready_effective: bool = False
    prod_ready_effective: bool = False
    source: Literal["USER", "SERVER_ENV"] = "SERVER_ENV"
    updated_at: datetime


class AutoTradeCandidateItem(BaseModel):
    ticker: str
    name: str | None = None
    source_tab: Literal["DAYTRADE", "MOVERS", "SUPPLY", "PAPERS", "LONGTERM", "FAVORITES", "UNKNOWN"] = "UNKNOWN"
    signal_price: float | None = None
    current_price: float | None = None
    chg_pct: float | None = None
    note: str | None = None


class AutoTradeCandidatesResponse(BaseModel):
    generated_at: datetime
    count: int
    items: list[AutoTradeCandidateItem]
    source_counts: dict[str, int] = {}
    warnings: list[str] = []


class AutoTradeOrderItem(BaseModel):
    id: int
    run_id: str
    source_tab: str
    environment: Literal["paper", "demo", "prod"] | None = None
    ticker: str
    name: str | None = None
    side: Literal["BUY", "SELL"] = "BUY"
    qty: int
    requested_price: float
    filled_price: float | None = None
    current_price: float | None = None
    pnl_pct: float | None = None
    status: str
    broker_order_no: str | None = None
    reason: str | None = None
    reason_detail: AutoTradeReasonDetail | None = None
    requested_at: datetime
    filled_at: datetime | None = None


class AutoTradeOrdersResponse(BaseModel):
    total: int
    items: list[AutoTradeOrderItem]


class AutoTradeReasonDetail(BaseModel):
    conclusion: str
    reason_code: str
    evidence: dict[str, str] = Field(default_factory=dict)
    action: str


class AutoTradePerformanceItem(BaseModel):
    ymd: str
    orders_total: int
    filled_total: int
    buy_amount_krw: float
    eval_amount_krw: float
    realized_pnl_krw: float
    unrealized_pnl_krw: float
    roi_pct: float
    win_rate: float
    mdd_pct: float
    total_asset_krw: float | None = None
    daily_return_pct: float | None = None
    twr_cum_pct: float | None = None
    holding_pnl_krw: float | None = None
    holding_pnl_pct: float | None = None
    today_pnl_krw: float | None = None
    today_pnl_pct: float | None = None
    updated_at: datetime


class AutoTradePerformanceResponse(BaseModel):
    days: int
    summary: AutoTradePerformanceItem | None = None
    items: list[AutoTradePerformanceItem]


class AutoTradeAccountPosition(BaseModel):
    ticker: str
    name: str | None = None
    source_tab: str | None = None
    qty: int
    avg_price: float
    current_price: float
    eval_amount_krw: float
    pnl_amount_krw: float
    pnl_pct: float


class AutoTradeAccountSnapshotResponse(BaseModel):
    environment: Literal["paper", "demo", "prod"] = "paper"
    source: Literal["BROKER_LIVE", "LOCAL_ESTIMATE", "UNAVAILABLE"] = "UNAVAILABLE"
    broker_connected: bool = False
    account_no_masked: str | None = None
    cash_krw: float | None = None
    orderable_cash_krw: float | None = None
    stock_eval_krw: float | None = None
    total_asset_krw: float | None = None
    realized_pnl_krw: float | None = None
    unrealized_pnl_krw: float | None = None
    real_eval_pnl_krw: float | None = None
    real_eval_pnl_pct: float | None = None
    asset_change_krw: float | None = None
    asset_change_pct: float | None = None
    positions: list[AutoTradeAccountPosition] = Field(default_factory=list)
    message: str | None = None
    updated_at: datetime


class AutoTradeBootstrapResponse(BaseModel):
    generated_at: datetime
    settings: AutoTradeSettingsResponse
    symbol_rules: AutoTradeSymbolRulesResponse
    broker: AutoTradeBrokerCredentialResponse
    account: AutoTradeAccountSnapshotResponse
    orders: AutoTradeOrdersResponse
    candidates_prefetch_limit: int = 80
    deferred_sections: list[str] = Field(default_factory=lambda: ["candidates", "performance"])


class AutoTradeRunRequest(BaseModel):
    dry_run: bool = False
    limit: int | None = None
    reserve_if_closed: bool = False


class AutoTradeManualBuyRequest(BaseModel):
    ticker: str
    name: str | None = None
    mode: Literal["paper", "demo", "prod"] = "demo"
    qty: int | None = Field(default=None, ge=1, le=1_000_000)
    budget_krw: float | None = Field(default=None, ge=10_000.0, le=1_000_000_000.0)
    request_price: float | None = Field(default=None, ge=1.0, le=10_000_000_000.0)
    market_order: bool | None = None
    dry_run: bool = False


class AutoTradeManualSellRequest(BaseModel):
    ticker: str
    name: str | None = None
    mode: Literal["paper", "demo", "prod"] = "demo"
    qty: int | None = Field(default=None, ge=1, le=1_000_000)
    request_price: float | None = Field(default=None, ge=1.0, le=10_000_000_000.0)
    market_order: bool | None = None
    dry_run: bool = False


class AutoTradeReservationPreviewItem(BaseModel):
    ticker: str
    name: str | None = None
    source_tab: str = "UNKNOWN"
    signal_price: float | None = None
    current_price: float | None = None
    chg_pct: float | None = None
    planned_qty: int | None = None
    planned_price: float | None = None
    planned_amount_krw: float | None = None
    order_type: str | None = None
    merged_count: int | None = None


class AutoTradeRunResponse(BaseModel):
    run_id: str
    message: str
    queued: bool = False
    reservation_id: int | None = None
    reservation_status: str | None = None
    reservation_merged: bool = False
    reservation_merge_requests: int | None = None
    reservation_preview_count: int | None = None
    reservation_preview_items: list[AutoTradeReservationPreviewItem] = Field(default_factory=list)
    requested_count: int
    submitted_count: int
    filled_count: int
    skipped_count: int
    orders: list[AutoTradeOrderItem]
    metric: AutoTradePerformanceItem | None = None


class AutoTradeReservationItem(BaseModel):
    id: int
    environment: Literal["paper", "demo", "prod"] = "demo"
    kind: str = "AUTOTRADE_ENTRY"
    mode: Literal["auto", "confirm"] = "auto"
    status: str
    requested_at: datetime
    execute_at: datetime
    confirm_deadline_at: datetime | None = None
    timeout_action: Literal["cancel", "auto"] = "cancel"
    reason_code: str | None = None
    reason_message: str | None = None
    result_run_id: str | None = None
    preview_count: int = 0
    preview_items: list[AutoTradeReservationPreviewItem] = Field(default_factory=list)
    result_summary: dict[str, Any] | None = None
    updated_at: datetime


class AutoTradeReservationsResponse(BaseModel):
    total: int
    items: list[AutoTradeReservationItem] = Field(default_factory=list)


class AutoTradeReservationActionResponse(BaseModel):
    ok: bool = True
    reservation: AutoTradeReservationItem | None = None
    run_result: AutoTradeRunResponse | None = None
    message: str | None = None


class AutoTradeOrderCancelResponse(BaseModel):
    ok: bool = True
    order: AutoTradeOrderItem | None = None
    scope: str = "symbol"
    requested_count: int = 0
    canceled_count: int = 0
    closed_count: int = 0
    reserved_count: int = 0
    failed_count: int = 0
    skipped_count: int = 0
    canceled_order_ids: list[int] = Field(default_factory=list)
    closed_order_ids: list[int] = Field(default_factory=list)
    reservation_id: int | None = None
    reservation_status: str | None = None
    message: str = ""


class AutoTradePendingCancelRequest(BaseModel):
    environment: Literal["paper", "demo", "prod"] | None = None
    max_count: int = Field(default=20, ge=1, le=300)


class AutoTradePendingCancelResponse(BaseModel):
    ok: bool = True
    requested_count: int = 0
    canceled_count: int = 0
    closed_count: int = 0
    reserved_count: int = 0
    reservation_id: int | None = None
    failed_count: int = 0
    skipped_count: int = 0
    canceled_orders: list[AutoTradeOrderItem] = Field(default_factory=list)
    failed_orders: list[AutoTradeOrderItem] = Field(default_factory=list)
    message: str = ""


class AutoTradeReservationPendingCancelRequest(BaseModel):
    environment: Literal["paper", "demo", "prod"] | None = None
    max_count: int = Field(default=30, ge=1, le=300)


class AutoTradeReservationPendingCancelResponse(BaseModel):
    ok: bool = True
    requested_count: int = 0
    canceled_count: int = 0
    failed_count: int = 0
    skipped_count: int = 0
    canceled_reservation_ids: list[int] = Field(default_factory=list)
    failed_reservation_ids: list[int] = Field(default_factory=list)
    message: str = ""


class AutoTradeReentryBlockItem(BaseModel):
    id: int
    environment: Literal["demo", "prod"] = "demo"
    ticker: str
    trigger_reason: Literal["STOP_LOSS", "TAKE_PROFIT"] = "STOP_LOSS"
    blocked_at: datetime
    released_at: datetime | None = None
    note: str | None = None


class AutoTradeReentryBlocksResponse(BaseModel):
    total: int = 0
    items: list[AutoTradeReentryBlockItem] = Field(default_factory=list)


class AutoTradeReentryReleaseRequest(BaseModel):
    environment: Literal["demo", "prod"] | None = None
    ticker: str | None = None
    trigger_reason: Literal["STOP_LOSS", "TAKE_PROFIT"] | None = None
    release_all: bool = False


class AutoTradeReentryReleaseResponse(BaseModel):
    ok: bool = True
    released_count: int = 0
    message: str = ""


class PaperSection(BaseModel):
    title: str
    bullets: list[str]


class PapersSummaryResponse(BaseModel):
    updated_at: datetime
    title: str
    sections: list[PaperSection]


class ChartPoint(BaseModel):
    date: str
    open: float | None = None
    high: float | None = None
    low: float | None = None
    close: float
    volume: float | None = None


class ChartDailyResponse(BaseModel):
    code: str
    name: str
    points: list[ChartPoint]


class ChartDailyBatchRequest(BaseModel):
    tickers: list[str] = Field(default_factory=list)
    days: int = 7
    interval: str = "1d"


class ChartDailyBatchItem(BaseModel):
    code: str
    name: str
    points: list[ChartPoint] = Field(default_factory=list)
    error: str | None = None


class ChartDailyBatchResponse(BaseModel):
    as_of: datetime
    items: list[ChartDailyBatchItem] = Field(default_factory=list)


class StrategySettingsPayload(BaseModel):
    algo_version: str | None = None
    risk_preset: str = "ADAPTIVE"
    use_custom_weights: bool = False
    w_ta: float | None = None
    w_re: float | None = None
    w_rs: float | None = None
    theme_cap: int = 2
    max_gap_pct: float = 0.0
    gate_threshold: float = 0.0
    gate_quantile: float | None = None

    @classmethod
    def validate_ranges(cls, v: "StrategySettingsPayload") -> "StrategySettingsPayload":
        if v.algo_version is not None:
            vv = str(v.algo_version).strip().upper()
            if vv not in {"V1", "V2"}:
                raise ValueError("algo_version must be V1 or V2")
            v.algo_version = vv
        if v.w_ta is not None and not (0.0 <= v.w_ta <= 1.0):
            raise ValueError("w_ta out of range")
        if v.w_re is not None and not (0.0 <= v.w_re <= 1.0):
            raise ValueError("w_re out of range")
        if v.w_rs is not None and not (0.0 <= v.w_rs <= 1.0):
            raise ValueError("w_rs out of range")
        if not (0.0 <= v.max_gap_pct <= 20.0):
            raise ValueError("max_gap_pct out of range")
        if not (-0.5 <= v.gate_threshold <= 0.5):
            raise ValueError("gate_threshold out of range")
        if v.gate_quantile is not None and not (0.0 <= v.gate_quantile <= 1.0):
            raise ValueError("gate_quantile out of range")
        return v

    def compute_hash(self) -> str:
        import hashlib
        import json as _json

        data = self.model_dump()
        data["algo_version"] = (str(data.get("algo_version") or "").strip().upper() or "V2")
        raw = _json.dumps(data, sort_keys=True, ensure_ascii=False)
        return hashlib.md5(raw.encode("utf-8")).hexdigest()


class StrategySettingsResponse(BaseModel):
    settings: StrategySettingsPayload
    settings_hash: str


class InviteCreateRequest(BaseModel):
    user_code: str | None = None
    name: str | None = None
    password_mode: Literal["AUTO", "MANUAL"] = "AUTO"
    initial_password: str | None = None
    role: Literal["USER", "MASTER"] = "USER"
    expires_in_days: int = 7
    memo: str | None = None
    device_binding_enabled: bool = False


class InviteCreateResponse(BaseModel):
    user_code: str
    initial_password: str
    expires_at: datetime
    invite_status: str


class InviteMarkSentResponse(BaseModel):
    ok: bool = True
    invite_status: str


class FirstLoginRequest(BaseModel):
    user_code: str
    initial_password: str
    device_id: str | None = None
    app_version: str | None = None


class LoginRequest(BaseModel):
    user_code: str
    password: str
    device_id: str | None = None
    app_version: str | None = None


class RefreshTokenRequest(BaseModel):
    refresh_token: str
    device_id: str | None = None
    app_version: str | None = None


class LoginResponse(BaseModel):
    token: str
    expires_at: datetime
    refresh_token: str | None = None
    refresh_expires_at: datetime | None = None
    force_password_change: bool
    invite_status: str
    role: str


class ProfileRequest(BaseModel):
    name: str
    phone: str
    consent: bool = True


class PasswordChangeRequest(BaseModel):
    current_password: str | None = None
    new_password: str


class PasswordResetRequest(BaseModel):
    password_mode: Literal["AUTO", "MANUAL"] = "AUTO"
    initial_password: str | None = None
    expires_in_days: int = 7


class UserIdentityUpdateRequest(BaseModel):
    user_code: str | None = None
    name: str | None = None
    memo: str | None = None
    phone: str | None = None


class UserSummary(BaseModel):
    user_code: str
    name: str | None = None
    phone: str | None = None
    role: str
    status: str
    last_login_at: datetime | None = None
    failed_attempts: int
    locked_until: datetime | None = None
    invite_status: str
    created_at: datetime


class UserDetail(UserSummary):
    memo: str | None = None
    force_password_change: bool
    expires_at: datetime | None = None
    device_binding_enabled: bool
    bound_device_id: str | None = None


class MenuPermissionsPayload(BaseModel):
    menu_daytrade: bool = True
    menu_autotrade: bool = True
    menu_holdings: bool = True
    menu_supply: bool = True
    menu_movers: bool = True
    menu_us: bool = True
    menu_news: bool = True
    menu_longterm: bool = True
    menu_papers: bool = True
    menu_eod: bool = True
    menu_alerts: bool = True


class MenuPermissionsResponse(BaseModel):
    user_code: str
    permissions: MenuPermissionsPayload
    updated_at: datetime | None = None
    inherited_default: bool = False


class UsersListResponse(BaseModel):
    items: list[UserSummary]
    total: int


class InvitedUserSummary(UserSummary):
    invited_at: datetime | None = None


class MyInvitedUsersResponse(BaseModel):
    items: list[InvitedUserSummary]
    total: int


class LoginEventItem(BaseModel):
    timestamp: datetime
    user_code: str | None = None
    result: str
    reason_code: str
    ip: str | None = None
    device_id: str | None = None
    app_version: str | None = None


class AdminAuditItem(BaseModel):
    timestamp: datetime
    admin_user_id: int
    action: str
    detail: dict[str, Any] = Field(default_factory=dict)


class LogsResponse(BaseModel):
    items: list[LoginEventItem]
    total: int


class AuditLogsResponse(BaseModel):
    items: list[AdminAuditItem]
    total: int


class UserLoginLogSummary(BaseModel):
    success_count: int = 0
    fail_count: int = 0
    reason_counts: dict[str, int] = Field(default_factory=dict)
    last_success_at: datetime | None = None
    last_fail_at: datetime | None = None
    active_session_count: int = 0


class UserLoginLogsResponse(BaseModel):
    user: UserSummary
    total: int
    items: list[LoginEventItem]
    summary: UserLoginLogSummary


class AdminUserAutoTradeOverviewResponse(BaseModel):
    user: UserDetail
    settings: AutoTradeSettingsPayload
    broker: AutoTradeBrokerCredentialResponse
    account: AutoTradeAccountSnapshotResponse
    performance_days: int = 30
    performance_summary: AutoTradePerformanceItem | None = None
    performance_items: list[AutoTradePerformanceItem] = Field(default_factory=list)
    symbol_rules: list[AutoTradeSymbolRuleItem] = Field(default_factory=list)
    recent_orders_total: int = 0
    recent_orders: list[AutoTradeOrderItem] = Field(default_factory=list)
    source_counts: dict[str, int] = Field(default_factory=dict)
    status_counts: dict[str, int] = Field(default_factory=dict)


class AdminPushSendRequest(BaseModel):
    title: str = Field(min_length=1, max_length=60)
    body: str = Field(min_length=1, max_length=300)
    target: Literal["all", "active_7d", "test"] = "all"
    alert_type: Literal["ADMIN", "UPDATE"] = "ADMIN"
    route: str | None = None
    dry_run: bool = False


class AdminPushSendResponse(BaseModel):
    ok: bool
    target_count: int = 0
    token_count: int = 0
    sent_count: int = 0
    failed_count: int = 0
    skipped_count: int = 0
    skipped_no_token_count: int = 0
    skipped_pref_count: int = 0
    push_ready: bool = False
    message: str = ""
    sample_tokens_masked: list[str] = Field(default_factory=list)


class AdminPushStatusResponse(BaseModel):
    push_ready: bool = False
    all_device_count: int = 0
    all_token_count: int = 0
    active_7d_device_count: int = 0
    active_7d_token_count: int = 0


# --- News (Hybrid: OpenDART + RSS + external ingest) ---

class NewsMeta(BaseModel):
    source: Literal["LIVE", "CACHE", "FALLBACK"] = "LIVE"
    status: Literal["OK", "MISS", "ERROR"] = "OK"
    message: str | None = None
    generated_at: datetime
    params: dict[str, Any] = Field(default_factory=dict)


class NewsClusterLite(BaseModel):
    id: int
    theme_key: str
    event_type: str
    title: str
    summary: str | None = None
    top_tickers: list[str] = Field(default_factory=list)
    published_start: datetime
    published_end: datetime
    published_ymd: int
    article_count: int


class NewsThemeCard(BaseModel):
    theme_key: str
    hot_score: float
    cluster_count: int
    article_count: int
    impact_sum: int
    latest_published_at: datetime | None = None
    top_clusters: list[NewsClusterLite] = Field(default_factory=list)


class NewsThemesResponse(BaseModel):
    meta: NewsMeta
    themes: list[NewsThemeCard] = Field(default_factory=list)


class NewsClusterListItem(BaseModel):
    id: int
    cluster_key: str
    theme_key: str
    event_type: str
    title: str
    summary: str | None = None
    top_tickers: list[str] = Field(default_factory=list)
    published_start: datetime
    published_end: datetime
    published_ymd: int
    article_count: int
    impact_sum: int
    hot_score: float


class NewsClustersResponse(BaseModel):
    meta: NewsMeta
    clusters: list[NewsClusterListItem] = Field(default_factory=list)


class NewsStockHotItem(BaseModel):
    ticker: str
    hot_score: float
    mention_count: int
    impact_sum: int
    latest_published_at: datetime | None = None
    name: str | None = None


class NewsStocksResponse(BaseModel):
    meta: NewsMeta
    stocks: list[NewsStockHotItem] = Field(default_factory=list)


class NewsArticleItem(BaseModel):
    source: str
    source_uid: str
    url: str
    title: str
    summary: str | None = None
    published_at: datetime
    published_ymd: int
    event_type: str
    polarity: str
    impact: int
    theme_key: str
    tickers: list[str] = Field(default_factory=list)


class NewsClusterItem(BaseModel):
    id: int
    cluster_key: str
    theme_key: str
    event_type: str
    title: str
    summary: str | None = None
    top_tickers: list[str] = Field(default_factory=list)
    published_start: datetime
    published_end: datetime
    published_ymd: int
    article_count: int


class NewsClusterResponse(BaseModel):
    meta: NewsMeta
    cluster: NewsClusterItem
    articles: list[NewsArticleItem] = Field(default_factory=list)


class NewsArticlesResponse(BaseModel):
    meta: NewsMeta
    articles: list[NewsArticleItem] = Field(default_factory=list)


class NewsIngestArticle(BaseModel):
    source: str
    source_uid: str
    url: str
    title: str
    summary: str | None = None
    published_at: datetime | None = None
    event_type: str | None = None
    polarity: str | None = None
    impact: int | None = None
    theme_key: str | None = None
    tickers: list[str] | None = None


class NewsIngestRequest(BaseModel):
    articles: list[NewsIngestArticle] = Field(default_factory=list)


class NewsIngestResponse(BaseModel):
    meta: NewsMeta
    inserted: int = 0
    updated: int = 0
    clusters_updated: int = 0
    mentions_inserted: int = 0


# ── 홈 화면 추가 API 스키마 ──

class TradeFeedItem(BaseModel):
    time: str | None = None
    ticker: str | None = None
    name: str | None = None
    side: str | None = None  # BUY / SELL
    qty: int | None = None
    price: float | None = None
    pnl: float | None = None

class TradeFeedSummary(BaseModel):
    total_count: int = 0
    realized_pnl: float = 0.0
    buy_count: int = 0
    sell_count: int = 0

class TradeFeedResponse(BaseModel):
    items: list[TradeFeedItem] = Field(default_factory=list)
    total: int = 0
    summary: TradeFeedSummary | None = None

class PnlCalendarDay(BaseModel):
    date: str
    pnl: float = 0.0
    trade_count: int = 0

class PnlCalendarResponse(BaseModel):
    days: list[PnlCalendarDay] = Field(default_factory=list)
    month_total_pnl: float = 0.0
    month_trade_count: int = 0
