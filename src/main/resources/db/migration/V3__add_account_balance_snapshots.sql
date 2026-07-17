ALTER TABLE cuentas
    ADD COLUMN saldo_base_pen       NUMERIC(12, 2),
    ADD COLUMN saldo_base_usd       NUMERIC(12, 2),
    ADD COLUMN saldo_configurado_en TIMESTAMP;

ALTER TABLE cuentas
    ADD CONSTRAINT ck_cuentas_saldo_base_pen_nonnegative
        CHECK (saldo_base_pen IS NULL OR saldo_base_pen >= 0),
    ADD CONSTRAINT ck_cuentas_saldo_base_usd_nonnegative
        CHECK (saldo_base_usd IS NULL OR saldo_base_usd >= 0),
    ADD CONSTRAINT ck_cuentas_saldos_complete
        CHECK (
            (saldo_base_pen IS NULL AND saldo_base_usd IS NULL AND saldo_configurado_en IS NULL)
                OR
            (saldo_base_pen IS NOT NULL AND saldo_base_usd IS NOT NULL AND saldo_configurado_en IS NOT NULL)
            );
