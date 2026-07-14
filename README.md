# NaviFinance

Bot multiusuario de Telegram para registrar ingresos y gastos personales en soles (PEN) o dólares (USD). Cada usuario queda aislado por su ID de Telegram; el bot usa long polling, por lo que no necesita un webhook ni puertos públicos.

## Requisitos

- JDK 25 y Docker Compose para desarrollo local.
- Un bot creado con [@BotFather](https://t.me/BotFather).
- Tesseract con datos de idioma español para OCR fuera del contenedor.

## Desarrollo

1. Inicia PostgreSQL:

   ```powershell
   docker compose -f compose.dev.yml up -d
   ```

2. Define credenciales y token para la sesión:

   ```powershell
   $env:TELEGRAM_BOT_TOKEN = "..."
   $env:TELEGRAM_BOT_USERNAME = "tu_bot"
   $env:QUARKUS_DATASOURCE_JDBC_URL = "jdbc:postgresql://localhost:5432/finance"
   $env:QUARKUS_DATASOURCE_USERNAME = "finance"
   $env:QUARKUS_DATASOURCE_PASSWORD = "finance"
   ```

3. Arranca Quarkus; Flyway aplica la migración automáticamente:

   ```powershell
   .\mvnw.cmd quarkus:dev
   ```

Comandos: `/start`, `/cuenta_nueva`, `/cuentas`, `/registrar`, `/resumen` y `/cancelar`. En `/registrar`, PEN es la opción inicial y se puede cambiar a USD antes de confirmar.

## Docker homelab

Compila primero el artefacto JVM y crea un archivo `.env` no versionado:

```powershell
.\mvnw.cmd package
```

```dotenv
POSTGRES_PASSWORD=cambia-esta-clave
TELEGRAM_BOT_TOKEN=token-de-botfather
TELEGRAM_BOT_USERNAME=tu_bot
```

Después inicia el stack:

```powershell
docker compose up -d --build
```

PostgreSQL usa el volumen persistente `finanzas-postgres`. Haz backups con `pg_dump` antes de actualizaciones relevantes.

## OCR

Las fotos se procesan de forma temporal: se convierten a blanco y negro y se envían a Tesseract con idioma `spa`; no se guardan. El importe y fecha detectados deben revisarse y confirmar explícitamente antes de crear una transacción. Si el OCR no detecta importe, el bot solicita ingresarlo manualmente.

La precisión depende mucho de banco, iluminación y recorte. Antes de invertir en otro motor, probar al menos cinco vouchers reales, sin incorporarlos al repositorio, y registrar aquí la tasa de aciertos:

| Muestra | Banco/app | Monto correcto | Fecha correcta | Observaciones |
| --- | --- | --- | --- | --- |
| Pendiente | Pendiente | Pendiente | Pendiente | Pendiente |
