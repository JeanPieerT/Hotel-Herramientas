package com.gestion.hotelera.service;

import com.gestion.hotelera.enums.EstadoReserva;
import com.gestion.hotelera.exception.ClienteConReservasActivasException;
import com.gestion.hotelera.model.Cliente;
import com.gestion.hotelera.model.Reserva;
import com.gestion.hotelera.model.Usuario;
import com.gestion.hotelera.repository.ClienteRepository;
import com.gestion.hotelera.repository.ReservaRepository;
import com.gestion.hotelera.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@SuppressWarnings("null")
public class ClienteService {

    private static final Logger logger = LoggerFactory.getLogger(ClienteService.class);
    private static final int PAGINA_DEFAULT_SIZE = 20;

    private final ClienteRepository clienteRepository;
    private final AuditoriaService auditoriaService;
    private final ReservaRepository reservaRepository;
    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    public ClienteService(ClienteRepository clienteRepository,
            AuditoriaService auditoriaService,
            ReservaRepository reservaRepository,
            UsuarioRepository usuarioRepository,
            PasswordEncoder passwordEncoder) {
        this.clienteRepository = clienteRepository;
        this.auditoriaService = auditoriaService;
        this.reservaRepository = reservaRepository;
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public @NonNull Cliente guardar(@NonNull Cliente cliente) {
        validarClienteNoNulo(cliente);
        return clienteRepository.save(cliente);
    }

    @Transactional
    public Cliente crearCliente(Cliente cliente) {
        validarClienteNoNulo(cliente);
        validarDatosCliente(cliente);
        validarDniUnico(cliente.getDni(), null);
        validarEmailUnico(cliente.getEmail(), null);

        // Verificar si se enviaron datos de usuario válidos
        if (cliente.getUsuario() != null) {
            String username = cliente.getUsuario().getUsername();
            String password = cliente.getUsuario().getPassword();

            // Si el nombre de usuario o contraseña están vacíos, asumimos que no se quiere
            // crear cuenta
            if ((username == null || username.trim().isEmpty()) && (password == null || password.trim().isEmpty())) {
                cliente.setUsuario(null);
            } else {
                // Si hay datos, validamos y procesamos
                procesarUsuarioCliente(cliente);
            }
        }

        Cliente nuevoCliente = clienteRepository.save(cliente);
        registrarAuditoriaCreacion(nuevoCliente);
        logger.info("Cliente creado exitosamente: ID={}, DNI={}", nuevoCliente.getId(), nuevoCliente.getDni());
        return nuevoCliente;
    }

    @Transactional
    public Cliente actualizarCliente(Cliente clienteActualizado) {
        if (clienteActualizado.getId() == null) {
            throw new IllegalArgumentException("El ID del cliente no puede ser nulo para actualizar.");
        }

        return clienteRepository.findById(clienteActualizado.getId())
                .map(clienteExistente -> actualizarDatosCliente(clienteExistente, clienteActualizado))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cliente con ID " + clienteActualizado.getId() + " no encontrado para actualizar."));
    }

    @Transactional(readOnly = true)
    public Optional<Cliente> obtenerClientePorId(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return clienteRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Cliente> buscarClientePorDni(String dni) {
        return clienteRepository.findByDni(dni);
    }

    @Transactional(readOnly = true)
    public boolean existeClientePorDni(String dni) {
        return clienteRepository.findByDni(dni).isPresent();
    }

    @Transactional(readOnly = true)
    public List<Cliente> obtenerTodosLosClientes() {
        return clienteRepository.findAll();
    }

    @Transactional(readOnly = true)
    public long contarClientes() {
        return clienteRepository.count();
    }

    @Transactional
    public boolean eliminarClientePorId(@NonNull Long id) {
        return clienteRepository.findById(id)
                .map(cliente -> {
                    // 1. Validar reservas activas (Check-in pendiente o en curso) -> Impide
                    // eliminación
                    validarReservasActivas(cliente);

                    // 2. Verificar si tiene historial financiero que debemos preservar (Reservas
                    // FINALIZADAS)
                    boolean tieneHistorial = reservaRepository.findByCliente(cliente).stream()
                            .anyMatch(r -> EstadoReserva.FINALIZADA.getValor().equalsIgnoreCase(r.getEstadoReserva()));

                    Usuario usuario = cliente.getUsuario();

                    if (tieneHistorial) {
                        // >>> HISTORIAL EXISTE: ANONIMIZAR (SOFT DELETE) <<<
                        // Objetivo: Mantener la integridad de los reportes de ingresos.

                        // 1. Revocar acceso (Eliminar Usuario)
                        if (usuario != null) {
                            cliente.setUsuario(null);
                            usuarioRepository.delete(usuario);
                        }

                        // 2. Anonimizar datos personales para liberar DNI/Email
                        // Generamos un DNI dummy para evitar colisiones: 9 + ID (rellenado a 7 ceros)
                        String dummyDni = String.format("9%07d", cliente.getId() % 10000000);

                        cliente.setNombres("Cliente");
                        cliente.setApellidos("Eliminado " + cliente.getId());
                        cliente.setDni(dummyDni);
                        cliente.setEmail("deleted_" + cliente.getId() + "@system.local");
                        cliente.setTelefono(null);

                        // 3. Guardar cambios
                        clienteRepository.save(cliente);

                        registrarAuditoriaEliminacion(cliente);
                        logger.info("Cliente ID={} anonimizado (historial preservado).", id);

                    } else {
                        // >>> SIN HISTORIAL: ELIMINACIÓN FÍSICA (HARD DELETE) <<<
                        // Solo tiene reservas canceladas o ninguna. Limpiamos todo.

                        List<Reserva> reservas = reservaRepository.findByCliente(cliente);
                        if (!reservas.isEmpty()) {
                            reservaRepository.deleteAll(reservas);
                        }

                        clienteRepository.delete(cliente);
                        clienteRepository.flush();

                        if (usuario != null) {
                            usuarioRepository.delete(usuario);
                        }

                        registrarAuditoriaEliminacion(cliente);
                        logger.info("Cliente ID={} eliminado físicamente (sin historial).", id);
                    }
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Page<Cliente> findAllClientes(Pageable pageable) {
        return obtenerClientesPaginados(ensureValidPageable(pageable), null);
    }

    @Transactional(readOnly = true)
    public Page<Cliente> searchClientes(String searchTerm, Pageable pageable) {
        return obtenerClientesPaginados(ensureValidPageable(pageable), searchTerm);
    }

    @Transactional(readOnly = true)
    public Page<Cliente> obtenerClientesPaginados(@NonNull Pageable pageable, String search) {
        Pageable validPageable = ensureValidPageable(pageable);
        Page<Cliente> clientes;

        if (search == null || search.trim().isEmpty()) {
            clientes = clienteRepository.findAll(validPageable);
        } else {
            clientes = clienteRepository
                    .findByDniContainingIgnoreCaseOrNombresContainingIgnoreCaseOrApellidosContainingIgnoreCase(
                            search, search, search, validPageable);
        }

        clientes.forEach(this::cargarDatosTransitorios);
        return clientes;
    }

    @Transactional(readOnly = true)
    public Cliente obtenerPorEmail(String email) {
        return clienteRepository.findByEmail(email).orElse(null);
    }

    @Transactional(readOnly = true)
    public Cliente obtenerPorUsername(String username) {
        return clienteRepository.findByUsuarioUsername(username).orElse(null);
    }

    // ============== MÉTODOS PRIVADOS DE AYUDA ==============

    private void validarClienteNoNulo(Cliente cliente) {
        if (cliente == null) {
            throw new IllegalArgumentException("El cliente no puede ser nulo");
        }
    }

    private void validarDatosCliente(Cliente cliente) {
        if (cliente.getDni() == null || cliente.getDni().trim().isEmpty()) {
            throw new IllegalArgumentException("El DNI no puede estar vacío");
        }
        if (!cliente.getDni().matches("^\\d{8}$")) {
            throw new IllegalArgumentException("El DNI debe contener exactamente 8 dígitos numéricos");
        }
        if (cliente.getNombres() == null || cliente.getNombres().trim().isEmpty()) {
            throw new IllegalArgumentException("Los nombres no pueden estar vacíos");
        }
        if (cliente.getApellidos() == null || cliente.getApellidos().trim().isEmpty()) {
            throw new IllegalArgumentException("Los apellidos no pueden estar vacíos");
        }
        if (cliente.getTelefono() != null && !cliente.getTelefono().trim().isEmpty()
                && !cliente.getTelefono().matches("^\\d{9}$")) {
            throw new IllegalArgumentException("El teléfono debe contener exactamente 9 dígitos numéricos");
        }
    }

    private void validarDniUnico(String dni, Long idExcluir) {
        Optional<Cliente> existente = clienteRepository.findByDni(dni);
        if (existente.isPresent() && (idExcluir == null || !existente.get().getId().equals(idExcluir))) {
            throw new IllegalArgumentException("Ya existe un cliente con el DNI '" + dni + "'.");
        }
    }

    private void validarEmailUnico(String email, Long idExcluir) {
        if (email == null || email.trim().isEmpty()) {
            return;
        }
        Optional<Cliente> existente = clienteRepository.findByEmail(email);
        if (existente.isPresent() && (idExcluir == null || !existente.get().getId().equals(idExcluir))) {
            throw new IllegalArgumentException("Ya existe un cliente con el email '" + email + "'.");
        }
    }

    private void procesarUsuarioCliente(Cliente cliente) {
        Usuario usuario = cliente.getUsuario();

        if (usuario.getUsername() == null || usuario.getUsername().isEmpty()) {
            throw new IllegalArgumentException("El nombre de usuario es obligatorio");
        }
        if (usuario.getPassword() == null || usuario.getPassword().isEmpty()) {
            throw new IllegalArgumentException("La contraseña es obligatoria");
        }
        if (usuarioRepository.findByUsername(usuario.getUsername()).isPresent()) {
            throw new IllegalArgumentException("El nombre de usuario ya está en uso");
        }

        usuario.setPassword(passwordEncoder.encode(usuario.getPassword()));
        usuario.setRol("ROLE_CLIENTE");

        Usuario usuarioGuardado = usuarioRepository.save(usuario);
        cliente.setUsuario(usuarioGuardado);
    }

    private Cliente actualizarDatosCliente(Cliente clienteExistente, Cliente clienteActualizado) {
        // Validar cambios en DNI y email
        if (!clienteExistente.getDni().equals(clienteActualizado.getDni())) {
            validarDniUnico(clienteActualizado.getDni(), clienteExistente.getId());
        }
        if (clienteActualizado.getEmail() != null && !clienteActualizado.getEmail().isEmpty()
                && !clienteActualizado.getEmail().equals(clienteExistente.getEmail())) {
            validarEmailUnico(clienteActualizado.getEmail(), clienteExistente.getId());
        }

        // Actualizar datos
        clienteExistente.setNombres(clienteActualizado.getNombres());
        clienteExistente.setApellidos(clienteActualizado.getApellidos());
        clienteExistente.setDni(clienteActualizado.getDni());
        clienteExistente.setNacionalidad(clienteActualizado.getNacionalidad());
        clienteExistente.setEmail(clienteActualizado.getEmail());
        clienteExistente.setTelefono(clienteActualizado.getTelefono());

        Cliente clienteGuardado = clienteRepository.save(clienteExistente);
        registrarAuditoriaActualizacion(clienteGuardado);
        logger.info("Cliente actualizado: ID={}, DNI={}", clienteGuardado.getId(), clienteGuardado.getDni());
        return clienteGuardado;
    }

    private void validarReservasActivas(Cliente cliente) {
        List<Reserva> reservas = reservaRepository.findByCliente(cliente);
        List<ClienteConReservasActivasException.ReservaActivaResumen> reservasActivas = reservas.stream()
                .filter(this::esReservaActiva)
                .map(this::mapearResumenReservaActiva)
                .collect(Collectors.toList());

        if (!reservasActivas.isEmpty()) {
            throw new ClienteConReservasActivasException(cliente.getId(), reservasActivas);
        }
        // Nota: Ya no eliminamos las reservas aquí. Eso se maneja en el método
        // principal según el caso.
    }

    private boolean esReservaActiva(Reserva reserva) {
        if (reserva.getEstadoReserva() == null) {
            return false;
        }
        String estado = reserva.getEstadoReserva().toUpperCase();
        return estado.equals(EstadoReserva.ACTIVA.getValor()) || estado.equals(EstadoReserva.PENDIENTE.getValor());
    }

    private ClienteConReservasActivasException.ReservaActivaResumen mapearResumenReservaActiva(Reserva reserva) {
        String habitacion = reserva.getHabitacion() != null ? reserva.getHabitacion().getNumero() : "Sin asignar";
        return new ClienteConReservasActivasException.ReservaActivaResumen(
                reserva.getId(),
                habitacion,
                reserva.getEstadoReserva(),
                reserva.getFechaInicio(),
                reserva.getFechaFin());
    }

    private void cargarDatosTransitorios(Cliente cliente) {
        List<Reserva> reservas = reservaRepository.findByCliente(cliente);
        cliente.setTotalReservas((long) reservas.size());

        Optional<Reserva> reservaActiva = reservas.stream().filter(this::esReservaActiva).findFirst();
        if (reservaActiva.isPresent()) {
            cliente.setHasActiveReservations(true);
            cliente.setActiveReservationId(reservaActiva.get().getId());
        } else {
            cliente.setHasActiveReservations(false);
        }

        reservas.stream()
                .filter(r -> EstadoReserva.FINALIZADA.getValor().equalsIgnoreCase(r.getEstadoReserva()))
                .map(Reserva::getFechaFin)
                .max(java.util.Comparator.naturalOrder())
                .ifPresent(cliente::setUltimaEstancia);
    }

    private Pageable ensureValidPageable(Pageable pageable) {
        return pageable != null ? pageable : PageRequest.of(0, PAGINA_DEFAULT_SIZE);
    }

    private void registrarAuditoriaCreacion(Cliente cliente) {
        if (cliente.getId() != null) {
            auditoriaService.registrarAccion(
                    "CREACION_CLIENTE",
                    "Nuevo cliente registrado: " + cliente.getNombres() + " " + cliente.getApellidos() +
                            " (DNI: " + cliente.getDni() + ")",
                    "Cliente",
                    cliente.getId());
        }
    }

    private void registrarAuditoriaActualizacion(Cliente cliente) {
        if (cliente.getId() != null) {
            auditoriaService.registrarAccion(
                    "ACTUALIZACION_CLIENTE",
                    "Cliente '" + cliente.getNombres() + " " + cliente.getApellidos() +
                            "' (ID: " + cliente.getId() + ") actualizado.",
                    "Cliente",
                    cliente.getId());
        }
    }

    private void registrarAuditoriaEliminacion(Cliente cliente) {
        if (cliente.getId() != null) {
            auditoriaService.registrarAccion(
                    "ELIMINACION_CLIENTE",
                    "Cliente '" + cliente.getNombres() + " " + cliente.getApellidos() +
                            "' (ID: " + cliente.getId() + ") eliminado.",
                    "Cliente",
                    cliente.getId());
        }
    }
}