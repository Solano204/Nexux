# src/service/utils/logging_config.py
"""
Logging configuration for the service module.

Re-exports the structured logger from the main utils.logging_config
with service-module-specific context. Provides a convenience import
so service module files can:

    from service.utils.logging_config import service_log

The service_log instance automatically tags log entries with
component="service-dashboard" for CloudWatch Logs Insights filtering.
"""

import logging
import os
import sys

from pythonjsonlogger import jsonlogger


def _setup_service_logger() -> logging.Logger:
    """Create a logger scoped to the service module."""
    logger = logging.getLogger("nexus-health-monitor.service")
    if logger.handlers:
        return logger

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

    return logger


class _ServiceLogger:
    """Structured logger with service module context."""

    def __init__(self, logger: logging.Logger):
        self._logger = logger

    def debug(self, msg: str, **kwargs):
        kwargs.setdefault("component", "service-dashboard")
        self._logger.debug(msg, extra=kwargs)

    def info(self, msg: str, **kwargs):
        kwargs.setdefault("component", "service-dashboard")
        self._logger.info(msg, extra=kwargs)

    def warning(self, msg: str, **kwargs):
        kwargs.setdefault("component", "service-dashboard")
        self._logger.warning(msg, extra=kwargs)

    def error(self, msg: str, **kwargs):
        kwargs.setdefault("component", "service-dashboard")
        self._logger.error(msg, extra=kwargs)


service_log = _ServiceLogger(_setup_service_logger())
