/*
 * Copyright (C) 2018 Google, Inc.
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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;

/**
 * The context of a template evaluation. This consists of the template variables and the template
 * macros. The template variables start with the values supplied by the evaluation call, and can
 * be changed by {@code #set} directives and during the execution of {@code #foreach} and macro
 * calls. The macros are extracted from the template during parsing and never change thereafter.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
interface EvaluationContext {
  Object getVar(String var);

  boolean varIsDefined(String var);

  /**
   * Sets the given variable to the given value.
   *
   * @return a Runnable that will restore the variable to the value it had before. If the variable
   *     was undefined before this method was executed, the Runnable will make it undefined again.
   *     This allows us to restore the state of {@code $x} after {@code #foreach ($x in ...)}.
   */
  Runnable setVar(final String var, Object value);

  /** See {@link MethodFinder#publicMethodsWithName}. */
  ImmutableSet<Method> publicMethodsWithName(Class<?> startClass, String name);

  /** Gets the macro definition with the given name, or null if there is none. */
  Macro getMacro(String name);

  class PlainEvaluationContext implements EvaluationContext {
    private final Map<String, Object> vars;
    private final ImmutableMap<String, Macro> macros;
    private final MethodFinder methodFinder;

    PlainEvaluationContext(
        Map<String, ?> vars, ImmutableMap<String, Macro> macros, MethodFinder methodFinder) {
      this.vars = new TreeMap<>(vars);
      this.macros = macros;
      this.methodFinder = methodFinder;
    }

    @Override
    public Object getVar(String var) {
      return vars.get(var);
    }

    @Override
    public boolean varIsDefined(String var) {
      return vars.containsKey(var);
    }

    @Override
    public Runnable setVar(final String var, Object value) {
      Runnable undo;
      if (vars.containsKey(var)) {
        final Object oldValue = vars.get(var);
        undo = () -> vars.put(var, oldValue);
      } else {
        undo = () -> vars.remove(var);
      }
      vars.put(var, value);
      return undo;
    }

    @Override
    public ImmutableSet<Method> publicMethodsWithName(Class<?> startClass, String name) {
      return methodFinder.publicMethodsWithName(startClass, name);
    }

    @Override
    public Macro getMacro(String name) {
      return macros.get(name);
    }
  }
}
