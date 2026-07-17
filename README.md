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

Comandos: `/start`, `/cuenta_nueva`, `/cuentas`, `/registrar`, `/resumen` y `/cancelar`. Las cuentas de débito se crean
en una sola moneda (PEN o USD) y sólo aceptan movimientos en ella. Las cuentas de crédito mantienen deuda separada en
PEN y USD: pueden registrar cargos en ambas y pagarse desde una cuenta de débito. Si la moneda pagada difiere de la
deuda a reducir, el bot pide una tasa manual expresada como `1 USD = S/ X` y guarda el importe pagado y el aplicado.

`/resumen` muestra cada cuenta por separado. Para el mes actual presenta abonos y retiros en la moneda de cada débito;
en crédito muestra cargos, pagos aplicados, deuda pendiente y los importes realmente pagados desde débito. El botón
**Ver detalle por categoría** desglosa los movimientos de esa cuenta y mes. Las cuentas creadas antes de esta
funcionalidad pedirán su configuración antes del siguiente `/resumen` o `/registrar`.

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
