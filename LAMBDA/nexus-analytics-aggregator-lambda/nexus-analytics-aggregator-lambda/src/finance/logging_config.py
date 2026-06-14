# src/finance/logging_config.py
"""
Financial analytics logging configuration.

Re-exports the base structured logger from utils and adds
finance-specific context fields (currency, amount formatting).
Used by aggregator modules that need financial context in logs.
"""
import logging
import os
from pythonjsonlogger import jsonlogger


def get_finance_logger(module_name: str = "nexus.analytics.finance") -> logging.Logger:
    """
    Get a finance-specific structured logger.
    
    Adds default extra fields:
    - component: 'finance-aggregator'
    - service: 'nexus-analytics-aggregator-lambda'
    """
    level = getattr(logging,
                    os.environ.get("LOG_LEVEL", "INFO").upper(),
                    logging.INFO)
    
    logger = logging.getLogger(module_name)
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
    
    # Adapter that adds default fields
    return logging.LoggerAdapter(logger, {
        "component": "finance-aggregator",
        "service": "nexus-analytics-aggregator-lambda",
    })


finance_log = get_finance_logger()
