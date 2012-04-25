package edu.illinois.troups.tm.log;

import java.io.IOException;
import java.util.Comparator;

import edu.illinois.troups.tm.TID;
import edu.illinois.troups.tm.Key;

public interface Log<K extends Key, R extends LogRecord<K>>  extends Comparator<Long>{

  public static final int RECORD_TYPE_STATE_TRANSITION = 1;
  public static final int RECORD_TYPE_GET = 2;
  public static final int RECORD_TYPE_PUT = 3;
  public static final int RECORD_TYPE_DELETE = 4;

  public long appendStateTransition(TID tid, K groupKey, int state) throws IOException;

  public long appendGet(TID tid, K groupKey, K key, long version) throws IOException;

  public long appendPut(TID tid, K groupKey, K key) throws IOException;

  public long appendDelete(TID tid, K groupKey, K key) throws IOException;

  public abstract void truncate(long sid) throws IOException;

  public abstract Iterable<R> recover() throws IOException;

}