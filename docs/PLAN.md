# Plan de trabajo — Proyecto 2: Servidor Asíncrono de Imágenes

**Curso:** Ciencias de la Computación VIII · **Autores:** Marcos Masaya, Samuel Caal
**Documento vivo.** Aquí se registra **qué** se hace y en qué estado está. El **porqué** de cada decisión va en [`DECISIONES.md`](DECISIONES.md); los formatos y algoritmos, en [`PROTOCOLO.md`](PROTOCOLO.md).

**Cómo se usa:**
1. Cada fase se trabaja en su propia rama (`feat/...`, `docs/...`), que sale de `main`.
2. Se marcan las casillas al terminar cada tarea, **en la misma rama**.
3. La fase se integra a `main` solo cuando se cumple su **criterio de terminado**, con la evidencia anotada.
4. Si en la fase se toma una decisión de diseño, se agrega a `DECISIONES.md`.

Leyenda: ⬜ pendiente · 🟨 en progreso · ✅ terminada

---

## Contexto de evaluación (resumen)

- **40 % funcionamiento y usabilidad, 60 % protocolo.** La pirámide de tiles es la base y no da puntos.
- El techo de la nota lo pone la imagen más grande que funcione: 17 GB (20 pts), 28 GB (40), 55 GB (80), 93 GB (115).
- Imágenes de evaluación: PNG RGB 8 bits, sin compresión (bloques *stored*), cuadradas, con números de dígitos 3×5 px que deben **leerse**.
- Mecanismos aprobados por el ingeniero: FEC XOR, controlador PI, filtros de Bloom, EDF (ver `PROTOCOLO.md` §0).

---

## Estado general

| # | Fase | Rama | Estado |
|---|---|---|---|
| — | Base v1: HTTP/WebSocket propios, PIMG v1, cliente, ingesta en cascada | `main` | ✅ |
| — | Especificación `pimg.v2` y registro de decisiones | `docs/protocolo-v2` | ✅ |
| 1 | Lector PNG en streaming | `feat/png-source` | ✅ |
| 2 | Almacenamiento empaquetado + tiles PNG + ingesta reanudable | `feat/almacen-empaquetado` | ✅ |
| 3 | Legibilidad: zoom > 1:1 | `feat/legibilidad` | ✅ |
| — | Ingesta de las 4 imágenes de evaluación (corrige el heap, D-37) | `fix/memoria-ingesta` | ✅ |
| 4 | Cabecera v2 (`NUM`) + planificación EDF | `feat/edf` | ✅ |
| 5 | Red simulada + controles en el panel | `feat/red-simulada` | ✅ |
| 6 | FEC con paridad XOR entrelazada | `feat/fec` | ⬜ |
| 7 | Controlador PI (`REPORT`, `CTRL`, gráficas) | `feat/control-pi` | ⬜ |
| 8 | Filtros de Bloom, `RESUME` y re-declaración de vista | `feat/bloom-resume` | ⬜ |
| 9 | ARC, ingesta automática y navegación ("ir a x, y") | `feat/extras` | ⬜ |
| 10 | Pruebas finales con las 4 imágenes de evaluación | `test/evaluacion` | ⬜ |
| 11 | Documento final y preparación de la defensa | `docs/final` | ⬜ |

**Orden:** 1–3 primero, porque sin la imagen grande funcionando no hay nota. Mientras corren las ingestas largas (horas), se avanza en 4–8 en paralelo. Reparto sugerido: uno en ingesta y lectores (1, 2, 9) y otro en protocolo y cliente (3–8).

---

## Fase 1 — Lector PNG en streaming ✅

**Objetivo:** leer las imágenes de evaluación de principio a fin una sola vez, con memoria acotada por el ancho.
**Referencias:** `PROTOCOLO.md` §22.3 · `DECISIONES.md` D-12, D-13, D-31, D-32 (reemplazada por D-37).

- [x] `PngSource`: chunks, `Inflater`, 5 filtros de fila, RGB de 8 bits
- [x] `Fuentes.abrir`: elige el lector por la firma del archivo, no por la extensión
- [x] `IngestMain` usa la interfaz `ImageSource`, no una clase concreta
- [x] Herramienta de verificación: comparar `PngSource` contra el lector del JDK, píxel por píxel
- [x] Verificación sobre las 10 imágenes pequeñas reales: todas idénticas
- [x] Ingesta completa de la imagen de 4 GB (36 743 px): anotar tiempo, disco y memoria
- [x] Lectura completa de la imagen de 93 GB: anotar velocidad y memoria (se hizo dentro de su ingesta)
- [x] Registrar en `DECISIONES.md` lo que se decida en la fase
- [x] Merge a `main`

**Criterio de terminado:** la verificación da idéntico en todas las imágenes pequeñas y la de 93 GB se lee completa sin errores.

**Evidencia:**

| Prueba | Resultado |
|---|---|
| Verificación píxel por píxel (7 imágenes, 104 a 5775 px) | Idénticas al lector del JDK |
| Lectura completa (12 900 y 18 305 px) | Sin errores |
| Ingesta 5775 px | 723/723 tiles, 0.8 s, 230 MB |
| Ingesta 4 GB (36 743 px) | 27 660/27 660 tiles, 109 s, 1179 MB en disco, 250 MB de memoria |
| Velocidad del lector | ~1500 MB/s desde caché; ~40 MB/s desde el disco duro (el límite es el disco) |
| Lectura completa de la imagen de 93 GB (176 393 px) | Sin errores; 566.6 s de lectura (≈157 MB/s de píxeles) |

---

## Fase 2 — Almacenamiento empaquetado + tiles PNG + ingesta reanudable ✅

**Objetivo:** la imagen de 93 GB genera 635 214 tiles; como archivos sueltos son lentos de escribir, copiar y abrir. Además, los tiles pasan a PNG sin pérdida para que se lean los dígitos.
**Referencias:** `PROTOCOLO.md` §22.2 · `DECISIONES.md` D-14, D-33.

- [X] Un archivo por nivel (`z.pack`) + índice `(x, y) → (offset, longitud)`
- [X] `TileStore` lee del paquete; el servidor no cambia (solo usa `TileStore`)
- [X] Ingesta reanudable: **descartada** (D-34). El PNG se lee secuencial desde el inicio igual; la ingesta de 4 GB tarda 34 s
- [x] Tiles PNG sin pérdida (D-14): `TileEncoderPool` codifica en PNG y el mensaje TILE los envía con `FMT = 2`
- [x] Ingesta completa de la imagen de 4 GB: tiempo, disco y memoria, comparados contra JPEG (1179 MB, 109 s)
- [ ] ~~Barra de progreso con tiempo estimado restante~~ → se movió a la Fase 9 (junto con `PROCESSING` con %)

**Criterio:** la imagen de 4 GB queda procesada y navegable con tiles PNG; tiempo, disco y memoria anotados.

**Evidencia:**

| Prueba | Resultado |
|---|---|
| Tiles PNG contra JPEG (4 GB) | 836 MB contra 1179 MB: 29 % menos disco, sin pérdida |
| Archivos de la pirámide (4 GB) | 19 contra 27 660 |
| Tiempo de ingesta (4 GB) | 33.6 s contra 117 s con archivos sueltos (3.5×) |
| Índices .idx | Tamaño exacto 16 + tiles × 12 en los 9 niveles |
| Lectura desde el paquete | 27 660/27 660 tiles; 2.5 ms/tile incluyendo decodificación |
| Fidelidad (5775 px) | Nivel máximo idéntico al original, píxel por píxel |

---

## Fase 3 — Legibilidad ✅

**Objetivo:** que los dígitos de 3×5 px se lean claramente en la máxima definición.

- [x] Zoom más allá de 1:1, ampliando sin suavizado (`imageSmoothingEnabled = false`)
- [x] Coordenada de la imagen bajo el cursor
- [x] Verificar visualmente con las imágenes pequeñas

**Criterio:** en la imagen de 4 GB se lee cualquier número al máximo zoom.

**Evidencia:**

| Prueba | Resultado |
|---|---|
| Zoom máximo | 1600 %: cada píxel como bloque nítido, dígitos de 3×5 px legibles |
| Transferido al navegar hasta el máximo detalle | 0.586 % de la imagen original |
| Servidor leyendo del .pack (Fase 2 en uso real) | 0 tiles faltantes, 0 CRC malos |
| Caché compartida del servidor | Tras recargar la página, 9 de 9 tiles servidos desde RAM, sin tocar el disco |
| Coordenada bajo el cursor | Correcta; desaparece fuera de la imagen |

---

## Ingesta de las imágenes de evaluación ✅

**Rama:** `fix/memoria-ingesta` · **Referencia:** `DECISIONES.md` D-37.

- [x] Ingesta de las imágenes de 17, 28, 55 y 93 GB
- [x] Heap de la ingesta a 4 GB (con 1 GB, la de 93 GB falló en la franja 91/690 con `OutOfMemoryError`)
- [x] Hilos del compresor *daemon*: si la ingesta falla, el proceso termina solo
- [x] Navegación de la imagen de 93 GB hasta el máximo detalle

**Criterio:** las 4 imágenes quedan procesadas; la de 93 GB es navegable y sus números se leen en la posición correcta.

**Evidencia** (PC de pruebas: 16 GB de RAM, 6 hilos de compresión):

| Imagen | Ancho (px) | Tiles | Tiempo | Disco | Memoria máx. (límite) |
|---|---|---|---|---|---|
| 17 GB | 75 471 | 116 274 / 116 274 | 148.7 s | 3 359 MB | 600 MB (1 GB) |
| 28 GB | 96 922 | 191 840 / 191 840 | 271.8 s | 5 617 MB | 646 MB (1 GB) |
| 55 GB | 136 325 | 379 388 / 379 388 | 601.2 s | 11 070 MB | 878 MB (1 GB) |
| 93 GB | 176 393 | 635 214 / 635 214 | 1021.4 s | 17 594 MB | 1 455 MB (4 GB) |

| Prueba | Resultado |
|---|---|
| Fallo provocado (`-Xmx48m`, imagen de 4 GB) | `OutOfMemoryError` y el proceso vuelve al prompt sin Ctrl+C |
| Navegación de la imagen de 93 GB | Nivel 10 a 1600 %: 0 tiles faltantes, 0 CRC malos, 15.19 MB transferidos (0.017 % del original) |
| Número leído en (x 104 662, y 47 912) | `032628176`; el cálculo por posición da 6 844 × 4 767 + 2 828 = 32 628 176 (números de 37 × 7 px, 4 767 por fila). La fila de arriba muestra `032623409` (4 767 menos) |

---

## Fase 4 — Cabecera v2 + EDF ✅

**Referencia:** `PROTOCOLO.md` §8, §14.

- [x] Subprotocolo `pimg.v2`; cabecera binaria de 28 bytes con `NUM`
- [x] Cliente: valida `NUM` y cuenta saltos (`PERD`)
- [x] `PlanificadorEDF` en `src/pimg/transporte/` (cola de prioridad por plazo)
- [x] `SesionPimg` usa EDF; métrica `TARDE`

**Criterio:** el orden de envío es del centro hacia afuera y `TARDE` se reporta.

**Evidencia:**

| Prueba | Resultado |
|---|---|
| Cabecera v2 en el navegador | `Ultimo NUM` = `Tiles recibidos` (193 y 201), `Perdidos` 0, CRC malos 0 |
| DevTools | `101 Switching Protocols`, `Sec-WebSocket-Protocol: pimg.v2` |
| Rechazo de clientes v1 | Subprotocolo `pimg.v1` → `400`; `HELLO V:1` → `ERROR 426` y cierre `1002` |
| `ProbarEDF` | 9/9 OK; ~100 ns por trabajo; mismo orden que v1 en 10 000 vistas (226 874 tiles) |
| `TARDE` en localhost | `img093`, zona sin caché: 9 de 201 tiles tarde (4.5 %), por la lectura en frío del disco |

---

## Fase 5 — Red simulada ✅

**Referencia:** `PROTOCOLO.md` §16.

- [x] `RedSimulada`: pérdida (%), ancho de banda (KB/s) y latencia (ms)
- [x] Servidor con `--sim`; comando `SIM` / `SIM_OK`
- [x] Controles en el panel y opción "cliente lento"

**Criterio:** con 5 % de pérdida, el panel muestra `PERD` subiendo y tiles faltantes.

**Evidencia:**

| Prueba | Resultado |
|---|---|
| `ProbarRed` | 6/6 OK: 5010 perdidos de 100 000 (5.01 %); 100 × 10 KB a 100 KB/s terminan en 10 000 ms (10 080 ms con 80 ms de latencia); un enlace libre no acumula permisos; semilla repetible |
| `SIM` sin `--sim` | `ERROR 403 Servidor iniciado sin --sim` |
| Pérdida 5 % | `Ultimo NUM` 595 − `Tiles recibidos` 583 = 12 = `Perdidos`; huecos con el ancestro ampliado (`Faltan en pantalla` 1) que no se llenan solos |
| 300 KB/s + 80 ms, sin PI | El tile visible tardó > 10 s tras un zoom: cola del enlace llena de tiles de vistas abandonadas (peor caso 14 s). Motiva la Fase 7 |
| Cliente lento 100 ms/tile | Los tiles se procesan de uno en uno (10/s); el visible espera detrás de los niveles intermedios |

---

## Fase 6 — FEC con paridad XOR ⬜

**Referencia:** `PROTOCOLO.md` §11.

- [ ] `FecXor`: grupos entrelazados y cálculo de paridad
- [ ] Mensaje binario `PARIDAD` (`TIPO = 0x02`)
- [ ] Cliente: conserva los últimos 32 tiles y reconstruye; contador `REC`

**Criterio:** con 5 % de pérdida simulada, `REC` sube y la mayoría de los tiles se recuperan sin pedirlos.

---

## Fase 7 — Controlador PI ⬜

**Referencia:** `PROTOCOLO.md` §12.

- [ ] Cliente: `REPORT` cada 100 ms (`MAX`, `PERD`, `COLA`, `DEC`, `JIT`, `REC`)
- [ ] `ControladorPI` con anti-windup; pacing en el emisor; `CTRL`
- [ ] Gráficas de `R` y `Q` en el panel
- [ ] Sintonía de `Kp` y `Ki` con un escalón de ancho de banda

**Criterio:** ante un escalón de ancho de banda, `Q` vuelve a 8; sobrepico y tiempo de establecimiento anotados.

---

## Fase 8 — Filtros de Bloom y reanudación ⬜

**Referencia:** `PROTOCOLO.md` §13, §15.

- [ ] `FiltroBloom` (Java) y `bloom.js`: coinciden con los vectores de prueba de §13.3
- [ ] Comando `BLOOM`; lógica `tiene(t)` con `enviadosRecientes`
- [ ] `RESUME` tras reconectar
- [ ] Re-declaración de vista y cambio de semilla
- [ ] Retirar `GET_TILE` y `EVICT`

**Criterio:** tras reiniciar el servidor, el cliente reanuda sin que se reenvíen los tiles que conserva.

---

## Fase 9 — Extras ⬜

- [ ] ARC en `TileCache` (LRU como opción para comparar)
- [ ] Ingesta automática con `WatchService` (`PROCESSING` con %, `FAILED`)
- [ ] Barra de progreso de la ingesta con tiempo estimado restante (viene de la Fase 2)
- [ ] Reingestar una imagen con el servidor corriendo: marcarla PROCESSING, sacarla de `Catalogo` y cerrar sus `.pack` antes de regenerarla
- [ ] "Ir a x, y" en el cliente

---

## Fase 10 — Pruebas finales ⬜

- [ ] Las 4 imágenes de evaluación navegables
- [ ] Varios clientes simultáneos
- [ ] Sesión larga (sin fugas de memoria)
- [ ] JDK 21, sin internet, compilando desde cero
- [ ] Experimentos de `PROTOCOLO.md` §25.2 con sus números

---

## Fase 11 — Documento final y defensa ⬜

- [ ] `PROTOCOLO.md` con resultados reales y estados actualizados (✅)
- [ ] Diagramas de secuencia de los cuatro mecanismos
- [ ] Guion de la demostración (qué mostrar y en qué orden)
- [ ] Repaso de las preguntas de `PROTOCOLO.md` §24