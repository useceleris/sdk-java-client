package com.useceleris.client;

/** Where a channel is in its lifecycle. */
public enum ChannelState {
  /** Created and never connected. */
  IDLE("idle"),

  /** The first connection attempt is running. */
  CONNECTING("connecting"),

  /** A WebSocket is established. Nothing more is implied. */
  CONNECTED("connected"),

  /** The connection dropped and is being recovered. */
  RECONNECTING("reconnecting"),

  /** Connecting or recovery failed. {@link Channel#connect()} may be called again. */
  FAILED("failed"),

  /** {@link Channel#close()} is shutting the connection down. */
  CLOSING("closing"),

  /** Terminal. Create a new channel to connect again. */
  CLOSED("closed");

  private final String state;

  ChannelState(String state) {
    this.state = state;
  } // end constructor ChannelState

  /**
   * Returns the state's name as every Celeris SDK spells it, such as {@code "connected"}.
   *
   * @return the cross-SDK name of this state
   */
  @Override
  public String toString() {
    return state;
  } // end method toString
} // end enum ChannelState
