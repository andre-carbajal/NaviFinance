# NaviFinance

Bot multiusuario de Telegram para registrar ingresos y gastos personales en soles (PEN) o dólares (USD). Cada usuario
queda aislado por su ID de Telegram; el bot usa long polling, por lo que no necesita un webhook ni puertos públicos.

## Requisitos

- JDK 25 y Docker Compose para desarrollo local.
- Un bot creado con [@BotFather](https://t.me/BotFather).
- Espacio libre suficiente para el modelo de visión `qwen3-vl:2b-instruct`.

## Desarrollo

1. Inicia PostgreSQL y Ollama. El servicio `ollama-init` descarga `qwen3-vl:2b-instruct` automáticamente la primera vez:

   ```powershell
   docker compose -f compose.dev.yml up -d
   ```

2. Define credenciales y token para la sesión:

   ```powershell
   $env:TELEGRAM_BOT_TOKEN = "..."
   $env:QUARKUS_DATASOURCE_JDBC_URL = "jdbc:postgresql://localhost:5432/finance"
   $env:QUARKUS_DATASOURCE_USERNAME = "finance"
   $env:QUARKUS_DATASOURCE_PASSWORD = "finance"
   $env:OLLAMA_BASE_URL = "http://localhost:11434"
   ```

3. Arranca Quarkus; Flyway aplica la migración automáticamente:

   ```powershell
   .\mvnw.cmd quarkus:dev
   ```

Comandos: `/start`, `/cuenta_nueva`, `/cuentas`, `/registrar`, `/resumen` y `/cancelar`. Al crear una cuenta, el bot
solicita sus saldos actuales en PEN y USD (se puede ingresar `0`). En `/registrar`, PEN es la opción inicial y se puede
cambiar a USD antes de confirmar.

`/resumen` muestra cada cuenta por separado. Para el mes actual presenta abonos y retiros en ambas monedas, junto con el
saldo disponible de cuentas de débito o la deuda pendiente de cuentas de crédito. El botón **Ver detalle por categoría**
desglosa los movimientos de esa cuenta y mes. Las cuentas creadas antes de esta funcionalidad pedirán sus saldos una
sola
vez antes del siguiente `/resumen` o `/registrar`.

## Docker homelab

Compila primero el artefacto JVM y crea un archivo `.env` no versionado:

```powershell
.\mvnw.cmd package
```

```dotenv
POSTGRES_PASSWORD=cambia-esta-clave
TELEGRAM_BOT_TOKEN=token-de-botfather
OLLAMA_MODEL=qwen3-vl:2b-instruct
```

Después inicia el stack:

```powershell
docker compose up -d --build
```

PostgreSQL y Ollama usan volúmenes persistentes. Haz backups con `pg_dump` antes de actualizaciones relevantes. Puedes
comprobar el modelo con `docker compose exec ollama ollama list`.

## Lectura de vouchers con Moondream

Las fotos se procesan de forma temporal: se reducen a un máximo de 1600 px, se codifican en memoria y se envían a la API
local de Ollama; no se guardan. Moondream propone si es retiro o abono, importe, moneda, fecha, descripción y banco/app
de origen. Estos datos siempre pasan por revisión y confirmación explícita antes de crear una transacción.

En CPU, el análisis normalmente puede tardar entre 5 y 20 segundos. Si Ollama no responde, el modelo no está disponible
o la salida no es válida, el bot deriva al flujo manual `/registrar`. La precisión depende del voucher, iluminación y
recorte; prueba muestras reales sin incorporarlas al repositorio:

| Muestra   | Banco/app | Monto correcto | Fecha correcta | Observaciones |
|-----------|-----------|----------------|----------------|---------------|
| Pendiente | Pendiente | Pendiente      | Pendiente      | Pendiente     |
