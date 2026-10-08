package com.useceleris.client;

import java.util.concurrent.CompletionStage;

/**
 * Fetches credentials for one connection attempt, typically from your own credential endpoint,
 * which signs them on a trusted server. Never ship a signing secret in an application.
 *
 * <p>The SDK calls it on one of its own threads, once per attempt. When the attempt is abandoned,
 * by its deadline or {@link Channel#close()}, the SDK cancels the returned stage; a result it
 * produces afterwards is discarded. Any failure, a throw or a failed stage, is reported as {@link
 * ErrorCode#TRANSPORT}; its text is never inspected or passed on.
 */
@FunctionalInterface
public interface CredentialProvider {
  /**
   * Starts fetching credentials for one attempt.
   *
   * @param request the attempt credentials are wanted for
   * @return a stage completing with the credentials
   */
  CompletionStage<Credentials> provide(CredentialRequest request);
} // end interface CredentialProvider
