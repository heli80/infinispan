package org.infinispan.configuration.cache;

import org.infinispan.config.ConfigurationException;
import org.infinispan.transaction.LockingMode;
import org.infinispan.transaction.TransactionMode;
import org.infinispan.transaction.TransactionProtocol;
import org.infinispan.transaction.lookup.GenericTransactionManagerLookup;
import org.infinispan.transaction.lookup.TransactionManagerLookup;
import org.infinispan.transaction.lookup.TransactionSynchronizationRegistryLookup;
import org.infinispan.util.concurrent.IsolationLevel;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.concurrent.TimeUnit;

/**
 * Defines transactional (JTA) characteristics of the cache.
 *
 * @author pmuir
 *
 */
public class TransactionConfigurationBuilder extends AbstractConfigurationChildBuilder<TransactionConfiguration> {

    private static Log log = LogFactory.getLog(TransactionConfigurationBuilder.class);

    private boolean autoCommit = true;
    private long cacheStopTimeout = TimeUnit.SECONDS.toMillis(30);
    private boolean eagerLockingSingleNode = false;
    LockingMode lockingMode = LockingMode.OPTIMISTIC;
    private boolean syncCommitPhase = true;
    private boolean syncRollbackPhase = false;
    private TransactionManagerLookup transactionManagerLookup;
    private TransactionSynchronizationRegistryLookup transactionSynchronizationRegistryLookup;
    TransactionMode transactionMode = null;
    private boolean useEagerLocking = false;
    private boolean useSynchronization = false;
    private final RecoveryConfigurationBuilder recovery;
    private boolean use1PcForAutoCommitTransactions = false;

    //Pedro -- total order stuff
    private TransactionProtocol transactionProtocol = TransactionProtocol.NORMAL;
    private final TotalOrderThreadingConfigurationBuilder totalOrderThreading;

    TransactionConfigurationBuilder(ConfigurationBuilder builder) {
        super(builder);
        this.recovery = new RecoveryConfigurationBuilder(this);
        this.totalOrderThreading = new TotalOrderThreadingConfigurationBuilder(this);
    }

    /**
     * If the cache is transactional (i.e. {@link #isTransactionalCache()} == true) and
     * transactionAutoCommit is enabled then for single operation transactions the user doesn't need
     * to manually start a transaction, but a transactions is injected by the system. Defaults to
     * true.
     */
    public TransactionConfigurationBuilder autoCommit(boolean b) {
        this.autoCommit = b;
        return this;
    }

    /**
     * If there are any ongoing transactions when a cache is stopped, Infinispan waits for ongoing
     * remote and local transactions to finish. The amount of time to wait for is defined by the
     * cache stop timeout. It is recommended that this value does not exceed the transaction timeout
     * because even if a new transaction was started just before the cache was stopped, this could
     * only last as long as the transaction timeout allows it.
     * <p/>
     * This configuration property may be adjusted at runtime
     *
     * @deprecated use {@link #cacheStopTimeout(long)} instead
     */
    @Deprecated
    public TransactionConfigurationBuilder cacheStopTimeout(int i) {
        this.cacheStopTimeout = i;
        return this;
    }

    /**
     * If there are any ongoing transactions when a cache is stopped, Infinispan waits for ongoing
     * remote and local transactions to finish. The amount of time to wait for is defined by the
     * cache stop timeout. It is recommended that this value does not exceed the transaction timeout
     * because even if a new transaction was started just before the cache was stopped, this could
     * only last as long as the transaction timeout allows it.
     * <p/>
     * This configuration property may be adjusted at runtime
     */
    public TransactionConfigurationBuilder cacheStopTimeout(long l) {
        this.cacheStopTimeout = l;
        return this;
    }

    /**
     * Only has effect for DIST mode and when useEagerLocking is set to true. When this is enabled,
     * then only one node is locked in the cluster, disregarding numOwners config. On the opposite,
     * if this is false, then on all cache.lock() calls numOwners RPCs are being performed. The node
     * that gets locked is the main data owner, i.e. the node where data would reside if
     * numOwners==1. If the node where the lock resides crashes, then the transaction is marked for
     * rollback - data is in a consistent state, no fault tolerance.
     *
     * @deprecated starting with Infinispan 5.1 single node locking is used by default
     */
    @Deprecated
    public TransactionConfigurationBuilder eagerLockingSingleNode(boolean b) {
        this.eagerLockingSingleNode = b;
        return this;
    }

    /**
     * Configures whether the cache uses optimistic or pessimistic locking. If the cache is not
     * transactional then the locking mode is ignored.
     *
     * @see org.infinispan.config.Configuration#isTransactionalCache()
     */
    public TransactionConfigurationBuilder lockingMode(LockingMode lockingMode) {
        this.lockingMode = lockingMode;
        return this;
    }

    /**
     * If true, the cluster-wide commit phase in two-phase commit (2PC) transactions will be
     * synchronous, so Infinispan will wait for responses from all nodes to which the commit was
     * sent. Otherwise, the commit phase will be asynchronous. Keeping it as false improves
     * performance of 2PC transactions, since any remote failures are trapped during the prepare
     * phase anyway and appropriate rollbacks are issued.
     * <p/>
     * This configuration property may be adjusted at runtime
     */
    public TransactionConfigurationBuilder syncCommitPhase(boolean b) {
        this.syncCommitPhase = b;
        return this;
    }

    /**
     * If true, the cluster-wide rollback phase in two-phase commit (2PC) transactions will be
     * synchronous, so Infinispan will wait for responses from all nodes to which the rollback was
     * sent. Otherwise, the rollback phase will be asynchronous. Keeping it as false improves
     * performance of 2PC transactions.
     * <p />
     *
     * This configuration property may be adjusted at runtime.
     *
     * @param b
     * @return
     */
    public TransactionConfigurationBuilder syncRollbackPhase(boolean b) {
        this.syncRollbackPhase = b;
        return this;
    }

    /**
     * Configure Transaction manager lookup directly using an instance of TransactionManagerLookup.
     * Calling this method marks the cache as transactional.
     */
    public TransactionConfigurationBuilder transactionManagerLookup(TransactionManagerLookup tml) {
        this.transactionManagerLookup = tml;
        return this;
    }

    /**
     * Configure Transaction Synchronization Registry lookup directly using an instance of
     * TransactionManagerLookup. Calling this method marks the cache as transactional.
     */
    public TransactionConfigurationBuilder transactionSynchronizationRegistryLookup(
            TransactionSynchronizationRegistryLookup lookup) {
        this.transactionSynchronizationRegistryLookup = lookup;
        return this;
    }

    public TransactionConfigurationBuilder transactionMode(TransactionMode transactionMode) {
        this.transactionMode = transactionMode;
        return this;
    }

    /**
     * Only has effect for DIST mode and when useEagerLocking is set to true. When this is enabled,
     * then only one node is locked in the cluster, disregarding numOwners config. On the opposite,
     * if this is false, then on all cache.lock() calls numOwners RPCs are being performed. The node
     * that gets locked is the main data owner, i.e. the node where data would reside if
     * numOwners==1. If the node where the lock resides crashes, then the transaction is marked for
     * rollback - data is in a consistent state, no fault tolerance.
     * <p />
     * Note: Starting with infinispan 5.1 eager locking is replaced with pessimistic locking and can
     * be enforced by setting transaction's locking mode to PESSIMISTIC.
     *
     * @param b
     * @return
     */
    @Deprecated
    public TransactionConfigurationBuilder useEagerLocking(boolean b) {
        this.useEagerLocking = b;
        return this;
    }

    public TransactionConfigurationBuilder useSynchronization(boolean b) {
        this.useSynchronization = b;
        return this;
    }

    /**
     * This method allows configuration of the transaction recovery cache. When this method is
     * called, it automatically enables recovery. So, if you want it to be disabled, make sure you
     * call {@link org.infinispan.config.FluentConfiguration.RecoveryConfig#disable()}
     */
    public RecoveryConfigurationBuilder recovery() {
        recovery.enable();
        return recovery;
    }

    /**
     * This configuration option was added for the following situation:
     * - pre 5.1 code is using the cache
     */
    public TransactionConfigurationBuilder use1PcForAutoCommitTransactions(boolean b) {
        this.use1PcForAutoCommitTransactions = b;
        return this;
    }

    @Override
    void validate() {
        if (transactionManagerLookup == null) {
            if (!getBuilder().invocationBatching().enabled) {
                transactionManagerLookup = new GenericTransactionManagerLookup();
            } else {
                if (!useSynchronization) log.debug("Switching to Synchronization based enlistment.");
                useSynchronization = true;
            }
        }

//Pedro -- validate total order stuff
        if(transactionProtocol != TransactionProtocol.TOTAL_ORDER) {
            //no total order or not => no validation needed
            return;
        }

        //in the future we can allow this??
        if(transactionMode == TransactionMode.NON_TRANSACTIONAL) {
            throw new ConfigurationException("Non transactional cache can't be used with total order protocol");
        }

        boolean isRepeatableReadEnabled = locking().isolationLevel == IsolationLevel.REPEATABLE_READ;
        boolean isWriteSkewEnabled = locking().writeSkewCheck;

        //in the future it will be allowed with versioning...
        if(isRepeatableReadEnabled && isWriteSkewEnabled) {
            throw new ConfigurationException("Repeatable Read isolation level with write skew check not " +
                    "allowed with total order protocol");
        }

        //for now, only supports full replication
        if(!clustering().cacheMode().isReplicated()) {
            throw new ConfigurationException("the cache mode [" + clustering().cacheMode() + "] is not supported " +
                    "with total order protocol");
        }

        //eager locking no longer needed
        if(useEagerLocking) {
            throw new ConfigurationException("Eager locking not allowed in total order protocol");
        }
    }

    @Override
    TransactionConfiguration create() {
        if (useEagerLocking) {
            lockingMode = LockingMode.PESSIMISTIC;
        }
        if (transactionMode == null && getBuilder().invocationBatching().enabled)
            transactionMode = TransactionMode.TRANSACTIONAL;
        else if (transactionMode == null)
            transactionMode = TransactionMode.NON_TRANSACTIONAL;

        //Pedro -- added total order parameter
        return new TransactionConfiguration(autoCommit, cacheStopTimeout, eagerLockingSingleNode, lockingMode,
                syncCommitPhase, syncRollbackPhase, transactionManagerLookup, transactionSynchronizationRegistryLookup,
                transactionMode, useEagerLocking, useSynchronization, use1PcForAutoCommitTransactions,
                recovery.create(), transactionProtocol, totalOrderThreading.create());
    }

    @Override
    public TransactionConfigurationBuilder read(TransactionConfiguration template) {
        this.autoCommit = template.autoCommit();
        this.cacheStopTimeout = template.cacheStopTimeout();
        this.eagerLockingSingleNode = template.eagerLockingSingleNode();
        this.lockingMode = template.lockingMode();
        this.syncCommitPhase = template.syncCommitPhase();
        this.syncRollbackPhase = template.syncRollbackPhase();
        this.transactionManagerLookup = template.transactionManagerLookup();
        this.transactionMode = template.transactionMode();
        this.transactionSynchronizationRegistryLookup = template.transactionSynchronizationRegistryLookup();
        this.useEagerLocking = template.useEagerLocking();
        this.useSynchronization = template.useSynchronization();
        this.use1PcForAutoCommitTransactions = template.use1PcForAutoCommitTransactions();
        this.recovery.read(template.recovery());

        //Pedro -- total order stuff
        this.transactionProtocol = template.transactionProtocol();
        this.totalOrderThreading.read(template.totalOrderThreading());

        return this;
    }

    //Pedro -- total order stuff
    public TransactionProtocol transactionProtocol() {
        return transactionProtocol;
    }

    public TransactionConfigurationBuilder transactionProtocol(TransactionProtocol transactionProtocol) {
        this.transactionProtocol = transactionProtocol;
        return this;
    }

    public TotalOrderThreadingConfigurationBuilder totalOrderThreading() {
        return totalOrderThreading;
    }
}
