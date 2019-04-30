/*
 * Copyright (C) 2019 Google, Inc.
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

import static com.google.common.truth.Truth.assertThat;
import static java.util.stream.Collectors.toSet;

import com.google.common.collect.ImmutableMap;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MethodFinderTest {
  @Test
  public void visibleMethodFromClass() throws Exception {
    Map<String, String> map = Collections.singletonMap("foo", "bar");
    Class<?> mapClass = map.getClass();
    assertThat(Modifier.isPublic(mapClass.getModifiers())).isFalse();
    
    Method size = mapClass.getMethod("size");
    Method visibleSize = MethodFinder.visibleMethod(size, mapClass);
    assertThat(visibleSize.getDeclaringClass().isInterface()).isFalse();
    assertThat(visibleSize.invoke(map)).isEqualTo(1);
  }

  @Test
  public void visibleMethodFromInterface() throws Exception {
    Map<String, String> map = ImmutableMap.of("foo", "bar");
    Map.Entry<String, String> entry = map.entrySet().iterator().next();
    Class<?> entryClass = entry.getClass();
    assertThat(Modifier.isPublic(entryClass.getModifiers())).isFalse();
    
    Method getValue = entryClass.getMethod("getValue");
    Method visibleGetValue = MethodFinder.visibleMethod(getValue, entryClass);
    assertThat(visibleGetValue.getDeclaringClass().isInterface()).isTrue();
    assertThat(visibleGetValue.invoke(entry)).isEqualTo("bar");
  }

  @Test
  public void publicMethodsWithName() {
    List<String> list = Collections.singletonList("foo");
    Class<?> listClass = list.getClass();
    assertThat(Modifier.isPublic(listClass.getModifiers())).isFalse();
    
    MethodFinder methodFinder = new MethodFinder();
    Set<Method> methods = methodFinder.publicMethodsWithName(listClass, "remove");
    // This should find at least remove(int) and remove(Object).
    assertThat(methods.size()).isAtLeast(2);
    assertThat(methods.stream().map(Method::getName).collect(toSet())).containsExactly("remove");
    assertThat(methods.stream().allMatch(MethodFinderTest::isPublic)).isTrue();
    
    // We should cache the result, meaning we get back the same result if we ask a second time.
    Set<Method> methods2 = methodFinder.publicMethodsWithName(listClass, "remove");
    assertThat(methods2).isSameInstanceAs(methods);
  }

  @Test
  public void publicMethodsWithName_Nonexistent() {
    List<String> list = Collections.singletonList("foo");
    Class<?> listClass = list.getClass();
    assertThat(Modifier.isPublic(listClass.getModifiers())).isFalse();
    
    MethodFinder methodFinder = new MethodFinder();
    Set<Method> methods = methodFinder.publicMethodsWithName(listClass, "nonexistentMethod");
    assertThat(methods).isEmpty();
  }

  private static boolean isPublic(Method method) {
    return Modifier.isPublic(method.getModifiers())
        && Modifier.isPublic(method.getDeclaringClass().getModifiers());
  }
}
