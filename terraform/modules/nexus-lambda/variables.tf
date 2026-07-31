# nexus-lambda module — variables
# See CHANGES-BESTPRACTICES/05_TERRAFORM_AWS_CHANGES.md Section 3 for the
# audit this was designed from (the pattern already repeated 8 times by
# hand across lambda-*.tf before this module existed).

variable "function_name" {
  description = "Nombre completo, ej. nexus-josue-fraud-alert-lambda"
  type        = string
}

variable "handler" {
  description = "Java handler class::method, ej. com.nexus.fraud.lambda.FraudAlertHandler::handleRequest"
  type        = string
}

variable "runtime" {
  type    = string
  default = "java21"
}

variable "jar_path" {
  description = "Ruta al jar ya compilado por Maven. El módulo NO compila nada."
  type        = string
}

variable "source_hash" {
  description = "content_base64sha256 del jar - fuerza redeploy cuando el artefacto cambia"
  type        = string
}

variable "environment_variables" {
  type    = map(string)
  default = {}
}

variable "memory_size" {
  type    = number
  default = 512
}

variable "timeout" {
  type    = number
  default = 30
}

variable "reserved_concurrent_executions" {
  description = "null = sin reservar. Cuenta atómica: -1 en la API de AWS, pero el provider usa null."
  type        = number
  default     = null
}

variable "enable_snap_start" {
  type    = bool
  default = false
}

variable "enable_xray" {
  type    = bool
  default = true
}

variable "iam_policy_statements" {
  description = "Statements adicionales para la policy inline del rol - CADA Lambda define los suyos (mínimo privilegio real, no un rol genérico compartido)"
  type = list(object({
    sid       = string
    actions   = list(string)
    resources = list(string)
  }))
  default = []
}

# ── Trigger (uno de los 4 - null los que no apliquen) ──────────────────
variable "sqs_trigger" {
  type = object({
    queue_arn                          = string
    batch_size                         = optional(number, 5)
    maximum_batching_window_in_seconds = optional(number, 2)
  })
  default = null
}

variable "eventbridge_trigger" {
  type = object({
    schedule_expression = string # ej. "rate(5 minutes)"
  })
  default = null
}

variable "s3_trigger" {
  type = object({
    bucket_id     = string
    bucket_arn    = string
    events        = optional(list(string), ["s3:ObjectCreated:*"])
    filter_prefix = optional(string, "")
  })
  default = null
}

variable "sns_trigger" {
  type = object({
    topic_arn = string
  })
  default = null
}

variable "tags" {
  type    = map(string)
  default = {}
}
