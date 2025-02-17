package org.infinispan.scattered.impl;

import static org.infinispan.factories.KnownComponentNames.NON_BLOCKING_EXECUTOR;

import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.StampedLock;

import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.write.InvalidateVersionsCommand;
import org.infinispan.commons.CacheException;
import org.infinispan.commons.util.IntSet;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.container.impl.InternalDataContainer;
import org.infinispan.container.versioning.EntryVersion;
import org.infinispan.container.versioning.SimpleClusteredVersion;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.distribution.LocalizedCacheTopology;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.annotations.Stop;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.persistence.manager.OrderedUpdatesManager;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.impl.MapResponseCollector;
import org.infinispan.scattered.ScatteredVersionManager;
import org.infinispan.util.concurrent.CompletableFutures;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import net.jcip.annotations.GuardedBy;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Scope(Scopes.NAMED_CACHE)
public class ScatteredVersionManagerImpl<K> implements ScatteredVersionManager<K> {
   private static final AtomicIntegerFieldUpdater<ScatteredVersionManagerImpl> topologyIdUpdater
         = AtomicIntegerFieldUpdater.newUpdater(ScatteredVersionManagerImpl.class, "topologyId");

   protected static final Log log = LogFactory.getLog(ScatteredVersionManagerImpl.class);

   @Inject Configuration configuration;
   @Inject ComponentRegistry componentRegistry;
   @Inject @ComponentName(NON_BLOCKING_EXECUTOR)
   ExecutorService nonBlockingExecutor;
   @Inject CommandsFactory commandsFactory;
   @Inject RpcManager rpcManager;
   @Inject InternalDataContainer<K, ?> dataContainer;
   @Inject DistributionManager distributionManager;
   @Inject OrderedUpdatesManager orderedUpdatesManager;

   private int invalidationBatchSize;
   private int numSegments;
   private int preloadedTopologyId = 0;
   private volatile int topologyId = 0;
   private AtomicReferenceArray<SegmentState> segmentStates;
   private AtomicReferenceArray<CompletableFuture<Void>> blockedFutures;
   private AtomicLongArray segmentVersions;
   // holds the topologies in which this node has become the owner of given segment
   private AtomicIntegerArray ownerTopologyIds;
   private final StampedLock scheduledKeysLock = new StampedLock();
   private ConcurrentMap<K, InvalidationInfo> scheduledKeys;
   private final StampedLock removedKeysLock = new StampedLock();
   private ConcurrentMap<K, InvalidationInfo> removedKeys;

   // Whether we are currently transferring values
   private volatile boolean transferringValues = false;
   // The last topology for which we finished transferring values
   private volatile int valuesTopology = -1;
   // This future is completed when the current (or next, if transferringValues == false) value transfer finishes
   // Should be completed and replaced with another future atomically, or valuesFuture() will enter an infinite loop
   @GuardedBy("valuesLock")
   private CompletableFuture<Void> valuesFuture = new CompletableFuture<>();
   private final Object valuesLock = new Object();

   @Start(priority = 15) // before StateConsumerImpl and StateTransferManagerImpl
   public void start() {
      // this assumes that number of segments does not change
      numSegments = configuration.clustering().hash().numSegments();
      segmentVersions = new AtomicLongArray(numSegments);
      segmentStates = new AtomicReferenceArray<>(numSegments);
      blockedFutures = new AtomicReferenceArray<>(numSegments);
      ownerTopologyIds = new AtomicIntegerArray(numSegments);
      LocalizedCacheTopology cacheTopology = distributionManager.getCacheTopology();
      // Can't use the read owners, as we become read owners in the CH before we receive the data
      ConsistentHash ch = cacheTopology.getCurrentCH();
      for (int i = 0; i < numSegments; ++i) {
         // The component can be rewired, and then this is executed without any topology change
         SegmentState state = SegmentState.NOT_OWNED;
         if (cacheTopology.isConnected() && ch.isSegmentLocalToNode(rpcManager.getAddress(), i)) {
            state = SegmentState.OWNED;
         }
         segmentStates.set(i, state);
      }
      printTable();
      scheduledKeys = new ConcurrentHashMap<>(invalidationBatchSize);
      invalidationBatchSize = configuration.clustering().invalidationBatchSize();
      removedKeys = new ConcurrentHashMap<>(invalidationBatchSize);
   }

   @Stop
   public void stop() {
      log.trace("Stopping " + this + " on " + rpcManager.getAddress());
      synchronized (valuesLock) {
         valuesTopology = Integer.MAX_VALUE;
         valuesFuture.completeExceptionally(new CacheException("Cache is stopping"));
      }
      log.trace("Stopped " + this + " on " + rpcManager.getAddress());
   }

   @Override
   public EntryVersion incrementVersion(int segment) {
      switch (segmentStates.get(segment)) {
         case NOT_OWNED:
            throw new CacheException("Segment " + segment + " is not owned by " + rpcManager.getAddress());
         case BLOCKED:
            // If the segment is blocked, the PrefetchInterceptor should block execution until we receive
            // max version number. If the topology is changed afterwards, ScatteringInterceptor should throw OTE.
            throw new CacheException("Segment " + segment + " is currently blocked");
         case KEY_TRANSFER:
         case VALUE_TRANSFER:
         case OWNED:
            return new SimpleClusteredVersion(topologyId, segmentVersions.addAndGet(segment, 1));
         default:
            throw new IllegalStateException();
      }
   }

   @Override
   public void scheduleKeyInvalidation(K key, EntryVersion version, boolean removal) {
      InvalidationInfo ii = new InvalidationInfo((SimpleClusteredVersion) version, removal);
      boolean needsSend;
      long stamp = scheduledKeysLock.readLock();
      try {
         scheduledKeys.compute(key, (k, old) -> old == null ? ii :
               ii.version > old.version || (ii.removal && ii.version == old.version) ? ii : old);
         needsSend = scheduledKeys.size() >= invalidationBatchSize;
      } finally {
         scheduledKeysLock.unlockRead(stamp);
      }
      if (needsSend) {
         tryRegularInvalidations(false);
      }
   }

   // testing only
   protected boolean startFlush() {
      if (!scheduledKeys.isEmpty()) {
         tryRegularInvalidations(true);
         return true;
      } else {
         // when there are some invalidations, removed invalidations are triggered automatically
         // but here we have to start it manually
         if (!removedKeys.isEmpty()) {
            tryRemovedInvalidations();
            return true;
         } else {
            return false;
         }
      }
   }

   @Override
   public synchronized void registerSegment(int segment) {
      ownerTopologyIds.set(segment, topologyId);
      segmentVersions.set(segment, 0);
      blockedFutures.set(segment, new CompletableFuture<>());
      if (!segmentStates.compareAndSet(segment, SegmentState.NOT_OWNED, SegmentState.BLOCKED)) {
         throw new IllegalStateException("Segment " + segment + " is in state " + segmentStates.get(segment));
      } else {
         log.tracef("Node %s blocks access to segment %d", rpcManager.getAddress(), segment);
      }
   }

   @Override
   public synchronized void unregisterSegment(int segment) {
      SegmentState previous = segmentStates.getAndSet(segment, SegmentState.NOT_OWNED);
      if (log.isTraceEnabled()) {
         log.tracef("Unregistered segment %d (previous=%s)", segment, previous);
      }
      CompletableFuture<Void> blockedFuture = blockedFutures.get(segment);
      if (blockedFuture != null) {
         blockedFuture.completeExceptionally(new CacheException("The segment is no longer owned."));
      }
   }

   @Override
   public boolean isVersionActual(int segment, EntryVersion version) {
      SimpleClusteredVersion clusteredVersion = (SimpleClusteredVersion) version;
      return clusteredVersion.getTopologyId() >= ownerTopologyIds.get(segment);
   }

   @Override
   public void notifyKeyTransferFinished(int segment, boolean expectValues, boolean cancelled) {
      SegmentState update;
      if (cancelled) {
         // The transfer is cancelled when a newer topology is being installed.
         update = SegmentState.NOT_OWNED;
         assert !expectValues;
      } else if (expectValues) {
         update = SegmentState.VALUE_TRANSFER;
      } else {
         update = SegmentState.OWNED;
      }
      // It is possible that the segment is not in KEY_TRANSFER state, but can be in BLOCKED states as well
      // when the CONFIRM_REVOKED_SEGMENTS failed.
      SegmentState previous = segmentStates.getAndSet(segment, update);
      if (log.isTraceEnabled()) {
         log.tracef("Finished transfer for segment %d = %s -> %s", segment, previous, update);
      }
      CompletableFuture<Void> blockedFuture = blockedFutures.get(segment);
      if (blockedFuture != null) {
         blockedFuture.completeExceptionally(new CacheException("Segment state transition did not complete correctly."));
      }
      if (log.isTraceEnabled()) {
         if (expectValues) {
            log.tracef("Node %s, segment %d has all keys in, expects value transfer", rpcManager.getAddress(), segment);
         } else {
            log.tracef("Node %s, segment %d did not transfer any keys, segment is owned now", rpcManager.getAddress(), segment);
         }
      }
   }

   @Override
   public SegmentState getSegmentState(int segment) {
      return segmentStates.get(segment);
   }

   @Override
   public void setValuesTransferTopology(int topologyId) {
      log.tracef("Node will transfer value for topology %d", topologyId);
      synchronized (valuesLock) {
         transferringValues = true;
      }
   }

   @Override
   public void notifyValueTransferFinished() {
      for (int i = 0; i < numSegments; ++i) {
         LOOP: for (;;) {
            SegmentState state = segmentStates.get(i);
            switch (state) {
               case NOT_OWNED:
               case OWNED:
                  break LOOP;
               case BLOCKED:
               case KEY_TRANSFER:
                  blockedFutures.get(i).completeExceptionally(new CacheException("Failed to request versions"));
                  log.warnf("Stopped applying state for segment %d in topology %d but the segment is in state %s", i, topologyId, state);
                  // fall through
               case VALUE_TRANSFER:
                  if (segmentStates.compareAndSet(i, state, SegmentState.OWNED)) {
                     break LOOP;
                  }
            }
         }
      }
      synchronized (valuesLock) {
         valuesTopology = Math.max(topologyId, valuesTopology);
         transferringValues = false;
         valuesFuture.complete(null);
         valuesFuture = new CompletableFuture<>();
      }
      log.debugf("Node %s received values for all segments in topology %d", rpcManager.getAddress(), topologyId);
   }

   @Override
   public CompletableFuture<Void> getBlockingFuture(int segment) {
      return blockedFutures.get(segment);
   }

   @Override
   public void setTopologyId(int topologyId) {
      int currentTopologyId = this.topologyId;
      if (currentTopologyId >= topologyId) {
         throw new IllegalArgumentException("Updating to topology " + topologyId + " but current is " + currentTopologyId);
      } else if (!topologyIdUpdater.compareAndSet(this, currentTopologyId, topologyId)) {
         throw new IllegalStateException("Concurrent update to topology " + topologyId +
               ", current was " + currentTopologyId + " but now it's " + this.topologyId);
      }
   }

   @Override
   public void updatePreloadedEntryVersion(EntryVersion version) {
      if (version instanceof SimpleClusteredVersion) {
         int topologyId = ((SimpleClusteredVersion) version).getTopologyId();
         preloadedTopologyId = Math.max(preloadedTopologyId, topologyId);
      }
   }

   @Override
   public int getPreloadedTopologyId() {
      return preloadedTopologyId;
   }

   @Override
   public CompletableFuture<Void> valuesFuture(int topologyId) {
      // it is possible that someone will ask with topologyId that does not belong to a rebalance,
      // while the valuesTopology is updated only on rebalance. Therefore, without the extra boolean
      // we would get stuck here.
      if (transferringValues && topologyId > valuesTopology) {
         synchronized (valuesLock) {
            if (transferringValues && topologyId > valuesTopology) {
               return valuesFuture.thenCompose(nil -> valuesFuture(topologyId));
            }
         }
      }
      return CompletableFutures.completedNull();
   }

   @Override
   public void setOwnedSegments(IntSet segments) {
      for (PrimitiveIterator.OfInt iter = segments.iterator(); iter.hasNext(); ) {
         int segment = iter.nextInt();
         segmentVersions.set(segment, 0);
         ownerTopologyIds.set(segment, topologyId);
         if (!segmentStates.compareAndSet(segment, SegmentState.NOT_OWNED, SegmentState.OWNED)) {
            throw new IllegalStateException(String.format("Segment %d is in state %s", segment, segmentStates.get(segment)));
         }
      }
      if (log.isDebugEnabled()) {
         log.debugf("Node %s is now owner of segments %s", rpcManager.getAddress(), segments);
         printTable();
      }
   }

   @Override
   public void startKeyTransfer(IntSet segments) {
      for (PrimitiveIterator.OfInt iter = segments.iterator(); iter.hasNext(); ) {
         int segment = iter.nextInt();
         if (!segmentStates.compareAndSet(segment, SegmentState.BLOCKED, SegmentState.KEY_TRANSFER)) {
            throw new IllegalStateException(String.format("Segment %d is in state %s", segment, segmentStates.get(segment)));
         }
         blockedFutures.get(segment).complete(null);
         log.tracef("Node %s, segment %d expects key transfer", rpcManager.getAddress(), segment);
      }
   }

   private void printTable() {
      StringBuilder sb = new StringBuilder("Segments for node ").append(rpcManager.getAddress()).append(':');
      for (int i = 0; i < numSegments; i += 16) {
         sb.append('\n');
         for (int j = 0; j < 16 && i + j < numSegments; ++j) {
            sb.append(String.format("%4d=%c ", i + j, segmentStates.get(i + j).singleChar()));
         }
      }
      log.debug(sb.toString());
   }

   private void tryRegularInvalidations(boolean force) {
      ConcurrentMap<K, InvalidationInfo> scheduledKeys;
      long stamp = scheduledKeysLock.writeLock();
      try {
         scheduledKeys = this.scheduledKeys;
         this.scheduledKeys = new ConcurrentHashMap<>(invalidationBatchSize);
      } finally {
         scheduledKeysLock.unlockWrite(stamp);
      }

      // we'll invalidate all keys in one run
      // we don't have to keep any topology lock, because the versions increase monotonically
      int numKeys = scheduledKeys.size();
      Object[] keys = new Object[numKeys];
      int[] topologyIds = new int[numKeys];
      long[] versions = new long[numKeys];
      boolean[] isRemoved = new boolean[numKeys];
      int numRemoved = 0;
      int i = 0;
      for (Map.Entry<K, InvalidationInfo> entry : scheduledKeys.entrySet()) {
         keys[i] = entry.getKey();
         topologyIds[i] = entry.getValue().topologyId;
         versions[i] = entry.getValue().version;
         if (isRemoved[i] = entry.getValue().removal) { // intentional assignment
            numRemoved++;
         }
         ++i;
      }
      InvalidateVersionsCommand command = commandsFactory.buildInvalidateVersionsCommand(-1, keys, topologyIds, versions, false);
      sendRegularInvalidations(command, keys, topologyIds, versions, numRemoved, isRemoved, force);
   }

   private void sendRegularInvalidations(InvalidateVersionsCommand command, Object[] keys, int[] topologyIds, long[] versions, int numRemoved, boolean[] isRemoved, boolean force) {
      CompletionStage<Map<Address, Response>> future =
            rpcManager.invokeCommandOnAll(command, MapResponseCollector.ignoreLeavers(),
                                          rpcManager.getSyncRpcOptions());
      future.whenComplete((r, t) -> {
         if (t != null) {
            log.failedInvalidatingRemoteCache(t);
            sendRegularInvalidations(command, keys, topologyIds, versions, numRemoved, isRemoved, force);
         } else if (numRemoved > 0 || force) {
            regularInvalidationFinished(keys, topologyIds, versions, isRemoved, force);
         }
      });
   }

   protected void regularInvalidationFinished(Object[] keys, int[] topologyIds, long[] versions, boolean[] isRemoved, boolean force) {
      boolean needsSend;
      long stamp = removedKeysLock.readLock();
      try {
         for (int i = 0; i < isRemoved.length; ++i) {
            if (isRemoved[i]) {
               int topologyId = topologyIds[i];
               long version = versions[i];
               removedKeys.compute((K) keys[i], (k, prev) -> {
                  if (prev == null || prev.topologyId < topologyId
                        || prev.topologyId == topologyId && prev.version < version) {
                     return new InvalidationInfo(topologyId, version);
                  } else {
                     return prev;
                  }
               });
            }
         }
         needsSend = removedKeys.size() > invalidationBatchSize || (force && !removedKeys.isEmpty());
      } finally {
         removedKeysLock.unlockRead(stamp);
      }
      if (needsSend) {
         tryRemovedInvalidations();
      }
   }

   private void tryRemovedInvalidations() {
      final ConcurrentMap<K, InvalidationInfo> removedKeys;
      long stamp = removedKeysLock.writeLock();
      try {
         removedKeys = this.removedKeys;
         this.removedKeys = new ConcurrentHashMap<>(invalidationBatchSize);
      } finally {
         removedKeysLock.unlockWrite(stamp);
      }

      int numKeys = removedKeys.size();
      Object[] keys = new Object[numKeys];
      int[] topologyIds = new int[numKeys];
      long[] versions = new long[numKeys];
      int i = 0;
      for (Map.Entry<K, InvalidationInfo> entry : removedKeys.entrySet()) {
         keys[i] = entry.getKey();
         topologyIds[i] = entry.getValue().topologyId;
         versions[i] = entry.getValue().version;
      }
      InvalidateVersionsCommand removeCommand = commandsFactory.buildInvalidateVersionsCommand(-1, keys, topologyIds, versions, true);
      sendRemoveInvalidations(removeCommand);
   }

   private void sendRemoveInvalidations(InvalidateVersionsCommand removeCommand) {
      rpcManager.invokeCommandOnAll(removeCommand, MapResponseCollector.ignoreLeavers(),
                                    rpcManager.getSyncRpcOptions())
                .whenComplete((r, t) -> {
         if (t != null) {
            log.failedInvalidatingRemoteCache(t);
            sendRemoveInvalidations(removeCommand);
         } else {
            removeInvalidationsFinished();
         }
      });
      // remove the entries on self, too
      removeCommand.invokeAsync(componentRegistry);
   }

   protected void removeInvalidationsFinished() {
      // testing hook
   }

   @Override
   public void clearInvalidations() {
      long stamp1 = scheduledKeysLock.writeLock();
      try {
         scheduledKeys = new ConcurrentHashMap<>(invalidationBatchSize);
      } finally {
         scheduledKeysLock.unlockWrite(stamp1);
      }
      long stamp2 = removedKeysLock.writeLock();
      try {
         removedKeys = new ConcurrentHashMap<>(invalidationBatchSize);
      } finally {
         removedKeysLock.unlockWrite(stamp2);
      }
   }

   private static class InvalidationInfo {
      public final int topologyId;
      public final long version;
      public final boolean removal;

      private InvalidationInfo(SimpleClusteredVersion version, boolean removal) {
         this.topologyId = version.getTopologyId();
         this.version = version.getVersion();
         this.removal = removal;
      }

      private InvalidationInfo(int topologyId, long version) {
         this.topologyId = topologyId;
         this.version = version;
         this.removal = true;
      }
   }
}
