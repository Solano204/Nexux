# src/utils/logging_config.py
import logging
import os
from pythonjsonlogger import jsonlogger


def configure_logging() -> logging.Logger:
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
    return logger


log = configure_logging()