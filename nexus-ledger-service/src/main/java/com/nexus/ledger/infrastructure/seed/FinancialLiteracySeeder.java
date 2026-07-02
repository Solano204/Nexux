package com.nexus.ledger.infrastructure.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinancialLiteracySeeder implements ApplicationRunner {

    private final VectorStore financialLiteracyVectorStore;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM financial_literacy_embeddings", Long.class);
            if (count != null && count > 0) {
                log.info("financial_literacy_embeddings already has {} rows — skipping seed", count);
                return;
            }
            log.info("Seeding financial_literacy_embeddings...");
            financialLiteracyVectorStore.add(buildDocuments());
            log.info("financial_literacy_embeddings seeded successfully");
        } catch (Exception e) {
            log.warn("Could not seed financial_literacy_embeddings (OpenAI key missing?): {}", e.getMessage());
        }
    }

    private List<Document> buildDocuments() {
        return List.of(
            doc("FIN-LIT-001", "¿Qué es un débito en tu cuenta?",
                "Un débito es cuando sale dinero de tu cuenta. Por ejemplo, cuando compras " +
                "en una tienda, pagas una factura o haces una transferencia, eso aparece como " +
                "un débito. Tu saldo disminuye. En el estado de cuenta verás 'DEBITO' o 'CARGO'. " +
                "En inglés se dice 'debit' pero significa lo mismo: dinero que salió."),

            doc("FIN-LIT-002", "¿Qué es un crédito o abono en tu cuenta?",
                "Un crédito (o abono) es cuando entra dinero a tu cuenta. Por ejemplo, cuando " +
                "recibes tu salario, una transferencia de otra persona, o una devolución de compra. " +
                "Tu saldo aumenta. En el estado de cuenta verás 'CREDITO' o 'ABONO'. " +
                "No confundir con tarjeta de crédito — son conceptos distintos."),

            doc("FIN-LIT-003", "¿Cómo funciona una transferencia SPEI?",
                "SPEI (Sistema de Pagos Electrónicos Intergancarios) es el sistema del Banco de " +
                "México para transferir dinero entre diferentes bancos. Una transferencia SPEI " +
                "normalmente tarda segundos en procesarse, disponible las 24 horas del día, " +
                "365 días del año. Necesitas el número CLABE (18 dígitos) del destinatario. " +
                "Las transferencias SPEI dentro del mismo banco son casi instantáneas."),

            doc("FIN-LIT-004", "¿Qué es el CLABE?",
                "El CLABE (Clave Bancaria Estandarizada) es un número de 18 dígitos que identifica " +
                "de forma única tu cuenta en el sistema financiero mexicano. Los primeros 3 dígitos " +
                "son el código del banco, los siguientes 3 son el código de ciudad, luego 11 dígitos " +
                "de tu número de cuenta, y el último dígito es de verificación. Necesitas tu CLABE " +
                "para recibir transferencias SPEI de otros bancos."),

            doc("FIN-LIT-005", "¿Qué es CoDi?",
                "CoDi (Cobro Digital) es un sistema del Banco de México para hacer pagos en " +
                "comercios usando tu celular mediante códigos QR. Vinculas tu cuenta Nexus a " +
                "CoDi y puedes pagar escaneando el código QR del comercio o mostrando tu QR " +
                "para que te cobren. Es gratuito y disponible en cualquier cuenta con CLABE."),

            doc("FIN-LIT-006", "¿Qué significa 'saldo reservado' o 'fondos en tránsito'?",
                "Cuando inicias una transferencia, tu banco 'reserva' el dinero antes de enviarlo. " +
                "Durante ese tiempo el dinero ya no está disponible pero tampoco ha salido definitivamente. " +
                "Si la transferencia se cancela (por error o rechazo), el dinero reservado regresa " +
                "a tu saldo disponible. Esto puede tardar minutos u horas dependiendo del banco. " +
                "En Nexus esto se muestra como 'Fondos en tránsito' o 'Saldo reservado'."),

            doc("FIN-LIT-007", "¿Qué es una reversión o devolución de cargo?",
                "Una reversión ocurre cuando una transacción se cancela y el dinero regresa a tu cuenta. " +
                "Puede pasar porque: el comercio no pudo procesar el pago, hubo un error en la " +
                "transferencia, o presentaste una disputa exitosa. La reversión aparece como un " +
                "ABONO en tu estado de cuenta. Puede tardar 1-10 días hábiles dependiendo del tipo."),

            doc("FIN-LIT-008", "¿Cómo se calcula el interés en una cuenta de ahorro?",
                "El interés de tu cuenta de ahorro Nexus se calcula diariamente sobre tu saldo " +
                "promedio y se acredita mensualmente. Por ejemplo, con una tasa anual del 3.5%: " +
                "si tienes MXN 10,000 durante todo el mes, ganarías aproximadamente MXN 28.77 " +
                "ese mes (10,000 × 0.035 ÷ 12). La tasa anual se divide entre 12 para el cálculo mensual."),

            doc("FIN-LIT-009", "Transacciones pendientes vs. transacciones publicadas",
                "Una transacción pendiente ya fue autorizada pero aún no se ha procesado " +
                "completamente. El dinero está reservado pero no ha salido oficialmente. " +
                "Una transacción publicada (o liquidada) ya se procesó completamente y " +
                "es definitiva. Las compras con tarjeta suelen aparecer primero como pendientes " +
                "y se publican en 1-3 días hábiles. Las transferencias SPEI generalmente " +
                "pasan directo a publicadas."),

            doc("FIN-LIT-010", "¿Cómo leer tu estado de cuenta?",
                "Tu estado de cuenta muestra: fecha de la transacción, descripción del comercio " +
                "o beneficiario, el tipo (cargo/abono), el monto, y tu saldo después de la operación. " +
                "Las fechas pueden variar: 'fecha de operación' es cuando ocurrió, " +
                "'fecha de valor' es cuando afectó oficialmente tu saldo. " +
                "Las comisiones aparecen como 'COMISION' o 'CARGO POR SERVICIO'."),

            doc("FIN-LIT-011", "¿Qué es la contabilidad de doble entrada (ledger)?",
                "Cada transacción bancaria tiene dos lados: un cargo en una cuenta y un abono en otra. " +
                "Cuando transfières MXN 500 a otra persona, tu cuenta tiene un DEBITO de MXN 500 " +
                "y la cuenta del destinatario tiene un CREDITO de MXN 500. Esto garantiza que " +
                "el dinero total del sistema siempre cuadre. Es por eso que ves dos entradas " +
                "en transferencias: una salida y una confirmación de llegada."),

            doc("FIN-LIT-012", "Límites de transacciones en Nexus",
                "Tu cuenta Nexus tiene límites diarios y mensuales para protegerte. " +
                "Cuenta de cheques: límite diario de MXN 50,000, mensual de MXN 1,000,000. " +
                "Cuenta de ahorro: límite diario de MXN 10,000, mensual de MXN 200,000. " +
                "Transferencias SPEI nocturnas (11pm-6am): límite reducido de MXN 10,000. " +
                "Puedes solicitar aumentar tus límites en la app con verificación adicional.")
        );
    }

    private Document doc(String id, String title, String content) {
        return Document.builder()
                .id(id)
                .text(title + "\n\n" + content)
                .metadata("title", title)
                .metadata("language", "es")
                .metadata("category", "financial_literacy")
                .build();
    }
}
