package escalanters;

import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ArquitecturaBasadaEnEventosDemo {

    //  1. EVENTOS — Objetos inmutables que representan "algo que ocurrió"

    static abstract class Evento {
        private final String id;
        private final LocalDateTime marcaDeTiempo;

        public Evento() {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.marcaDeTiempo = LocalDateTime.now();
        }

        public String getId() { return id; }

        public LocalDateTime getMarcaDeTiempo() { return marcaDeTiempo; }

        public abstract String getTipo();

        @Override
        public String toString() {
            String hora = marcaDeTiempo.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            return String.format("[%s] %s (id=%s)", hora, getTipo(), id);
        }
    }

    static class EventoPedidoCreado extends Evento {
        private final String pedidoId;
        private final String correoCliente;
        private final List<String> articulos;
        private final double total;

        public EventoPedidoCreado(String pedidoId, String correoCliente,
                                  List<String> articulos, double total) {
            this.pedidoId = pedidoId;
            this.correoCliente = correoCliente;
            this.articulos = List.copyOf(articulos);
            this.total = total;
        }

        public String getPedidoId() { return pedidoId; }

        public String getCorreoCliente() { return correoCliente; }

        public List<String> getArticulos() { return articulos; }

        public double getTotal() { return total; }
        public String getTipo() { return "PEDIDO_CREADO"; }
    }

    static class EventoPagoProcesado extends Evento {
        private final String pedidoId;
        private final double monto;
        private final String metodo;

        public EventoPagoProcesado(String pedidoId, double monto, String metodo) {
            this.pedidoId = pedidoId;
            this.monto = monto;
            this.metodo = metodo;
        }

        public String getPedidoId() { return pedidoId; }

        public double getMonto() { return monto; }

        public String getMetodo() { return metodo; }
        public String getTipo() { return "PAGO_PROCESADO"; }
    }

    static class EventoUsuarioRegistrado extends Evento {
        private final String nombreUsuario;
        private final String correo;

        public EventoUsuarioRegistrado(String nombreUsuario, String correo) {
            this.nombreUsuario = nombreUsuario;
            this.correo = correo;
        }

        public String getNombreUsuario() { return nombreUsuario; }

        public String getCorreo() { return correo; }
        public String getTipo() { return "USUARIO_REGISTRADO"; }
    }

    //  2. INTERFAZ DEL MANEJADOR DE EVENTOS (Suscriptor)

    @FunctionalInterface
    interface ManejadorDeEvento<T extends Evento> {
        void manejar(T evento);
    }

    //  3. BUS DE EVENTOS — El corazón de la arquitectura
    //     Desacopla publicadores de suscriptores. Soporta:
    //       - Registro de múltiples manejadores por tipo de evento
    //       - Publicación asíncrona (no bloqueante)
    //       - Procesamiento concurrente con pool de hilos

    static class BusDeEventos {
        private final Map<Class<? extends Evento>, List<ManejadorDeEvento<?>>> manejadores = new ConcurrentHashMap<>();

        private final ExecutorService ejecutor = Executors.newFixedThreadPool(4);

        private final List<Evento> registroDeEventos = Collections.synchronizedList(new ArrayList<>());

        public <T extends Evento> void suscribir(Class<T> tipoEvento, ManejadorDeEvento<? super T> manejador) {
            manejadores.computeIfAbsent(tipoEvento, k -> new CopyOnWriteArrayList<>()).add(manejador);
            imprimirLog(" SUSCRIPCIÓN", manejador.getClass().getSimpleName() + " → " + tipoEvento.getSimpleName());
        }

        public <T extends Evento> void publicar(T evento) {
            registroDeEventos.add(evento);
            imprimirLog(" EVENTO PUBLICADO", evento.toString());

            List<ManejadorDeEvento<?>> listaManejadores = manejadores.getOrDefault(evento.getClass(), Collections.emptyList());

            if (listaManejadores.isEmpty()) {
                imprimirLog(" SIN SUSCRIPTORES", "Nadie escucha " + evento.getTipo());
                return;
            }

            for (ManejadorDeEvento<?> manejador : listaManejadores) {
                ejecutor.submit(() -> {
                    try {
                        @SuppressWarnings("unchecked")
                        ManejadorDeEvento<T> m = (ManejadorDeEvento<T>) manejador;
                        m.manejar(evento);
                    } catch (Exception e) {
                        imprimirLog("ERROR en manejador", e.getMessage());
                    }
                });
            }
        }

        public List<Evento> getRegistroDeEventos() {
            return Collections.unmodifiableList(registroDeEventos);
        }

        public void apagar() throws InterruptedException {
            ejecutor.shutdown();
            ejecutor.awaitTermination(5, TimeUnit.SECONDS);
            imprimirLog("BUS DE EVENTOS", "Apagado correctamente");
        }
    }

    //  4. SUSCRIPTORES — Módulos independientes que reaccionan a eventos
    //     Cada uno tiene su propia lógica, totalmente desacoplado de los demás.

    static class ServicioInventario implements ManejadorDeEvento<EventoPedidoCreado> {
        @Override
        public void manejar(EventoPedidoCreado evento) {
            simularTrabajo(150);
            imprimirLog("INVENTARIO",
                    String.format("Stock reservado para pedido %s → %s",
                            evento.getPedidoId(), evento.getArticulos()));
        }
    }

    static class ServicioCorreo implements ManejadorDeEvento<Evento> {
        @Override
        public void manejar(Evento evento) {
            if (evento instanceof EventoPedidoCreado pedido) {
                simularTrabajo(200);
                imprimirLog("CORREO",
                        String.format("Confirmación enviada a %s por pedido %s ($%.2f)",
                                pedido.getCorreoCliente(), pedido.getPedidoId(), pedido.getTotal()));

            } else if (evento instanceof EventoUsuarioRegistrado usuario) {
                simularTrabajo(100);
                imprimirLog("CORREO",
                        String.format("Bienvenida enviada a %s (%s)",
                                usuario.getNombreUsuario(), usuario.getCorreo()));
            }
        }
    }

    static class ServicioFacturacion implements ManejadorDeEvento<EventoPagoProcesado> {
        @Override
        public void manejar(EventoPagoProcesado evento) {
            simularTrabajo(300);
            String facturaId = "FAC-" + evento.getPedidoId();
            imprimirLog("FACTURACIÓN",
                    String.format("Factura %s generada → $%.2f vía %s",
                            facturaId, evento.getMonto(), evento.getMetodo()));
        }
    }

    static class ServicioAnaliticas {
        private final Map<String, Integer> contadores = new ConcurrentHashMap<>();

        public ManejadorDeEvento<EventoPedidoCreado> rastreadorPedidos() {
            return evento -> {
                contadores.merge("pedidos", 1, Integer::sum);
                imprimirLog("ANALÍTICAS",
                        String.format("Pedido rastreado: %s | Total pedidos: %d",
                                evento.getPedidoId(), contadores.get("pedidos")));
            };
        }

        public ManejadorDeEvento<EventoPagoProcesado> rastreadorPagos() {
            return evento -> {
                contadores.merge("pagos", 1, Integer::sum);
                imprimirLog("ANALÍTICAS",
                        String.format("Pago rastreado: $%.2f | Total pagos: %d",
                                evento.getMonto(), contadores.get("pagos")));
            };
        }

        public ManejadorDeEvento<EventoUsuarioRegistrado> rastreadorUsuarios() {
            return evento -> {
                contadores.merge("usuarios", 1, Integer::sum);
                imprimirLog("ANALÍTICAS",
                        String.format("Usuario rastreado: %s | Total usuarios: %d",
                                evento.getNombreUsuario(), contadores.get("usuarios")));
            };
        }

        public Map<String, Integer> getMetricas() {
            return Map.copyOf(contadores); }
    }

    static class ServicioNotificacionesPush implements ManejadorDeEvento<EventoPedidoCreado> {
        @Override
        public void manejar(EventoPedidoCreado evento) {
            simularTrabajo(80);
            imprimirLog("NOTIFICACIÓN",
                    String.format("Push enviado: '¡Tu pedido %s está en camino!'",
                            evento.getPedidoId()));
        }
    }

    //  5. PUBLICADORES — Servicios que generan eventos

    static class ServicioPedidos {
        private final BusDeEventos bus;

        public ServicioPedidos(BusDeEventos bus) {
            this.bus = bus;
        }

        public void crearPedido(String pedidoId, String correo, List<String> articulos, double total) {
            imprimirLog("PEDIDO", "Creando pedido " + pedidoId + "...");
            bus.publicar(new EventoPedidoCreado(pedidoId, correo, articulos, total));
        }
    }

    static class ServicioPagos {
        private final BusDeEventos bus;

        public ServicioPagos(BusDeEventos bus) {
            this.bus = bus;
        }

        public void procesarPago(String pedidoId, double monto, String metodo) {
            imprimirLog(" PAGO", "Procesando pago para pedido " + pedidoId + "...");
            bus.publicar(new EventoPagoProcesado(pedidoId, monto, metodo));
        }
    }

    static class ServicioUsuarios {
        private final BusDeEventos bus;

        public ServicioUsuarios(BusDeEventos bus) {
            this.bus = bus;
        }

        public void registrarUsuario(String nombreUsuario, String correo) {
            imprimirLog(" USUARIO", "Registrando " + nombreUsuario + "...");
            bus.publicar(new EventoUsuarioRegistrado(nombreUsuario, correo));
        }
    }





    private static void simularTrabajo(int milisegundos) {
        try { Thread.sleep(milisegundos); } catch (InterruptedException ignorado) {}
    }

    private static synchronized void imprimirLog(String etiqueta, String mensaje) {
        String hilo = Thread.currentThread().getName();
        System.out.printf("  %-20s │ %-55s │ [%s]%n", etiqueta, mensaje, hilo);
    }

    private static void separador(String titulo) {
        System.out.println();
        System.out.println("═".repeat(90));
        System.out.printf("  %s%n", titulo);
        System.out.println("═".repeat(90));
    }

    public static void main(String[] args) throws InterruptedException {

        separador("PASO 1 · Inicializando el Bus de Eventos y los servicios");
        BusDeEventos bus = new BusDeEventos();

        ServicioInventario servicioInventario = new ServicioInventario();
        ServicioCorreo servicioCorreo = new ServicioCorreo();
        ServicioFacturacion servicioFacturacion = new ServicioFacturacion();
        ServicioAnaliticas servicioAnaliticas = new ServicioAnaliticas();
        ServicioNotificacionesPush servicioNotificaciones = new ServicioNotificacionesPush();

        separador("PASO 2 · Registrando suscripciones (quién escucha qué)");

        bus.suscribir(EventoPedidoCreado.class, servicioInventario);
        bus.suscribir(EventoPedidoCreado.class, servicioCorreo);
        bus.suscribir(EventoPedidoCreado.class, servicioAnaliticas.rastreadorPedidos());
        bus.suscribir(EventoPedidoCreado.class, servicioNotificaciones);

        bus.suscribir(EventoPagoProcesado.class, servicioFacturacion);
        bus.suscribir(EventoPagoProcesado.class, servicioAnaliticas.rastreadorPagos());

        bus.suscribir(EventoUsuarioRegistrado.class, servicioCorreo);
        bus.suscribir(EventoUsuarioRegistrado.class, servicioAnaliticas.rastreadorUsuarios());

        ServicioPedidos servicioPedidos = new ServicioPedidos(bus);
        ServicioPagos servicioPagos = new ServicioPagos(bus);
        ServicioUsuarios servicioUsuarios = new ServicioUsuarios(bus);

        separador("PASO 3 · Simulando actividad (eventos en acción)");

        servicioUsuarios.registrarUsuario("maria_garcia", "maria@correo.com");
        Thread.sleep(500);

        servicioPedidos.crearPedido("PED-001", "maria@correo.com",
                List.of("Laptop HP", "Mouse Logitech", "Teclado mecánico"), 1249.99);
        Thread.sleep(600);

        servicioPagos.procesarPago("PED-001", 1249.99, "Tarjeta Visa");
        Thread.sleep(500);

        servicioUsuarios.registrarUsuario("carlos_lopez", "carlos@correo.com");
        servicioPedidos.crearPedido("PED-002", "carlos@correo.com",
                List.of("Monitor 4K", "Cable HDMI"), 459.50);
        Thread.sleep(600);

        servicioPagos.procesarPago("PED-002", 459.50, "PayPal");
        Thread.sleep(800);

        separador("RESUMEN · Métricas y Registro de Eventos");

        System.out.println("\n   Métricas de Analíticas:");
        servicioAnaliticas.getMetricas().forEach((clave, valor) ->
                System.out.printf("     • %-10s : %d%n", clave, valor));

        System.out.println("\n   Registro de Eventos (auditoría):");
        bus.getRegistroDeEventos().forEach(e ->
                System.out.println("     " + e));

        bus.apagar();
    }
}