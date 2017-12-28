/*
 * Copyright (C) 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.escapevelocity;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;

/**
 * An immutable set. This implementation is only suitable for sets with a small number of elements.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class ImmutableSet<E> extends AbstractSet<E> {
  private final E[] elements;

  private ImmutableSet(E[] elements) {
    this.elements = elements;
  }

  @Override
  public Iterator<E> iterator() {
    return Arrays.asList(elements).iterator();
  }

  @Override
  public int size() {
    return elements.length;
  }

  @SafeVarargs
  static <E> ImmutableSet<E> of(E... elements) {
    int len = elements.length;
    for (int i = 0; i < len - 1; i++) {
      for (int j = len - 1; j > i; j--) {
        if (elements[i].equals(elements[j])) {
          // We want to exclude elements[j] from the final set. We can do that by copying the
          // current last element in place of j (this might be j itself) and then reducing the
          // size of the set.
          elements[j] = elements[len - 1];
          len--;
        }
      }
    }
    return new ImmutableSet<>(Arrays.copyOf(elements, len));
  }
}
