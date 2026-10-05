# PIMG — Servidor Asíncrono de Imágenes de Ultra Alta Resolución

Proyecto 2 de **Ciencias de la Computación VIII** · **Autores:** Marcos Masaya, Samuel Caal

Servidor en **Java 21, sin dependencias externas**, que permite explorar desde el navegador imágenes de decenas de gigabytes transfiriendo solo los *tiles* necesarios para la vista actual. HTTP/1.1 (implementado a mano) entrega los archivos iniciales; la imagen viaja por un protocolo propio, **PIMG**, sobre WebSocket (también implementado a mano).

## Documentación

| Documento | Contenido |
|---|---|
| [`docs/PROTOCOLO.md`](docs/PROTOCOLO.md) | **Especificación de PIMG y fuente de verdad del proyecto**: mensajes, cabecera binaria, estados, cachés, ingesta, transporte v2, decisiones, resultados y pendientes |
| [`docs/PLAN.md`](docs/PLAN.md) | Plan de fases original |
| [`docs/DECISIONES.md`](docs/DECISIONES.md) | Registro de decisiones técnicas |
| [`CLAUDE.md`](CLAUDE.md) | Contexto para asistentes de IA |

## Requisitos

- **JDK 21** (`java -version`, `javac -version`). Con un JDK más nuevo también compila, porque `build.bat` usa `--release 21`, pero la prueba final debe hacerse con JDK 21.
- Windows (scripts `.bat`). Funciona sin internet.
- Espacio en disco: imagen original + su pirámide (ver `docs/PROTOCOLO.md` §16).

## Uso rápido (PowerShell, desde la raíz del repositorio)

**1. Compilar**

```powershell
.\build.bat
```

**2. Procesar una imagen** (genera la pirámide en `data/tiles/<id>/`)

```powershell
.\ingest.bat data\input\eso1242a.tif eso1242a
```

Otros modos:

```powershell
.\ingest.bat --plan 176393 176393          # calcula niveles y tiles sin leer la imagen
.\ingest.bat --leer data\input\imagen.tif  # solo mide la velocidad de lectura
```

**3. Iniciar el servidor**

```powershell
.\run.bat
```

Abrir `http://localhost:8080`. Arrastrar para mover, rueda para cambiar de nivel, **G** para ver la cuadrícula de tiles.

**Inspeccionar la cabecera de un PNG** (sin cargar la imagen):

```powershell
javac -d tools\out tools\PngInfo.java
java -cp tools\out PngInfo "data\input\imagen.png"
```

## Estructura

```
├── build.bat / run.bat / ingest.bat
├── docs/                   PROTOCOLO.md (especificación), PLAN.md, DECISIONES.md
├── src/pimg/
│   ├── Main.java           Arma el servidor: catálogo, caché, router, WebSocket
│   ├── http/               HTTP/1.1 propio: parser, respuestas, archivos estáticos, router
│   ├── websocket/          RFC 6455 propio: handshake, frames, heartbeat, cierre
│   ├── protocol/           PIMG: mensajes, cabecera binaria, vista, sesión por cliente
│   ├── tiles/              Pirámide: geometría, almacenamiento en disco, catálogo, caché
│   └── ingest/             Construcción de la pirámide en una sola pasada
├── web/                    Cliente: index.html, css/, js/ (módulos ES, sin librerías)
├── tools/                  Herramientas de prueba (PngInfo, pruebas de lectura)
└── data/                   (ignorado por git) input/ = originales, tiles/ = pirámides
```

Regla de dependencias: `protocol → websocket → http` y `protocol → tiles ← ingest`. `http` no conoce WebSocket y `tiles` no conoce sockets; solo `Main` conecta todo.

## Convenciones

- `main` siempre compila.
- Commits: `tipo: descripción` con `feat`, `fix`, `docs`, `test`, `chore`, `refactor`.
- Un cambio de formato del protocolo se hace **primero** en `docs/PROTOCOLO.md` y luego en `src/pimg/protocol/` y `web/js/pimg.js`.
- Las imágenes y pirámides **nunca** se suben al repositorio (`data/` está en `.gitignore`).