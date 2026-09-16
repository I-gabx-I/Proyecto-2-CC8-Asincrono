# PIMG — Servidor Asíncrono de Imágenes de Ultra Alta Resolución

Proyecto 2 de **Ciencias de la Computación VIII**.
**Autores:** Marcos Masaya, Samuel Caal

Servidor en Java 21 que permite explorar imágenes de cientos de gigabytes desde el navegador, transfiriendo solo los fragmentos (*tiles*) necesarios para la vista actual. Usa HTTP/1.1 para los archivos iniciales y un protocolo propio (**PIMG**) sobre WebSocket para la transmisión de la imagen.

## Documentación

| Documento | Contenido |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | Plan de fases y estado |
| [`docs/PROTOCOLO.md`](docs/PROTOCOLO.md) | Especificación del protocolo PIMG (contrato cliente-servidor) |
| [`docs/DECISIONES.md`](docs/DECISIONES.md) | Registro de decisiones técnicas y su justificación |

## Requisitos

- JDK 21 (`java -version` y `javac -version`)
- Sin dependencias externas: compila y ejecuta sin internet

## Compilar y ejecutar

```powershell
# Windows (PowerShell)
.\build.ps1
java -cp out cc8.pimg.Main --port=8080
```

```sh
# Linux / macOS / Git Bash
./build.sh
java -cp out cc8.pimg.Main --port=8080
```

**Pruebas del protocolo:**
```sh
java -cp out cc8.pimg.protocol.ProtocolSelfTest
```

**Spike de lectura de imágenes grandes (Fase 0):**
```sh
java -Xmx2g -cp out cc8.pimg.tools.RegionReadSpike <ruta-imagen> [x y ancho alto]
```

## Estructura

```
├── build.ps1 / build.sh        Scripts de compilación
├── docs/                       Plan, protocolo y decisiones
├── server/src/cc8/pimg/
│   ├── Main.java               Punto de entrada
│   ├── config/                 Configuración por argumentos (--port, --web, --data)
│   ├── protocol/               Contrato PIMG: mensajes, cabecera binaria, geometría, errores
│   ├── http/                   (Fase 2) Servidor HTTP/1.1 y archivos estáticos
│   ├── websocket/              (Fase 3) Handshake y frames RFC 6455
│   ├── session/                (Fase 5) Estado por cliente y despacho de comandos
│   ├── pyramid/                (Fase 4) Ingesta y generación de la pirámide de tiles
│   ├── cache/                  (Fase 5) Caché LRU de tiles en RAM
│   └── tools/                  Herramientas de desarrollo (spikes)
├── web/
│   ├── index.html              Página del visor
│   ├── js/protocol.js          Contrato PIMG del lado del cliente
│   ├── css/                    Estilos
│   └── lib/                    Librerías de terceros alojadas localmente (sin CDN)
└── data/                       (ignorado por git) input/ = imágenes nuevas, tiles/ = pirámides
```

## Convenciones de trabajo

- `main` siempre compila. Cada tarea se trabaja en una rama (`feat/...`, `fix/...`, `docs/...`) y se integra con Pull Request.
- Mensajes de commit: `tipo: descripción` con `feat`, `fix`, `docs`, `test`, `chore`, `refactor`.
- Un cambio de formato del protocolo se hace **primero** en `docs/PROTOCOLO.md` y luego en `protocol/` (Java) y `web/js/protocol.js`.
- Las imágenes y tiles **nunca** se suben al repositorio.
