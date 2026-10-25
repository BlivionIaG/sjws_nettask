package sjws_nettask.msgrepl;

import sjws_nettask.messaging.ledger.Replica;

/** Test access to {@link RaftReplica#map} for appends, the one cluster-to-messaging error boundary (#254). */
public final class ReplicaErrors {

    private ReplicaErrors() {
    }

    public static Throwable map(Replica replica, Throwable consensusError) {
        return ((RaftReplica) replica).map(consensusError, true);
    }
}
