CREATE TABLE usuarios (
    id SERIAL PRIMARY KEY,
    telegram_id BIGINT UNIQUE NOT NULL,
    nombre TEXT,
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE cuentas (
    id SERIAL PRIMARY KEY,
    usuario_id INT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    tipo TEXT NOT NULL CHECK (tipo IN ('debito', 'credito')),
    activo BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE categorias (
    id SERIAL PRIMARY KEY,
    usuario_id INT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    CONSTRAINT uq_categorias_usuario_nombre UNIQUE (usuario_id, nombre)
);

CREATE TABLE transacciones (
    id SERIAL PRIMARY KEY,
    usuario_id INT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    cuenta_id INT NOT NULL REFERENCES cuentas(id),
    categoria_id INT NOT NULL REFERENCES categorias(id),
    tipo TEXT NOT NULL CHECK (tipo IN ('retiro', 'abono')),
    monto NUMERIC(12,2) NOT NULL CHECK (monto > 0),
    moneda CHAR(3) NOT NULL DEFAULT 'PEN' CHECK (moneda IN ('PEN', 'USD')),
    descripcion TEXT,
    origen TEXT NOT NULL CHECK (origen IN ('manual', 'ocr')),
    fecha DATE NOT NULL,
    creado_en TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_transacciones_usuario_fecha ON transacciones(usuario_id, fecha);
CREATE INDEX idx_transacciones_cuenta ON transacciones(cuenta_id);
