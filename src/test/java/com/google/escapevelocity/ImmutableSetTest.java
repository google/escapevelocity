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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
@RunWith(JUnit4.class)
public class ImmutableSetTest {
  @Test
  public void empty() {
    ImmutableSet<String> empty = ImmutableSet.of();
    assertThat(empty).isEmpty();
    assertThat(empty).doesNotContain("");
  }

  @Test
  public void duplicates() {
    ImmutableSet<Integer> ints = ImmutableSet.of(1, 2, 3, 2, 1, 2, 3, 3);
    assertThat(ints).hasSize(3);
    assertThat(ints).containsExactly(1, 2, 3);

    ImmutableSet<Integer> ints2 = ImmutableSet.of(1, 2, 3, 4, 5, 3);
    assertThat(ints2).containsExactly(1, 2, 3, 4, 5);
  }
}
