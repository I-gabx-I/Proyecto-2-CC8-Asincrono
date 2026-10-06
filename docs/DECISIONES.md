# Registro de decisiones técnicas

Cada decisión importante del proyecto, con su contexto, la alternativa descartada y el porqué. Sirve para defender el diseño: cuando el jurado pregunte "¿por qué así?", la respuesta está aquí. La especificación completa está en [`PROTOCOLO.md`](PROTOCOLO.md); la tabla resumida de decisiones, en su §23.

**Formato:** `D-NN · Título` · Fecha · Estado (**Vigente**, **Reemplazada por D-NN**, **Propuesta**).

---

## Arquitectura y concurrencia

### D-01 · HTTP/1.1 y WebSocket implementados a mano · 2026-09-22 · Vigente
- **Contexto:** el curso exige protocolo propio y prohíbe dependencias externas.
- **Decisión:** parser HTTP y frames RFC 6455 propios sobre `java.net.Socket`.
- **Descartado:** `com.sun.net.httpserver` (no soporta WebSocket) y librerías externas.
- **Por qué:** control total del ciclo de vida de la conexión; el handshake se verificó contra el ejemplo de RFC 6455 §1.3.

### D-02 · Un hilo virtual por conexión, sin pool · 2026-09-22 · Vigente
- **Decisión:** `Executors.newVirtualThreadPerTaskExecutor()`.
- **Descartado:** `newFixedThreadPool(n)`.
- **Por qué:** un hilo virtual cuesta unos cientos de bytes; un pool fijo limitaría los clientes simultáneos a `n`. Reutilizar hilos virtuales es un antipatrón (JEP 444). Para limitar clientes se usaría un `Semaphore`.

### D-03 · Pool de hilos de plataforma para comprimir tiles · 2026-09-23 · Vigente
- **Por qué:** comprimir es trabajo de CPU; los hilos virtuales solo ayudan con E/S. Cola acotada + `CallerRunsPolicy` para contrapresión.

### D-04 · `ReentrantLock` en lugar de `synchronized` · 2026-09-23 · Vigente
- **Por qué:** en Java 21, bloquearse dentro de `synchronized` fija el hilo virtual a su hilo portador (*pinning*).

### D-05 · El parser HTTP lee byte por byte · 2026-09-22 · Vigente
- **Por qué:** un `BufferedReader` lee por adelantado y se "comería" los primeros frames WebSocket que llegan tras el handshake por el mismo stream.

### D-06 · Paquetes con dependencias en una sola dirección · 2026-09-22 · Vigente
- **Regla:** `protocol → websocket → http`, `protocol → transporte`, `protocol → tiles ← ingest`. Solo `Main` conecta todo.
- **Por qué:** cada capa se prueba y se entiende sin las demás; el formato en disco puede cambiar sin tocar la red.

---

## Pirámide e ingesta

### D-07 · Tiles de 256 × 256 · 2026-09-22 · Vigente
- **Por qué:** estándar de mapas web; una pantalla Full HD necesita ~40 tiles. Con 512 habría menos mensajes pero más bytes desperdiciados en los bordes.

### D-08 · Número de niveles con aritmética entera · 2026-09-22 · Vigente
- **Por qué:** `log₂` en punto flotante puede redondear mal justo en potencias de 2.

### D-09 · Pirámide completa en una sola pasada · 2026-09-23 · Vigente
- **Descartado:** generar el nivel máximo y luego releer sus JPEG para construir los demás (pruebas 3 y 4).
- **Por qué:** una sola lectura del disco, sin recompresión JPEG en cascada, memoria ≈ 2 franjas del nivel máximo.
- **Medido:** 24 796 tiles en 93.5 s, igual que solo leer la imagen.

### D-10 · Formato interno BGR y reordenamiento manual · 2026-09-23 · Vigente
- **Descartado:** convertir con `Graphics2D.drawImage`.
- **Medido:** 12 s → 0.08 s por franja.

### D-11 · `meta.json` se escribe al final · 2026-09-23 · Vigente
- **Por qué:** su existencia significa `READY`; una pirámide a medio generar nunca aparece en el catálogo.

### D-12 · Lector PNG propio en streaming · 2026-09-24 · Vigente (implementada)
- **Contexto:** la imagen de 93 GB es PNG sin comprimir (bloques *stored*), 176 393 × 176 393.
- **Por qué:** el lector del JDK vuelve a descomprimir desde el inicio en cada lectura por región (costo cuadrático).

### D-13 · Formatos soportados explícitos, detectados por firma · 2026-09-24 · Vigente (implementada)
- **Decisión:** PNG (lector propio), TIFF/JPEG/BMP (`ImageIO`), PSB opcional; lo demás se rechaza con `FAILED`.
- **Por qué:** soportar "cualquier formato" no es realista; la interfaz `ImageSource` es el punto de extensión.

### D-14 · Tiles en PNG sin pérdida · 2026-10-04 · Vigente
- **Contexto:** JPEG se eligió para la foto eso1242a. Las imágenes de evaluación son texto de colores planos con dígitos de 3×5 px.
- **Por qué:** JPEG difumina los bordes y genera artefactos alrededor del texto; PNG conserva cada píxel y comprime muy bien los colores planos.
- **Aclaración:** los tiles se codifican sí o sí (el PNG de origen es un solo flujo comprimido de filas completas, no se puede recortar). La elección es solo el formato de cada tile.
- **Medido (imagen de 4 GB):** PNG 836 MB y 117 s contra JPEG 1179 MB y 109 s. 29 % menos disco, sin pérdida; el tiempo lo sigue dominando la lectura del disco (90 s).

---

## Protocolo v1

### D-15 · WebSocket con subprotocolo propio, no HTTP por tile · 2026-09-22 · Vigente
- **Por qué:** conexión persistente con estado por cliente; el servidor decide qué enviar. Pedir archivos por nombre sería el modelo "galería" que el curso descarta.

### D-16 · Tiles en binario con CRC32 · 2026-09-23 · Vigente
- **Descartado:** Base64 en frames de texto (+33 %).
- **Por qué del CRC32:** cubre disco → pantalla, no solo la red (argumento *end-to-end*).

### D-17 · Cancelación por `SEQ` vaciando la cola · 2026-09-23 · Vigente
- **Medido:** de una vista abandonada de 256 tiles llegó 1 (0.4 %).

### D-18 · La cola guarda coordenadas, no bytes · 2026-09-23 · Vigente
- **Por qué:** cancelar no desperdicia lecturas de disco y una cola larga no consume memoria.

### D-19 · Un tile se marca "enviado" al enviarlo · 2026-09-23 · Vigente
- **Por qué:** marcarlo al encolarlo y luego cancelarlo dejaría al servidor creyendo que el cliente lo tiene.

### D-20 · `COLA_MAX = 300` y vista máxima de 4096 px · 2026-09-23 · Vigente
- **Contexto:** con 256 la prueba envió 255 tiles de una vista de 256: el último nunca llegaba.
- **Por qué:** la cola debe caber la vista máxima: (4096/256 + 1)² + paridades + `DONE` < 300.

### D-21 · Zoom continuo con cambio de nivel por umbral · 2026-09-23 · Vigente
- **Descartado:** saltos de ×2 por cada paso de la rueda (se veía como "cambiar de imagen").
- **Por qué:** transición suave; el detalle siempre viene de tiles del nivel correcto y nunca se pasa de 1:1 (salvo D-14/legibilidad).

---

## Protocolo v2

### D-22 · Diseño de transporte tipo TCP (ACK, SACK, Slow Start…) · 2026-09-24 · Reemplazada por D-23
- **Motivo del reemplazo:** el ingeniero indicó que SACK, Slow Start, NACK, ACK acumulativo, control por retardo, cubeta de tokens, créditos y recuperación parcial por relevancia ya estaban tomados por otros grupos.

### D-23 · Cuatro mecanismos aprobados · 2026-10-02 · Vigente
- **Decisión:** FEC con paridad XOR entrelazada; controlador PI sobre la ocupación del búfer de recepción; sincronización de caché con filtros de Bloom; planificación EDF.
- **Por qué:** aprobados por el ingeniero y con una responsabilidad cada uno (qué, cuándo, cómo proteger, qué tan rápido). Detalle en `PROTOCOLO.md` §10–§14.

### D-24 · `NUM` en cada mensaje binario (cabecera de 28 bytes) · 2026-10-02 · Vigente
- **Por qué:** como el número de secuencia de RTP: mide pérdidas y ocupación, y fecha el filtro de Bloom. **No** confirma ni provoca retransmisiones.

### D-25 · FEC solo en los 16 tiles más prioritarios, grupos de 4 entrelazados · 2026-10-02 · Vigente
- **Por qué:** costo acotado (~10 % por vista) donde más importa; el entrelazado resiste ráfagas de hasta 4 pérdidas.

### D-26 · El PI controla la ocupación `Q`, sin término derivativo · 2026-10-02 · Vigente
- **Por qué de `Q`:** combina red y cliente en una sola variable y acota lo que se desperdicia al cancelar.
- **Por qué sin D:** amplificaría el ruido de la medición cada 100 ms.

### D-27 · Bloom con estado completo y semilla · 2026-10-02 · Vigente
- **Descartado:** `EVICT` (listas de diferencias) y el registro exacto de v1.
- **Por qué:** tamaño fijo (512 B), se auto-corrige ante cualquier mensaje perdido, permite `RESUME`; la semilla vuelve transitorio un falso positivo.

### D-28 · Recuperación por re-declaración de vista, sin `GET_TILE` · 2026-10-02 · Vigente
- **Por qué:** `GET_TILE` equivalía a un NACK (no permitido). El cliente vuelve a declarar su estado y su vista; el servidor decide con las reglas de siempre.

### D-29 · Red simulada dentro del servidor · 2026-10-02 · Vigente
- **Por qué:** en localhost no hay pérdidas ni límite de ancho de banda; sin simulación, FEC y PI no se pueden observar ni medir.

### D-30 · ARC en la caché del servidor · 2026-09-23 · Propuesta
- **Descartado:** LRU como aporte (usado por otros grupos).
- **Por qué:** LRU no resiste barridos: un cliente recorriendo el nivel máximo expulsaría lo que usan todos. Se mantendrá LRU como opción para comparar la tasa de aciertos.
### D-31 · No verificar el CRC de cada chunk del PNG de entrada · 2026-10-04 · Vigente
- **Por qué:** obligaría a recorrer los 93 GB con un cálculo extra. La integridad del flujo de píxeles la comprueba igual el Adler-32 de zlib, que `Inflater` valida al final. El CRC32 que importa para el protocolo es el de cada tile (D-16).

### D-32 · Ingesta con `-Xmx1g` · 2026-10-04 · Reemplazada por D-37
- **Contexto:** la memoria de la ingesta crece con el ancho de la imagen (franjas de 256 filas a todo lo ancho).
- **Medido:** 250 MB con la imagen de 4 GB (36 743 px). La de 93 GB mide 176 393 px de ancho y quedaría cerca de 512 MB.
- **Decisión:** `ingest.bat` usa 1 GB para tener margen. El servidor sigue con 512 MB.

### D-33 · Almacenamiento empaquetado por nivel con índice denso · 2026-10-04 · Vigente
- **Contexto:** la imagen de 93 GB genera 635 214 tiles; como archivos sueltos son muy lentos de crear, copiar y borrar en disco duro (el costo es por archivo, no por byte).
- **Decisión:** por nivel, un `{z}.pack` con los tiles concatenados y un `{z}.idx` con una entrada de 12 bytes (offset + longitud) por tile, en orden fila por fila.
- **Descartado:** un solo archivo para toda la pirámide (los niveles se escriben en paralelo durante la cascada); un índice con búsqueda (la entrada de tamaño fijo se calcula en O(1)).
- **Analogía:** el `.pack` es la tabla y el `.idx` un índice denso sobre la clave `(z, x, y)`.
- **Medido (imagen de 4 GB):** 19 archivos en lugar de 27 660; ingesta de 117 s a 33.6 s, porque el disco escribe en secuencia en vez de crear miles de archivos mientras lee el PNG.

### D-34 · Ingesta reanudable descartada · 2026-10-05 · Vigente
- **Por qué:** el PNG se lee en secuencia desde el byte 0 igual, así que reanudar solo ahorraría codificar y escribir; reconstruir el estado de la cascada es complejo. La ingesta de 4 GB tarda 34 s; si se corta, se borra con `clean.bat tiles <id>` y se repite. `meta.json` al final garantiza que una pirámide incompleta nunca aparezca como lista.

### D-35 · Un `TileStore` por imagen, con el índice cargado en RAM al primer uso · 2026-10-05 · Vigente
- **Por qué:** todas las sesiones comparten el mismo índice (7.6 MB para la imagen de 93 GB); la lectura posicional permite leer en paralelo sin lock.
- **Limitación conocida:** reingestar una imagen con el mismo id mientras el servidor corre deja al servidor con el índice viejo. Hasta la Fase 9, hay que reiniciar el servidor.

### D-36 · Zoom hasta 16× con vecino más cercano solo al ampliar · 2026-10-05 · Vigente
- **Contexto:** a 1:1, un dígito de 3×5 px es ilegible en un monitor.
- **Decisión:** zoom máximo 16× (un dígito ocupa 48×80 px de pantalla). Por encima de 1:1, sin suavizado; por debajo, con suavizado.
- **Por qué no es "zoom tipo Amazon":** no se interpola ni se inventa detalle; se muestran los píxeles reales del nivel máximo, que llegaron como tiles por el protocolo.
- **Por qué el suavizado depende del zoom:** al ampliar, suavizar difumina los bordes; al reducir, no suavizar produce aliasing (moiré) en el texto.

### D-37 · Heap de la ingesta: 4 GB, y los hilos del compresor son daemon (corrige D-32) · 2026-10-05 · Vigente

**Problema.** La ingesta de 93 GB (176 393 px de ancho) falló en la franja 91/690 con `OutOfMemoryError` en `Reductor.reducir`, precedido por `Retried waiting for GCLocker too often`. Además, el proceso quedó colgado después del error.

**Causa.**
1. La memoria de la ingesta es proporcional al **ancho**: una franja del nivel máximo, más los búferes de los niveles superiores (≈ otra franja), más la cola de tiles. Memoria máxima observada con `-Xmx1g`: 600 MB (75 471 px), 646 MB (96 922 px) y 878 MB (136 325 px). Para 176 393 px ya no alcanza. El valor de D-32 se había medido con la imagen de 4 GB (36 743 px).
2. `Deflater` e `Inflater` bloquean temporalmente el recolector de basura (GCLocker) mientras trabajan sobre un arreglo. Con el heap casi lleno, la reserva de 32 MiB contiguos no pudo esperar a una recolección y falló.
3. Los hilos de `ThreadPoolExecutor` no eran *daemon*: al morir `main`, nadie llamaba a `terminar()` y la JVM no terminaba.

**Decisión.**
- `ingest.bat` usa `-Xmx4g`: memoria máxima observada con 176 393 px: 1455 MB, 2.8 veces por debajo del límite, y una cuarta parte de los 16 GB de la PC de pruebas. La memoria sigue sin depender del **alto** de la imagen; solo se ajustó el límite al ancho máximo de evaluación. El servidor no cambia (`-Xmx512m`), porque no maneja franjas.
- `TileEncoderPool` crea sus hilos con `Thread.ofPlatform().daemon()`. En una ingesta normal no cambia nada (`terminar()` espera a todos los tiles). Ante un error, el proceso termina solo.

**Verificación.** Con `-Xmx48m` sobre la imagen de 4 GB se provoca `OutOfMemoryError` y el proceso vuelve al prompt sin Ctrl+C. La ingesta de 93 GB con `-Xmx4g`: ver `PLAN.md`.

### D-38 · EDF: la cola por plazos vive en `transporte/` y no lee el reloj · 2026-10-05 · Vigente
- **Contexto:** la cola de v1 (`ArrayDeque`, ordenada una vez) no puede intercalar paridades ni medir la atención al usuario.
- **Decisión:** `PlanificadorEDF<T>` genérico en `pimg.transporte`, sin lock propio (lo protege `lockCola` de la sesión) y sin leer el reloj: recibe los plazos y el instante actual. Los empates se resuelven por orden de inserción con un contador, porque `PriorityQueue` no es estable. La distancia de §6 está en un solo lugar (`Vista.distancia`), y la usan tanto el orden de los visibles como los plazos.
- **Consecuencias:** se prueba aislado con tiempos inventados (`ProbarEDF`, 9 pruebas, ~100 ns por trabajo). Con una sola vista, el orden es idéntico al de v1 (verificado en 10 000 vistas). `GET_TILE` con la cola llena ahora se ignora, en lugar de descartar "el más antiguo" (concepto que no existe en una cola por plazos); `GET_TILE` se elimina en la Fase 8.

### D-39 · Red simulada: solo se pierden los mensajes binarios · 2026-10-05 · Vigente
- **Contexto:** sobre TCP en localhost no hay pérdidas ni límite de ancho de banda, así que FEC y el PI no se pueden demostrar. §16 pedía "descartar el mensaje" sin decir cuáles.
- **Decisión:** el enlace simulado descarta solo los mensajes **binarios** (tiles y, en la Fase 6, paridades). Los de texto (`META`, `DONE`, `ERROR`, `SIM_OK`) pasan por la misma cola FIFO, con la misma latencia y el mismo ancho de banda, pero nunca se pierden.
- **Por qué:** es el diseño clásico de los sistemas multimedia: canal de control confiable y canal de datos con pérdidas (RTSP sobre TCP con RTP sobre UDP). Perder un `META` o un `DONE` dejaría al cliente en un estado que no ocurre en una red real con TCP, y ninguno de los mecanismos aprobados está pensado para recuperarlo: FEC protege datos.
- **Modelo del enlace:** ancho de banda = retardo de transmisión (`bytes / BW`, 1 KB = 1000 B); latencia = retardo de propagación; un mensaje perdido no ocupa el enlace. No es una cubeta de tokens: con `max(ahora, libreDesde)` no se acumulan permisos cuando el enlace está libre. Semilla fija (42) para que los experimentos se puedan repetir.

### D-40 · FEC: detalles de implementación · 2026-10-06 · Vigente
- **Qué se protege:** los primeros 16 tiles **nuevos** de cada vista, en orden de plazo. Los que el cliente ya tiene no se envían y no forman parte de ningún grupo.
- **Lugar en la cola:** se reservan 4 lugares para paridades y 1 para el `DONE` (`COLA_MAX − 5` tiles como máximo), así la vista máxima sigue cabiendo en `COLA_MAX = 300`.
- **Cálculo:** el XOR se hace al **enviar** la paridad, con los datos de sus miembros desde la caché del servidor (acaban de enviarse, están en RAM). Un miembro que no se pudo leer se excluye; con menos de 2 miembros la paridad no se envía.
- **Cliente:** guarda los datos crudos de los últimos 32 tiles en un `Map` en orden de llegada. Un miembro "falta" si no está ahí. Un tile que llegó con CRC incorrecto no se guarda, así que FEC también lo reconstruye (§8.4: corrupto = perdido). La reconstrucción se verifica con el `CRC_i` del miembro antes de entregarla.
- **Lógica pura aparte:** `transporte/FecXor` (grupos, paridad y reconstrucción) se prueba sin servidor con `ProbarFec`; el formato del mensaje PARIDAD se verificó entre Java y JavaScript.

### D-41 · Controlador PI: regla de reposo, pacing antes de extraer y sintonía · 2026-10-06 · Vigente
- **Regla de reposo (extensión del anti-windup de §12.5):** la integral no acumula error **positivo** si el servidor no tiene pedidos pendientes. Sin esto, en reposo `Q = 0` da `e = +8` constante y la integral lleva `R` al máximo (400 msg/s); la siguiente vista en un enlace lento sale a esa tasa. En la simulación, el pico de `Q` sube de ~20 a ~70 tiles. Alternativas descartadas: *back-calculation* (agrega una ganancia que hay que sintonizar) y reiniciar la integral en cada vista (pierde la tasa aprendida).
- **Pacing antes de sacar el pedido:** el emisor espera `1/R` y **después** saca de la cola EDF. Así la espera cuenta en `TARDE`, y un tile de una vista que se canceló mientras esperaba ya no sale.
- **Q incluye la cola del enlace:** `Q = (último NUM enviado − MAX) + COLA`. Lo que espera en el enlace simulado cuenta como "en camino", que es justo lo que el PI debe limitar.
- **Sintonía:** se mantienen `Kp = 4`, `Ki = 8`. Con el retraso de un reporte (100 ms), `Kp ≥ 10` oscila y no se estabiliza (`ProbarPI`, prueba 6). `Kp = Ki = 8` estabiliza un escalón en 6.8 s en lugar de 7.8 s, con menos margen de estabilidad: no compensa.
- **Modo de experimento `--sin-pi`:** el servidor no espera entre tiles y responde `CTRL|R:0`. No cambia el protocolo; sirve para medir con y sin control con el mismo código.

### D-42 · Filtros de Bloom: cuándo se envían, qué incluyen y re-declaración · 2026-10-06 · Vigente
- **Envío periódico:** cada 1 s si la caché cambió, contando también los tiles **nuevos** y no solo las expulsiones (§13.5 decía solo expulsiones). Sin esto, si el cliente nunca expulsa, `enviadosRecientes` crece sin límite durante toda la sesión. Costo: 684 caracteres por segundo mientras se navega, frente a ~30 KB de un tile.
- **Antes de un VIEWPORT** solo se envía si hubo expulsiones: el servidor debe saber que un tile expulsado ya no está antes de calcular una vista donde vuelve a ser visible.
- **Qué incluye:** los tiles en caché **más** los recibidos que todavía se decodifican; si no, el servidor reenviaría tiles que ya están en camino al lienzo. `MAX` es el último `NUM` recibido: todo lo anterior ya está en uno de esos dos lugares.
- **Sin `GET_TILE`:** un tile con CRC incorrecto no se guarda; lo reconstruye FEC o vuelve por el filtro o la re-declaración (§15). Se retiran `GET_TILE` y `EVICT` (ideas no permitidas).
- **Servidor:** `enviadosRecientes` es un `LinkedHashMap` en orden de `NUM`; al recibir `BLOOM` se borra desde el principio hasta el primer `NUM > MAX`. Un reenvío se saca y se vuelve a insertar para conservar el orden.
- **Re-declaración:** 250 ms después del `DONE` de la vista vigente, si faltan tiles en pantalla y no hay nada decodificándose, se envían `BLOOM` y el mismo `VIEWPORT` con `SEQ` nuevo. Máximo 3 intentos por vista; el tercero con `SEM + 1` (posible falso positivo, §13.7). El contador se reinicia cuando el usuario se mueve.
