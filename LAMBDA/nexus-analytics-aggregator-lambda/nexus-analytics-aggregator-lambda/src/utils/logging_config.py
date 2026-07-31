# src/utils/logging_config.py
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
    level = getattr(logging,
                    os.environ.get("LOG_LEVEL", "INFO").upper(),
                    logging.INFO)
    logger = logging.getLogger("nexus.analytics.aggregator")
    logger.setLevel(level)
    if not logger.handlers:
        handler = logging.StreamHandler()
        formatter = jsonlogger.JsonFormatter(
            fmt="%(asctime)s %(levelname)s %(name)s %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S",
            json_ensure_ascii=False,
        )
        handler.setFormatter(formatter)
        logger.addHandler(handler)
    logging.getLogger("botocore").setLevel(logging.WARNING)
    logging.getLogger("boto3").setLevel(logging.WARNING)
    return StructuredLogger(logger)


log = configure_logging()