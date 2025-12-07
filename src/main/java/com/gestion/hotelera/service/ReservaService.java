package com.gestion.hotelera.service;

import com.gestion.hotelera.enums.EstadoHabitacion;
import com.gestion.hotelera.enums.EstadoReserva;
import com.gestion.hotelera.model.Cliente;
import com.gestion.hotelera.model.Reserva;
import com.gestion.hotelera.model.Servicio;
import com.gestion.hotelera.repository.ReservaRepository;
import com.gestion.hotelera.repository.ServicioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@SuppressWarnings("null")
public class ReservaService {

    private static final Logger logger = LoggerFactory.getLogger(ReservaService.class);
    // Re-validated by system
    private final ReservaRepository reservaRepository;
    private final AuditoriaService auditoriaService;
    private final ServicioRepository servicioRepository;
    private final HabitacionService habitacionService;
    private final EmailService emailService;
    private final NotificacionService notificacionService;
    private final com.gestion.hotelera.repository.ClienteRepository clienteRepository;

    public ReservaService(ReservaRepository reservaRepository,
            AuditoriaService auditoriaService,
            ServicioRepository servicioRepository,
            HabitacionService habitacionService,
            EmailService emailService,
            NotificacionService notificacionService,
            com.gestion.hotelera.repository.ClienteRepository clienteRepository) {
        this.reservaRepository = reservaRepository;
        this.auditoriaService = auditoriaService;
        this.servicioRepository = servicioRepository;
        this.habitacionService = habitacionService;
        this.emailService = emailService;
        this.notificacionService = notificacionService;
        this.clienteRepository = clienteRepository;
    }

    @Transactional
    public @NonNull Reserva crearOActualizarReserva(@NonNull Reserva reserva) {
        validarReserva(reserva);
        Long habitacionId = reserva.getHabitacion().getId();
        Long reservaId = reserva.getId();

        verificarHabitacionOperativa(habitacionId);
        verificarDisponibilidadFechas(habitacionId, reserva, reservaId);

        try {
            // Aumentar puntos de lealtad si es nueva reserva
            if (reserva.getId() == null && reserva.getCliente() != null && reserva.getCliente().getId() != null) {
                clienteRepository.findById(reserva.getCliente().getId()).ifPresent(clienteDb -> {
                    clienteDb.setPuntos((clienteDb.getPuntos() != null ? clienteDb.getPuntos() : 0) + 10);
                    clienteRepository.save(clienteDb);
                    reserva.setCliente(clienteDb);
                });
            }

            Reserva guardada = reservaRepository.save(reserva);
            actualizarEstadoHabitacionSegunReserva(guardada);
            registrarAuditoriaCreacionOActualizacion(guardada);
            enviarEmailConfirmacionSiEsNueva(reserva.getId() == null, guardada);

            if (reserva.getId() == null && notificacionService != null) {
                crearNotificacionNuevaReserva(guardada);
            }

            logger.info("Reserva creada/actualizada: ID={}, Cliente={}, Habitación={}",
                    guardada.getId(),
                    guardada.getCliente() != null ? guardada.getCliente().getNombres() : "N/A",
                    guardada.getHabitacion() != null ? guardada.getHabitacion().getNumero() : "N/A");

            return guardada;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error al guardar reserva", e);
            throw new RuntimeException("Error al guardar la reserva: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<Reserva> obtenerTodasLasReservas() {
        return reservaRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Reserva> obtenerUltimasReservas(int limite) {
        return reservaRepository.findAll(
                PageRequest.of(0, limite, Sort.by(Sort.Direction.DESC, "id")))
                .getContent();
    }

    @Transactional(readOnly = true)
    public Optional<Reserva> buscarReservaPorId(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return reservaRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Reserva> obtenerReservaPorId(Long id) {
        return buscarReservaPorId(id);
    }

    @Transactional(readOnly = true)
    public List<Reserva> obtenerReservasPorCliente(Cliente cliente) {
        return reservaRepository.findByCliente(cliente);
    }

    @Transactional(readOnly = true)
    public List<Reserva> obtenerReservasPorClienteId(Long clienteId) {
        if (clienteId == null) {
            return new ArrayList<>();
        }
        List<Reserva> todas = reservaRepository.findAll();
        return todas.stream()
                .filter(r -> r.getCliente() != null && Objects.equals(r.getCliente().getId(), clienteId))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long contarReservas() {
        long count = reservaRepository.count();
        logger.debug("Total reservas en BD: {}", count);
        return count;
    }

    @Transactional
    public boolean cancelarReserva(@NonNull Long id, String userRole) {
        return reservaRepository.findById(id)
                .map(reserva -> {
                    validarCancelacion(reserva, userRole);
                    reserva.setEstadoReserva(EstadoReserva.CANCELADA.getValor());
                    Reserva reservaCancelada = reservaRepository.save(reserva);

                    liberarHabitacion(reservaCancelada);
                    registrarAuditoriaCancelacion(reservaCancelada, userRole);

                    logger.info("Reserva cancelada: ID={}, Estado={}", id, reservaCancelada.getEstadoReserva());
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean cancelarReserva(Long id) {
        if (id == null)
            return false;
        return cancelarReserva(id, "ROLE_ADMIN");
    }

    @Transactional
    public boolean eliminarReservaFisica(Long id) {
        if (id == null) {
            return false;
        }
        return reservaRepository.findById(id)
                .map(reserva -> {
                    liberarHabitacion(reserva); // Liberar habitación antes de eliminar
                    reservaRepository.deleteById(id);
                    auditoriaService.registrarAccion("ELIMINACION_RESERVA",
                            "Reserva (ID: " + id + ") eliminada físicamente.", "Reserva", id);
                    logger.info("Reserva eliminada físicamente: ID={}", id);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public void finalizarReserva(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("El ID de la reserva no puede ser nulo");
        }

        reservaRepository.findById(id).ifPresent(reserva -> {
            if (EstadoReserva.FINALIZADA.getValor().equalsIgnoreCase(reserva.getEstadoReserva())) {
                return;
            }

            String estadoAnterior = reserva.getEstadoReserva();
            reserva.setEstadoReserva(EstadoReserva.FINALIZADA.getValor());

            if (reserva.getFechaSalidaReal() == null) {
                reserva.setFechaSalidaReal(LocalDate.now());
            }

            reservaRepository.save(reserva);
            liberarHabitacion(reserva);

            auditoriaService.registrarAccion("FINALIZACION_RESERVA",
                    "Reserva finalizada (ID: " + reserva.getId() + ") - Estado anterior: " + estadoAnterior,
                    "Reserva", reserva.getId());

            logger.info("Reserva finalizada: ID={}, Estado anterior={}", id, estadoAnterior);
        });
    }

    @Transactional
    public void realizarCheckIn(Long id) {
        reservaRepository.findById(id).ifPresent(reserva -> {
            reserva.setEstadoReserva(EstadoReserva.ACTIVA.getValor());
            reserva.setFechaCheckinReal(LocalDate.now());
            reservaRepository.save(reserva);

            // Actualizar estado de la habitación a OCUPADA
            if (reserva.getHabitacion() != null) {
                habitacionService.actualizarEstadoHabitacion(reserva.getHabitacion().getId(),
                        EstadoHabitacion.OCUPADA.getValor());
            }

            if (notificacionService != null) {
                crearNotificacionCheckIn(reserva);
            }

            auditoriaService.registrarAccion("CHECK_IN",
                    "Check-in realizado para reserva ID: " + id, "Reserva", id);
            logger.info("Check-in realizado: ID={}", id);
        });
    }

    @Transactional
    public void realizarCheckOut(Long id) {
        reservaRepository.findById(id).ifPresent(reserva -> {
            reserva.setEstadoReserva(EstadoReserva.FINALIZADA.getValor());
            reserva.setFechaSalidaReal(LocalDate.now());
            reservaRepository.save(reserva);

            liberarHabitacion(reserva);

            if (notificacionService != null) {
                crearNotificacionCheckOut(reserva);
            }

            auditoriaService.registrarAccion("CHECK_OUT",
                    "Check-out realizado para reserva ID: " + id, "Reserva", id);
            logger.info("Check-out realizado: ID={}", id);
        });
    }

    @Transactional(readOnly = true)
    public Integer calcularDiasEstadia(LocalDate inicio, LocalDate fin) {
        long dias = java.time.temporal.ChronoUnit.DAYS.between(inicio, fin);
        return dias == 0 ? 1 : (int) dias;
    }

    @Transactional(readOnly = true)
    public Double calcularTotalPagar(Double precioPorNoche, Integer dias) {
        return precioPorNoche * dias;
    }

    @Transactional(readOnly = true)
    public double calcularIngresosTotales() {
        List<Reserva> reservas = reservaRepository.findAll();
        // Sumar Total Base + Servicios - Descuentos para reservas válidas
        return reservas.stream()
                .filter(r -> EstadoReserva.FINALIZADA.getValor().equalsIgnoreCase(r.getEstadoReserva()) ||
                        EstadoReserva.ACTIVA.getValor().equalsIgnoreCase(r.getEstadoReserva()) ||
                        (EstadoReserva.PENDIENTE.getValor().equalsIgnoreCase(r.getEstadoReserva())
                                && r.getPago() != null && "COMPLETADO".equalsIgnoreCase(r.getPago().getEstado())))
                .mapToDouble(r -> {
                    double base = r.getTotalPagar() != null ? r.getTotalPagar() : 0.0;
                    double servicios = r.calcularTotalServicios();
                    double descuento = r.getMontoDescuento() != null ? r.getMontoDescuento() : 0.0;
                    return Math.max(0, base + servicios - descuento);
                })
                .sum();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getIngresosPorPeriodo(LocalDate inicio, LocalDate fin) {
        List<Reserva> reservas = reservaRepository.findAll();
        Map<LocalDate, Double> ingresosPorFecha = new TreeMap<>();

        LocalDate fecha = inicio;
        while (!fecha.isAfter(fin)) {
            ingresosPorFecha.put(fecha, 0.0);
            fecha = fecha.plusDays(1);
        }

        reservas.stream()
                .filter(r -> EstadoReserva.FINALIZADA.getValor().equalsIgnoreCase(r.getEstadoReserva()) ||
                        EstadoReserva.ACTIVA.getValor().equalsIgnoreCase(r.getEstadoReserva()))
                .forEach(r -> {
                    LocalDate fechaIngreso = r.getFechaSalidaReal() != null ? r.getFechaSalidaReal() : r.getFechaFin();
                    if (fechaIngreso != null && !fechaIngreso.isBefore(inicio) && !fechaIngreso.isAfter(fin)) {
                        ingresosPorFecha.merge(fechaIngreso, r.calcularTotalConDescuento(), Double::sum);
                    }
                });

        return ingresosPorFecha.entrySet().stream()
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("fecha", e.getKey().toString());
                    map.put("ingresos", e.getValue());
                    return map;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getOcupacionDiariaPorPeriodo(LocalDate inicio, LocalDate fin) {
        List<Reserva> reservas = reservaRepository.findAll();
        Map<LocalDate, Long> ocupacionPorFecha = new TreeMap<>();

        LocalDate fecha = inicio;
        while (!fecha.isAfter(fin)) {
            final LocalDate current = fecha;
            long ocupadas = reservas.stream()
                    .filter(r -> (EstadoReserva.ACTIVA.getValor().equalsIgnoreCase(r.getEstadoReserva()) ||
                            EstadoReserva.FINALIZADA.getValor().equalsIgnoreCase(r.getEstadoReserva())))
                    .filter(r -> !r.getFechaInicio().isAfter(current) && r.getFechaFin().isAfter(current))
                    .count();

            ocupacionPorFecha.put(fecha, ocupadas);
            fecha = fecha.plusDays(1);
        }

        return ocupacionPorFecha.entrySet().stream()
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("fecha", e.getKey().toString());
                    map.put("ocupacion", e.getValue());
                    return map;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getMovimientoPorPeriodo(LocalDate inicio, LocalDate fin) {
        List<Reserva> reservas = reservaRepository.findAll();
        Map<LocalDate, Integer> checkInsPorFecha = new TreeMap<>();
        Map<LocalDate, Integer> checkOutsPorFecha = new TreeMap<>();

        LocalDate fecha = inicio;
        while (!fecha.isAfter(fin)) {
            checkInsPorFecha.put(fecha, 0);
            checkOutsPorFecha.put(fecha, 0);
            fecha = fecha.plusDays(1);
        }

        reservas.stream()
                .filter(r -> r.getFechaInicio() != null)
                .filter(r -> !r.getFechaInicio().isBefore(inicio) && !r.getFechaInicio().isAfter(fin))
                .forEach(r -> checkInsPorFecha.merge(r.getFechaInicio(), 1, Integer::sum));

        reservas.stream()
                .filter(r -> r.getFechaFin() != null)
                .filter(r -> !r.getFechaFin().isBefore(inicio) && !r.getFechaFin().isAfter(fin))
                .forEach(r -> checkOutsPorFecha.merge(r.getFechaFin(), 1, Integer::sum));

        return checkInsPorFecha.keySet().stream()
                .map(f -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("fecha", f.toString());
                    map.put("checkIns", checkInsPorFecha.get(f));
                    map.put("checkOuts", checkOutsPorFecha.get(f));
                    return map;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long contarReservasPorEstado(String estado) {
        return reservaRepository.countByEstadoReservaIgnoreCase(estado);
    }

    // Contar check-ins: Todas las reservas vigentes (Pendientes + Activas)
    @Transactional(readOnly = true)
    public long contarCheckInsHoy() {
        List<String> estados = Arrays.asList(EstadoReserva.PENDIENTE.getValor(), EstadoReserva.ACTIVA.getValor());
        return reservaRepository.findAll().stream()
                .filter(r -> estados.contains(r.getEstadoReserva()))
                .count();
    }

    // Contar check-outs: Reservas finalizadas HOY
    @Transactional(readOnly = true)
    public long contarCheckOutsHoy() {
        LocalDate hoy = LocalDate.now();
        return reservaRepository.findAll().stream()
                .filter(r -> EstadoReserva.FINALIZADA.getValor().equalsIgnoreCase(r.getEstadoReserva()))
                .filter(r -> r.getFechaSalidaReal() != null && hoy.equals(r.getFechaSalidaReal()))
                .count();
    }

    // Nueva lógica: Contar habitaciones únicas que tienen alguna reserva activa o
    // futura (Pendiente/Activa)
    @Transactional(readOnly = true)
    public long contarHabitacionesReservadas() {
        return reservaRepository.findAll().stream()
                .filter(r -> EstadoReserva.ACTIVA.getValor().equalsIgnoreCase(r.getEstadoReserva()) ||
                        EstadoReserva.PENDIENTE.getValor().equalsIgnoreCase(r.getEstadoReserva()))
                .map(r -> r.getHabitacion().getId())
                .distinct()
                .count();
    }

    @Transactional(readOnly = true)
    public List<Reserva> obtenerLlegadasHoy() {
        LocalDate hoy = LocalDate.now();
        return reservaRepository.findAll().stream()
                .filter(r -> hoy.equals(r.getFechaInicio()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Reserva> obtenerSalidasHoy() {
        LocalDate hoy = LocalDate.now();
        return reservaRepository.findAll().stream()
                .filter(r -> hoy.equals(r.getFechaFin()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Reserva> obtenerReservasPorPeriodo(LocalDate inicio, LocalDate fin) {
        return reservaRepository.findAll().stream()
                .filter(r -> r.getFechaInicio() != null && r.getFechaFin() != null)
                .filter(r -> !r.getFechaInicio().isAfter(fin) && !r.getFechaFin().isBefore(inicio))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long contarReservasPorCliente(String username) {
        List<Reserva> todas = reservaRepository.findAll();
        return todas.stream()
                .filter(r -> r.getCliente() != null && r.getCliente().getUsuario() != null)
                .filter(r -> username.equals(r.getCliente().getUsuario().getUsername()))
                .count();
    }

    @Transactional(readOnly = true)
    public long contarReservasActivasPorCliente(String username) {
        List<Reserva> todas = reservaRepository.findAll();
        return todas.stream()
                .filter(r -> r.getCliente() != null && r.getCliente().getUsuario() != null)
                .filter(r -> username.equals(r.getCliente().getUsuario().getUsername()))
                .filter(r -> EstadoReserva.ACTIVA.getValor().equalsIgnoreCase(r.getEstadoReserva()))
                .count();
    }

    @Transactional(readOnly = true)
    public long contarReservasFinalizadasPorCliente(String username) {
        List<Reserva> todas = reservaRepository.findAll();
        return todas.stream()
                .filter(r -> r.getCliente() != null && r.getCliente().getUsuario() != null)
                .filter(r -> username.equals(r.getCliente().getUsuario().getUsername()))
                .filter(r -> EstadoReserva.FINALIZADA.getValor().equalsIgnoreCase(r.getEstadoReserva()))
                .count();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getIngresosUltimosDias(int dias) {
        LocalDate hoy = LocalDate.now();
        LocalDate inicio = hoy.minusDays(dias - 1);

        List<Reserva> reservas = reservaRepository.findAll();
        double total = reservas.stream()
                .filter(r -> r.getFechaSalidaReal() != null)
                .filter(r -> !r.getFechaSalidaReal().isBefore(inicio))
                .mapToDouble(Reserva::calcularTotalConDescuento)
                .sum();

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("total", total);
        resultado.put("dias", dias);
        resultado.put("fechaInicio", inicio.toString());
        resultado.put("fechaFin", hoy.toString());

        return resultado;
    }

    @Transactional
    public void asignarServicios(Long reservaId, List<Long> servicioIds, List<Integer> cantidades) {
        reservaRepository.findById(reservaId).ifPresent(reserva -> {
            Set<Servicio> servicios = new HashSet<>();
            if (servicioIds != null) {
                for (Long servicioId : servicioIds) {
                    servicioRepository.findById(servicioId).ifPresent(servicios::add);
                }
            }
            reserva.setServicios(servicios);
            reservaRepository.save(reserva);

            auditoriaService.registrarAccion("ASIGNACION_SERVICIOS",
                    "Servicios asignados a reserva ID: " + reservaId, "Reserva", reservaId);
        });
    }

    // ============== MÉTODOS PRIVADOS DE AYUDA ==============

    private void validarReserva(Reserva reserva) {
        if (reserva == null) {
            throw new IllegalArgumentException("La reserva no puede ser nula");
        }
        if (reserva.getCliente() == null) {
            throw new IllegalArgumentException("La reserva debe tener un cliente asignado");
        }
        if (reserva.getHabitacion() == null) {
            throw new IllegalArgumentException("La reserva debe tener una habitación asignada");
        }
        if (reserva.getFechaInicio() == null || reserva.getFechaFin() == null) {
            throw new IllegalArgumentException("Las fechas de inicio y fin son obligatorias");
        }
        if (reserva.getFechaInicio().isAfter(reserva.getFechaFin())) {
            throw new IllegalArgumentException("La fecha de inicio debe ser anterior a la fecha de fin");
        }
    }

    private void verificarHabitacionOperativa(Long habitacionId) {
        Optional<com.gestion.hotelera.model.Habitacion> habitacionOpt = habitacionService
                .buscarHabitacionPorId(habitacionId);
        if (habitacionOpt.isEmpty()) {
            throw new IllegalArgumentException("La habitación seleccionada no existe");
        }
        if ("MANTENIMIENTO".equalsIgnoreCase(habitacionOpt.get().getEstado())) {
            throw new IllegalArgumentException("La habitación seleccionada está en mantenimiento");
        }
    }

    private void verificarDisponibilidadFechas(Long habitacionId, Reserva reserva, Long reservaId) {
        List<Reserva> reservasExistentes = reservaRepository.findAll().stream()
                .filter(r -> r.getHabitacion() != null && r.getHabitacion().getId().equals(habitacionId))
                .filter(r -> reservaId == null || !r.getId().equals(reservaId))
                .filter(r -> !EstadoReserva.CANCELADA.getValor().equalsIgnoreCase(r.getEstadoReserva()))
                .collect(Collectors.toList());

        for (Reserva r : reservasExistentes) {
            // Determinar fecha fin efectiva: Si ya finalizó, usamos la salida real (libera
            // días si salió antes)
            LocalDate finEfectivo = r.getFechaFin();
            if (EstadoReserva.FINALIZADA.getValor().equalsIgnoreCase(r.getEstadoReserva())
                    && r.getFechaSalidaReal() != null) {
                finEfectivo = r.getFechaSalidaReal();
            }

            // Se considera solapamiento si:
            // (NuevaInicio < FinEfectivo) Y (NuevaFin > ExistenteInicio)
            if (reserva.getFechaInicio().isBefore(finEfectivo) &&
                    reserva.getFechaFin().isAfter(r.getFechaInicio())) {
                throw new IllegalArgumentException(
                        "La habitación ya está reservada en las fechas seleccionadas");
            }
        }
    }

    private void actualizarEstadoHabitacionSegunReserva(Reserva reserva) {
        // Solo actualizar el estado físico de la habitación a OCUPADA si la reserva es
        // para HOY
        LocalDate hoy = LocalDate.now();
        boolean esParaHoy = !hoy.isBefore(reserva.getFechaInicio()) && !hoy.isAfter(reserva.getFechaFin());

        if (esParaHoy && (EstadoReserva.ACTIVA.getValor().equalsIgnoreCase(reserva.getEstadoReserva()) ||
                EstadoReserva.PENDIENTE.getValor().equalsIgnoreCase(reserva.getEstadoReserva()) ||
                "PROCESANDO".equalsIgnoreCase(reserva.getEstadoReserva()))) {
            habitacionService.actualizarEstadoHabitacion(reserva.getHabitacion().getId(),
                    EstadoHabitacion.OCUPADA.getValor());
        }
    }

    private void liberarHabitacion(Reserva reserva) {
        if (reserva.getHabitacion() != null && reserva.getHabitacion().getId() != null) {
            habitacionService.actualizarEstadoHabitacion(reserva.getHabitacion().getId(),
                    EstadoHabitacion.DISPONIBLE.getValor());
        }
    }

    private void validarCancelacion(Reserva reserva, String userRole) {
        if (EstadoReserva.CANCELADA.getValor().equalsIgnoreCase(reserva.getEstadoReserva())) {
            throw new IllegalStateException("La reserva ya está cancelada");
        }
        if (EstadoReserva.FINALIZADA.getValor().equalsIgnoreCase(reserva.getEstadoReserva())) {
            throw new IllegalStateException("No se puede cancelar una reserva finalizada");
        }
        // Solo restringir cancelación de pagados si NO es personal del hotel
        boolean esPersonal = userRole.contains("ADMIN") || userRole.contains("RECEPCIONISTA");
        if (!esPersonal && reserva.getPago() != null && "COMPLETADO".equalsIgnoreCase(reserva.getPago().getEstado())) {
            throw new IllegalStateException(
                    "No puede cancelar una reserva ya pagada. Contacte a recepción para solicitar reembolso/cancelación.");
        }
    }

    private void registrarAuditoriaCreacionOActualizacion(Reserva reserva) {
        String accion = reserva.getId() != null ? "ACTUALIZACION_RESERVA" : "CREACION_RESERVA";
        String descripcion = "Reserva creada o actualizada: ID=" + reserva.getId() +
                ", Cliente=" + (reserva.getCliente() != null ? reserva.getCliente().getNombres() : "N/A") +
                ", Habitación=" + (reserva.getHabitacion() != null ? reserva.getHabitacion().getNumero() : "N/A");

        auditoriaService.registrarAccion(accion, descripcion, "Reserva", reserva.getId());
    }

    private void registrarAuditoriaCancelacion(Reserva reserva, String userRole) {
        auditoriaService.registrarAccion("CANCELACION_RESERVA",
                "Reserva cancelada (ID: " + reserva.getId() + ") por usuario con rol: " + userRole,
                "Reserva", reserva.getId());
    }

    private void enviarEmailConfirmacionSiEsNueva(boolean esNueva, Reserva reserva) {
        if (esNueva && reserva.getCliente() != null && reserva.getCliente().getEmail() != null) {
            try {
                String destinatario = reserva.getCliente().getEmail();
                String asunto;
                String mensaje;

                // Verificar si está pendiente para enviar mensaje acorde
                if (EstadoReserva.PENDIENTE.getValor().equalsIgnoreCase(reserva.getEstadoReserva())) {
                    asunto = "Reserva Registrada - Pendiente de Pago";
                    mensaje = String.format(
                            "Estimado/a %s,\n\nSu reserva ha sido registrada en nuestro sistema.\n" +
                                    "Estado actual: PENDIENTE DE PAGO.\n\n" +
                                    "Detalles de la reserva:\n" +
                                    "- Habitación: %s\n" +
                                    "- Fecha de llegada: %s\n" +
                                    "- Fecha de salida: %s\n" +
                                    "- Total a pagar: S/. %.2f\n\n" +
                                    "Por favor, complete el pago para confirmar su estadía definitivamente.\n" +
                                    "Gracias por su preferencia.",
                            reserva.getCliente().getNombres(),
                            reserva.getHabitacion() != null ? reserva.getHabitacion().getNumero() : "N/A",
                            reserva.getFechaInicio(),
                            reserva.getFechaFin(),
                            reserva.getTotalPagar());
                } else {
                    // Caso por defecto (ACTIVA u otros)
                    asunto = "Confirmación de Reserva - Hotel";
                    mensaje = String.format(
                            "Estimado/a %s,\n\nSu reserva ha sido confirmada exitosamente.\n\n" +
                                    "Detalles de la reserva:\n" +
                                    "- Habitación: %s\n" +
                                    "- Fecha de llegada: %s\n" +
                                    "- Fecha de salida: %s\n" +
                                    "- Total a pagar: S/. %.2f\n\n" +
                                    "Gracias por su preferencia.",
                            reserva.getCliente().getNombres(),
                            reserva.getHabitacion() != null ? reserva.getHabitacion().getNumero() : "N/A",
                            reserva.getFechaInicio(),
                            reserva.getFechaFin(),
                            reserva.getTotalPagar());
                }

                emailService.enviarEmail(destinatario, asunto, mensaje);
            } catch (Exception e) {
                logger.warn("No se pudo enviar email de confirmación: {}", e.getMessage());
            }
        }
    }

    private void crearNotificacionNuevaReserva(Reserva reserva) {
        if (reserva.getCliente() != null && reserva.getHabitacion() != null) {
            String nombreCliente = reserva.getCliente().getNombres() + " " + reserva.getCliente().getApellidos();
            String mensaje = "Nueva reserva creada: " + nombreCliente +
                    " - Habitación " + reserva.getHabitacion().getNumero();
            notificacionService.crearNotificacion("Nueva Reserva", mensaje, "INFORMACION");
        }
    }

    private void crearNotificacionCheckIn(Reserva reserva) {
        if (reserva.getCliente() != null && reserva.getHabitacion() != null) {
            String nombreCliente = reserva.getCliente().getNombres() + " " + reserva.getCliente().getApellidos();
            String mensaje = "Check-in realizado: " + nombreCliente +
                    " - Habitación " + reserva.getHabitacion().getNumero();
            notificacionService.crearNotificacion("Check-In Realizado", mensaje, "INFORMACION");
        }
    }

    private void crearNotificacionCheckOut(Reserva reserva) {
        if (reserva.getCliente() != null && reserva.getHabitacion() != null) {
            String nombreCliente = reserva.getCliente().getNombres() + " " + reserva.getCliente().getApellidos();
            String mensaje = "Check-out realizado: " + nombreCliente +
                    " - Habitación " + reserva.getHabitacion().getNumero();
            notificacionService.crearNotificacion("Check-Out Realizado", mensaje, "INFORMACION");
        }
    }
}
