package edu.illinois.troups.tm.region;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import edu.illinois.troups.tm.TID;
import edu.illinois.troups.tm.log.OperationLogRecord;

public abstract class HOperationLogRecord extends HLogRecord implements
    OperationLogRecord<HKey> {

  private HKey key;

  public HOperationLogRecord(int type) {
    super(type);
  }

  public HOperationLogRecord(int type, long sid, TID tid, HKey groupKey, HKey key) {
    super(type, sid, tid, groupKey);
    this.key = key;
  }

  @Override
  public HKey getKey() {
    return key;
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    super.readFields(in);
    key = new HKey();
    key.readFields(in);
  }

  @Override
  public void write(DataOutput out) throws IOException {
    super.write(out);
    key.write(out);
  }

}