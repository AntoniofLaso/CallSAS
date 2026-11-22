package com.sas.diraya.citas.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/agendas")
public class AgendaController {

    // Mapa: idMedico -> Lista de huecos horarios
    private final Map<String, List<SlotAgendaDTO>> agendasMedicos = new ConcurrentHashMap<>();

    // RF2: El médico especialista actualiza su disponibilidad en la agenda
    @PostMapping("/{idMedico}/huecos")
    public ResponseEntity<?> publicarDisponibilidad(
            @PathVariable String idMedico,
            @RequestBody List<LocalDateTime> fechasDisponibles) {

        agendasMedicos.putIfAbsent(idMedico, Collections.synchronizedList(new ArrayList<>()));
        List<SlotAgendaDTO> agenda = agendasMedicos.get(idMedico);

        for (LocalDateTime fecha : fechasDisponibles) {
            String idSlot = "SLOT-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
            agenda.add(new SlotAgendaDTO(idSlot, fecha, true, null));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "idMedico", idMedico,
                "mensaje", "Agenda actualizada y sincronizada con DIRAYA.",
                "huecosTotales", agenda
        ));
    }

    // Consultar huecos libres de un especialista
    @GetMapping("/{idMedico}/disponibles")
    public ResponseEntity<?> obtenerHuecosLibres(@PathVariable String idMedico) {
        List<SlotAgendaDTO> agenda = agendasMedicos.getOrDefault(idMedico, List.of());
        List<SlotAgendaDTO> libres = agenda.stream().filter(SlotAgendaDTO::isDisponible).toList();
        return ResponseEntity.ok(libres);
    }

    // RF2 (Flujo Excepcional): Reserva de hueco con control de concurrencia / colisión
    @PostMapping("/{idMedico}/reservar/{idSlot}")
    public ResponseEntity<?> reservarHueco(
            @PathVariable String idMedico,
            @PathVariable String idSlot,
            @RequestParam String idPaciente) {

        List<SlotAgendaDTO> agenda = agendasMedicos.get(idMedico);
        if (agenda == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Agenda médica no encontrada."));
        }

        Optional<SlotAgendaDTO> slotOpt = agenda.stream()
                .filter(s -> s.getIdSlot().equals(idSlot))
                .findFirst();

        if (slotOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Hueco horario no existe."));
        }

        SlotAgendaDTO slot = slotOpt.get();

        // Bloqueo sincronizado para detectar colisiones simultáneas (RF2 Flujo Excepcional)
        synchronized (slot) {
            if (!slot.isDisponible()) {
                // Dos pacientes intentaron reservar la misma fecha/hora simultáneamente
                registrarIncidenciaAdministrativa(idMedico, idSlot, slot.getPacienteAsignado(), idPaciente);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "Conflicto de reserva simultánea detectado.",
                        "detalle", "El hueco acaba de ser ocupado. Se ha emitido un aviso urgente a un administrativo para reasignación."
                ));
            }

            slot.setDisponible(false);
            slot.setPacienteAsignado(idPaciente);
        }

        return ResponseEntity.ok(Map.of(
                "mensaje", "Hueco reservado correctamente.",
                "slot", slot
        ));
    }

    private void registrarIncidenciaAdministrativa(String idMedico, String idSlot, String paciente1, String paciente2) {
        System.out.println("[INCIDENCIA CONCURRENCIA RF2] Médico: " + idMedico + " | Slot: " + idSlot +
                " | Conflicto entre " + paciente1 + " y " + paciente2 + ". Escalado a Personal Administrativo.");
    }

    public static class SlotAgendaDTO {
        private String idSlot;
        private LocalDateTime fechaHora;
        private boolean disponible;
        private String pacienteAsignado;

        public SlotAgendaDTO(String idSlot, LocalDateTime fechaHora, boolean disponible, String pacienteAsignado) {
            this.idSlot = idSlot;
            this.fechaHora = fechaHora;
            this.disponible = disponible;
            this.pacienteAsignado = pacienteAsignado;
        }
        public String getIdSlot() { return idSlot; }
        public LocalDateTime getFechaHora() { return fechaHora; }
        public boolean isDisponible() { return disponible; }
        public void setDisponible(boolean disponible) { this.disponible = disponible; }
        public String getPacienteAsignado() { return pacienteAsignado; }
        public void setPacienteAsignado(String pacienteAsignado) { this.pacienteAsignado = pacienteAsignado; }
    }
}
