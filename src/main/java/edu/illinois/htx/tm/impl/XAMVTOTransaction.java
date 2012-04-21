package edu.illinois.htx.tm.impl;

import static edu.illinois.htx.tm.TransactionState.ABORTED;
import static edu.illinois.htx.tm.TransactionState.COMMITTED;
import static edu.illinois.htx.tm.TransactionState.FINALIZED;
import static edu.illinois.htx.tm.TransactionState.STARTED;
import static edu.illinois.htx.tm.impl.TransientTransactionState.BLOCKED;
import static edu.illinois.htx.tm.impl.TransientTransactionState.CREATED;
import static edu.illinois.htx.tm.XATransactionState.JOINED;
import static edu.illinois.htx.tm.XATransactionState.PREPARED;

import java.io.IOException;

import edu.illinois.htx.tm.Key;
import edu.illinois.htx.tm.TID;
import edu.illinois.htx.tm.TransactionAbortedException;
import edu.illinois.htx.tm.XID;
import edu.illinois.htx.tm.log.XALog;
import edu.illinois.htx.tsm.NoSuchTimestampException;
import edu.illinois.htx.tsm.SharedTimestampManager;
import edu.illinois.htx.tsm.TimestampManager.TimestampListener;

public class XAMVTOTransaction<K extends Key> extends MVTOTransaction<K>
    implements TimestampListener {

  public XAMVTOTransaction(XAMVTOTransactionManager<K, ?> tm) {
    super(tm);
  }

  @Override
  protected boolean shouldBegin() {
    throw newISA("Cannot begin XA transaction");
  }

  @Override
  protected boolean isActive() {
    switch (state) {
    case JOINED:
    case PREPARED:
      return true;
    }
    return super.isActive();
  }

  public synchronized void join(TID id) throws IOException {
    checkJoin();
    long ts = id.getTS();
    long pid = getTimestampManager().acquireReference(ts);
    XID xid = new XID(ts, pid);
    getTimestampManager().addTimestampListener(ts, this);
    long sid = getTransactionLog().appendXAStateTransition(xid, JOINED);
    setJoined(xid, sid);
  }

  protected void checkJoin() {
    switch (state) {
    case CREATED:
      break;
    default:
      throw newISA("join");
    }
  }

  protected void setJoined(XID id, long sid) {
    this.id = id;
    this.sid = sid;
    this.state = JOINED;
  }

  public synchronized void prepare() throws TransactionAbortedException,
      IOException {
    if (!shouldPrepare())
      return;
    waitForReadFrom();
    getTransactionLog().appendXAStateTransition(getID(), PREPARED);
    setPrepared();
  }

  protected boolean shouldPrepare() {
    switch (state) {
    case JOINED:
      return true;
    case PREPARED:
      return false;
    default:
      throw newISA("checkPrepare");
    }
  }

  protected synchronized void setPrepared() {
    this.state = PREPARED;
  }

  @Override
  protected boolean shouldCommit() {
    switch (state) {
    case PREPARED:
      return true;
    case JOINED:
      return false;
    default:
      return super.shouldCommit();
    }
  }

  @Override
  protected boolean shouldAbort() {
    switch (state) {
    case PREPARED:
      return true;
    case JOINED:
      return true;
    default:
      return super.shouldAbort();
    }
  }

  /**
   * Transaction has been aborted: note this method will only be called if the
   * prepare phase was unsuccessful.
   * 
   * @param ts
   */
  @Override
  public synchronized void released(long ts) {
    switch (state) {
    case FINALIZED:
    case COMMITTED:
    case ABORTED:
      return;
    case STARTED:
    case BLOCKED:
      break;
    case CREATED:
      throw newISA("deleted");
    }
    try {
      abort();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  protected void releaseTimestamp() throws IOException {
    try {
      getTimestampManager().releaseReference(getID().getTS(), getID().getPid());
    } catch (NoSuchTimestampException e) {
      // ignore
    }
  }

  @Override
  protected SharedTimestampManager getTimestampManager() {
    return ((XAMVTOTransactionManager<K, ?>) tm).getTimestampManager();
  }

  @Override
  protected XALog<K, ?> getTransactionLog() {
    return (XALog<K, ?>) tm.getTransactionLog();
  }

  synchronized XID getID() {
    return (XID) super.getID();
  }

}
