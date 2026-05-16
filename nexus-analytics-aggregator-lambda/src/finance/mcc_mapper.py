# src/utils/mcc_mapper.py
"""
ISO 18245 Merchant Category Code → human-readable category name.
Used to populate categoryName field in analytics tables.
"""

_MCC_MAP: dict[str, str] = {
    "5411": "Supermercados",
    "5412": "Tiendas de Abarrotes",
    "5812": "Restaurantes",
    "5814": "Cafeterías",
    "5541": "Gasolineras",
    "4111": "Transporte Público",
    "4121": "Taxis y Rideshare",
    "5999": "Compras",
    "5311": "Tiendas Departamentales",
    "7011": "Hoteles",
    "4511": "Aerolíneas",
    "5912": "Farmacias",
    "8099": "Salud",
    "6011": "Cajero ATM",
    "6012": "Servicios Bancarios",
    "4814": "Telefonía e Internet",
    "4900": "Servicios Públicos",
    "7941": "Entretenimiento",
    "5815": "Streaming",
    "5942": "Librerías",
    "5732": "Electrónica",
    "7523": "Estacionamiento",
    "8011": "Médicos",
    "8049": "Dentistas",
    "5621": "Ropa",
    "5945": "Jugueterías",
}


def mcc_to_category_name(mcc: str | None) -> str:
    if not mcc:
        return "Otros"
    return _MCC_MAP.get(str(mcc).strip(), "Otros")