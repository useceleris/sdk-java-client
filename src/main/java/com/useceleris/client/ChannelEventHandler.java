package com.useceleris.client;

import java.util.function.Consumer;

/**
 * Registers channel-wide listeners (DEV-01). Obtain one with {@link Channel#events()}. Each
 * registration returns a {@link Registration} that removes exactly that listener.
 */
public final class ChannelEventHandler {
  private final Channel channel;

  ChannelEventHandler(Channel channel) {
    this.channel = channel;
  } // end constructor ChannelEventHandler

  /**
   * Registers a listener for every state transition.
   *
   * @param listener receives each new state
   * @return the registration
   */
  public Registration onStateChange(Consumer<ChannelState> listener) {
    return channel.addListener(channel.stateListeners, listener);
  } // end method onStateChange

  /**
   * Registers a listener for every successful reconnect.
   *
   * @param listener receives each recovery
   * @return the registration
   */
  public Registration onRecovery(Consumer<RecoveryEvent> listener) {
    return channel.addListener(channel.recoveryListeners, listener);
  } // end method onRecovery

  /**
   * Registers a listener for the server's raw notices.
   *
   * @param listener receives each notice
   * @return the registration
   */
  public Registration onNotice(Consumer<ServerNotice> listener) {
    return channel.addListener(channel.noticeListeners, listener);
  } // end method onNotice

  /**
   * Registers a listener for failures no caller is waiting for: a {@link ServerErrorException} the
   * server sent, or a {@link CelerisException} such as an undecodable message, a failed reconnect,
   * or a listener that threw.
   *
   * @param listener receives each failure
   * @return the registration
   */
  public Registration onError(Consumer<RuntimeException> listener) {
    return channel.addListener(channel.errorListeners, listener);
  } // end method onError

  /**
   * Registers a listener for every delivery from any segment, after that segment's own listeners
   * (MSG-02). It puts nothing on the wire: the server decides what arrives.
   *
   * <p>When you close the returned {@link Registration}, the SDK removes only this channel
   * listener. The other channel listeners and the segment listeners continue to receive messages.
   * Your subscriptions do not change, and the SDK does not send a message to the server. When you
   * call {@code close()} again, it has no effect.
   *
   * @param listener receives each delivery, with its own copy of the payload
   * @return the registration that removes only this listener
   */
  public Registration onMessage(MessageListener listener) {
    return channel.addListener(channel.channelMessageListeners, listener);
  } // end method onMessage
} // end class ChannelEventHandler
