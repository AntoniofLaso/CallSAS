package com.sas.diraya.citas.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/sistema")
public class IntegracionNotificacionController {

    private final List> colaContingenciaDiraya = new ArrayList<>();
    private boolean dirayaOperativo = true;

    // Interruptor para probar el flujo excepcional de caída de DIRAYA (RF3)
    @PatchMapping("/diraya/estado")
    public ResponseEntity<?> simularEstadoDiraya(@RequestParam boolean operativo) {
        this.dirayaOperativo = operativo;
        return ResponseEntity.ok(Map.of(
                "dirayaOperativo", this.dirayaOperativo,
                "registrosPendientesContingencia", colaContingenciaDiraya.size()
        ));
    }

    // RF3: Sincronización bidireccional HL7/FHIR con DIRAYA
    @PostMapping("/diraya/sincronizar")
    public ResponseEntity<?> sincronizarDatosDiraya(@RequestBody Map<String, Object> payloadFHIR) {
        if (!dirayaOperativo) {
            // Flujo Excepcional RF3: Caída de DIRAYA -> Almacenar en cola segura para evitar pérdida de datos
            payloadFHIR.put("timestampFallo", LocalDateTime.now().toString());
            colaContingenciaDiraya.add(payloadFHIR);

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "estado", "CONTINGENCIA_ACTIVADA",
                    "mensaje", "DIRAYA no responde. Datos encolados de forma segura bajo normativa ENS para volcado posterior.",
                    "enCola", colaContingenciaDiraya.size()
            ));
        }

        // Si DIRAYA vuelve a estar operativo, vacía la cola pendiente primero
        int sincronizadosPrevios = colaContingenciaDiraya.size();
        colaContingenciaDiraya.clear();

        return ResponseEntity.ok(Map.of(
                "estado", "SINCRONIZADO_OK",
                "estandar", "HL7/FHIR",
                "registrosRecuperadosDeContingencia", sincronizadosPrevios,
                "datosActuales", payloadFHIR
        ));
    }

    // RF5: Envío de notificación con reintento por canal alternativo y escalado administrativo
    @PostMapping("/notificaciones/enviar")
    public ResponseEntity<?> enviarNotificacionPaciente(@RequestBody SolicitudNotificacionDTO req) {
        // 1. Intento por canal principal (ej. PUSH_APP)
        boolean exitoPrincipal = simularEnvioCanal(req.canalPrincipal(), req.fallarPrincipal());

        if (exitoPrincipal) {
            return ResponseEntity.ok(Map.of(
                    "estado", "ENVIADA",
                    "canalUtilizado", req.canalPrincipal(),
                    "destinatario", req.idPaciente()
            ));
        }

        // 2. Flujo Alternativo RF5: Fallo en canal principal -> Reintento por vía alternativa (ej. SMS)
        boolean exitoAlternativo = simularEnvioCanal(req.canalAlternativo(), req.fallarAlternativo());

        if (exitoAlternativo) {
            return ResponseEntity.ok(Map.of(
                    "estado", "ENVIADA_POR_VIA_ALTERNATIVA",
                    "canalFallido", req.canalPrincipal(),
                    "canalUtilizado", req.canalAlternativo(),
                    "destinatario", req.idPaciente()
            ));
        }

        // 3. Flujo Alternativo RF5 (Fallo persistente): Escalado a Personal Administrativo
        System.out.println("[ESCALADO ADMINISTRATIVO RF5] Imposible contactar con paciente " + req.idPaciente() +
                " tras fallar " + req.canalPrincipal() + " y " + req.canalAlternativo());

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "estado", "ERROR_PERSISTENTE_ESCALADO",
                "mensaje", "Fallaron todos los canales de notificación. Se ha derivado el aviso al personal administrativo para contacto telefónico manual."
        ));
    }

    private boolean simularEnvioCanal(String canal, boolean forzarFallo) {
        return !forzarFallo && canal != null && !canal.isBlank();
    }

    public record SolicitudNotificacionDTO(
            String idPaciente,
            String mensaje,
            String canalPrincipal,
            String canalAlternativo,
            boolean fallarPrincipal,
            boolean fallarAlternativo
    ) {}
}
