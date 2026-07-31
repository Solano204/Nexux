# src/utils/logging_config.py
"""
Structured JSON logging for CloudWatch Logs Insights.

CloudWatch Logs Insights can query structured JSON logs:
  fields @timestamp, userId, verificationId, processingStatus
  | filter processingStatus = "QUALITY_REJECTED"
  | stats count() by failureReason

All Lambda log lines are prefixed automatically with:
  [level] [requestId] [functionName]

We add: processingId, userId, verificationId, duration context.
"""

import logging
import os
from pythonjsonlogger import jsonlogger


class StructuredLogger:
    """Wraps a stdlib Logger so call sites can pass structured fields as
    kwargs (e.g. log.info("msg", user_id=x)) instead of via extra=."""

    def __init__(self, logger: logging.Logger):
        self._logger = logger

    def debug(self, msg, **kwargs):
        self._logger.debug(msg, extra=kwargs)

    def info(self, msg, **kwargs):
        self._logger.info(msg, extra=kwargs)

    def warning(self, msg, **kwargs):
        self._logger.warning(msg, extra=kwargs)

    def error(self, msg, **kwargs):
        self._logger.error(msg, extra=kwargs)

    def critical(self, msg, **kwargs):
        self._logger.critical(msg, extra=kwargs)


def configure_logging() -> StructuredLogger:
    log_level = os.environ.get("LOG_LEVEL", "INFO").upper()

    logger = logging.getLogger("nexus.kyc.rekognition")
    logger.setLevel(getattr(logging, log_level, logging.INFO))

    if not logger.handlers:
        handler = logging.StreamHandler()
        formatter = jsonlogger.JsonFormatter(
            fmt="%(asctime)s %(levelname)s %(name)s %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S",
            json_ensure_ascii=False,
        )
        handler.setFormatter(formatter)
        logger.addHandler(handler)

    # Quieten noisy AWS SDK loggers
    logging.getLogger("botocore").setLevel(logging.WARNING)
    logging.getLogger("boto3").setLevel(logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)

    return StructuredLogger(logger)


log = configure_logging()