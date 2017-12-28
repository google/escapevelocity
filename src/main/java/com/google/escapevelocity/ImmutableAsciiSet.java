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
import java.util.BitSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An immutable set of ASCII characters.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class ImmutableAsciiSet extends AbstractSet<Integer> {
  private final BitSet bits;

  ImmutableAsciiSet(BitSet bits) {
    this.bits = bits;
  }

  static ImmutableAsciiSet of(char c) {
    return ofRange(c, c);
  }

  static ImmutableAsciiSet ofRange(char from, char to) {
    if (from > to) {
      throw new IllegalArgumentException("from > to");
    }
    if (to >= 128) {
      throw new IllegalArgumentException("Not ASCII");
    }
    BitSet bits = new BitSet();
    bits.set(from, to + 1);
    return new ImmutableAsciiSet(bits);
  }

  ImmutableAsciiSet union(ImmutableAsciiSet that) {
    BitSet union = (BitSet) bits.clone();
    union.or(that.bits);
    return new ImmutableAsciiSet(union);
  }

  @Override
  public boolean contains(Object o) {
    int i = -1;
    if (o instanceof Character) {
      i = (Character) o;
    } else if (o instanceof Integer) {
      i = (Integer) o;
    }
    return contains(i);
  }

  boolean contains(int i) {
    if (i < 0) {
      return false;
    } else {
      return bits.get(i);
    }
  }

  @Override
  public Iterator<Integer> iterator() {
    return new Iterator<Integer>() {
      private int index;

      @Override
      public boolean hasNext() {
        return bits.nextSetBit(index) >= 0;
      }

      @Override
      public Integer next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        int next = bits.nextSetBit(index);
        index = next + 1;
        return next;
      }
    };
  }

  @Override
  public int size() {
    return bits.cardinality();
  }
}
