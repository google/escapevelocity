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

import static com.google.common.reflect.Reflection.getPackageName;
import static java.util.stream.Collectors.toSet;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Finds public methods in a class. For each one, it determines the public class or interface in
 * which it is declared. This avoids a problem with reflection, where we get an exception if we call
 * a {@code Method} in a non-public class, even if the {@code Method} is public and if there is a
 * public ancestor class or interface that declares it. We need to use the {@code Method} from the
 * public ancestor.
 *
 * <p>Because looking for these methods is relatively expensive, an instance of this class will keep
 * a cache of methods it previously discovered.
 */
class MethodFinder {

  /**
   * For a given class and name, returns all public methods of that name in the class, as previously
   * determined by {@link #publicMethodsWithName}. The set of methods for a given class and name is
   * saved the first time it is searched for, and returned directly thereafter. It may be empty.
   *
   * <p>Currently we add the entry for any given (class, name) pair on demand. An alternative would
   * be to add all the methods for a given class at once. With the current scheme, we may end up
   * calling {@link Class#getMethods()} several times for the same class, if methods of the
   * different names are called at different times. With an all-at-once scheme, we might end up
   * computing and storing information about a bunch of methods that will never be called. Because
   * the profiling that led to the creation of this class revealed that {@link #visibleMethods} in
   * particular is quite expensive, it's probably best to avoid calling it unnecessarily.
   */
  private final Table<Class<?>, String, ImmutableSet<Method>> methodCache = HashBasedTable.create();

  /**
   * Returns the set of public methods with the given name in the given class. Here, "public
   * methods" means public methods in public classes or interfaces. If {@code startClass} is not
   * itself public, its methods are effectively not public either, but inherited methods may still
   * appear in the returned set, with the {@code Method} objects belonging to public ancestors. More
   * than one ancestor may define an appropriate method, but it doesn't matter because invoking any
   * of those {@code Method} objects will have the same effect.
   */
  synchronized ImmutableSet<Method> publicMethodsWithName(Class<?> startClass, String name) {
    ImmutableSet<Method> cachedMethods = methodCache.get(startClass, name);
    if (cachedMethods == null) {
      cachedMethods = uncachedPublicMethodsWithName(startClass, name);
      methodCache.put(startClass, name, cachedMethods);
    }
    return cachedMethods;
  }

  private ImmutableSet<Method> uncachedPublicMethodsWithName(Class<?> startClass, String name) {
    // Class.getMethods() only returns public methods, so no need to filter explicitly for public.
    Set<Method> methods =
        Arrays.stream(startClass.getMethods())
            .filter(m -> m.getName().equals(name))
            .collect(toSet());
    if (!classIsPublic(startClass)) {
      methods =
          methods.stream()
              .map(m -> visibleMethod(m, startClass))
              .filter(Objects::nonNull)
              .collect(toSet());
      // It would be a bit simpler to use ImmutableSet.toImmutableSet() here, but there've been
      // problems in the past with versions of Guava that don't have that method.
    }
    return ImmutableSet.copyOf(methods);
  }

  private static final String THIS_PACKAGE = getPackageName(Node.class) + ".";

  /**
   * Returns a Method with the same name and parameter types as the given one, but that is in a
   * public class or interface. This might be the given method, or it might be a method in a
   * superclass or superinterface.
   *
   * @return a public method in a public class or interface, or null if none was found.
   */
  static Method visibleMethod(Method method, Class<?> in) {
    if (in == null) {
      return null;
    }
    Method methodInClass;
    try {
      methodInClass = in.getMethod(method.getName(), method.getParameterTypes());
    } catch (NoSuchMethodException e) {
      return null;
    }
    if (classIsPublic(in) || in.getName().startsWith(THIS_PACKAGE)) {
      // The second disjunct is a hack to allow us to use the public methods of $foreach without
      // having to make the ForEachVar class public. We can invoke those methods from the same
      // package since ForEachVar is package-protected.
      return methodInClass;
    }
    Method methodInSuperclass = visibleMethod(method, in.getSuperclass());
    if (methodInSuperclass != null) {
      return methodInSuperclass;
    }
    for (Class<?> superinterface : in.getInterfaces()) {
      Method methodInSuperinterface = visibleMethod(method, superinterface);
      if (methodInSuperinterface != null) {
        return methodInSuperinterface;
      }
    }
    return null;
  }

  /**
   * Returns whether the given class is public as seen from this class. Prior to Java 9, a class was
   * either public or not public. But with the introduction of modules in Java 9, a class can be
   * marked public and yet not be visible, if it is not exported from the module it appears in. So,
   * on Java 9, we perform an additional check on class {@code c}, which is effectively {@code
   * c.getModule().isExported(c.getPackageName())}. We use reflection so that the code can compile
   * on earlier Java versions.
   */
  private static boolean classIsPublic(Class<?> c) {
    return Modifier.isPublic(c.getModifiers()) && classIsExported(c);
  }

  private static boolean classIsExported(Class<?> c) {
    if (CLASS_GET_MODULE_METHOD == null) {
      return true; // There are no modules, so all classes are exported.
    }
    try {
      String pkg = getPackageName(c);
      Object module = CLASS_GET_MODULE_METHOD.invoke(c);
      return (Boolean) MODULE_IS_EXPORTED_METHOD.invoke(module, pkg);
    } catch (Exception e) {
      return false;
    }
  }

  private static final Method CLASS_GET_MODULE_METHOD;
  private static final Method MODULE_IS_EXPORTED_METHOD;

  static {
    Method classGetModuleMethod;
    Method moduleIsExportedMethod;
    try {
      classGetModuleMethod = Class.class.getMethod("getModule");
      Class<?> moduleClass = classGetModuleMethod.getReturnType();
      moduleIsExportedMethod = moduleClass.getMethod("isExported", String.class);
    } catch (Exception e) {
      classGetModuleMethod = null;
      moduleIsExportedMethod = null;
    }
    CLASS_GET_MODULE_METHOD = classGetModuleMethod;
    MODULE_IS_EXPORTED_METHOD = moduleIsExportedMethod;
  }
}
