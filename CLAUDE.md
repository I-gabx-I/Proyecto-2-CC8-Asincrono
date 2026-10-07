# CLAUDE.md — Contexto para asistentes de IA

Lee este archivo completo antes de responder o modificar algo. La especificación detallada está en **`docs/PROTOCOLO.md`**: es la fuente de verdad (formatos, estado de cada parte, decisiones, resultados medidos y pendientes). Si algo aquí contradice a `PROTOCOLO.md`, manda `PROTOCOLO.md`.

## 1. Qué es el proyecto

Servidor Java 21 que sirve imágenes de decenas de GB a varios navegadores a la vez, enviando solo los tiles de la vista actual. HTTP/1.1 y WebSocket están implementados a mano sobre sockets. Encima va **PIMG**, el protocolo propio: el cliente informa su vista (`VIEWPORT` con `SEQ`), y el servidor decide qué tiles enviar (del centro hacia afuera), recuerda lo enviado a cada cliente y cancela lo pendiente de vistas abandonadas.

## 2. Reglas de evaluación (condicionan todas las decisiones)

- Java 20/21 en el servidor. **Sin librerías externas y sin internet** al calificar. El frontend no puede pedir nada a servidores externos (ni CDN).
- HTTP solo para archivos iniciales; la imagen viaja por el protocolo propio.
- **No es una galería ni un simple zoom.** La pirámide de niveles/tiles **es la base y no da puntos**.
- **40 % funcionamiento y usabilidad, 60 % protocolo.** El protocolo debe controlar la transmisión y recuperarse de fallos usando o **adaptando** mecanismos tipo TCP: Selective Repeat, Go-Back-N, SACK, ventana deslizante, control de flujo, control de congestión, Slow Start. No debe dejar al usuario desatendido.
- Se valida con DevTools del navegador (peticiones, caché).
- Imágenes de evaluación: 17, 28, 55 y 93 GB (punteo máximo 20, 40, 80 y 115). Son **números con dígitos de 3×5 px** que deben **leerse** en la máxima definición. La de 93 GB es PNG RGB 8 bits, 176 393 × 176 393, **sin compresión** (bloques deflate *stored*), sin entrelazar.
- Se trabaja **en pareja** (Marcos y Samuel).
- **Mecanismos aprobados por el ingeniero (los nuestros, `pimg.v2`):** (1) FEC con paridad XOR entrelazada (RFC 5109); (2) control del ritmo con controlador PI sobre la ocupación del búfer de recepción, con reportes estilo RTCP (RFC 3550, PIE RFC 8033); (3) sincronización del estado de caché con filtros de Bloom (Summary Cache); (4) planificación por plazos EDF. Caché del servidor: ARC.
- **Ideas que NO se pueden usar** (tomadas por otros grupos, según el ingeniero): imagen "como stream" como idea central; Strong/Weak/Diff ACK con anillo de vista previa; LRU como aporte; Selective Repeat; SACK; Slow Start; NACK; ACK acumulativo; control de congestión por retardo (LEDBAT/Vegas); cubeta de tokens; control de flujo por créditos; recuperación parcial por relevancia (PR-SCTP). No reintroducirlas, ni siquiera con otro nombre: por eso `GET_TILE` y `EVICT` se eliminaron en v2.

## 3. Arquitectura

```
src/pimg/
├── Main.java            Arma todo: Catalogo + TileCache compartidos, Router, WebSocketHandler con fábrica de SesionPimg
├── http/                HttpServer (hilo virtual por conexión, keep-alive), HttpRequest (parser byte a byte),
│                        HttpResponse, StaticFileHandler (anti path traversal), Router, RequestHandler, Conexion
├── websocket/           WebSocketHandler (handshake, heartbeat), WebSocketConnection (frames, ReentrantLock),
│                        WebSocketListener (una instancia por conexión), WebSocketException, EchoListener (prueba, sin uso)
├── protocol/            SesionPimg (máquina de estados, cola EDF, emisor con pacing PI, FEC, estado de la caché
│                        por Bloom), Enlace (salida directa o red simulada), Mensaje, TileFrame (binario 28 B +
│                        CRC32, TILE y PARIDAD), Vista (tiles visibles y distancia), PimgException
├── tiles/               PyramidLayout (matemática, compartida), TileStore (formato en disco),
│                        Catalogo (lee meta.json), TileCache (ARC compartida en bytes; CacheArc,
│                        CacheLru con --lru, PoliticaCache)
└── ingest/              IngestMain, ImageSource (interfaz), ImageIOSource, PyramidBuilder (cascada),
                         Reductor (2x2), TileEncoderPool (hilos de plataforma + backpressure), Franja,
                         IngestaAutomatica (WatchService sobre data/entrada, ingesta en proceso aparte)
web/js/                  app.js (composición, re-declaración de vista), pimg.js (protocolo), visor.js (cámara
                         continua, relleno con ancestros, fundido), cache.js (LRU con close), crc32.js, panel.js,
                         grafica.js (R y Q del PI)

src/pimg/transporte/     Lógica pura, sin sockets: PlanificadorEDF, RedSimulada, FecXor, ControladorPI, FiltroBloom
web/js/transporte/       fec.js, bloom.js, reportes.js
```

**Reglas de dependencia:** `protocol → websocket → http`, `protocol → transporte` y `protocol → tiles ← ingest`. `transporte` no depende de nada del proyecto. `http` nunca importa `websocket`; `tiles` nunca importa sockets. Solo `Main` conecta. No mezclar lógicas entre paquetes.

## 4. Comandos

```powershell
.\build.bat                                    # javac --release 21 -encoding UTF-8 --source-path src (Main + IngestMain)
.\run.bat                                      # java -Xmx512m -cp out pimg.Main 8080
.\run.bat --sim                                # red simulada: pérdida, ancho de banda y latencia desde el panel
.\run.bat --sim --sin-pi                       # experimento: sin control de ritmo (comparación con PI)
java -cp "out;tools\out" ProbarEDF             # también ProbarRed, ProbarFec, ProbarPI, ProbarBloom
.\ingest.bat data\input\<archivo> <id>         # pirámide en data/tiles/<id>/
                                               # o copiar el archivo a data\entrada con el servidor corriendo (ingesta automática)
.\ingest.bat --plan <ancho> <alto>             # solo calcula niveles y tiles
java -cp tools\out PngInfo "<ruta.png>"        # cabecera de un PNG
```

En PowerShell: los `.bat` se ejecutan con `.\`, y curl es `curl.exe` (`curl` es un alias de `Invoke-WebRequest`). La carpeta del usuario tiene espacios: usar rutas relativas.

## 5. Restricciones técnicas (no romperlas)

- **Solo JDK:** `java.*`, `javax.imageio`, `java.util.zip`. Nada en `lib/` sin justificarlo en `docs/DECISIONES.md`.
- Compilar con `--release 21`; no usar APIs posteriores a Java 21.
- Hilos virtuales para conexiones, **sin pool**. Pool de **hilos de plataforma** para trabajo de CPU.
- `ReentrantLock` en lugar de `synchronized` en código que corre en hilos virtuales.
- El parser HTTP lee **byte por byte** (el mismo stream lo usa WebSocket después del handshake).
- La memoria debe quedar acotada por el ancho de la imagen, nunca por su tamaño total.
- `data/` nunca va al repositorio.

## 6. Lecciones ya aprendidas

- `Graphics2D.drawImage` para convertir tipos de imagen es ~12× más lento que reordenar bytes a mano.
- ImageIO usa archivos temporales como caché al escribir si no se usa `MemoryCacheImageOutputStream`.
- **El lector PNG del JDK descomprime desde el inicio en cada lectura por región** (costo cuadrático): para PNG grandes hace falta el `PngSource` propio en streaming.
- La cola de envío debe caber la vista máxima permitida (por eso `COLA_MAX = 300` con vista ≤ 4096 px).
- Un tile se marca como enviado **al enviarlo**, no al encolarlo.
- El cliente acepta todos los tiles válidos de la imagen actual; descartarlos desincroniza el registro del servidor.
- En localhost no hay pérdidas ni congestión: para demostrar FEC y el controlador PI hace falta el modo de red simulada.
- Los hashes del filtro de Bloom deben coincidir bit a bit entre Java y JS: usar los vectores de prueba de `docs/PROTOCOLO.md` §13.3 (en JS, `Math.imul` y `>>> 0`; en Java, `Integer.remainderUnsigned`).
- Interrumpir un hilo que lee de un FileChannel lo cierra para todos: TileStore reabre el canal (D-45). No compartir canales sin manejar ClosedChannelException.

## 7. Estado y próximos pasos

Funcional y probado (fases 1 a 8, todas en `main`): ingesta en cascada, HTTP/WebSocket propios, `pimg.v2` completo (EDF, red simulada, FEC, controlador PI, filtros de Bloom con `RESUME` y re-declaración de vista), cliente con zoom continuo, cachés LRU, cancelación. La especificación está en `docs/PROTOCOLO.md` (§0 resume qué cambió). Orden de trabajo, una rama por fase:

1. ✅ `feat/png-source`: `PngSource` en streaming y prueba con la imagen de 93 GB en la PC de escritorio (1 TB).
2. ✅ `feat/almacen-empaquetado`: un archivo por nivel + índice; ingesta reanudable (~635 000 tiles).
3. ✅ `feat/legibilidad`: tiles PNG sin pérdida en niveles altos + zoom > 1:1 sin suavizado.
4. ✅ `feat/edf`: cabecera v2 con `NUM` + cola por plazos (§8, §14).
5. ✅ `feat/red-simulada`: pérdida, ancho de banda y latencia; controles en el panel (§16).
6. ✅ `feat/fec`: paridad XOR entrelazada (§11).
7. ✅ `feat/control-pi`: `REPORT`, controlador PI, `CTRL` y gráficas (§12).
8. ✅ `feat/bloom-resume`: filtro de Bloom, `RESUME` y re-declaración de vista; retirado `EVICT` y `GET_TILE` (§13, §15).
9. ✅ `feat/extras`: ARC en el servidor, ingesta automática (`WatchService`, D-44), coordenadas / "ir a x, y".
10. **Siguiente:** pruebas finales con JDK 21 sin internet.
11. Documento final estilo RFC 9293.

## 8. Cómo colaborar con el equipo

- Explicar **el porqué** de cada decisión, no solo entregar código. Las decisiones importantes se registran en `docs/DECISIONES.md` y se reflejan en `docs/PROTOCOLO.md`.
- Trabajar **paso a paso**: el equipo crea los archivos. Entregar archivo por archivo con su propósito, qué comando ejecutar y qué resultado esperar.
- Proponer siempre una **prueba verificable** (salida esperada, números) antes de pasar al siguiente paso.
- Idioma: español. Identificadores del código en español.
- Ser honesto sobre lo no verificado: marcar estimaciones como estimaciones.