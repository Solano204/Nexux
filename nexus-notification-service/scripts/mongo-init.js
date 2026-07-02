// scripts/mongo-init.js
// Runs automatically when MongoDB starts for the first time.
// Creates nexus_notification DB with all required collections and indexes.

db = db.getSiblingDB('nexus_notification');

// ── notifications collection ────────────────────────────────────────
db.createCollection('notifications');

db.notifications.createIndex(
    { userId: 1, createdAt: -1 },
    { name: "idx_notifications_user_date" }
);
db.notifications.createIndex(
    { userId: 1, read: 1 },
    { name: "idx_notifications_user_unread" }
);
db.notifications.createIndex(
    { eventType: 1 },
    { name: "idx_notifications_event_type" }
);
db.notifications.createIndex(
    { createdAt: 1 },
    { expireAfterSeconds: 86400 * 90, name: "idx_notifications_ttl" }  // 90-day TTL
);

// ── user_notification_preferences collection ────────────────────────
db.createCollection('user_notification_preferences');

db.user_notification_preferences.createIndex(
    { userId: 1 },
    { unique: true, name: "idx_preferences_user_id" }
);

// ── notification_templates collection ───────────────────────────────
// Fallback templates when OpenAI is unavailable
db.createCollection('notification_templates');

db.notification_templates.createIndex(
    { eventType: 1, language: 1 },
    { unique: true, name: "idx_templates_event_lang" }
);

// Insert default fallback templates (Spanish + English)
db.notification_templates.insertMany([
    {
        eventType: "TRANSACTION_COMPLETED",
        language: "es",
        title: "Transacción completada",
        shortBody: "Tu transacción de {amount} {currency} fue procesada exitosamente.",
        longBody: "Tu transacción de {amount} {currency} fue procesada exitosamente el {date}.",
        tone: "POSITIVE",
        createdAt: new Date()
    },
    {
        eventType: "TRANSACTION_COMPLETED",
        language: "en",
        title: "Transaction completed",
        shortBody: "Your {amount} {currency} transaction was processed successfully.",
        longBody: "Your {amount} {currency} transaction was successfully processed on {date}.",
        tone: "POSITIVE",
        createdAt: new Date()
    },
    {
        eventType: "FRAUD_FLAGGED",
        language: "es",
        title: "Actividad inusual detectada",
        shortBody: "Detectamos actividad inusual en tu cuenta. Revisa tus transacciones.",
        longBody: "Hemos detectado actividad inusual en tu cuenta. Por tu seguridad, revisa tus transacciones recientes y contacta a soporte si no reconoces alguna.",
        tone: "URGENT",
        createdAt: new Date()
    },
    {
        eventType: "IDENTITY_VERIFIED",
        language: "es",
        title: "¡Identidad verificada!",
        shortBody: "Tu identidad ha sido verificada. Ya puedes usar todos los servicios de Nexus.",
        longBody: "¡Felicidades! Tu proceso de verificación de identidad fue exitoso. Ya tienes acceso completo a todos los servicios de Nexus Bank.",
        tone: "CELEBRATORY",
        createdAt: new Date()
    }
]);

print('MongoDB nexus_notification database initialized');
print('Collections: notifications, user_notification_preferences, notification_templates');
