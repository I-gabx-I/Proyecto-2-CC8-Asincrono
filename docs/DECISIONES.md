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

### D-32 · Ingesta con `-Xmx1g` · 2026-10-04 · Vigente
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
