# Plan de Fases — Proyecto 2: Servidor Asíncrono de Imágenes

**Curso:** Ciencias de la Computación VIII
**Autores:** Marcos Masaya, Samuel Caal
**Stack:** Java 21 (servidor, sin dependencias externas salvo que se justifique) · HTML/JS/CSS (cliente)
**Condiciones de evaluación:** sin internet · imagen de prueba de ~100 GB

> Documento vivo. Cada fase se marca al cumplir su **criterio de terminado**.
> Las decisiones importantes se registran en `docs/DECISIONES.md`.

---

## Estado general

| Fase | Nombre | Responsable | Estado |
|---|---|---|---|
| 0 | Setup y validación de riesgos | Ambos | ⬜ |
| 1 | Especificación del protocolo v0 | Ambos | ⬜ |
| 2 | Servidor HTTP propio | Por definir | ⬜ |
| 3 | WebSocket (RFC 6455) | Por definir | ⬜ |
| 4 | Ingesta y pirámide de tiles | Por definir | ⬜ |
| 5 | Protocol Image en el servidor | Por definir | ⬜ |
| 6 | Cliente web | Por definir | ⬜ |
| 7 | Robustez y pruebas | Ambos | ⬜ |
| 8 | Documento del protocolo | Ambos | ⬜ |
| 9 | Preparación de entrega y defensa | Ambos | ⬜ |

Leyenda: ⬜ pendiente · 🟨 en progreso · ✅ terminado

**Paralelización:** una vez cerrada la Fase 1, las Fases 2-5 (servidor) y la Fase 6 (cliente) pueden avanzar en paralelo, porque ambos trabajan contra el mismo contrato (`PROTOCOLO.md`).

---

## Fase 0 — Setup y validación de riesgos

**Objetivo:** tener el repo listo y confirmar, antes de diseñar más, que Java puede leer la imagen de prueba.

- [ ] Estructura de carpetas, `.gitignore` (excluir `data/`, imágenes, binarios), README y CLAUDE.md iniciales
- [ ] Confirmar JDK 21 instalado en ambas máquinas (`java -version`)
- [ ] Decidir sistema de build: `javac` + script, o Maven/Gradle **que compile offline**
- [ ] **Averiguar el formato de la imagen de 100 GB** (TIFF, BigTIFF, PSB, PNG…) y sus dimensiones
- [ ] Prueba de concepto (*spike*): leer una región arbitraria de una imagen grande con `ImageReader` + `ImageReadParam.setSourceRegion`
  - [ ] Verificar si el lector TIFF del JDK soporta BigTIFF
  - [ ] Si no, evaluar una librería alojada localmente en `lib/` (p. ej. TwelveMonkeys) y registrar la decisión
  - [ ] Medir memoria usada y tiempo por región
- [ ] Conseguir imágenes de prueba: una pequeña (~50-200 MB) para desarrollo y una grande (>2 GB)
- [ ] Estimar para la imagen de 100 GB: número de niveles, cantidad de tiles, espacio en disco y tiempo de generación

**Criterio de terminado:** el spike lee regiones de una imagen grande sin cargarla completa y el formato de la imagen de prueba está confirmado.

---

## Fase 1 — Especificación del protocolo v0

**Objetivo:** fijar el contrato entre cliente y servidor antes de programar.

- [ ] Redactar `docs/PROTOCOLO.md` v0:
  - [ ] Pila de capas: Protocol Image / WebSocket / HTTP (solo Upgrade) / TCP
  - [ ] Comandos de control en texto (`HELLO`, `LIST`, `OPEN`, `META`, `VIEWPORT`, `GET_TILE`, `CANCEL`, `RESUME`, `ERROR`)
  - [ ] Formato binario del mensaje `TILE` (cabecera: tipo, seq, z, x, y, formato, longitud, CRC32)
  - [ ] Máquina de estados de la sesión (CONNECTED → READY → IMAGE_OPEN → CLOSED)
  - [ ] Tabla de códigos de error
  - [ ] Diagramas de secuencia: arranque, navegación, reconexión
- [ ] Definir el sistema de coordenadas: nivel 0 = menor resolución; origen (0,0) arriba a la izquierda
- [ ] Definir el tamaño de tile (256 o 512) y el formato (JPEG/PNG) y registrarlo en DECISIONES.md
- [ ] Decidir el modelo de concurrencia (Virtual Threads o NIO) y registrarlo en DECISIONES.md

**Criterio de terminado:** ambos aprueban PROTOCOLO.md v0 y pueden programar sin consultarse sobre formatos.

---

## Fase 2 — Servidor HTTP propio

**Objetivo:** servir los archivos del cliente sobre sockets TCP, atendiendo múltiples clientes.

- [ ] `ServerSocket` + ejecutor de hilos virtuales (o canal asíncrono, según la Fase 1)
- [ ] Parser de peticiones HTTP/1.1 (línea de petición, cabeceras) — RFC 9112
- [ ] Servir archivos estáticos desde `web/` con `Content-Type` y `Content-Length` correctos
- [ ] Protección contra *path traversal* (`../`)
- [ ] Respuestas 200, 400, 404, 405, 500
- [ ] Soporte de `Connection: keep-alive`
- [ ] Log de peticiones

**Criterio de terminado:** el navegador carga `index.html` con su JS y CSS, con varias pestañas a la vez.

---

## Fase 3 — WebSocket (RFC 6455)

**Objetivo:** hacer el Upgrade y manejar frames manualmente.

- [ ] Detectar `Upgrade: websocket` y validar las cabeceras
- [ ] Calcular `Sec-WebSocket-Accept` (SHA-1 + Base64 con el GUID del RFC) y responder `101 Switching Protocols`
- [ ] Lectura de frames: FIN, opcode, máscara (obligatoria del cliente al servidor), longitudes de 7, 16 y 64 bits
- [ ] Escritura de frames de texto (0x1) y binarios (0x2), sin máscara
- [ ] Reensamblado de mensajes fragmentados (opcode 0x0)
- [ ] Frames de control: PING (0x9), PONG (0xA), CLOSE (0x8) con código
- [ ] Heartbeat periódico y cierre de conexiones zombie
- [ ] Escritura thread-safe por conexión (un único escritor o un lock)

**Criterio de terminado:** echo funcional con el `WebSocket` nativo del navegador, con mensajes grandes y cierre limpio.

---

## Fase 4 — Ingesta y pirámide de tiles

**Objetivo:** convertir una imagen gigante en una pirámide multirresolución sin cargarla completa en memoria.

- [ ] Lectura por franjas o regiones según el formato confirmado en la Fase 0 (una sola pasada si el formato es secuencial)
- [ ] Generación del nivel máximo (tiles de resolución original)
- [ ] Construcción ascendente de niveles: cada 4 tiles se reducen a 1
- [ ] Formato de almacenamiento:
  - [ ] Versión simple: `tiles/{img}/{z}/{x}_{y}.jpg` (para desarrollo)
  - [ ] Versión final: un archivo empaquetado por nivel + índice de offsets (necesaria por la cantidad de tiles de la imagen de 100 GB)
- [ ] Generar `meta.json` por imagen (ancho, alto, tile size, niveles, formato)
- [ ] `WatchService` sobre `data/input/` + cola de procesamiento en un pool separado
- [ ] Estados de la imagen: `PROCESSING` (con %), `READY`, `FAILED`
- [ ] Reanudación: si el proceso se interrumpe, no empezar desde cero
- [ ] Medir tiempo y espacio con la imagen grande

**Criterio de terminado:** una imagen nueva copiada en `data/input/` termina en estado `READY` con su pirámide completa, usando memoria acotada.

---

## Fase 5 — Protocol Image en el servidor

**Objetivo:** implementar la lógica del protocolo y el control de resolución por cliente.

- [ ] Parser y despachador de comandos de texto
- [ ] Sesión por cliente: imagen abierta, nivel actual, tiles enviados, último `seq`
- [ ] `LIST`, `OPEN` → `META`
- [ ] `VIEWPORT` → cálculo de tiles visibles, orden del centro hacia afuera, omitir los ya enviados
- [ ] Cola de envío por cliente con límite (backpressure) y descarte al llegar un `seq` nuevo
- [ ] `GET_TILE`, `CANCEL`, `RESUME`
- [ ] Caché LRU de tiles en RAM, compartida y con límite en bytes
- [ ] Construcción del mensaje binario `TILE` con CRC32
- [ ] Manejo de errores con `ERROR|CODE|MSG`

**Criterio de terminado:** un cliente de prueba navega una imagen y el servidor solo envía los tiles necesarios; se cancelan los obsoletos.

---

## Fase 6 — Cliente web

**Objetivo:** visualizar y navegar la imagen gestionando la memoria del navegador.

- [ ] `index.html` + CSS; todas las librerías servidas localmente (sin CDN)
- [ ] Conexión WebSocket y flujo `HELLO` → `LIST` → `OPEN`
- [ ] Selector de imágenes con su estado (incluido el % de procesamiento)
- [ ] Render en `<canvas>` según nivel y desplazamiento
- [ ] Arrastre (pan) con throttling; cambio de nivel con debouncing
- [ ] Envío de `VIEWPORT` con `seq` creciente; descarte de tiles con `seq` viejo
- [ ] Decodificación de la cabecera binaria y verificación del CRC32
- [ ] Caché LRU con límite y liberación explícita (`ImageBitmap.close()`)
- [ ] Relleno temporal con el nivel anterior escalado mientras llegan los nuevos tiles
- [ ] Tabla de peticiones pendientes con timeout y reintento (`GET_TILE`)
- [ ] Reconexión con backoff exponencial + `RESUME`
- [ ] Panel de depuración: tiles en memoria, bytes recibidos, nivel actual, peticiones pendientes

**Criterio de terminado:** se navega la imagen con fluidez, el panel muestra memoria acotada y los bytes recibidos son muy inferiores al tamaño de la imagen.

---

## Fase 7 — Robustez y pruebas

**Objetivo:** demostrar concurrencia, eficiencia y tolerancia a fallos.

- [ ] Varios clientes simultáneos (pestañas y/o cliente de carga en Java)
- [ ] Simular caída de conexión y verificar la reconexión
- [ ] Cliente lento: verificar backpressure
- [ ] Medir: bytes transferidos vs. tamaño total, tiempos de respuesta, uso de RAM del servidor y del navegador
- [ ] Prueba completa con la imagen de ~100 GB
- [ ] Prueba del sistema **sin internet**
- [ ] Revisar fugas de memoria en una sesión larga

**Criterio de terminado:** resultados medidos y registrados, listos para el documento.

---

## Fase 8 — Documento del protocolo (35%)

**Objetivo:** documento coherente, conciso y con referencias.

- [ ] Problema y justificación de la pirámide / quadtree implícito
- [ ] Arquitectura y pila de capas
- [ ] Especificación completa del Protocol Image (desde PROTOCOLO.md)
- [ ] Concurrencia, cachés y gestión de memoria
- [ ] Manejo de errores y confiabilidad
- [ ] Resultados de pruebas (Fase 7)
- [ ] Referencias: RFC 9110, RFC 9112, RFC 6455, RFC 9293, RFC 3174, RFC 4648, OSGeo TMS, IIIF Image API, Deep Zoom

**Criterio de terminado:** documento revisado por ambos y consistente con el código.

---

## Fase 9 — Preparación de entrega y defensa

- [ ] Pirámide de la imagen de 100 GB **ya generada** antes de la calificación
- [ ] Imagen pequeña preparada para demostrar la ingesta en vivo
- [ ] Guion de demo: carga inicial → navegación → panel de depuración → varios clientes → reconexión → ingesta
- [ ] Compilación limpia desde cero en una máquina sin internet
- [ ] Entrega en GES + agendar calificación
- [ ] Repaso de preguntas probables (por qué WebSocket, por qué TCP, por qué quadtree implícito, Virtual Threads vs NIO)

---

## Riesgos abiertos

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Java no lee el formato de la imagen de 100 GB | Crítico | Spike en la Fase 0; librería local si hace falta |
| Tiempo de generación de la pirámide (horas) | Alto | Reanudación; generar con anticipación |
| Espacio en disco insuficiente | Alto | Estimar en la Fase 0; compresión JPEG |
| Demasiados archivos de tiles | Medio | Almacenamiento empaquetado por nivel |
| Dependencias que requieran internet al compilar | Alto | Todo en el repo o en `lib/`; probar offline |