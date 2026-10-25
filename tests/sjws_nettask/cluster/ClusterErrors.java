package sjws_nettask.cluster;

/** Test access to the package-private consensus refusals, for callers' classification tests (#254). */
public final class ClusterErrors {

    private ClusterErrors() {
    }

    public static ApplyLagException applyLag(GroupId g) {
        return new ApplyLagException(g, 10, 5);
    }

    public static DurabilityUnavailableException durabilityUnavailable(GroupId g) {
        return new DurabilityUnavailableException(g, 1, 2);
    }

    public static TermChangedException termChanged(GroupId g) {
        return new TermChangedException(g, 1, 2);
    }

    public static GroupFencedException fenced(GroupId g) {
        return new GroupFencedException(g, new java.io.IOException("injected"));
    }

    public static BatchRolledBackException rolledBack(GroupId g) {
        return new BatchRolledBackException(g, 7, new java.io.UncheckedIOException(new java.io.IOException("injected")));
    }
}
