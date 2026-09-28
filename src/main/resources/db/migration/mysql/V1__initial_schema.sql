CREATE TABLE app_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(10)  NOT NULL,
    enabled       BOOLEAN      NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    CONSTRAINT uk_app_user_username UNIQUE (username)
);

CREATE TABLE app_setting (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(1000)
);

CREATE TABLE stock (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol           VARCHAR(20)  NOT NULL,
    market           VARCHAR(20)  NOT NULL,
    company_name     VARCHAR(255),
    currency         VARCHAR(3),
    current_price    DECIMAL(19, 4),
    price_updated_at DATETIME(6),
    CONSTRAINT uk_stock_symbol_market UNIQUE (symbol, market)
);

CREATE TABLE trading_account (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT       NOT NULL,
    name     VARCHAR(255) NOT NULL,
    broker   VARCHAR(255),
    currency VARCHAR(3),
    CONSTRAINT uk_trading_account_owner_name UNIQUE (owner_id, name),
    CONSTRAINT fk_trading_account_owner FOREIGN KEY (owner_id) REFERENCES app_user (id)
);

CREATE TABLE holding (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_account_id BIGINT         NOT NULL,
    stock_id           BIGINT         NOT NULL,
    shares             DECIMAL(19, 6) NOT NULL,
    average_cost       DECIMAL(19, 4),
    CONSTRAINT uk_holding_account_stock UNIQUE (trading_account_id, stock_id),
    CONSTRAINT fk_holding_account FOREIGN KEY (trading_account_id) REFERENCES trading_account (id),
    CONSTRAINT fk_holding_stock FOREIGN KEY (stock_id) REFERENCES stock (id)
);

CREATE TABLE speculation_entry (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id    BIGINT         NOT NULL,
    stock_id    BIGINT         NOT NULL,
    added_at    DATETIME(6)   NOT NULL,
    added_price DECIMAL(19, 4) NOT NULL,
    added_by    VARCHAR(10)    NOT NULL,
    note        VARCHAR(1000),
    CONSTRAINT uk_speculation_owner_stock UNIQUE (owner_id, stock_id),
    CONSTRAINT fk_speculation_owner FOREIGN KEY (owner_id) REFERENCES app_user (id),
    CONSTRAINT fk_speculation_stock FOREIGN KEY (stock_id) REFERENCES stock (id)
);

CREATE TABLE saved_analysis (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id    BIGINT       NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    model       VARCHAR(255),
    duration_ms BIGINT       NOT NULL,
    prompt      LONGTEXT    NOT NULL,
    response    LONGTEXT    NOT NULL,
    CONSTRAINT fk_saved_analysis_owner FOREIGN KEY (owner_id) REFERENCES app_user (id)
);
