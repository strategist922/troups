package edu.illinois.troups.tm;

import java.io.IOException;

public interface XATransactionManager extends TransactionManager {

  public static final long VERSION = 1L;

  XID join(TID tid) throws IOException;

  void prepare(XID xid) throws TransactionAbortedException, IOException;

  void commit(XID xid, boolean onePhase) throws IOException;

  void abort(XID xid) throws IOException;

}