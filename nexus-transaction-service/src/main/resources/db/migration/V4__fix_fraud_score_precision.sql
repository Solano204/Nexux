-- fraud_score precision 5,4 only allows up to 9.9999; scores are 0-100 integers.
ALTER TABLE transactions
    ALTER COLUMN fraud_score TYPE NUMERIC(5, 2);
