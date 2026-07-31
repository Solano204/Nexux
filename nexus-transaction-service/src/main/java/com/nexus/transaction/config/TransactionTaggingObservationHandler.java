package com.nexus.transaction.config;

import com.nexus.transaction.domain.model.Transaction;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

/**
 * Tags transactionType/accountId/status consistently on any Observation
 * whose Context carries a Transaction - avoids repeating
 * .lowCardinalityKeyValue(...) at every call site that opens a
 * "transaction.*" span by hand. Same shape as the platform-wide
 * ErrorTaggingObservationHandler (nexus-tracing-common) - an
 * ObservationHandler bean, not an ObservationConvention, to stay
 * consistent with that existing registration pattern. See
 * CHANGES-BESTPRACTICES/03_ZIPKIN_TRACING_CHANGES.md Section 7 for the
 * platform-wide tag catalog this implements (only accountId/transactionType/
 * status - no PII, no unbounded-cardinality fields like transactionId or
 * timestamps).
 *
 * status is read in onStop, not onStart: the transaction's status can
 * still change between when the span opens and when it closes (e.g.
 * PENDING -> COMPLETED), and onStop reflects the final state.
 */
public class TransactionTaggingObservationHandler
        implements ObservationHandler<TransactionTaggingObservationHandler.Context> {

    public static class Context extends Observation.Context {
        private final Transaction transaction;
        public Context(Transaction transaction) { this.transaction = transaction; }
        public Transaction transaction() { return transaction; }
    }

    @Override
    public void onStart(Context context) {
        Transaction txn = context.transaction();
        context.addLowCardinalityKeyValue(
                KeyValue.of("transactionType", txn.getTransactionType().name()));
        context.addLowCardinalityKeyValue(
                KeyValue.of("accountId", txn.getSourceAccountId().toString()));
    }

    @Override
    public void onStop(Context context) {
        Transaction txn = context.transaction();
        context.addLowCardinalityKeyValue(
                KeyValue.of("status", txn.getStatus().name()));
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof Context;
    }
}
