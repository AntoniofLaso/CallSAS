package com.sas.diraya.citas.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/citas")
public class CitaController {

    // Simulación de persistencia en memoria para validación funcional
    private final Map<String, CitaDTO> baseDatosCitas = new ConcurrentHashMap<>();
    private final Queue<String> listaEsperaCardiologia = new LinkedList<>(Arrays.asList("PAC-101", "PAC-102", "PAC-103"));
    private static final long PLAZO_MINIMO_HORAS_MODIFICACION = 24;

    // RF1: Asignación automática de cita al siguiente paciente en lista de espera
    @PostMapping("/asignar-automatica")
    public ResponseEntity<?> asignarCitaDesdeListaEspera(@RequestBody SolicitudAsignacionDTO solicitud) {
        if (listaEsperaCardiologia.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("mensaje", "No hay pacientes pendientes en la lista de espera."));
        }

        String idPaciente = listaEsperaCardiologia.poll();
        String idCita = "CITA-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        CitaDTO nuevaCita = new CitaDTO(
                idCita,
                idPaciente,
                solicitud.idMedico(),
                solicitud.fechaHora(),
                "PENDIENTE_CONFIRMACION"
        );

        baseDatosCitas.put(idCita, nuevaCita);

        // Simulación de sincronización con DIRAYA (RF3) y Notificación (RF5)
        boolean sincronizadoDiraya = sincronizarConDiraya(nuevaCita);
        notificarPaciente(idPaciente, "Se le ha asignado una cita para el " + solicitud.fechaHora());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "cita", nuevaCita,
                "sincronizadoDiraya", sincronizadoDiraya,
                "estadoListaEspera", "Paciente extraído de cola. Restantes: " + listaEsperaCardiologia.size()
        ));
    }

    // RF4: Aceptar cita por parte del paciente
    @PatchMapping("/{idCita}/aceptar")
    public ResponseEntity<?> aceptarCita(@PathVariable String idCita) {
        CitaDTO cita = baseDatosCitas.get(idCita);
        if (cita == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Cita no encontrada."));
        }

        cita.setEstado("ACEPTADA");
        sincronizarConDiraya(cita);
        return ResponseEntity.ok(Map.of("mensaje", "Cita confirmada en el calendario y en DIRAYA.", "cita", cita));
    }

    // RF4: Reprogramar o cancelar cita (con validación de plazo y flujo alternativo)
    @PutMapping("/{idCita}/gestionar")
    public ResponseEntity<?> reprogramarOCancelarCita(
            @PathVariable String idCita,
            @RequestBody AccionPacienteDTO accion) {

        CitaDTO cita = baseDatosCitas.get(idCita);
        if (cita == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Cita no encontrada."));
        }

        // Validación de plazo establecido por la administración (RF4 - Flujo Alternativo)
        long horasRestantes = ChronoUnit.HOURS.between(LocalDateTime.now(), cita.getFechaHora());
        if (horasRestantes < PLAZO_MINIMO_HORAS_MODIFICACION) {
            avisoPersonalAdministrativo(idCita, "Intento de modificación fuera de plazo por paciente " + cita.getIdPaciente());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "Fuera de plazo permitido (< 24h).",
                    "accionRequerida", "Se ha notificado al personal administrativo. Contacte con su centro de salud."
            ));
        }

        if ("CANCELAR".equalsIgnoreCase(accion.tipoAccion())) {
            cita.setEstado("CANCELADA");
            // Se reincorpora al paciente o se reorganiza la lista de espera (RF4)
            listaEsperaCardiologia.offer(cita.getIdPaciente());
            sincronizarConDiraya(cita);
            return ResponseEntity.ok(Map.of(
                    "mensaje", "Cita cancelada. Hueco liberado y lista de espera reorganizada.",
                    "cita", cita
            ));
        } else if ("REPROGRAMAR".equalsIgnoreCase(accion.tipoAccion()) && accion.nuevaFecha() != null) {
            cita.setFechaHora(accion.nuevaFecha());
            cita.setEstado("REPROGRAMADA");
            sincronizarConDiraya(cita);
            notificarPaciente(cita.getIdPaciente(), "Su cita ha sido reprogramada para: " + accion.nuevaFecha());
            return ResponseEntity.ok(Map.of("mensaje", "Cita reprogramada con éxito.", "cita", cita));
        }

        return ResponseEntity.badRequest().body(Map.of("error", "Acción no válida. Use CANCELAR o REPROGRAMAR."));
    }

    // Métodos auxiliares de integración (RF3, RF5)
    private boolean sincronizarConDiraya(CitaDTO cita) {
        // Simulación de volcado HL7/FHIR hacia CPD Junta de Andalucía
        return true;
    }

    private void notificarPaciente(String idPaciente, String mensaje) {
        System.out.println("[NOTIFICACIÓN -> " + idPaciente + "]: " + mensaje);
    }

    private void avisoPersonalAdministrativo(String idCita, String motivo) {
        System.out.println("[ALERTA ADMINISTRATIVO - Cita " + idCita + "]: " + motivo);
    }

    // DTOs internos para validación directa
    public record SolicitudAsignacionDTO(String idMedico, LocalDateTime fechaHora) {}
    public record AccionPacienteDTO(String tipoAccion, LocalDateTime nuevaFecha) {}

    public static class CitaDTO {
        private String idCita;
        private String idPaciente;
        private String idMedico;
        private LocalDateTime fechaHora;
        private String estado;

        public CitaDTO(String idCita, String idPaciente, String idMedico, LocalDateTime fechaHora, String estado) {
            this.idCita = idCita;
            this.idPaciente = idPaciente;
            this.idMedico = idMedico;
            this.fechaHora = fechaHora;
            this.estado = estado;
        }
        public String getIdCita() { return idCita; }
        public String getIdPaciente() { return idPaciente; }
        public String getIdMedico() { return idMedico; }
        public LocalDateTime getFechaHora() { return fechaHora; }
        public void setFechaHora(LocalDateTime fechaHora) { this.fechaHora = fechaHora; }
        public String getEstado() { return estado; }
        public void setEstado(String estado) { this.estado = estado; }
    }
}
