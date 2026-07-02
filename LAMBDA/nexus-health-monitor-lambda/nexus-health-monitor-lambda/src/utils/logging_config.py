"""
Structured JSON logging for Lambda CloudWatch Logs.

Uses python-json-logger for structured output that CloudWatch
Logs Insights can query natively:
  fields @timestamp, service, status, consecutive_failures
  | filter level = "WARNING"
  | sort @timestamp desc
"""

import logging
import os
import sys

from pythonjsonlogger import jsonlogger


class _StructuredLogger:
    """
    Wrapper that adds key=value kwargs as structured fields.
    Usage: log.info("message", service="name", status="DOWN")
    """

    def __init__(self, logger: logging.Logger):
        self._logger = logger

    def debug(self, msg: str, **kwargs):
        self._logger.debug(msg, extra=kwargs)

    def info(self, msg: str, **kwargs):
        self._logger.info(msg, extra=kwargs)

    def warning(self, msg: str, **kwargs):
        self._logger.warning(msg, extra=kwargs)

    def error(self, msg: str, **kwargs):
        self._logger.error(msg, extra=kwargs)

    def critical(self, msg: str, **kwargs):
        self._logger.critical(msg, extra=kwargs)


def _setup() -> _StructuredLogger:
    logger = logging.getLogger("nexus-health-monitor")
    if logger.handlers:
        return _StructuredLogger(logger)

    level = os.environ.get("LOG_LEVEL", "INFO").upper()
    logger.setLevel(getattr(logging, level, logging.INFO))

    handler = logging.StreamHandler(sys.stdout)
    formatter = jsonlogger.JsonFormatter(
        fmt="%(asctime)s %(levelname)s %(name)s %(message)s",
        rename_fields={"asctime": "timestamp", "levelname": "level"},
    )
    handler.setFormatter(formatter)
    logger.addHandler(handler)
    logger.propagate = False

    return _StructuredLogger(logger)


log = _setup()
