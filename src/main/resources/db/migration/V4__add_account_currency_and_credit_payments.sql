ALTER TABLE cuentas
    ADD COLUMN moneda CHAR(3);

ALTER TABLE cuentas
    ADD CONSTRAINT ck_cuentas_moneda
        CHECK (moneda IS NULL OR moneda IN ('PEN', 'USD'));

ALTER TABLE transacciones
    ADD COLUMN monto_pagado  NUMERIC(12, 2),
    ADD COLUMN moneda_pagada CHAR(3),
    ADD COLUMN tasa_cambio   NUMERIC(12, 6),
    ADD COLUMN operacion_id  UUID;

ALTER TABLE transacciones
    ADD CONSTRAINT ck_transacciones_monto_pagado
        CHECK (monto_pagado IS NULL OR monto_pagado > 0),
    ADD CONSTRAINT ck_transacciones_moneda_pagada
        CHECK (moneda_pagada IS NULL OR moneda_pagada IN ('PEN', 'USD')),
    ADD CONSTRAINT ck_transacciones_tasa_cambio
        CHECK (tasa_cambio IS NULL OR tasa_cambio > 0),
    ADD CONSTRAINT ck_transacciones_pago_completo
        CHECK (
            (monto_pagado IS NULL AND moneda_pagada IS NULL AND tasa_cambio IS NULL)
                OR
            (monto_pagado IS NOT NULL AND moneda_pagada IS NOT NULL)
            );

CREATE INDEX idx_transacciones_operacion ON transacciones (operacion_id)
    WHERE operacion_id IS NOT NULL;
