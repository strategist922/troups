package edu.illinois.troups.tm;


public interface XATransactionState extends TransactionState {

  public static final int JOINED = 5;
  public static final int PREPARED = 6;

}