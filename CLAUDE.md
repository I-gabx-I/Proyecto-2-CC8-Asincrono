# CLAUDE.md

Proyecto universitario (CC8): servidor asíncrono en Java 21 que sirve imágenes de ultra alta resolución (~100 GB en la calificación) mediante una pirámide de tiles y un protocolo propio (PIMG) sobre WebSocket.

## Restricciones obligatorias
- Java 21, **sin dependencias externas** salvo que estén en `lib/` y justificadas en `docs/DECISIONES.md`.
- HTTP/1.1 y WebSocket (RFC 6455) implementados a mano sobre sockets; no usar servidores HTTP de librerías.
- Todo recurso del cliente se sirve desde el servidor Java: **sin CDN ni peticiones externas** (se califica offline).
- Nunca cargar la imagen completa en memoria.
- Los autores quieren entender cada cambio: explicar el porqué y avanzar por pasos pequeños.

## Fuentes de verdad
- `docs/PROTOCOLO.md`: contrato cliente-servidor. Cambiar primero ahí, luego en `server/src/cc8/pimg/protocol/` y `web/js/protocol.js`.
- `docs/PLAN.md`: fases y estado.
- `docs/DECISIONES.md`: decisiones y justificación.

## Comandos
- Compilar: `./build.sh` o `.\build.ps1`
- Pruebas del protocolo: `java -cp out cc8.pimg.protocol.ProtocolSelfTest`
- Ejecutar: `java -cp out cc8.pimg.Main --port=8080`

## Estilo
- Un paquete por módulo (`http`, `websocket`, `session`, `pyramid`, `cache`, `protocol`).
- Pruebas sin JUnit (clases `*SelfTest` con `main`).
- Comentarios en español.
