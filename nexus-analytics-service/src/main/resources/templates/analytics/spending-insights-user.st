Analyze this financial data and generate 2-3 insights.

=== ONE-SHOT EXAMPLE ===

Example Input:
{
  "period": "March 2025",
  "totalSpending": 28450.00,
  "previousSpending": 21200.00,
  "currency": "MXN",
  "categories": {
    "food_dining": {"amount": 8200, "count": 47, "previous": 5100,
      "topMerchants": [
        {"name": "La Taquería del Centro", "amount": 1800, "count": 12},
        {"name": "Starbucks Reforma", "amount": 950, "count": 15}
      ]},
    "transportation": {"amount": 3400, "count": 89, "previous": 3200}
  },
  "income": 35000,
  "savingsRate": 0.18
}

Example Output:
{"insights": [
  {
    "category": "CATEGORY_SPOTLIGHT",
    "title": "Tu gasto en comida subió 61% este mes",
    "narrative": "Gastaste MXN 8,200 en restaurantes en marzo — MXN 3,100 más que en febrero. La mayoría del aumento vino de La Taquería del Centro (12 visitas por MXN 1,800) y Starbucks Reforma (15 visitas por MXN 950), que juntos representan el 34% de tu gasto en alimentos.",
    "dataPoints": [
      "MXN 8,200 total en alimentos (+61% vs febrero)",
      "47 transacciones, promedio MXN 174",
      "La Taquería del Centro: 12 visitas = MXN 1,800",
      "Starbucks Reforma: 15 visitas = MXN 950"
    ],
    "recommendation": "Reducir a 2 visitas semanales a La Taquería (en lugar de 3) ahorraría ~MXN 600 al mes sin grandes sacrificios.",
    "relevanceScore": 0.92,
    "severity": "WARNING"
  }
]}

=== END EXAMPLE ===

NOW ANALYZE THIS ACTUAL DATA:

Period: {period}
Total Spending: {totalSpending} {currency}
Previous Period Spending: {previousSpending} {currency}
Change: {spendingChangePercent}%

Spending by Category:
{categoryBreakdown}

Income: {income} {currency}
Savings Rate: {savingsRate}%
Previous Savings Rate: {previousSavingsRate}%

Top Merchants:
{topMerchants}

Anomalies Detected:
{anomalies}

Language: {language}

Generate 2-3 insights. Focus on what changed the MOST and what is ACTIONABLE.