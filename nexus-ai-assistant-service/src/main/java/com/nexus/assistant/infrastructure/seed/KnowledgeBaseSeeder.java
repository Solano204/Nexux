package com.nexus.assistant.infrastructure.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class KnowledgeBaseSeeder implements ApplicationRunner {

    private final PgVectorStore financialKnowledgeVectorStore;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBaseSeeder(
            @Qualifier("financialKnowledgeVectorStore") PgVectorStore financialKnowledgeVectorStore,
            JdbcTemplate jdbcTemplate) {
        this.financialKnowledgeVectorStore = financialKnowledgeVectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM financial_knowledge_base", Long.class);
            if (count != null && count > 0) {
                log.info("financial_knowledge_base already has {} rows — skipping seed", count);
                return;
            }
            log.info("Seeding financial_knowledge_base...");
            financialKnowledgeVectorStore.add(buildDocuments());
            log.info("financial_knowledge_base seeded successfully");
        } catch (Exception e) {
            log.warn("Could not seed financial_knowledge_base (OpenAI key missing?): {}", e.getMessage());
        }
    }

    private List<Document> buildDocuments() {
        return List.of(
            doc("KB-ACC-001", "Cuenta de Cheques Nexus",
                "La cuenta de cheques Nexus es tu cuenta principal para el día a día. " +
                "Sin comisión de mantenimiento con saldo mínimo de MXN 0. Incluye: " +
                "tarjeta de débito sin costo, transferencias SPEI ilimitadas, " +
                "acceso a CoDi, estado de cuenta digital. Límite diario de transferencia: " +
                "MXN 50,000. Sin comisión por retiros en cajeros Nexus."),

            doc("KB-ACC-002", "Cuenta de Ahorro Nexus",
                "La cuenta de ahorro Nexus genera intereses sobre tu saldo diario. " +
                "Tasa de interés anual: 3.5% (sujeta a cambios, consulta la app). " +
                "Los intereses se calculan diariamente y se acreditan cada mes. " +
                "Sin comisión de mantenimiento. Límite diario: MXN 10,000. " +
                "Ideal para tu fondo de emergencia o metas de ahorro a corto plazo."),

            doc("KB-ACC-003", "Comisiones y Tarifas",
                "Nexus tiene una política de comisiones transparentes: " +
                "Transferencias SPEI: GRATIS (ilimitadas). " +
                "Retiro en cajero Nexus: GRATIS. " +
                "Retiro en cajero de otro banco en México: MXN 15 por retiro. " +
                "Retiro en cajero internacional: MXN 65 + tipo de cambio. " +
                "Reposición de tarjeta por extravío: MXN 120. " +
                "No hay comisión por saldo mínimo, ni por consulta de saldo."),

            doc("KB-TRF-001", "¿Cómo hacer una transferencia SPEI?",
                "Para transferir dinero por SPEI desde Nexus: " +
                "1. Ve a 'Transferencias' en la app o banca en línea. " +
                "2. Selecciona 'Nuevo destinatario' o elige uno guardado. " +
                "3. Ingresa el CLABE de 18 dígitos del destinatario. " +
                "4. Indica el monto y concepto (opcional). " +
                "5. Confirma con tu PIN o huella digital. " +
                "Las transferencias SPEI son inmediatas y disponibles 24/7. " +
                "Límite: MXN 50,000 por día en cuenta de cheques."),

            doc("KB-TRF-002", "Pagos con CoDi",
                "CoDi es el sistema de pagos digitales del Banco de México. " +
                "Para pagar con CoDi en Nexus: " +
                "1. Activa CoDi en la app (solo necesitas hacerlo una vez). " +
                "2. En el comercio, escanea su código QR con la app Nexus. " +
                "3. Confirma el monto y autoriza. " +
                "También puedes cobrar: genera tu QR en la app para que te paguen. " +
                "CoDi es gratuito y funciona sin internet en algunos dispositivos."),

            doc("KB-SEC-001", "Seguridad de tu cuenta — qué hacer si sospechas fraude",
                "Si sospechas que tu cuenta fue comprometida: " +
                "1. Llama inmediatamente al 800-NEXUS-01 (disponible 24/7). " +
                "2. O bloquea tu tarjeta desde la app: Configuración → Tarjeta → Bloquear. " +
                "3. Cambia tu contraseña desde un dispositivo de confianza. " +
                "Nexus NUNCA te pedirá tu NIP, contraseña o código OTP por teléfono, " +
                "mensaje o correo. Si alguien te los pide, es un fraude."),

            doc("KB-SEC-002", "¿Cómo disputar un cargo no reconocido?",
                "Si ves un cargo que no reconoces: " +
                "1. Dentro de la app ve a la transacción y selecciona 'Reportar problema'. " +
                "2. O llama al 800-NEXUS-01 dentro de los 90 días del cargo. " +
                "Nexus investigará en un máximo de 15 días hábiles. " +
                "Durante la investigación, el monto disputado puede quedar 'en suspenso'. " +
                "Si el cargo es fraudulento, Nexus devuelve el dinero completo sin costo."),

            doc("KB-CARD-001", "Tarjeta perdida o robada",
                "Si perdiste tu tarjeta Nexus o fue robada: " +
                "INMEDIATAMENTE: Bloquea la tarjeta en la app → Configuración → Mi Tarjeta → Bloquear. " +
                "O llama al 800-NEXUS-01 y la bloqueamos en segundos. " +
                "Solicita una tarjeta de reposición en la app (costo: MXN 120, llega en 5-7 días). " +
                "Tu dinero está protegido — los cargos fraudulentos después del reporte " +
                "son responsabilidad de Nexus, no tuya."),

            doc("KB-INT-001", "Transacciones internacionales",
                "Con tu tarjeta Nexus puedes comprar en el extranjero y en tiendas en línea internacionales. " +
                "El tipo de cambio usado es el interbancario del día más una comisión del 3%. " +
                "Límite de compras internacionales: MXN 25,000 diarios. " +
                "Retiro en cajero internacional: MXN 65 + tipo de cambio. " +
                "Activa 'Uso internacional' en la app antes de viajar " +
                "(Configuración → Mi Tarjeta → Uso Internacional)."),

            doc("KB-SAV-001", "Cómo crear una meta de ahorro",
                "En Nexus puedes crear metas de ahorro con nombre y fecha objetivo. " +
                "En la app: ve a 'Ahorro' → 'Nueva Meta'. " +
                "Puedes configurar transferencias automáticas semanales o mensuales " +
                "desde tu cuenta de cheques a tu meta de ahorro. " +
                "Ejemplo: meta 'Viaje a Oaxaca' de MXN 15,000 en 6 meses → " +
                "Nexus te sugiere apartar MXN 2,500 mensualmente. " +
                "El dinero en metas sigue generando el mismo interés del 3.5% anual."),

            doc("KB-SPEI-001", "Límites SPEI y horarios",
                "Transferencias SPEI desde Nexus: " +
                "Horario de alta disponibilidad: 6:00 am a 11:00 pm → límite MXN 50,000. " +
                "Horario nocturno (11:00 pm a 6:00 am) → límite reducido MXN 10,000. " +
                "Límite mensual: MXN 1,000,000 en cuenta de cheques. " +
                "Para aumentar tu límite: solicítalo en la app con verificación adicional " +
                "(puede tomar hasta 24 horas hábiles la aprobación)."),

            doc("KB-OPEN-001", "Apertura de cuenta — requisitos",
                "Para abrir una cuenta Nexus necesitas: " +
                "INE o pasaporte vigente (identificación oficial). " +
                "CURP. Comprobante de domicilio reciente (no mayor a 3 meses). " +
                "Selfie en vivo para verificación biométrica. " +
                "Ser mayor de 18 años. " +
                "El proceso es 100% digital y toma menos de 10 minutos. " +
                "La cuenta se activa inmediatamente tras la verificación exitosa."),

            doc("KB-KYC-001", "Verificación de identidad (KYC)",
                "Nexus realiza verificación de identidad (Know Your Customer) para cumplir " +
                "con regulaciones CNBV y LFPIORPI. Durante el proceso: " +
                "1. Captura foto frontal y trasera de tu INE. " +
                "2. Toma una selfie para comparación biométrica. " +
                "3. El sistema verifica automáticamente en segundos. " +
                "Si la verificación falla, un agente humano revisará en máximo 48 horas. " +
                "Tu información está protegida y nunca se comparte con terceros."),

            doc("KB-SCHED-001", "Pagos programados y domiciliación",
                "Puedes programar pagos automáticos desde tu cuenta Nexus: " +
                "En la app: ve a 'Transferencias' → 'Programar'. " +
                "Configura: destinatario (CLABE), monto, frecuencia (semanal/mensual/única vez), fecha. " +
                "Domiciliación de servicios: también puedes autorizar a empresas (CFE, Telmex, etc.) " +
                "a cobrar directamente. Se muestra en tu app como 'Cargo domiciliado'. " +
                "Puedes cancelar domiciliaciones en cualquier momento desde la app."),

            doc("KB-PASS-001", "Recuperación de contraseña y PIN",
                "Si olvidaste tu contraseña de la app Nexus: " +
                "1. En la pantalla de login toca '¿Olvidaste tu contraseña?'. " +
                "2. Ingresa tu número de celular registrado. " +
                "3. Recibirás un código OTP por SMS o llamada. " +
                "4. Crea una nueva contraseña (mínimo 8 caracteres, mayúsculas, número y símbolo). " +
                "Si olvidaste tu NIP de tarjeta: llama al 800-NEXUS-01 o ve a un cajero " +
                "Nexus para resetearlo con tu huella digital.")
        );
    }

    private Document doc(String id, String title, String content) {
        return Document.builder()
                .id(id)
                .text(title + "\n\n" + content)
                .metadata("title", title)
                .metadata("language", "es")
                .metadata("category", "product_knowledge")
                .build();
    }
}
