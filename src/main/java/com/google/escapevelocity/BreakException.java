/*
 * Copyright (C) 2023 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.escapevelocity;

/** Exception thrown when a {@code #break} is encountered. */
final class BreakException extends RuntimeException {
  private final boolean forEachScope;

  /**
   * Constructs a new {@code BreakException}.
   *
   * @param forEachScope true if this is {@code #break($foreach)}. Then if we are not actually
   *     inside a {@code #foreach} it is an error.
   */
  BreakException(String message, boolean forEachScope) {
    super(message);
    this.forEachScope = forEachScope;
  }

  boolean forEachScope() {
    return forEachScope;
  }
}
