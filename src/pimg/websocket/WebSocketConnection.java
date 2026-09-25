package pimg.websocket;

import pimg.http.Conexion;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/** Conexión WebSocket establecida: lectura y escritura de frames (RFC 6455 §5). */
public final class WebSocketConnection {
    static final int OP_CONTINUACION = 0x0;
    static final int OP_TEXTO        = 0x1;
    static final int OP_BINARIO      = 0x2;
    static final int OP_CIERRE       = 0x8;
    static final int OP_PING         = 0x9;
    static final int OP_PONG         = 0xA;

    private record Frame(boolean fin, int opcode, byte[] datos) {}

    private final InputStream entrada;
    private final OutputStream salida;
    private final int maxMensaje;
    private final String cliente;
    private final ReentrantLock lockEscritura = new ReentrantLock();
    private boolean cierreEnviado = false; // protegido por lockEscritura

    WebSocketConnection(Conexion con, int maxMensaje) {
        this.entrada = con.entrada();  // el MISMO stream que leyó el handshake HTTP
        this.salida = con.salida();
        this.maxMensaje = maxMensaje;
        this.cliente = con.socket().getRemoteSocketAddress().toString();
    }

    public String cliente() { return cliente; }

    // ======================= ESCRITURA (cualquier hilo) =======================

    public void enviarTexto(String texto) throws IOException {
        escribirFrame(OP_TEXTO, texto.getBytes(StandardCharsets.UTF_8));
    }

    public void enviarBinario(byte[] datos) throws IOException {
        escribirFrame(OP_BINARIO, datos);
    }

    void enviarPing() throws IOException {
        escribirFrame(OP_PING, new byte[0]);
    }

    /** Envía CLOSE con código y motivo (motivo en ASCII). Si ya se envió, no hace nada. */
    public void cerrar(int codigo, String motivo) throws IOException {
        lockEscritura.lock();
        try {
            if (cierreEnviado) {
                return;
            }
            byte[] m = motivo.getBytes(StandardCharsets.UTF_8);
            int n = Math.min(m.length, 123);          // un frame de control lleva ≤ 125 bytes
            byte[] payload = new byte[2 + n];
            payload[0] = (byte) (codigo >>> 8);        // código de cierre: 2 bytes big-endian
            payload[1] = (byte) codigo;
            System.arraycopy(m, 0, payload, 2, n);
            escribirFrame(OP_CIERRE, payload);
            cierreEnviado = true;
        } finally {
            lockEscritura.unlock();
        }
    }

    private void escribirFrame(int opcode, byte[] datos) throws IOException {
        lockEscritura.lock();
        try {
            if (cierreEnviado) {
                throw new IOException("La conexion ya envio CLOSE");
            }
            int n = datos.length;
            salida.write(0x80 | opcode);               // FIN = 1: el servidor no fragmenta
            if (n <= 125) {
                salida.write(n);                       // MASK = 0: el servidor no enmascara
            } else if (n <= 0xFFFF) {
                salida.write(126);
                salida.write(n >>> 8);
                salida.write(n);
            } else {
                salida.write(127);
                for (int i = 7; i >= 0; i--) {
                    salida.write((int) ((long) n >>> (8 * i)));
                }
            }
            salida.write(datos);
            salida.flush();
        } finally {
            lockEscritura.unlock();
        }
    }

    private void escribirSiAbierta(int opcode, byte[] datos) throws IOException {
        lockEscritura.lock();
        try {
            if (!cierreEnviado) {
                escribirFrame(opcode, datos);
            }
        } finally {
            lockEscritura.unlock();
        }
    }

    // ======================= LECTURA (solo el hilo de la conexión) =======================

    /** Lee frames hasta recibir CLOSE. Devuelve el código de cierre del cliente. */
    int atender(WebSocketListener sesion) throws IOException {
        ByteArrayOutputStream fragmentos = null;  // != null mientras se reensambla un mensaje
        int opcodeMensaje = 0;

        while (true) {
            Frame f = leerFrame();
            switch (f.opcode()) {
                case OP_PING -> escribirSiAbierta(OP_PONG, f.datos());  // RFC 6455 §5.5.2
                case OP_PONG -> { }                                     // solo renueva el timeout
                case OP_CIERRE -> {
                    byte[] d = f.datos();
                    int codigo = d.length >= 2 ? ((d[0] & 0xFF) << 8) | (d[1] & 0xFF) : 1000;
                    cerrar(codigo, "");      // responder el cierre (si no lo iniciamos nosotros)
                    return codigo;
                }
                case OP_TEXTO, OP_BINARIO -> {
                    if (fragmentos != null) {
                        throw new WebSocketException(1002, "Mensaje nuevo antes de terminar el anterior");
                    }
                    if (f.fin()) {
                        entregar(sesion, f.opcode(), f.datos());
                    } else {                  // primer fragmento
                        fragmentos = new ByteArrayOutputStream();
                        fragmentos.write(f.datos());
                        opcodeMensaje = f.opcode();
                    }
                }
                case OP_CONTINUACION -> {
                    if (fragmentos == null) {
                        throw new WebSocketException(1002, "Continuacion sin mensaje inicial");
                    }
                    if (fragmentos.size() + f.datos().length > maxMensaje) {
                        throw new WebSocketException(1009, "Mensaje demasiado grande");
                    }
                    fragmentos.write(f.datos());
                    if (f.fin()) {
                        entregar(sesion, opcodeMensaje, fragmentos.toByteArray());
                        fragmentos = null;
                    }
                }
                default -> throw new WebSocketException(1002, "Opcode desconocido: " + f.opcode());
            }
        }
    }

    private Frame leerFrame() throws IOException {
        int b0 = leerByte();
        int b1 = leerByte();

        boolean fin = (b0 & 0x80) != 0;
        if ((b0 & 0x70) != 0) {
            throw new WebSocketException(1002, "Bits RSV activos sin extension negociada");
        }
        int opcode = b0 & 0x0F;
        if ((b1 & 0x80) == 0) {
            throw new WebSocketException(1002, "Frame del cliente sin mascara"); // RFC 6455 §5.1
        }

        long largo = b1 & 0x7F;
        if (largo == 126) {
            largo = ((long) leerByte() << 8) | leerByte();
        } else if (largo == 127) {
            largo = 0;
            for (int i = 0; i < 8; i++) {
                largo = (largo << 8) | leerByte();
            }
            if (largo < 0) {
                throw new WebSocketException(1002, "Longitud invalida");
            }
        }

        boolean control = opcode >= 0x8;
        if (control && (largo > 125 || !fin)) {
            throw new WebSocketException(1002, "Frame de control invalido"); // RFC 6455 §5.5
        }
        if (largo > maxMensaje) {
            throw new WebSocketException(1009, "Mensaje demasiado grande");
        }

        byte[] clave = leerExacto(4);
        byte[] datos = leerExacto((int) largo);
        for (int i = 0; i < datos.length; i++) {
            datos[i] ^= clave[i & 3];          // quitar la máscara
        }
        return new Frame(fin, opcode, datos);
    }

    private void entregar(WebSocketListener sesion, int opcode, byte[] datos) throws IOException {
        if (opcode == OP_TEXTO) {
            String texto;
            try {
                texto = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(datos)).toString();
            } catch (CharacterCodingException e) {
                throw new WebSocketException(1007, "Texto que no es UTF-8 valido"); // RFC 6455 §8.1
            }
            sesion.alRecibirTexto(this, texto);
        } else {
            sesion.alRecibirBinario(this, datos);
        }
    }

    private int leerByte() throws IOException {
        int b = entrada.read();
        if (b < 0) {
            throw new EOFException("El cliente cerro la conexion TCP");
        }
        return b;
    }

    private byte[] leerExacto(int n) throws IOException {
        byte[] datos = entrada.readNBytes(n);
        if (datos.length != n) {
            throw new EOFException("Frame incompleto");
        }
        return datos;
    }
}