# FastTrack-Notify

Solución de notificaciones desacopladas y basadas en eventos para **FastTrack-Logistics**, desarrollada para la Evaluación Sumativa 3 de **JVY0101 - Java: Diseño y Construcción de Soluciones nativas en Nube**.

## 1. Arquitectura de la solución

```
Cliente (Postman)
      │ HTTP POST /orders
      ▼
┌─────────────────┐        red privada "fasttrack-net"
│   api-gateway    │  (puerto 8080, único expuesto al host)
│ Spring Cloud GW  │
└────────┬─────────┘
         │ enruta solo /orders (POST/GET)
         ▼
┌─────────────────┐
│  orders-service  │  (puerto 8081, SIN exposición pública)
│   Spring Boot    │──────► H2 (persistencia in-memory)
└────────┬─────────┘
         │ publica evento JSON
         ▼
┌─────────────────┐
│   AWS SQS        │  fasttrack-notification-queue
│ (LocalStack)     │
└────────┬─────────┘
         │ trigger / poller
         ▼
┌─────────────────┐
│ notification-    │  Función serverless (Python o Node.js)
│ function (FaaS)  │  simula envío de email/SMS
└──────────────────┘
```

El cliente nunca accede directamente a `orders-service`: todo el tráfico público pasa por el **API Gateway**, que solo tiene definidas rutas para `POST /orders` y `GET /orders`. Cualquier otro path o método no coincide con ningún predicado y el Gateway responde `404`, sin reenviar nunca el tráfico al backend.

## 2. Estructura del proyecto

```
.
├── orders-service/        # Microservicio Spring Boot (persistencia + productor SQS)
├── api-gateway/           # Spring Cloud Gateway (enrutamiento + protección)
├── notification-function/ # Función FaaS (Python y Node.js) + poller del trigger SQS
├── docker-compose.yml     # Orquestación local (docker compose up) - red bridge
├── docker-stack.yml       # Orquestación con Docker Swarm (docker stack deploy) - red overlay
└── .github/workflows/     # Pipeline CI/CD
```

## 3. Requisitos previos

- Docker y Docker Compose (v2+)
- JDK 17 y Maven (solo si se quiere compilar/testear fuera de Docker)
- Python 3.10+ con `boto3` o Node.js 18+ (para correr el poller / función FaaS localmente)
- Postman, `curl`, o `Invoke-RestMethod` (PowerShell) para las pruebas E2E

> **Nota sobre la imagen de LocalStack:** el `docker-compose.yml` fija la imagen en `localstack/localstack:3.0` en lugar de `:latest`. Versiones más recientes de LocalStack (`2026.x`) comenzaron a exigir un `LOCALSTACK_AUTH_TOKEN` válido incluso para arrancar, lo que rompe el flujo gratuito/Community que usa este proyecto. Si se actualiza la imagen en el futuro, verificar primero con `docker logs localstack_fasttrack` que no aparezca un error de `License activation failed`.

## 4. Cómo levantar todo localmente (modo desarrollo rápido)

```bash
# 1. Levantar LocalStack, orders-service y api-gateway
docker compose up -d --build

# 2. Verificar que los 3 contenedores estén corriendo
docker compose ps
```

El Gateway queda disponible en `http://localhost:8080`. `orders-service` **no** publica su puerto al host (por diseño), por lo que no es accesible en `localhost:8081`.

> **Nota sobre la red `fasttrack-net` y los dos archivos de compose:** Docker Compose (`docker compose up`) y Docker Swarm (`docker stack deploy`) tienen requisitos de red incompatibles entre sí. Compose crea contenedores sueltos y solo puede conectarlos mediante redes de scope `local` (`driver: bridge`). Swarm, en cambio, exige que los *services* usen exclusivamente redes de scope `swarm` (`driver: overlay`) — un service de Swarm directamente **no puede** unirse a una red `bridge`, sin importar lo que diga el `deploy:` del archivo. Por eso este proyecto usa **dos archivos separados**:
> - `docker-compose.yml` → para desarrollo local rápido con `docker compose up` (red `bridge`, incluye `build:` para compilar las imágenes).
> - `docker-stack.yml` → para Swarm con `docker stack deploy` (red `overlay`, usa las imágenes ya construidas, sin `build:` porque Swarm no construye imágenes).
>
> Antes de usar `docker-stack.yml`, asegúrate de haber construido las imágenes al menos una vez con `docker compose build` o `docker compose up -d --build` (sección 4), ya que Swarm las toma del daemon local por nombre (`fasttrack/orders-service:latest`, `fasttrack/api-gateway:latest`).

### 4.1 Crear una orden de prueba (a través del Gateway)

**Linux / macOS (bash):**

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
        "trackingId": "FT-0001",
        "clientName": "Juan Pérez",
        "clientEmail": "juan.perez@example.com",
        "destination": "Santiago, Chile",
        "product": "Notebook 15\"",
        "price": 499990
      }'
```

**Windows (PowerShell):** evitar `curl` con comillas simples y `-d` inline — PowerShell puede despojar las comillas dobles internas del JSON antes de pasarlo al proceso, generando un `400 Bad Request` con `JSON parse error`. La forma confiable es `Invoke-RestMethod`:

```powershell
$body = @{
    trackingId   = "FT-0001"
    clientName   = "Juan Perez"
    clientEmail  = "juan.perez@example.com"
    destination  = "Santiago, Chile"
    product      = "Notebook 15 pulgadas"
    price        = 499990
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/orders" -Method POST -ContentType "application/json" -Body $body
```

Alternativa con `curl.exe` (el binario real de Windows, no el alias de PowerShell) leyendo el cuerpo desde un archivo, que también evita el problema de escaping:

```powershell
@'
{
  "trackingId": "FT-0001",
  "clientName": "Juan Perez",
  "clientEmail": "juan.perez@example.com",
  "destination": "Santiago, Chile",
  "product": "Notebook 15 pulgadas",
  "price": 499990
}
'@ | Out-File -Encoding utf8 orden.json

curl.exe -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d "@orden.json"
```

Respuesta esperada: `201 Created` con el objeto `Order` guardado, en menos de 50 ms perceptibles para el cliente (la publicación a SQS ocurre antes de responder, pero es una operación local/rápida sobre LocalStack).

### 4.2 Verificar el acceso directo bloqueado al backend

```bash
curl -i http://localhost:8081/orders
# Esperado: conexión rechazada / no resuelve, porque el puerto no está
# publicado al host (solo accesible dentro de la red "fasttrack-net").
```

### 4.3 Ejecutar el "trigger" de la función serverless (consumidor SQS)

LocalStack **Community** (gratuito) no permite configurar un Event Source Mapping nativo Lambda↔SQS (eso requiere LocalStack Pro). Para cumplir el mismo comportamiento, se incluye un poller (`notification-function/sqs_trigger_poller.py`) que hace long-polling sobre la cola y invoca la función serverless por cada mensaje, exactamente como lo haría AWS Lambda en producción.

```bash
cd notification-function
pip install -r requirements.txt --break-system-packages   # o sin la flag, según tu entorno
python3 sqs_trigger_poller.py     # Linux/macOS
```

```powershell
cd notification-function
pip install -r requirements.txt
python sqs_trigger_poller.py      # Windows (usar 'python' o 'py', no 'python3')
```

Tras enviar una orden (paso 4.1), el poller debe imprimir en segundos el log de notificación simulada:

```
=======================================================
[FaaS Notify] NUEVO EVENTO DETECTADO EN LA COLA SQS
=======================================================
Orden ID: 1
Código de Seguimiento: FT-0001
Cliente: Juan Pérez (juan.perez@example.com)
Destino: Santiago, Chile
Detalle: Enviando correo de confirmación de despacho...
[FaaS Notify] ¡Correo de despacho enviado con éxito!
=======================================================
```

> La versión Node.js equivalente está en `notification-function/index.js`; se puede invocar de forma análoga con un pequeño wrapper de `@aws-sdk/client-sqs` si el equipo prefiere JavaScript.

## 5. Orquestación con Docker Swarm

### 5.1 Inicializar el clúster

```bash
docker swarm init
```

Esto convierte al nodo actual en **manager**. El comando entrega un token y un comando `docker swarm join` con la forma:

```
docker swarm join --token SWMTKN-1-xxxxxxxxxxxxx 192.168.x.x:2377
```

### 5.2 Unir un nodo worker

En un entorno real (otra máquina/VM), ese nodo ejecutaría el comando entregado por `docker swarm init` para unirse como **worker**:

```bash
docker swarm join --token <TOKEN_GENERADO> <IP_MANAGER>:2377
```

Como este encargo se desarrolla en una sola máquina, no es posible levantar un segundo host físico para el rol *worker*. Se documenta el comando exacto para cumplir el indicador y se explica la diferencia de roles a continuación.

### 5.3 Diferencia entre nodo Manager y nodo Worker

| Aspecto | **Manager** | **Worker** |
|---|---|---|
| Responsabilidad | Mantiene el estado del clúster (Raft), programa (schedule) los servicios y tareas | Solo ejecuta las tareas (contenedores) que el manager le asigna |
| API de Swarm | Expone la API de administración (`docker service`, `docker stack`) | No administra el clúster |
| Alta disponibilidad | Se recomienda un número impar (1, 3, 5) para quórum Raft | Puede haber tantos como se necesite para capacidad de cómputo |
| Rol de cómputo | Por defecto también puede ejecutar tareas (a menos que se le aplique `--availability drain`) | Su única función es ejecutar contenedores |

En este proyecto, al ser una sola máquina, el nodo manager también actúa como worker para poder ejecutar las réplicas de los servicios.

### 5.4 Desplegar el stack

> Antes de este paso, confirma que las imágenes ya existen localmente (de la sección 4): `docker images | findstr fasttrack` (Windows) o `docker images | grep fasttrack` (Linux/macOS) debe mostrar `fasttrack/orders-service` y `fasttrack/api-gateway`.

```bash
docker stack deploy -c docker-stack.yml fasttrack
```

Verificar:

```bash
docker stack services fasttrack
docker stack ps fasttrack
```

## 6. Escalabilidad de réplicas (demo de tolerancia a fallas)

Escalar `orders-service` a 3 réplicas en caliente:

```bash
docker service scale fasttrack_orders-service=3
```

Verificar la distribución de tareas entre réplicas:

```bash
docker service ps fasttrack_orders-service
```

Al enviar varias órdenes seguidas vía Postman al Gateway (`http://localhost:8080/orders`), Swarm distribuye automáticamente las peticiones entre las 3 réplicas usando su balanceador interno (VIP / round-robin a nivel de red overlay), sin que el API Gateway necesite saber cuántas réplicas existen: solo conoce el nombre del servicio `orders-service`.

Para simular una falla y observar la auto-recuperación:

```bash
# Obtener el ID de una de las tareas/contenedores
docker service ps fasttrack_orders-service

# Forzar la detención de un contenedor de la réplica
docker kill <container_id>

# Swarm reprograma automáticamente una nueva réplica (restart_policy: on-failure)
docker service ps fasttrack_orders-service
```

## 7. Decisiones de diseño: escalabilidad, políticas de reinicio y límites de recursos

- **Escalabilidad de réplicas**: `orders-service` se declara sin estado (*stateless*) a nivel de aplicación —toda persistencia vive en H2 dentro de cada contenedor solo para efectos de esta evaluación— lo que permite escalarlo horizontalmente sin coordinación entre réplicas. Docker Swarm enruta el tráfico entre ellas usando su red *overlay* y *Virtual IP (VIP)* del servicio.
- **Políticas de reinicio (`restart_policy`)**: se configuró `condition: on-failure` con `max_attempts: 3`. Esto evita reinicios infinitos ante un fallo persistente (por ejemplo, un error de configuración), mientras sigue recuperando automáticamente fallos transitorios (caída momentánea, OOM puntual).
- **Límites de memoria/CPU (`resources.limits` / `reservations`)**: se limitó cada réplica de `orders-service` a 0.5 CPU / 512 MB como techo, con una reserva mínima garantizada de 0.25 CPU / 256 MB. Esto previene que una réplica con fuga de memoria o picos de carga consuma todos los recursos del nodo y afecte a las demás réplicas o servicios (incluyendo `localstack` y `api-gateway`).
- **API Gateway como único punto de entrada**: al no publicar el puerto de `orders-service` hacia el host (solo `expose`, sin `ports`), se reduce la superficie de ataque: el backend solo es alcanzable desde dentro de la red `fasttrack-net`.

## 8. Pipeline CI/CD

Definido en `.github/workflows/ci-cd.yml`, se dispara en cada `push` a `main` y ejecuta, en orden:

1. **build-and-test**: `mvn clean test` para `orders-service` y `api-gateway`, y empaquetado de ambos `.jar`.
2. **docker-build**: construcción de las imágenes Docker multi-stage de ambos servicios y simulación del `push` al registro de contenedores.
3. **provision-cloud**: levanta LocalStack en el runner y aprovisiona la cola `fasttrack-notification-queue` vía AWS CLI, simulando el aprovisionamiento de infraestructura cloud.
4. **deploy-ready**: confirma que el pipeline completo finalizó correctamente y que el sistema queda listo para `docker stack deploy`.

## 9. Resumen de puertos

| Servicio | Puerto interno | Expuesto al host | Acceso |
|---|---|---|---|
| api-gateway | 8080 | ✅ `8080:8080` | Público (único punto de entrada) |
| orders-service | 8081 | ❌ solo `expose` | Privado (red `fasttrack-net`) |
| localstack | 4566 | ✅ `4566:4566` | Público (solo para fines de desarrollo/demo) |

## 10. Troubleshooting (problemas reales encontrados durante las pruebas)

### 10.1 `UnknownHostException: localstack` al usar `docker compose up`

**Síntoma:** `orders-service` falla en el arranque (o el `POST /orders` responde `500`) con un stack trace de AWS SDK terminando en `java.net.UnknownHostException: localstack`.

**Causa:** la red `fasttrack-net` quedó creada en un intento anterior como `overlay`/scope `swarm` (por ejemplo, si antes se ejecutó `docker stack deploy` con un archivo que declaraba esa misma red con ese nombre). Al volver a hacer `docker compose up`, Compose reutiliza la red existente tal cual está, y los contenedores quedan sin DNS común entre sí.

**Solución:**
```bash
docker compose down
docker network ls                  # verificar si fasttrack-net sigue como overlay/swarm
docker network rm ev3dj_fasttrack-net   # (el nombre puede variar según el nombre del proyecto)
docker compose up -d --build
docker network ls                  # confirmar que ahora aparece como bridge/local
```

### 10.2 `failed to create service ...: Only networks scoped to the swarm can be used` al usar `docker stack deploy`

**Síntoma:** al ejecutar `docker stack deploy -c <archivo>.yml fasttrack`, Docker responde:
```
failed to create service fasttrack_orders-service: Error response from daemon:
The network fasttrack_fasttrack-net cannot be used with services.
Only networks scoped to the swarm can be used, such as those created with the overlay driver.
```

**Causa:** a diferencia de lo que se podría asumir, **Docker Swarm no convierte automáticamente** una red declarada como `bridge` en una red `overlay` al hacer `stack deploy`. Si el archivo de compose usado para Swarm declara `driver: bridge` (el mismo archivo que usa `docker compose up` localmente), Swarm intenta crear esa red tal cual —como `bridge`, scope `local`— y luego falla porque los *services* de Swarm exigen redes de scope `swarm` (`overlay`).

**Solución:** usar un archivo separado para Swarm. Este proyecto incluye `docker-stack.yml`, idéntico en estructura a `docker-compose.yml` pero con `driver: overlay` en la red y sin la clave `build:` (Swarm no construye imágenes, solo las despliega desde las que ya existen en el daemon local). Ver sección 5.4.

### 10.3 LocalStack se cae con `Exited (55)` — `License activation failed`

**Síntoma:** `docker compose ps` no muestra el servicio `localstack` corriendo, o aparece como `Exited`. Revisando el log:
```bash
docker logs localstack_fasttrack          # modo docker compose
docker service logs fasttrack_localstack  # modo docker stack / Swarm
```
se ve:
```
License activation failed! 🔑❌
Reason: No credentials were found in the environment...
```

**Causa:** la imagen `localstack/localstack:latest` puede apuntar a una versión que exige un `LOCALSTACK_AUTH_TOKEN` (funcionalidad Pro) incluso para servicios gratuitos como SQS.

**Solución:** fijar la imagen a una versión Community estable, tanto en `docker-compose.yml` como en `docker-stack.yml` (ya aplicado en este repo):
```yaml
localstack:
  image: localstack/localstack:3.0   # NO usar ':latest'
```

### 10.4 `400 Bad Request` / `JSON parse error` al usar `curl` desde PowerShell

**Síntoma:** el POST llega al servidor (se ve en los logs de `orders-service`) pero responde `400` con `JSON parse error: Unexpected character ('t'...): was expecting double-quote to start field name`.

**Causa:** PowerShell interpreta las comillas dobles dentro de un string aunque esté envuelto en comillas simples, y puede despojarlas antes de pasar el cuerpo a `curl.exe`, dejando el JSON sin comillas alrededor de los nombres de campo.

**Solución:** usar `Invoke-RestMethod` (nativo de PowerShell) o leer el cuerpo desde un archivo con `curl.exe -d "@archivo.json"`. Ver los ejemplos completos en la sección 4.1.

### 10.5 `python3: command not found` en Windows

**Síntoma:** `python3 sqs_trigger_poller.py` abre la Microsoft Store o dice "Python was not found".

**Causa:** en Windows, el ejecutable normalmente se llama `python` (o se invoca vía el launcher `py`), no `python3`. El alias `python3` solo existe por defecto en Linux/macOS.

**Solución:** usar `python sqs_trigger_poller.py` o `py sqs_trigger_poller.py` en Windows.
