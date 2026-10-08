package com.useceleris.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Names the lock that guards a field or method. Error Prone matches it by name. */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD})
@interface GuardedBy {
  String value();
} // end annotation GuardedBy
