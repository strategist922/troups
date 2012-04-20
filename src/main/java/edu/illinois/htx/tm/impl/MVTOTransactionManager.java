package edu.illinois.htx.tm.impl;

import static edu.illinois.htx.tm.TransactionState.ABORTED;
import static edu.illinois.htx.tm.TransactionState.COMMITTED;
import static edu.illinois.htx.tm.TransactionState.FINALIZED;
import static edu.illinois.htx.tm.TransactionState.STARTED;
import static edu.illinois.htx.tm.impl.LocalTransactionState.BLOCKED;
import static edu.illinois.htx.tm.impl.LocalTransactionState.CREATED;
import static edu.illinois.htx.tm.log.Log.RECORD_TYPE_DELETE;
import static edu.illinois.htx.tm.log.Log.RECORD_TYPE_PUT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.jboss.netty.util.internal.ConcurrentHashMap;

import edu.illinois.htx.tm.Key;
import edu.illinois.htx.tm.KeyValueStore;
import edu.illinois.htx.tm.KeyVersions;
import edu.illinois.htx.tm.LifecycleListener;
import edu.illinois.htx.tm.ObservingTransactionManager;
import edu.illinois.htx.tm.TransactionAbortedException;
import edu.illinois.htx.tm.log.GetLogRecord;
import edu.illinois.htx.tm.log.Log;
import edu.illinois.htx.tm.log.LogRecord;
import edu.illinois.htx.tm.log.OperationLogRecord;
import edu.illinois.htx.tm.log.StateTransitionLogRecord;
import edu.illinois.htx.tsm.TimestampManager;
import edu.illinois.htx.tsm.TimestampManager.TimestampReclamationListener;

/**
 * Note: this class (and all other classes in this package) do not depend on
 * HBase. This ensures separation of protocol from implementation, so let's keep
 * it that way, unless there is a good reason not to!
 * 
 * A generic transaction manager that implements the Multiversion Timestamp
 * Ordering Protocol (MVTO) as described in 'Transactional Information Systems'
 * by Weikum and Vossen in Section 5.5. The protocol consists of the following
 * three rules:
 * 
 * <ol>
 * <li>A read by transaction i on object x [= r_i(x)] is transformed into a read
 * by transaction i on the version k of x [= r_i(x_k)], where k is the version
 * of x that carries the largest timestamp less than or equal to the timestamp
 * of transaction i [= ts(x_k) <= ts(t_i)] and was written by transaction k, k
 * != i.</li>
 * <li>A write by transaction i [= w_i(x)] is processed as follows:
 * <ol>
 * <li>If a transaction j has read a version k of x [= r_j(x_k)] such that the
 * timestamp of transaction k is smaller than that of i and the timestamp of i
 * smaller than that of j [= ts(t_k) < ts(t_i) < ts(t_j), then the write is
 * rejected and transaction i is aborted.</li>
 * <li>Otherwise the write of x is transformed into a write of version i of x [=
 * w_i(x_i)].</li>
 * </ol>
 * <li>A commit of transaction i is delayed until the commit of all transactions
 * that have written new versions of data items read by transaction i.</li>
 * </ol>
 * 
 * Schedules produced by this protocol are view serialiable. One implication of
 * this is that blind reads are not necessarily serialized. If blind reads are
 * not allowed, then it produces conflict serializable schedules.
 * 
 * If we wanted to support blind writes, we may be able to add an implicit read
 * for every write that does not have a matching read preceding it in its
 * transaction.
 * 
 * Current assumption made by the implementation (unchecked):
 * <ol>
 * <li>transactions execute a read, write, or delete only once per Key
 * <li>transactions execute either a write or a delete for a Key, not both
 * <li>transactions always execute a read for a Key before writing/deleting it
 * </ol>
 * 
 * 
 * TODO (in order of priority):
 * <ol>
 * <li>remove implementation assumptions (see above)
 * 
 * <li>support alternate policy to only read committed versions (to eliminate
 * cascading aborts)
 * 
 * <li>think about consequences of IOExceptions
 * <li>think about if there is a way to do finalize asynchronously
 * <li>think about reading (1) conflicting write in progress and (2) delete from
 * removed finalized transactions
 * <li>refactor to clearly separate: local transaction processing, distributed
 * transactions processing, and concurrency control policy
 * <li>follow up with HBase dev team to get get/put failed notifiers
 * 
 * </ol>
 */
public class MVTOTransactionManager<K extends Key, R extends LogRecord>
    implements ObservingTransactionManager<K>, TimestampReclamationListener,
    LifecycleListener {

  // immutable state
  // key value store this TM is governing
  protected final KeyValueStore<K> keyValueStore;
  // transaction log
  protected final Log<K, R> transactionLog;
  // timestamp oracle
  protected final TimestampManager timestampManager;

  // mutable state
  // transactions by transaction ID for efficient direct lookup
  protected final NavigableMap<Long, MVTOTransaction<K>> transactions;
  // TAs indexed by key and versions read for efficient conflict detection
  protected final Map<K, NavigableMap<Long, NavigableSet<MVTOTransaction<K>>>> readers;
  // TAs indexed by key currently being written for efficient conflict detection
  protected final Map<K, NavigableSet<MVTOTransaction<K>>> activeWriters;
  // sequence of reading and finalized transactions to synchronize TA removal
  // TODO implement more efficiently (delay work to GC time)
  protected final Queue<MVTOTransaction<K>> activeReaders;
  // queue of transactions ready to be reclaimed
  protected final Set<MVTOTransaction<K>> reclaimables;
  // lock protect the previous two conflict detection data structures
  protected final ConcurrentMap<Key, Lock> keyLocks = new ConcurrentHashMap<Key, Lock>();
  // flag to indicate whether this TM is running
  protected boolean running;
  // guards the running flag
  protected ReadWriteLock runLock = new ReentrantReadWriteLock();

  public MVTOTransactionManager(KeyValueStore<K> keyValueStore, Log<K, R> log,
      TimestampManager timestampManager) {
    this.keyValueStore = keyValueStore;
    this.transactionLog = log;
    this.timestampManager = timestampManager;

    this.transactions = new TreeMap<Long, MVTOTransaction<K>>();
    this.readers = new HashMap<K, NavigableMap<Long, NavigableSet<MVTOTransaction<K>>>>();
    this.activeWriters = new HashMap<K, NavigableSet<MVTOTransaction<K>>>();
    this.activeReaders = new ConcurrentLinkedQueue<MVTOTransaction<K>>();
    this.reclaimables = new HashSet<MVTOTransaction<K>>();

    this.keyValueStore.addLifecycleListener(this);
    this.keyValueStore.addTransactionOperationObserver(this);
    this.timestampManager.addTimestampReclamationListener(this);
  }

  public KeyValueStore<K> getKeyValueStore() {
    return keyValueStore;
  }

  public Log<K, R> getTransactionLog() {
    return transactionLog;
  }

  public TimestampManager getTimestampManager() {
    return timestampManager;
  }

  @Override
  public void starting() {
    runLock.writeLock().lock();
    try {
      if (running)
        return;
      for (LogRecord record : transactionLog.recover())
        replay(record);
      for (MVTOTransaction<K> ta : transactions.values())
        recover(ta);
      timestampManager.addTimestampReclamationListener(this);
      running = true;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } finally {
      runLock.writeLock().unlock();
    }
  }

  @Override
  public void started() {
    // nothing to do here
  }

  // TODO probably better to drain out transactions to reduce recovery effort
  @Override
  public void stopping() {
    if (!runLock.writeLock().tryLock()) {
      while (true) {
        for (MVTOTransaction<K> ta : transactions.values())
          ta.unblock();
        try {
          if (runLock.writeLock().tryLock(100, TimeUnit.MILLISECONDS))
            break;
        } catch (InterruptedException e) {
          Thread.interrupted();
        }
      }
    }
    try {
      if (!running)
        return;
      running = false;
    } finally {
      runLock.writeLock().unlock();
    }
  }

  @Override
  public void aborting() {
    // we could probably do without this
    for (MVTOTransaction<K> ta : transactions.values())
      try {
        ta.releaseTimestamp();
      } catch (IOException e) {
        e.printStackTrace();
      }
  }

  @Override
  public void stopped() {
    // nothing to do here
  }

  @Override
  public void beforeGet(long tid, final Iterable<? extends K> keys)
      throws IOException {
    new WithReadLock() {
      @Override
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.beforeGet(keys);
      }
    }.run(tid);
  }

  @Override
  public void afterGet(long tid, final Iterable<? extends KeyVersions<K>> kvs)
      throws IOException {
    new WithReadLock() {
      @Override
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.afterGet(kvs);
      }
    }.run(tid);
  }

  @Override
  public void failedGet(long tid, final Iterable<? extends K> keys,
      final Throwable t) throws TransactionAbortedException, IOException {
    new WithReadLock() {
      @Override
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.failedGet(keys, t);
      }
    }.run(tid);
  }

  @Override
  public void beforePut(long tid, final Iterable<? extends K> keys)
      throws IOException {
    new WithReadLock() {
      @Override
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.beforePut(keys);
      }
    }.run(tid);
  }

  @Override
  public void afterPut(long tid, final Iterable<? extends K> keys)
      throws IOException {
    new WithReadLock() {
      @Override
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.afterPut(keys);
      }
    }.run(tid);
  }

  @Override
  public void failedPut(long tid, final Iterable<? extends K> keys,
      final Throwable t) throws TransactionAbortedException, IOException {
    new WithReadLock() {
      @Override
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.failedPut(keys, t);
      }
    }.run(tid);
  }

  @Override
  public void beforeDelete(long tid, final Iterable<? extends K> keys)
      throws TransactionAbortedException, IOException {
    new WithReadLock() {
      @Override
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.beforeDelete(keys);
      }
    }.run(tid);
  }

  @Override
  public void failedDelete(long tid, final Iterable<? extends K> keys,
      final Throwable t) throws TransactionAbortedException, IOException {
    new WithReadLock() {
      @Override
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.failedDelete(keys, t);
      }
    }.run(tid);
  }

  @Override
  public void afterDelete(long tid, final Iterable<? extends K> keys)
      throws TransactionAbortedException, IOException {
    new WithReadLock() {
      @Override
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.afterDelete(keys);
      }
    }.run(tid);
  }

  @Override
  public long begin() throws IOException {
    runLock.readLock().lock();
    try {
      checkRunning();
      MVTOTransaction<K> ta = new MVTOTransaction<K>(this);
      ta.begin();
      addTransaction(ta);
      return ta.getID();
    } finally {
      runLock.readLock().unlock();
    }
  }

  @Override
  public void commit(final long tid) throws IOException {
    new WithReadLock() {
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.commit();
        System.out.println("Committed " + tid);
      }
    }.run(tid);
  }

  @Override
  public void abort(final long tid) throws IOException {
    new WithReadLock() {
      void execute(MVTOTransaction<K> ta) throws IOException {
        ta.abort();
        System.out.println("Aborted " + tid);
      }
    }.run(tid);
  }

  abstract class WithReadLock {
    void run(long tid) throws IOException {
      runLock.readLock().lock();
      try {
        checkRunning();
        MVTOTransaction<K> ta = getTransaction(tid);
        if (ta == null)
          throw new IllegalStateException("Transaction " + tid
              + " does not exist");
        execute(ta);
      } finally {
        runLock.readLock().unlock();
      }
    }

    void execute(MVTOTransaction<K> ta) throws IOException {
      // overwrite
    }
  }

  void checkRunning() {
    runLock.readLock().lock();
    try {
      if (!running)
        throw new IllegalStateException("Transaction Manager stopped");
    } finally {
      runLock.readLock().unlock();
    }
  }

  Lock getLock() {
    return runLock.readLock();
  }

  void lock(Key key) {
    Lock keyLock = keyLocks.get(key);
    if (keyLock == null) {
      keyLock = new ReentrantLock();
      Lock raceLock = keyLocks.putIfAbsent(key, keyLock);
      if (raceLock != null)
        keyLock = raceLock;
    }
    keyLock.lock();
  }

  void unlock(Key key) {
    Lock keyLock = keyLocks.remove(key);
    if (keyLock != null)
      keyLock.unlock();
  }

  void addTransaction(MVTOTransaction<K> ta) {
    synchronized (transactions) {
      transactions.put(ta.getID(), ta);
    }
  }

  /**
   * Get the transaction object for the given timestamp
   * 
   * @param tid
   * @return
   */
  MVTOTransaction<K> getTransaction(long tid) {
    synchronized (transactions) {
      return transactions.get(tid);
    }
  }

  Iterable<MVTOTransaction<K>> getTransactions() {
    List<MVTOTransaction<K>> snapshot;
    synchronized (transactions) {
      snapshot = new ArrayList<MVTOTransaction<K>>(transactions.values());
    }
    return snapshot;
  }

  MVTOTransaction<K> removeTransaction(MVTOTransaction<K> ta) {
    synchronized (transactions) {
      return transactions.remove(ta.getID());
    }
  }

  // must be called with key lock held
  void addReader(K key, long version, MVTOTransaction<K> reader) {
    NavigableMap<Long, NavigableSet<MVTOTransaction<K>>> versions;
    synchronized (readers) {
      versions = readers.get(key);
      if (versions == null)
        readers.put(key,
            versions = new TreeMap<Long, NavigableSet<MVTOTransaction<K>>>());
    }
    NavigableSet<MVTOTransaction<K>> readers = versions.get(version);
    if (readers == null)
      versions.put(version, readers = new TreeSet<MVTOTransaction<K>>());
    readers.add(reader);
  }

  // must be called with key lock held
  NavigableMap<Long, NavigableSet<MVTOTransaction<K>>> getReaders(K key) {
    synchronized (readers) {
      return readers.get(key);
    }
  }

  // must be called with key lock held
  void removeReader(Key key, long version, MVTOTransaction<K> reader) {
    synchronized (readers) {
      NavigableMap<Long, NavigableSet<MVTOTransaction<K>>> versions = readers
          .get(key);
      if (versions != null) {
        NavigableSet<MVTOTransaction<K>> tas = versions.get(version);
        if (tas != null) {
          tas.remove(reader);
          if (tas.isEmpty()) {
            versions.remove(version);
            if (versions.isEmpty())
              readers.remove(key);
          }
        }
      }
    }
  }

  // must hold key lock to call this method
  void addActiveWriter(K key, MVTOTransaction<K> writer) {
    NavigableSet<MVTOTransaction<K>> writes;
    synchronized (activeWriters) {
      writes = activeWriters.get(key);
      if (writes == null)
        activeWriters.put(key, writes = new TreeSet<MVTOTransaction<K>>());
    }
    writes.add(writer);
  }

  // must hold key lock to call this method
  NavigableSet<MVTOTransaction<K>> getActiveWriters(K key) {
    synchronized (activeWriters) {
      return activeWriters.get(key);
    }
  }

  // must hold key lock to call this method
  void removeActiveWriter(Key key, MVTOTransaction<K> writer) {
    synchronized (activeWriters) {
      NavigableSet<MVTOTransaction<K>> writes = activeWriters.get(key);
      if (writes != null) {
        writes.remove(writer);
        if (writes.isEmpty())
          activeWriters.remove(key);
      }
    }
  }

  void addActiveReader(MVTOTransaction<K> ta) {
    activeReaders.add(ta);
  }

  void removeActiveReader(MVTOTransaction<K> ta) {
    activeReaders.remove(ta);
  }

  // garbage collection
  @Override
  public void reclaimed(long ts) {
    updateReclaimables();
    for (MVTOTransaction<K> ta : getTransactions())
      if (ta.getID() <= ts)
        reclaim(ta);
  }

  private void updateReclaimables() {
    Iterator<MVTOTransaction<K>> it = activeReaders.iterator();
    while (it.hasNext()) {
      MVTOTransaction<K> ta = it.next();
      if (ta.getState() != FINALIZED)
        break;
      reclaimables.add(ta);
      it.remove();
    }
  }

  protected void reclaim(MVTOTransaction<K> ta) {
    switch (ta.getState()) {
    case CREATED:
      removeTransaction(ta);
      break;
    case STARTED:
    case BLOCKED:
      System.out.println("WARNING: found active TAs before oldest timestamp "
          + ta);
      try {
        ta.abort();
      } catch (IOException e) {
        e.printStackTrace();
      }
      break;
    case ABORTED:
    case COMMITTED:
      // still needs to be finalized
      break;
    case FINALIZED:
      /*
       * reads can only be cleaned up once all transactions that started before
       * this one have completed
       */
      ta.removeReads();
      if (reclaimables.remove(ta))
        removeTransaction(ta);
      break;
    }
  }

  protected void replay(LogRecord record) {
    long tid = record.getTID();
    int type = record.getType();
    MVTOTransaction<K> ta = transactions.get(tid);
    switch (type) {
    case Log.RECORD_TYPE_STATE_TRANSITION: {
      StateTransitionLogRecord stlr = (StateTransitionLogRecord) record;
      switch (stlr.getTransactionState()) {
      case STARTED:
        if (ta != null)
          throw new IllegalStateException(
              "begin record for existing transaction");
        transactions.put(tid, ta = new MVTOTransaction<K>(this));
        ta.setStarted(tid, record.getSID());
        return;
      case COMMITTED:
        if (ta == null)
          return;
        ta.setCommitted();
        return;
      case ABORTED:
        if (ta == null)
          return;
        ta.setAborted();
        return;
      case FINALIZED:
        if (ta == null)
          return;
        switch (ta.getState()) {
        case COMMITTED:
          ta.setCommitFinalized();
          break;
        case ABORTED:
          ta.setAbortFinalized();
          break;
        }
        return;
      }
      return;
    }
    case Log.RECORD_TYPE_GET: {
      if (ta == null)
        return;
      @SuppressWarnings("unchecked")
      GetLogRecord<K> glr = (GetLogRecord<K>) record;
      K key = glr.getKey();
      long version = glr.getVersion();
      ta.addGet(key, version);
      return;
    }
    case RECORD_TYPE_PUT:
    case RECORD_TYPE_DELETE: {
      if (ta == null)
        return;
      @SuppressWarnings("unchecked")
      OperationLogRecord<K> olr = (OperationLogRecord<K>) record;
      K key = olr.getKey();
      boolean isDelete = olr.getType() == RECORD_TYPE_DELETE;
      ta.addMutation(key, isDelete);
      return;
    }
    }
    throw new IllegalStateException("Invalid log record: " + record);
  }

  // technically we don't need to abort
  protected void recover(MVTOTransaction<K> ta) {
    try {
      long tid = ta.getID();
      switch (ta.getState()) {
      case CREATED:
        throw new IllegalStateException("Created transaction during recovery");
      case STARTED:
        if (!timestampManager.isHeldByCaller(tid))
          ta.abort();
      case BLOCKED:
        throw new IllegalStateException("Blocked transaction during recovery");
      case ABORTED:
      case COMMITTED:
        ta.finalize();
        break;
      case FINALIZED:
        // nothing to do here, but be happy
        break;
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

}