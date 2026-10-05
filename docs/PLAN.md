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
| 1 | Lector PNG en streaming | `feat/png-source` | ⬜ |
| 2 | Almacenamiento empaquetado + ingesta reanudable | `feat/almacen-empaquetado` | ⬜ |
| 3 | Legibilidad: tiles PNG + zoom > 1:1 | `feat/legibilidad` | ⬜ |
| 4 | Cabecera v2 (`NUM`) + planificación EDF | `feat/edf` | ⬜ |
| 5 | Red simulada + controles en el panel | `feat/red-simulada` | ⬜ |
| 6 | FEC con paridad XOR entrelazada | `feat/fec` | ⬜ |
| 7 | Controlador PI (`REPORT`, `CTRL`, gráficas) | `feat/control-pi` | ⬜ |
| 8 | Filtros de Bloom, `RESUME` y re-declaración de vista | `feat/bloom-resume` | ⬜ |
| 9 | ARC, ingesta automática y navegación ("ir a x, y") | `feat/extras` | ⬜ |
| 10 | Pruebas finales con las 4 imágenes de evaluación | `test/evaluacion` | ⬜ |
| 11 | Documento final y preparación de la defensa | `docs/final` | ⬜ |

**Orden:** 1–3 primero, porque sin la imagen grande funcionando no hay nota. Mientras corren las ingestas largas (horas), se avanza en 4–8 en paralelo. Reparto sugerido: uno en ingesta y lectores (1, 2, 9) y otro en protocolo y cliente (3–8).

---

## Fase 1 — Lector PNG en streaming ⬜

**Objetivo:** leer las imágenes de evaluación de principio a fin una sola vez, con memoria acotada por el ancho.
**Referencias:** `PROTOCOLO.md` §22.3 · `DECISIONES.md` D-12, D-13.

- [X ] `PngSource`: chunks, `Inflater`, 5 filtros de fila, RGB de 8 bits
- [ X] `Fuentes.abrir`: elige el lector por la firma del archivo, no por la extensión
- [ X] `IngestMain` usa la interfaz `ImageSource`, no una clase concreta
- [X ] Herramienta de verificación: comparar `PngSource` contra el lector del JDK, píxel por píxel
- [ X] Verificación sobre las 10 imágenes pequeñas reales: todas idénticas
- [ X] Ingesta completa de la imagen de 4 GB (36 743 px): anotar tiempo, disco y memoria
- [ X] `--leer` sobre la imagen de 93 GB: anotar velocidad y memoria
- [ X] Registrar en `DECISIONES.md` lo que se decida en la fase
- [ X] Merge a `main`

**Criterio de terminado:** la verificación da idéntico en todas las imágenes pequeñas y la de 93 GB se lee completa sin errores.

**Evidencia:**

Prueba	Resultado
Verificación píxel por píxel (7 imágenes, 104 a 5775 px)	Idénticas al lector del JDK
Lectura completa (12 900 y 18 305 px)	Sin errores
Ingesta 5775 px	723/723 tiles, 0.8 s, 230 MB
Ingesta 4 GB (36 743 px)	27 660/27 660 tiles, 109 s, 1179 MB en disco, 250 MB de memoria
Velocidad del lector	~1500 MB/s desde caché; ~40 MB/s desde el disco duro (el límite es el disco)
---

## Fase 2 — Almacenamiento empaquetado + ingesta reanudable ⬜

**Objetivo:** la imagen de 93 GB genera 635 214 tiles; como archivos sueltos son lentos de escribir, copiar y abrir.

- [ ] Un archivo por nivel (`z.pack`) + índice `(x, y) → (offset, longitud)`
- [ ] `TileStore` lee del paquete; el servidor no cambia (solo usa `TileStore`)
- [ ] Ingesta reanudable: punto de control por franja; si se interrumpe, continúa donde quedó
- [ ] Barra de progreso con tiempo estimado restante
- [ ] Ingesta completa de las imágenes de 17, 28, 55 y 93 GB

**Criterio:** la de 93 GB queda procesada y navegable; tiempo, disco y memoria anotados.

---

## Fase 3 — Legibilidad ⬜

**Objetivo:** que los dígitos de 3×5 px se lean claramente en la máxima definición.

- [ ] Tiles PNG sin pérdida (al menos en los niveles altos)
- [ ] Zoom más allá de 1:1, ampliando sin suavizado (`imageSmoothingEnabled = false`)
- [ ] Coordenada de la imagen bajo el cursor
- [ ] Verificar visualmente con las imágenes pequeñas

**Criterio:** en la imagen de 4 GB se lee cualquier número al máximo zoom.

---

## Fase 4 — Cabecera v2 + EDF ⬜

**Referencia:** `PROTOCOLO.md` §8, §14.

- [ ] Subprotocolo `pimg.v2`; cabecera binaria de 28 bytes con `NUM`
- [ ] Cliente: valida `NUM` y cuenta saltos (`PERD`)
- [ ] `PlanificadorEDF` en `src/pimg/transporte/` (cola de prioridad por plazo)
- [ ] `SesionPimg` usa EDF; métrica `TARDE`

**Criterio:** el orden de envío es del centro hacia afuera y `TARDE` se reporta.

---

## Fase 5 — Red simulada ⬜

**Referencia:** `PROTOCOLO.md` §16.

- [ ] `RedSimulada`: pérdida (%), ancho de banda (KB/s) y latencia (ms)
- [ ] Servidor con `--sim`; comando `SIM` / `SIM_OK`
- [ ] Controles en el panel y opción "cliente lento"

**Criterio:** con 5 % de pérdida, el panel muestra `PERD` subiendo y tiles faltantes.

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