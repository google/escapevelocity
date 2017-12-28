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

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * An immutable list.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class ImmutableList<E> extends AbstractList<E> {
  private static final ImmutableList<?> EMPTY = new ImmutableList<>(new Object[0]);

  private final E[] elements;

  private ImmutableList(E[] elements) {
    this.elements = elements;
  }

  @Override
  public Iterator<E> iterator() {
    return Arrays.asList(elements).iterator();
  }

  @Override
  public E get(int index) {
    if (index < 0 || index >= elements.length) {
      throw new IndexOutOfBoundsException(String.valueOf(index));
    }
    return elements[index];
  }

  @Override
  public int size() {
    return elements.length;
  }

  static <E> ImmutableList<E> of() {
    @SuppressWarnings("unchecked")
    ImmutableList<E> empty = (ImmutableList<E>) EMPTY;
    return empty;
  }

  @SafeVarargs
  static <E> ImmutableList<E> of(E... elements) {
    return new ImmutableList<>(elements.clone());
  }

  static <E> ImmutableList<E> copyOf(List<E> list) {
    @SuppressWarnings("unchecked")
    E[] elements = (E[]) new Object[list.size()];
    list.toArray(elements);
    return new ImmutableList<>(elements);
  }

  static <E> Builder<E> builder() {
    return new Builder<E>();
  }

  static class Builder<E> {
    private final List<E> list = new ArrayList<>();

    void add(E element) {
      list.add(element);
    }

    ImmutableList<E> build() {
      if (list.isEmpty()) {
        return ImmutableList.of();
      }
      @SuppressWarnings("unchecked")
      E[] elements = (E[]) new Object[list.size()];
      list.toArray(elements);
      return new ImmutableList<>(elements);
    }
  }
}
